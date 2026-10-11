"""Strict, testable definitions of SMC/ICT and Quarters Theory concepts.

Discretionary concepts only become testable once they have one exact
definition. These are the definitions this bot uses; if yours differ, change
them here (and the tests) rather than inside a strategy.

All outputs are causal: the value at row i is known at the close of bar i.
"""

from __future__ import annotations

import numpy as np
import pandas as pd


def swing_points(df: pd.DataFrame, strength: int = 2) -> pd.DataFrame:
    """Fractal swing highs/lows and the most recent *confirmed* one at each bar.

    Bar j is a swing high if its high is strictly above the `strength` bars
    before it and at least as high as the `strength` bars after it (mirror for
    swing lows). A swing at j is only known at bar j + strength, so
    `last_swing_high[i]` only uses pivots with j + strength <= i.

    Columns:
      is_swing_high / is_swing_low  - marks the pivot bar itself (NOT causal,
                                      for charts and analysis only)
      last_swing_high / last_swing_low - causal: latest confirmed pivot price
    """
    if strength < 1:
        raise ValueError("strength must be >= 1")
    high = df["high"].to_numpy(dtype=float)
    low = df["low"].to_numpy(dtype=float)
    n = len(df)
    k = strength
    is_sh = np.zeros(n, dtype=bool)
    is_sl = np.zeros(n, dtype=bool)
    for j in range(k, n - k):
        left_h, right_h = high[j - k : j], high[j + 1 : j + k + 1]
        if high[j] > left_h.max() and high[j] >= right_h.max():
            is_sh[j] = True
        left_l, right_l = low[j - k : j], low[j + 1 : j + k + 1]
        if low[j] < left_l.min() and low[j] <= right_l.min():
            is_sl[j] = True

    last_sh = np.full(n, np.nan)
    last_sl = np.full(n, np.nan)
    cur_h = cur_l = np.nan
    for i in range(n):
        j = i - k  # pivot that becomes confirmed at bar i
        if j >= 0:
            if is_sh[j]:
                cur_h = high[j]
            if is_sl[j]:
                cur_l = low[j]
        last_sh[i] = cur_h
        last_sl[i] = cur_l

    return pd.DataFrame(
        {
            "is_swing_high": is_sh,
            "is_swing_low": is_sl,
            "last_swing_high": last_sh,
            "last_swing_low": last_sl,
        },
        index=df.index,
    )


def market_structure(df: pd.DataFrame, strength: int = 2) -> pd.DataFrame:
    """Break of structure (BOS) and change of character (CHoCH).

    BOS up at bar i: the close of bar i is above the latest confirmed swing
    high, and that swing high had not been broken before. Mirror for BOS down.
    `structure` is +1 after the last BOS up, -1 after the last BOS down, 0
    before any. A CHoCH is a BOS against the previous structure.
    """
    swings = swing_points(df, strength)
    close = df["close"].to_numpy(dtype=float)
    last_sh = swings["last_swing_high"].to_numpy()
    last_sl = swings["last_swing_low"].to_numpy()
    n = len(df)
    bos = np.zeros(n, dtype=int)
    choch = np.zeros(n, dtype=bool)
    structure = np.zeros(n, dtype=int)

    broken_h = broken_l = np.nan  # level already used by a BOS
    state = 0
    for i in range(n):
        level_h, level_l = last_sh[i], last_sl[i]
        if not np.isnan(level_h) and close[i] > level_h and level_h != broken_h:
            bos[i] = 1
            broken_h = level_h
        elif not np.isnan(level_l) and close[i] < level_l and level_l != broken_l:
            bos[i] = -1
            broken_l = level_l
        if bos[i] != 0:
            choch[i] = state != 0 and bos[i] == -state
            state = bos[i]
        structure[i] = state

    out = swings[["last_swing_high", "last_swing_low"]].copy()
    out["bos"] = bos
    out["choch"] = choch
    out["structure"] = structure
    return out


def fair_value_gaps(df: pd.DataFrame, min_size: float = 0.0) -> pd.DataFrame:
    """Three-candle fair value gaps, known at the close of the third candle.

    Bullish FVG at bar i: low[i] - high[i-2] > min_size. The gap is
    [high[i-2], low[i]]. Bearish FVG: low[i-2] - high[i] > min_size, gap
    [high[i], low[i-2]]. Columns are NaN where there is no gap.
    """
    high, low = df["high"], df["low"]
    h2, l2 = high.shift(2), low.shift(2)
    bull = (low - h2) > min_size
    bear = (l2 - high) > min_size
    return pd.DataFrame(
        {
            "fvg_bull_bottom": h2.where(bull),
            "fvg_bull_top": low.where(bull),
            "fvg_bear_bottom": high.where(bear),
            "fvg_bear_top": l2.where(bear),
        },
        index=df.index,
    )


def quarter_below(price: np.ndarray | pd.Series | float, quarter_size: float):
    """Highest quarter level at or below `price` (Quarters Theory round numbers)."""
    return np.floor(np.asarray(price, dtype=float) / quarter_size) * quarter_size


def quarter_above(price: np.ndarray | pd.Series | float, quarter_size: float):
    """Lowest quarter level at or above `price`."""
    return np.ceil(np.asarray(price, dtype=float) / quarter_size) * quarter_size


def liquidity_sweeps(
    df: pd.DataFrame,
    quarter_size: float | None,
    swing_strength: int | None,
    min_penetration: pd.Series | float = 0.0,
) -> pd.DataFrame:
    """Sweep-and-reclaim of a liquidity level within one bar.

    Bullish sweep at bar i of level L:
      close[i-1] > L  (price came from above)
      low[i] < L - min_penetration  (traded through, where stops sit)
      close[i] > L  (closed back above: the move was rejected)

    Levels come from quarter levels (round numbers, see Osler 2003 on stop
    clustering) and/or the latest swing low confirmed before bar i. When both
    qualify, the higher one (closest to price) is reported. Mirror for bearish.
    """
    if quarter_size is None and swing_strength is None:
        raise ValueError("need quarter_size and/or swing_strength")
    close = df["close"].to_numpy(dtype=float)
    high = df["high"].to_numpy(dtype=float)
    low = df["low"].to_numpy(dtype=float)
    prev_close = np.concatenate([[np.nan], close[:-1]])
    pen = np.broadcast_to(np.asarray(min_penetration, dtype=float), close.shape)
    n = len(df)

    bull_level = np.full(n, np.nan)
    bear_level = np.full(n, np.nan)

    def take_bull(level: np.ndarray) -> None:
        ok = (prev_close > level) & (low < level - pen) & (close > level)
        better = ok & ~(bull_level >= level)
        bull_level[better] = level[better]

    def take_bear(level: np.ndarray) -> None:
        ok = (prev_close < level) & (high > level + pen) & (close < level)
        better = ok & ~(bear_level <= level)
        bear_level[better] = level[better]

    with np.errstate(invalid="ignore"):
        if quarter_size is not None:
            take_bull(quarter_below(np.fmin(close, prev_close), quarter_size))
            take_bear(quarter_above(np.fmax(close, prev_close), quarter_size))
        if swing_strength is not None:
            swings = swing_points(df, swing_strength)
            # Only swings confirmed before this bar count as resting liquidity.
            take_bull(swings["last_swing_low"].shift(1).to_numpy(dtype=float))
            take_bear(swings["last_swing_high"].shift(1).to_numpy(dtype=float))

    return pd.DataFrame({"sweep_bull_level": bull_level, "sweep_bear_level": bear_level}, index=df.index)
