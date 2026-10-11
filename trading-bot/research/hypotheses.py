"""The 85 pre-registered trials (PREREGISTRATION.md). Do not edit grids after the fact."""

from __future__ import annotations

import itertools
from dataclasses import dataclass, field
from typing import Callable

import numpy as np
import pandas as pd

from lab import Costs, segments, simulate, state_machine


@dataclass
class Ctx:
    df: pd.DataFrame
    start: pd.Timestamp
    rf: np.ndarray
    vix: np.ndarray
    real_open: bool
    has_range: bool
    costs: Costs = field(default_factory=Costs)

    # Cached indicators -----------------------------------------------------
    def __post_init__(self) -> None:
        d = self.df
        self.c = d["close"].to_numpy(float)
        self.h = d["high"].to_numpy(float)
        self.l = d["low"].to_numpy(float)
        self.o = d["open"].to_numpy(float)
        self.close = d["close"]
        self._cache: dict = {}

    def sma(self, n: int) -> np.ndarray:
        key = ("sma", n)
        if key not in self._cache:
            self._cache[key] = self.close.rolling(n, min_periods=n).mean().to_numpy()
        return self._cache[key]

    def above(self, n: int = 200) -> np.ndarray:
        with np.errstate(invalid="ignore"):
            return self.c > self.sma(n)

    def ibs(self) -> np.ndarray:
        rng = self.h - self.l
        with np.errstate(invalid="ignore", divide="ignore"):
            return np.where(rng > 0, (self.c - self.l) / rng, 0.5)

    def rsi(self, n: int = 2) -> np.ndarray:
        key = ("rsi", n)
        if key not in self._cache:
            delta = self.close.diff()
            up = delta.clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
            dn = (-delta.clip(upper=0)).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
            rs = up / dn
            self._cache[key] = (100 - 100 / (1 + rs)).fillna(50).to_numpy()
        return self._cache[key]

    def atr(self, n: int = 14) -> np.ndarray:
        key = ("atr", n)
        if key not in self._cache:
            pc = self.close.shift(1)
            tr = pd.concat([self.df["high"] - self.df["low"], (self.df["high"] - pc).abs(), (self.df["low"] - pc).abs()], axis=1).max(axis=1)
            self._cache[key] = tr.ewm(alpha=1 / n, adjust=False, min_periods=n).mean().to_numpy()
        return self._cache[key]

    def prev(self, x: np.ndarray, k: int = 1) -> np.ndarray:
        return np.concatenate([np.full(k, np.nan), x[:-k]])


@dataclass
class Trial:
    hyp: str
    params: dict
    build: Callable[[Ctx], tuple[np.ndarray, np.ndarray, bool]]  # -> night, day, close_exec
    needs_open: bool = False
    needs_range: bool = False
    custom: Callable[[Ctx], tuple[pd.Series, int, float]] | None = None  # full simulation

    @property
    def name(self) -> str:
        return self.hyp + "(" + ",".join(f"{k}={v}" for k, v in self.params.items()) + ")"


def _mode_a(state: np.ndarray):
    night, day = segments(state, "A")
    return night, day, True


# ------------------------------------------------------------------ H1 / H2
def h1(n):
    return Trial("H1", {"sma": n}, lambda x: _mode_a(np.nan_to_num(x.above(n)).astype(float)))


def h2(k):
    def build(x: Ctx):
        with np.errstate(invalid="ignore"):
            s = (x.c / x.prev(x.c, k) > 1).astype(float)
        return _mode_a(s)

    return Trial("H2", {"k": k}, build)


# ------------------------------------------------------------- H3..H7 (MR)
def _filt(x: Ctx, use: bool) -> np.ndarray:
    return x.above(200) if use else np.ones(len(x.c), dtype=bool)


def h3(a, exit_rule, filt):
    def build(x: Ctx):
        entry = (x.ibs() < a) & _filt(x, filt)
        if exit_rule == "next":
            ex = np.ones(len(entry), dtype=bool)
        else:
            with np.errstate(invalid="ignore"):
                ex = x.c > x.prev(x.h)
        return _mode_a(state_machine(entry, ex))

    return Trial("H3", {"ibs<": a, "exit": exit_rule, "sma200": filt}, build, needs_range=True)


def h4(a, exit_rule, filt):
    def build(x: Ctx):
        r = x.rsi(2)
        entry = (r < a) & _filt(x, filt)
        with np.errstate(invalid="ignore"):
            ex = x.c > x.sma(5) if exit_rule == "sma5" else r > 70
        return _mode_a(state_machine(entry, ex))

    return Trial("H4", {"rsi2<": a, "exit": exit_rule, "sma200": filt}, build)


def h5(n, filt):
    def build(x: Ctx):
        low_n = x.close.rolling(n, min_periods=n).min().to_numpy()
        entry = (x.c <= low_n) & _filt(x, filt)
        with np.errstate(invalid="ignore"):
            ex = x.c > x.prev(x.h)
        return _mode_a(state_machine(entry, ex, max_hold=10))

    return Trial("H5", {"n": n, "sma200": filt}, build, needs_range=True)


