"""Load and align the research datasets (see PREREGISTRATION.md for the splits)."""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

import numpy as np
import pandas as pd

RAW = Path(__file__).resolve().parent / "data_raw"
OHLC = ["open", "high", "low", "close"]


def _ohlc(df: pd.DataFrame) -> pd.DataFrame:
    df = df.rename(columns=str.lower)[OHLC].astype(float)
    df.index = pd.DatetimeIndex(df.index).tz_localize(None).normalize()
    df.index.name = "date"
    df = df[~df.index.duplicated(keep="last")].sort_index().dropna()
    return df


@lru_cache
def spx_ohlc() -> pd.DataFrame:
    df = pd.read_csv(RAW / "sp500_1927_2019.csv", parse_dates=["Date"], index_col="Date")
    return _ohlc(df)


@lru_cache
def comp_ohlc() -> pd.DataFrame:
    df = pd.read_csv(RAW / "nasdaq_comp_1999_2018.csv.gz", parse_dates=["Date"], index_col="Date", date_format="%m/%d/%Y")
    return _ohlc(df)


@lru_cache
def ndx_ohlc() -> pd.DataFrame:
    return _ohlc(pd.read_csv(RAW / "ndx_daily.csv", parse_dates=["Date"], index_col="Date"))


@lru_cache
def nq_ohlc() -> pd.DataFrame:
    return _ohlc(pd.read_csv(RAW / "nq_daily.csv", parse_dates=["Date"], index_col="Date"))


@lru_cache
def spx_closes() -> pd.Series:
    """S&P 500 closes 1927 -> 2026, spliced from three sources (checked for agreement)."""
    a = spx_ohlc()["close"]
    b = pd.read_csv(RAW / "sp500_close_1990_2022.csv.gz", parse_dates=["Date"], index_col="Date")["SP500"]
    raw = json.loads((RAW / "sp500_signals.json").read_text())["series"]
    c = pd.Series(raw["close"], index=pd.to_datetime(raw["date"]), dtype=float)
    for x, y, name in ((a, b, "fja05680 vs skfolio"), (b, c, "skfolio vs sp500-signals")):
        common = x.index.intersection(y.index)
        diff = (x[common] / y[common] - 1).abs()
        if len(common) and diff.max() > 0.002:
            raise ValueError(f"{name}: closes disagree by {diff.max():.4%} on {diff.idxmax().date()}")
    out = pd.concat([a, b[b.index > a.index[-1]], c[c.index > b.index[-1]]]).sort_index()
    out.index = out.index.normalize()
    out.name = "close"
    return out[~out.index.duplicated()]


@lru_cache
def vix() -> pd.Series:
    df = pd.read_csv(RAW / "vix_daily.csv", parse_dates=["DATE"], index_col="DATE")
    s = df["CLOSE"].astype(float)
    s.index = s.index.normalize()
    return s


@lru_cache
def risk_free() -> pd.Series:
    """Annual risk-free rate in percent, daily, forward-filled to 2026."""
    df = pd.read_csv(RAW / "us_market_data.csv", parse_dates=["Date"], index_col="Date", usecols=["Date", "Risk Free Rate"])
    s = df["Risk Free Rate"].astype(float)
    idx = pd.date_range(s.index[0], "2026-12-31", freq="D")
    return s.reindex(idx).ffill()


def closes_as_ohlc(close: pd.Series) -> pd.DataFrame:
    """Close-only data in OHLC shape (H = L = O = C). Only for close-based rules."""
    return pd.DataFrame({c: close for c in OHLC})


# Dataset id -> (loader, start, end, has_real_open, has_range)
SETS = {
    "SPX-E": (spx_ohlc, "1962-01-02", "1989-12-29", False, True),
    "SPX-D": (spx_ohlc, "1990-01-02", "2009-12-31", False, True),
    "COMP-D": (comp_ohlc, "1999-01-04", "2009-12-31", True, True),
    "SPX-V": (spx_ohlc, "2010-01-04", "2019-12-23", True, True),
    "COMP-V": (comp_ohlc, "2010-01-04", "2018-12-31", True, True),
    "NDX-H": (ndx_ohlc, "2019-01-02", "2026-10-09", True, True),
    "NQ-H": (nq_ohlc, "2019-01-02", "2026-10-09", True, True),
    "SPXC-H": (lambda: closes_as_ohlc(spx_closes()), "2020-01-02", "2026-10-09", False, False),
}

# Warm-up history loaded before each window so 200/252-day indicators are ready.
WARMUP_DAYS = 400


def load(set_id: str) -> tuple[pd.DataFrame, pd.Timestamp, bool, bool]:
    """Bars including warm-up, the first date that counts, and data flags."""
    loader, start, end, real_open, has_range = SETS[set_id]
    df = loader()
    start_ts = pd.Timestamp(start)
    i0 = max(0, int(df.index.searchsorted(start_ts)) - WARMUP_DAYS)
    df = df.iloc[i0:]
    df = df[df.index <= pd.Timestamp(end)]
    return df, start_ts, real_open, has_range


def rf_aligned(index: pd.DatetimeIndex) -> np.ndarray:
    return risk_free().reindex(index).ffill().bfill().to_numpy()


def vix_aligned(index: pd.DatetimeIndex) -> np.ndarray:
    return vix().reindex(index).ffill().to_numpy()
