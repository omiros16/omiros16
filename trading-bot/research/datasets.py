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


def nq_roll_days(nq: pd.DataFrame, ndx: pd.DataFrame) -> tuple[pd.DatetimeIndex, pd.DatetimeIndex]:
    """Quarterly expiry (SOQ) days and the following roll days of the NQ continuous contract.

    Yahoo's NQ=F stays on the expiring contract until expiry: on the expiry day (the third
    Friday of Mar/Jun/Sep/Dec, or the last NDX trading day before it) the 'close' is the
    final settlement at the 09:30 ET opening quotation, and the next row is already the next
    contract. Both rows therefore carry a fake move (the change in the futures premium).
    """
    soq, roll = [], []
    last = nq.index[-1]
    for y in range(nq.index[0].year, last.year + 1):
        for m in (3, 6, 9, 12):
            first = pd.Timestamp(y, m, 1)
            expiry = first + pd.Timedelta(days=(4 - first.weekday()) % 7 + 14)
            if expiry > last:
                continue
            candidates = nq.index[(nq.index <= expiry) & nq.index.isin(ndx.index)]
            if not len(candidates):
                continue
            i = nq.index.get_loc(candidates[-1])
            if i + 1 < len(nq):
                soq.append(nq.index[i])
                roll.append(nq.index[i + 1])
    return pd.DatetimeIndex(soq), pd.DatetimeIndex(roll)


@lru_cache
def nq_ohlc_spot() -> pd.DataFrame:
    """NQ futures bars made to behave like a cash-index CFD (no roll gaps, no futures carry).

    1. On expiry and roll days the bar's shape relative to the previous close is taken from
       the NASDAQ-100 cash index, removing the fake roll moves.
    2. Within each contract's life the futures premium shrinks towards expiry (carry, about
       -1 bp a day): that drift is removed so the level follows the cash index. A CFD pays
       financing through its swap, which the simulations charge separately; keeping the carry
       as well would charge it twice.
    The 23-hour session (open 18:00 ET, high/low of the whole session) is kept, which is what
    makes NQ closer to the CFD than the cash index with its overnight gaps.
    """
    nq, ndx = nq_ohlc(), ndx_ohlc()
    soq, roll = nq_roll_days(nq, ndx)
    special = soq.union(roll)
    rel_nq = nq[OHLC].div(nq["close"].shift(1), axis=0)
    rel_ndx = ndx[OHLC].div(ndx["close"].shift(1), axis=0).reindex(nq.index)
    rel_nq.loc[special] = rel_ndx.loc[special].to_numpy()
    close = nq["close"].iloc[0] * rel_nq["close"].fillna(1.0).cumprod()

    # Remove the carry drift of log(NQ / NDX) inside each contract period.
    log_ret = np.log(close).diff()
    ratio = np.log(close / ndx["close"].reindex(nq.index))
    bounds = [nq.index[0], *roll, nq.index[-1] + pd.Timedelta(days=1)]
    adjusted = log_ret.copy()
    for a, b in zip(bounds[:-1], bounds[1:]):
        period = nq.index[(nq.index >= a) & (nq.index < b) & ~nq.index.isin(soq)]
        normal = period[~period.isin(roll)]
        r = ratio.loc[period].dropna()
        if len(r) >= 3 and len(normal):
            adjusted.loc[normal] -= (r.iloc[-1] - r.iloc[0]) / len(normal)
    new_close = close.iloc[0] * np.exp(adjusted.fillna(0.0).cumsum())
    prev = new_close.shift(1)
    out = pd.DataFrame({
        "open": prev * rel_nq["open"], "high": prev * rel_nq["high"],
        "low": prev * rel_nq["low"], "close": new_close,
    }, index=nq.index)
    out.iloc[0] = nq[OHLC].iloc[0]
    out["high"] = out[OHLC].max(axis=1)
    out["low"] = out[OHLC].min(axis=1)
    return out


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


@lru_cache
def vatsal(name: str) -> pd.DataFrame:
    df = pd.read_csv(RAW / name, parse_dates=["Date"], index_col="Date")
    df = df.drop(columns=[c for c in df.columns if c.startswith("Unnamed")])
    return _ohlc(df)


# Dataset id -> (loader, start, end, has_real_open, has_range)
SETS = {
    "SPX-E": (spx_ohlc, "1962-01-02", "1989-12-29", False, True),
    "SPX-D": (spx_ohlc, "1990-01-02", "2009-12-31", False, True),
    "COMP-D": (comp_ohlc, "1999-01-04", "2009-12-31", True, True),
    "SPX-V": (spx_ohlc, "2010-01-04", "2019-12-23", True, True),
    "COMP-V": (comp_ohlc, "2010-01-04", "2018-12-31", True, True),
    "NDX-H": (ndx_ohlc, "2019-01-02", "2026-10-09", True, True),
    "NQ-H": (nq_ohlc, "2019-01-02", "2026-10-09", True, True),
    # Data correction after the holdout: NQ without roll gaps and carry (see nq_ohlc_spot).
    "NQ-HA": (nq_ohlc_spot, "2019-01-02", "2026-10-09", True, True),
    "SPXC-H": (lambda: closes_as_ohlc(spx_closes()), "2020-01-02", "2026-10-09", False, False),
    # Amendment 4: cross-market out-of-sample (first 400 rows are warm-up).
    "N225-X": (lambda: vatsal("nikkei225.csv"), "2006-08-01", "2019-12-30", True, True),
    "HSI-X": (lambda: vatsal("hangseng.csv"), "2006-08-01", "2019-12-27", True, True),
    "DJIA-X": (lambda: vatsal("djia.csv"), "2001-08-01", "2019-09-30", True, True),
    "NIFTY-X": (lambda: vatsal("nifty50.csv"), "2001-08-01", "2019-12-02", True, True),
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
