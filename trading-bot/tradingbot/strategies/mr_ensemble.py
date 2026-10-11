"""MR-ENS-VT: daily mean-reversion ensemble with volatility targeting.

This is the strategy that came out of the research in research/ (see
RESEARCH.md). It is an *ensemble* of the 52 pre-registered daily
mean-reversion rules H3-H7, so it makes no parameter choice inside the
family; each rule is long or flat, and the position is the fraction of the
rules that are long, scaled by volatility:

  exposure = leverage x (rules long / 52) x min(vol_cap, vol_target / sigma20)

where sigma20 is the annualised standard deviation of the last 20 daily
returns. The rules (all evaluated at the daily close, long only):

  H3 IBS < a           a in .10 .15 .20 .25; exit next close or close > prior high
  H4 RSI(2) < a        a in 5 10 15 20;      exit close > SMA(5) or RSI(2) > 70
  H5 close = N-day low N in 3 5 7 10;        exit close > prior high or 10 days
  H6 k down closes     k in 2 3 4;           exit first up close
  H7 z-score < -z      z in 1.0 1.5 2.0;     exit z > 0
  each with and without the filter close > SMA(200)  -> 52 rules

Regime guard (exploratory safety switch, see RESEARCH.md): if the lag-1
autocorrelation of daily returns over the last 504 days is above
`regime_max_autocorr`, the premise of mean reversion is gone and exposure
is set to 0.

Validated on US indices only (S&P 500, NASDAQ, NASDAQ-100, Dow Jones). It did
not work on Nikkei, Hang Seng or NIFTY: do not run it on other markets.
"""

from __future__ import annotations

import itertools

import numpy as np
import pandas as pd

from ..models import Position, Signal
from .base import Strategy

IBS_LEVELS = (0.10, 0.15, 0.20, 0.25)
RSI_LEVELS = (5, 10, 15, 20)
LOW_WINDOWS = (3, 5, 7, 10)
DOWN_RUNS = (2, 3, 4)
Z_LEVELS = (1.0, 1.5, 2.0)


def _state_machine(entry: np.ndarray, exit_: np.ndarray, max_hold: int | None = None) -> np.ndarray:
    """Long/flat after each close; a fresh entry signal renews a trade only when its exit fires."""
    n = len(entry)
    out = np.zeros(n)
    pos = False
    held = 0
    for t in range(n):
        if pos:
            held += 1
            if exit_[t] or (max_hold is not None and held >= max_hold):
                if entry[t]:
                    held = 0
                else:
                    pos = False
        elif entry[t]:
            pos = True
            held = 0
        out[t] = 1.0 if pos else 0.0
    return out


def _prev(x: np.ndarray) -> np.ndarray:
    return np.concatenate([[np.nan], x[:-1]])


def rule_states(bars: pd.DataFrame) -> pd.DataFrame:
    """State (1 long / 0 flat) of each of the 52 rules after each close."""
    close = bars["close"].astype(float)
    c = close.to_numpy()
    h = bars["high"].to_numpy(dtype=float)
    low = bars["low"].to_numpy(dtype=float)
    n = len(c)
    with np.errstate(invalid="ignore", divide="ignore"):
        sma200 = close.rolling(200, min_periods=200).mean().to_numpy()
        above = c > sma200
        rng = h - low
        ibs = np.where(rng > 0, (c - low) / rng, 0.5)
        up_through_high = c > _prev(h)
        delta = close.diff()
        gain = delta.clip(lower=0).ewm(alpha=0.5, adjust=False, min_periods=2).mean()
        loss = (-delta.clip(upper=0)).ewm(alpha=0.5, adjust=False, min_periods=2).mean()
        rsi = (100 - 100 / (1 + gain / loss)).fillna(50).to_numpy()
        sma5 = close.rolling(5, min_periods=5).mean().to_numpy()
        sma20 = close.rolling(20, min_periods=20).mean().to_numpy()
        sd20 = close.rolling(20, min_periods=20).std().to_numpy()
        z = (c - sma20) / sd20
        down = c < _prev(c)
        up = c > _prev(c)
    run = pd.Series(down).groupby((~pd.Series(down)).cumsum()).cumsum().to_numpy()
    always = np.ones(n, dtype=bool)
    filters = {False: always, True: above}

    cols: dict[str, np.ndarray] = {}
    for a, ex, f in itertools.product(IBS_LEVELS, ("next", "c>h1"), (False, True)):
        exit_ = always if ex == "next" else up_through_high
        cols[f"ibs<{a}|{ex}|sma200={f}"] = _state_machine((ibs < a) & filters[f], exit_)
    for a, ex, f in itertools.product(RSI_LEVELS, ("sma5", "rsi70"), (False, True)):
        with np.errstate(invalid="ignore"):
            exit_ = c > sma5 if ex == "sma5" else rsi > 70
        cols[f"rsi2<{a}|{ex}|sma200={f}"] = _state_machine((rsi < a) & filters[f], exit_)
    for w, f in itertools.product(LOW_WINDOWS, (False, True)):
        low_n = close.rolling(w, min_periods=w).min().to_numpy()
        with np.errstate(invalid="ignore"):
            entry = (c <= low_n) & filters[f]
        cols[f"low{w}|sma200={f}"] = _state_machine(entry, up_through_high, max_hold=10)
    for k, f in itertools.product(DOWN_RUNS, (False, True)):
        cols[f"down{k}|sma200={f}"] = _state_machine((run >= k) & filters[f], up)
    for zz, f in itertools.product(Z_LEVELS, (False, True)):
        with np.errstate(invalid="ignore"):
            cols[f"z<-{zz}|sma200={f}"] = _state_machine((z < -zz) & filters[f], z > 0)
    assert len(cols) == 52
    return pd.DataFrame(cols, index=bars.index)


