"""Research backtester for daily rules, with costs and swaps per session segment.

Each trading day t has two segments:
  night[t]: held from the close of t-1 to the open of t
  day[t]:   held from the open of t to the close of t
A rule is a pair of position arrays (night, day) in units of equity
(1 = 100% notional long, -1 = short). Costs are charged on every change of
position between consecutive segments; swap is charged for every calendar
night a position is held, using the risk-free rate of that time.

When night[t] == day[t] the day's return is computed close to close, so data
with unreliable opens (S&P 500 before 2010) is only used through the open
when a rule actually trades at the open.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from statistics import NormalDist

import numpy as np
import pandas as pd

TRADING_DAYS = 252
EULER = 0.5772156649015329


@dataclass(frozen=True)
class Costs:
    round_trip_bp: float = 2.0  # spread + slippage, round trip
    close_extra_bp: float = 1.0  # extra round-trip cost for orders placed just before the close
    swap_markup_pct: float = 2.5  # long pays rf + markup, short pays markup - rf (annual %)

    def stressed(self) -> "Costs":
        return Costs(4.0, self.close_extra_bp, self.swap_markup_pct + 1.0)


@dataclass
class Result:
    name: str
    returns: pd.Series  # daily net returns, from the evaluation start
    night: np.ndarray
    day: np.ndarray
    trades: int
    params: dict = field(default_factory=dict)

    @property
    def exposure(self) -> float:
        return float(np.mean((np.abs(self.night) + np.abs(self.day)) > 0))

    def stats(self) -> dict:
        return stats(self.returns, self.trades, self.exposure)


def simulate(
    df: pd.DataFrame,
    night: np.ndarray,
    day: np.ndarray,
    rf_pct: np.ndarray,
    costs: Costs,
    start: pd.Timestamp,
    close_exec: bool = True,
) -> tuple[pd.Series, int]:
    """Net daily returns of holding `night`/`day` positions on `df` bars."""
    o = df["open"].to_numpy(float)
    c = df["close"].to_numpy(float)
    prev_c = np.concatenate([[np.nan], c[:-1]])
    r_cc = c / prev_c - 1
    r_night = o / prev_c - 1
    r_day = c / o - 1
    same = night == day
    gross = np.where(
        same,
        night * r_cc,
        (1 + night * np.nan_to_num(r_night)) * (1 + day * np.nan_to_num(r_day)) - 1,
    )
    gross[0] = 0.0

    side = costs.round_trip_bp / 2 / 1e4
    close_side = side + (costs.close_extra_bp / 2 / 1e4 if close_exec else 0.0)
    prev_day = np.concatenate([[0.0], day[:-1]])
    turnover_close = np.abs(night - prev_day)  # traded at the previous close
    turnover_open = np.abs(day - night)  # traded at this open
    cost = turnover_close * close_side + turnover_open * side

    dates = df.index
    nights = np.concatenate([[1], (dates[1:] - dates[:-1]).days.to_numpy()])
    rf_prev = np.concatenate([[rf_pct[0]], rf_pct[:-1]])
    long_rate = (rf_prev + costs.swap_markup_pct) / 100 / 360
    short_rate = (costs.swap_markup_pct - rf_prev) / 100 / 360
    swap = np.where(night > 0, night * long_rate, np.where(night < 0, -night * short_rate, 0.0)) * nights

    net = gross - cost - swap
    mask = dates >= start
    # Entries: a segment goes from flat to a position (night after day, day after night).
    entries = ((night != 0) & (prev_day == 0)) | ((day != 0) & (night == 0))
    trades = int(np.sum(entries[mask]))
    return pd.Series(net[mask], index=dates[mask], name="ret"), trades


def segments(state: np.ndarray, mode: str) -> tuple[np.ndarray, np.ndarray]:
    """Position decided at close t (state[t]) -> segments of day t+1.

    mode 'A' (close): night[t+1] = day[t+1] = state[t]
    mode 'B' (next open): night[t+1] = state[t-1], day[t+1] = state[t]
    """
    s = state.astype(float)
    shifted = np.concatenate([[0.0], s[:-1]])
    if mode == "A":
        return shifted, shifted.copy()
    if mode == "B":
        return np.concatenate([[0.0], shifted[:-1]]), shifted
    raise ValueError(mode)


def state_machine(entry: np.ndarray, exit_: np.ndarray, max_hold: int | None = None, side: int = 1) -> np.ndarray:
    """Position after each close. Enter on entry[t]; leave on exit_[t] (unless entry[t] renews it)."""
    n = len(entry)
    s = np.zeros(n)
    held = 0
    pos = 0
    for t in range(n):
        if pos:
            held += 1
            leave = bool(exit_[t]) or (max_hold is not None and held >= max_hold)
            if leave:
                if entry[t]:
                    held = 0
                else:
                    pos = 0
        elif entry[t]:
            pos = side
            held = 0
        s[t] = pos
    return s


# ---------------------------------------------------------------------- stats
def max_drawdown(returns: pd.Series) -> float:
    eq = (1 + returns).cumprod()
    return float((eq / eq.cummax() - 1).min()) if len(eq) else 0.0


def stats(r: pd.Series, trades: int = 0, exposure: float = float("nan")) -> dict:
    r = r.dropna()
    n = len(r)
    if n < 2:
        return {"n": n}
    sd = r.std(ddof=1)
    sr = r.mean() / sd if sd > 0 else float("nan")
    years = n / TRADING_DAYS
    total = float((1 + r).prod())
    return {
        "n": n,
        "sharpe": sr * math.sqrt(TRADING_DAYS) if math.isfinite(sr) else float("nan"),
        "sr_daily": sr,
        "cagr": total ** (1 / years) - 1 if total > 0 else -1.0,
        "vol": sd * math.sqrt(TRADING_DAYS),
        "maxdd": max_drawdown(r),
        "tstat": sr * math.sqrt(n) if math.isfinite(sr) else float("nan"),
        "skew": float(r.skew()),
        "kurt": float(r.kurt()) + 3,
        "exposure": exposure,
        "trades": trades,
        "trades_per_year": trades / years,
        "worst_day": float(r.min()),
    }


def deflated_sharpe(sr: float, n: int, skew: float, kurt: float, n_trials: int, sr_var: float) -> float:
    """Bailey & López de Prado (2014), per-period Sharpe."""
    if not math.isfinite(sr) or n < 3:
        return float("nan")
    norm = NormalDist()
    sr0 = 0.0
    if n_trials > 1 and sr_var > 0:
        sr0 = math.sqrt(sr_var) * (
            (1 - EULER) * norm.inv_cdf(1 - 1 / n_trials) + EULER * norm.inv_cdf(1 - 1 / (n_trials * math.e))
        )
    denom = 1 - skew * sr + (kurt - 1) / 4 * sr**2
    if denom <= 0:
        return float("nan")
    return norm.cdf((sr - sr0) * math.sqrt(n - 1) / math.sqrt(denom))