def h6(k, filt):
    def build(x: Ctx):
        down = x.c < x.prev(x.c)
        run = pd.Series(down).groupby((~pd.Series(down)).cumsum()).cumsum().to_numpy()
        entry = (run >= k) & _filt(x, filt)
        ex = x.c > x.prev(x.c)
        return _mode_a(state_machine(entry, ex))

    return Trial("H6", {"k": k, "sma200": filt}, build)


def h7(z0, filt):
    def build(x: Ctx):
        sd = x.close.rolling(20, min_periods=20).std().to_numpy()
        with np.errstate(invalid="ignore", divide="ignore"):
            z = (x.c - x.sma(20)) / sd
        entry = (z < -z0) & _filt(x, filt)
        ex = z > 0
        return _mode_a(state_machine(entry, ex))

    return Trial("H7", {"z<": -z0, "sma200": filt}, build)


# ------------------------------------------------------------------ H8 night
def h8(variant):
    def build(x: Ctx):
        with np.errstate(invalid="ignore"):
            r = x.c / x.prev(x.c) - 1
        cond = {"always": np.ones(len(x.c), bool), "down": r < 0, "down1pct": r < -0.01}[variant]
        cond = np.nan_to_num(cond).astype(float)
        night = np.concatenate([[0.0], cond[:-1]])  # bought at close t, held overnight into t+1
        return night, np.zeros_like(night), True

    return Trial("H8", {"when": variant}, build, needs_open=True)


# ------------------------------------------------------------------ H9 / H10
def _month_ranks(index: pd.DatetimeIndex) -> tuple[np.ndarray, np.ndarray]:
    key = index.year * 12 + index.month
    s = pd.Series(1, index=index)
    from_start = s.groupby(key).cumsum().to_numpy()
    from_end = s[::-1].groupby(key[::-1]).cumsum()[::-1].to_numpy()
    return from_start, from_end


def h9(k, m):
    def build(x: Ctx):
        fs, fe = _month_ranks(x.df.index)
        s = ((fe <= k) | (fs < m)).astype(float)
        return _mode_a(s)

    return Trial("H9", {"k": k, "m": m}, build)


def h10():
    def build(x: Ctx):
        nxt = np.concatenate([x.df.index.month[1:], [x.df.index.month[-1]]])
        s = np.isin(nxt, [11, 12, 1, 2, 3, 4]).astype(float)
        return _mode_a(s)

    return Trial("H10", {}, build)


# ------------------------------------------------------------------ H11 short
def h11(variant):
    def build(x: Ctx):
        below = ~x.above(200) & ~np.isnan(x.sma(200))
        if variant == "trend":
            return _mode_a(-below.astype(float))
        b = {"ibs>.8": 0.8, "ibs>.9": 0.9}[variant]
        entry = (x.ibs() > b) & below
        return _mode_a(state_machine(entry, np.ones(len(entry), bool), side=-1))

    return Trial("H11", {"v": variant}, build, needs_range=variant != "trend")


# ------------------------------------------------------------------ H12
def h12(n, m):
    def build(x: Ctx):
        hi = x.df["high"].rolling(n, min_periods=n).max().shift(1).to_numpy()
        lo = x.df["low"].rolling(m, min_periods=m).min().shift(1).to_numpy()
        with np.errstate(invalid="ignore"):
            entry, ex = x.c > hi, x.c < lo
        return _mode_a(state_machine(entry, ex))

    return Trial("H12", {"n": n, "m": m}, build, needs_range=True)