class MREnsemble(Strategy):
    name = "mr_ensemble"
    defaults = {
        "leverage": 1.0,
        "vol_target": 0.15,
        "vol_cap": 2.0,
        "vol_window": 20,
        "regime_max_autocorr": 0.10,  # None disables the regime guard
        "regime_window": 504,
        "min_change": 0.02,  # resize only when the exposure moves by >= 2% of equity
    }

    def validate(self) -> None:
        p = self.params
        if not 0 < p["leverage"] <= 5:
            raise ValueError("leverage must be in (0, 5]")
        if p["vol_target"] <= 0 or p["vol_cap"] <= 0:
            raise ValueError("vol_target and vol_cap must be > 0")
        if p["min_change"] < 0:
            raise ValueError("min_change must be >= 0")
        if not 0 < p["vol_target"] <= 1:
            raise ValueError(f"vol_target is a fraction: 0.15 means 15% a year (got {p['vol_target']!r})")
        if int(p["vol_window"]) < 2:
            raise ValueError("vol_window must be >= 2")
        if int(p["regime_window"]) < 20:
            raise ValueError("regime_window must be >= 20")
        r = p["regime_max_autocorr"]
        if r is not None and not -1 < r < 1:
            raise ValueError("regime_max_autocorr must be between -1 and 1, or null to switch the guard off")

    @property
    def warmup_bars(self) -> int:
        guard = self.params["regime_max_autocorr"] is not None
        return max(260, self.params["regime_window"] + 10) if guard else 260

    def prepare(self, bars: pd.DataFrame) -> pd.DataFrame:
        p = self.params
        data = bars.copy()
        states = rule_states(bars)
        data["rules_long"] = states.sum(axis=1)
        data["mr_ens"] = states.mean(axis=1)
        ret = data["close"].pct_change()
        sigma = ret.rolling(p["vol_window"], min_periods=p["vol_window"]).std() * np.sqrt(252)
        with np.errstate(divide="ignore", invalid="ignore"):
            scale = np.clip(p["vol_target"] / sigma.to_numpy(), 0, p["vol_cap"])
        data["sigma20"] = sigma
        data["vol_scale"] = np.nan_to_num(scale, nan=1.0)
        if p["regime_max_autocorr"] is not None:
            w = p["regime_window"]
            # Lag-1 autocorrelation over the last w (return, previous return) pairs.
            ac = ret.rolling(w, min_periods=w // 2).corr(ret.shift(1))
            data["autocorr"] = ac
            data["regime_ok"] = np.where(ac.isna(), 1.0, (ac <= p["regime_max_autocorr"]).astype(float))
        else:
            data["autocorr"] = np.nan
            data["regime_ok"] = 1.0
        data["target_exposure"] = p["leverage"] * data["mr_ens"] * data["vol_scale"] * data["regime_ok"]
        return data

    def on_bar(self, data: pd.DataFrame, i: int, position: Position | None) -> Signal | None:
        exposure = float(data["target_exposure"].iat[i])
        n_long = int(data["rules_long"].iat[i])
        sigma = data["sigma20"].iat[i]
        why = f"{n_long}/52 rules long, vol {sigma:.0%}" if sigma == sigma else f"{n_long}/52 rules long"
        if data["regime_ok"].iat[i] == 0:
            why = f"regime guard: autocorr {data['autocorr'].iat[i]:+.2f}"
        return Signal.target(exposure, self.params["min_change"], why)
