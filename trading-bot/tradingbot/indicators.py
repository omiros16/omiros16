"""Causal indicators: the value at row i only uses rows <= i (no lookahead)."""

from __future__ import annotations

import numpy as np
import pandas as pd


def sma(series: pd.Series, period: int) -> pd.Series:
    return series.rolling(period, min_periods=period).mean()


def true_range(df: pd.DataFrame) -> pd.Series:
    prev_close = df["close"].shift(1)
    ranges = pd.concat(
        [df["high"] - df["low"], (df["high"] - prev_close).abs(), (df["low"] - prev_close).abs()],
        axis=1,
    )
    return ranges.max(axis=1, skipna=True)


def atr(df: pd.DataFrame, period: int = 14) -> pd.Series:
    """Wilder's ATR."""
    tr = true_range(df)
    return tr.ewm(alpha=1.0 / period, adjust=False, min_periods=period).mean()


def lookback_return(close: pd.Series, period: int) -> pd.Series:
    return close / close.shift(period) - 1.0


def donchian(df: pd.DataFrame, period: int) -> tuple[pd.Series, pd.Series]:
    upper = df["high"].rolling(period, min_periods=period).max()
    lower = df["low"].rolling(period, min_periods=period).min()
    return upper, lower


def realized_vol(close: pd.Series, period: int = 20) -> pd.Series:
    """Rolling standard deviation of log returns (per bar)."""
    return np.log(close).diff().rolling(period, min_periods=period).std()