# ------------------------------------------------------------------ H13 SMC
def sweep_sim(x: Ctx, level_source: str, rr: float, max_bars: int = 10, swing_strength: int = 3,
              min_pen: float = 0.05, max_pen: float = 1.0, sl_buf: float = 0.2) -> tuple[pd.Series, int, float]:
    import sys
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from tradingbot.smc import liquidity_sweeps

    df = x.df
    c, h, l, o = x.c, x.h, x.l, x.o
    atr = x.atr(14)
    sma = x.sma(200)
    with np.errstate(divide="ignore", invalid="ignore"):
        big = 10.0 ** (np.floor(np.log10(c)) - 1)
    quarter = big / 4
    sw = liquidity_sweeps(
        df,
        quarter_size=quarter if level_source in ("quarters", "both") else None,
        swing_strength=swing_strength if level_source in ("swings", "both") else None,
        min_penetration=np.nan_to_num(atr * min_pen, nan=np.inf),
    )
    bull = sw["sweep_bull_level"].to_numpy()
    bear = sw["sweep_bear_level"].to_numpy()

    costs = x.costs
    side_c = costs.round_trip_bp / 2 / 1e4
    close_c = side_c + costs.close_extra_bp / 2 / 1e4
    dates = df.index
    n = len(df)
    ret = np.zeros(n)
    expo = np.zeros(n, dtype=bool)
    pos = 0
    trades = 0
    entry_px = stop = target = 0.0
    held = 0
    for t in range(1, n):
        if pos:
            held += 1
            expo[t] = True
            nights = (dates[t] - dates[t - 1]).days
            rate = ((x.rf[t - 1] + costs.swap_markup_pct) if pos > 0 else (costs.swap_markup_pct - x.rf[t - 1])) / 100 / 360
            ret[t] -= rate * nights
            prev_close = c[t - 1]
            exit_px = None
            open_px = o[t] if x.real_open else c[t - 1]
            if pos > 0:
                if open_px <= stop:
                    exit_px = open_px
                elif open_px >= target:
                    exit_px = open_px
                elif l[t] <= stop:
                    exit_px = stop
                elif h[t] >= target:
                    exit_px = target
            else:
                if open_px >= stop:
                    exit_px = open_px
                elif open_px <= target:
                    exit_px = open_px
                elif h[t] >= stop:
                    exit_px = stop
                elif l[t] <= target:
                    exit_px = target
            if exit_px is not None:
                ret[t] += pos * (exit_px / prev_close - 1) - side_c
                pos = 0
            elif held >= max_bars:
                ret[t] += pos * (c[t] / prev_close - 1) - close_c
                pos = 0
            else:
                ret[t] += pos * (c[t] / prev_close - 1)
        if pos == 0 and not np.isnan(atr[t]) and not np.isnan(sma[t]) and dates[t] >= x.start:
            if not np.isnan(bull[t]) and c[t] > sma[t] and bull[t] - l[t] <= max_pen * atr[t]:
                pos, entry_px = 1, c[t]
                stop = l[t] - sl_buf * atr[t]
                target = entry_px + rr * (entry_px - stop)
            elif not np.isnan(bear[t]) and c[t] < sma[t] and h[t] - bear[t] <= max_pen * atr[t]:
                pos, entry_px = -1, c[t]
                stop = h[t] + sl_buf * atr[t]
                target = entry_px - rr * (stop - entry_px)
            if pos:
                ret[t] -= close_c
                trades += 1
                held = 0
    mask = dates >= x.start
    return pd.Series(ret[mask], index=dates[mask], name="ret"), trades, float(expo[mask].mean())


def h13(level, rr):
    return Trial("H13", {"level": level, "rr": rr}, build=None, needs_range=True,
                 custom=lambda x: sweep_sim(x, level, rr))


# ------------------------------------------------------------------ H14
def h14(variant):
    def build(x: Ctx):
        if variant == "vix":
            v = pd.Series(x.vix)
            hot = (v > v.rolling(252, min_periods=126).median()).to_numpy()
        else:
            sd = x.close.pct_change().rolling(20, min_periods=20).std()
            hot = (sd > sd.rolling(252, min_periods=126).median()).to_numpy()
        entry = (x.ibs() < 0.2) & hot
        return _mode_a(state_machine(entry, np.ones(len(entry), bool)))

    return Trial("H14", {"vol": variant}, build, needs_range=True)


# ------------------------------------------------------------------ registry
def all_trials() -> list[Trial]:
    t: list[Trial] = []
    t += [h1(n) for n in (50, 100, 150, 200, 250)]
    t += [h2(k) for k in (63, 126, 189, 252)]
    t += [h3(a, e, f) for a, e, f in itertools.product((0.10, 0.15, 0.20, 0.25), ("next", "c>h1"), (False, True))]
    t += [h4(a, e, f) for a, e, f in itertools.product((5, 10, 15, 20), ("sma5", "rsi70"), (False, True))]
    t += [h5(n, f) for n, f in itertools.product((3, 5, 7, 10), (False, True))]
    t += [h6(k, f) for k, f in itertools.product((2, 3, 4), (False, True))]
    t += [h7(z, f) for z, f in itertools.product((1.0, 1.5, 2.0), (False, True))]
    t += [h8(v) for v in ("always", "down", "down1pct")]
    t += [h9(k, m) for k, m in ((1, 3), (2, 3), (4, 2), (1, 1))]
    t += [h10()]
    t += [h11(v) for v in ("ibs>.8", "ibs>.9", "trend")]
    t += [h12(n, m) for n, m in ((20, 10), (55, 20))]
    t += [h13(lv, rr) for lv, rr in itertools.product(("quarters", "swings", "both"), (1.5, 2.0, 3.0))]
    t += [h14(v) for v in ("vix", "realized")]
    assert len(t) == 85, len(t)
    return t


def run_trial(trial: Trial, ctx: Ctx):
    """-> (returns, trades, exposure) or None if the data cannot support the rule."""
    if trial.needs_open and not ctx.real_open:
        return None
    if trial.needs_range and not ctx.has_range:
        return None
    if trial.custom is not None:
        return trial.custom(ctx)
    night, day, close_exec = trial.build(ctx)
    night = np.nan_to_num(night)
    day = np.nan_to_num(day)
    ret, trades = simulate(ctx.df, night, day, ctx.rf, ctx.costs, ctx.start, close_exec)
    mask = ctx.df.index >= ctx.start
    exposure = float(np.mean(((np.abs(night) + np.abs(day)) > 0)[mask]))
    return ret, trades, exposure
