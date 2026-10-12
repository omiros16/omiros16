"""Load and save OHLC bars as CSV.

Accepts the common layouts:
  * time,open,high,low,close[,volume]            (this bot's own format)
  * Date,Open,High,Low,Close,...                 (Yahoo / most exports)
  * <DATE>\t<TIME>\t<OPEN>\t<HIGH>\t<LOW>\t<CLOSE>  (MT5 "Export bars")
"""

from __future__ import annotations

import re
from pathlib import Path

import pandas as pd

OHLC = ["open", "high", "low", "close"]


def load_bars_csv(path: str | Path, start: str | None = None, end: str | None = None) -> pd.DataFrame:
    path = Path(path)
    with path.open("r", encoding="utf-8-sig") as fh:
        first = fh.readline()
    sep = "\t" if "\t" in first else ("," if "," in first else ";")
    raw = pd.read_csv(path, sep=sep, encoding="utf-8-sig")
    raw.columns = [str(c).strip().strip("<>").lower() for c in raw.columns]

    if "date" in raw.columns and "time" in raw.columns:
        stamps = _parse_times(raw["date"].astype(str) + " " + raw["time"].astype(str), path)
    else:
        col = next((c for c in ("time", "datetime", "date", "timestamp") if c in raw.columns), None)
        if col is None:
            raise ValueError(f"{path}: no time/date column in {list(raw.columns)}")
        stamps = _parse_times(raw[col], path)

    missing = [c for c in OHLC if c not in raw.columns]
    if missing:
        raise ValueError(f"{path}: missing columns {missing}")
    cols = OHLC + (["volume"] if "volume" in raw.columns else ["tickvol"] if "tickvol" in raw.columns else [])
    df = raw[cols].apply(pd.to_numeric, errors="coerce")
    df.columns = OHLC + (["volume"] if len(cols) == 5 else [])
    df.index = pd.DatetimeIndex(stamps, name="time")
    # Drop empty rows ('null' rows in Yahoo files) before removing duplicate stamps,
    # so that an empty duplicate never replaces a real bar.
    df = df[df.index.notna()].dropna(subset=OHLC)
    df = df[~df.index.duplicated(keep="last")].sort_index()
    if start:
        df = df[df.index >= pd.Timestamp(start)]
    if end:
        df = df[df.index <= pd.Timestamp(end)]
    _check(df, path)
    return df


# A UTC offset after a time of day ("...00:00+02:00", "...T00:00:00Z"); a date such as
# "15-01-2024" has no time, so its "-2024" is never taken for an offset.
_OFFSET = re.compile(r"^(.*\d:\d{2}(?::\d{2}(?:\.\d+)?)?)\s*(?:[+-]\d{2}:?\d{2}|Z)$")
_DMY = re.compile(r"^\s*(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})")


def _parse_times(values: pd.Series, path: Path) -> pd.Series:
    """Bar times as naive wall-clock timestamps.

    * integer columns are epoch seconds (or milliseconds), as MT5 writes them;
    * a UTC offset is dropped, keeping the wall time: a daily bar stamped
      '2024-01-05 00:00+02:00' stays on Friday 5 January;
    * dd/mm/yyyy vs mm/dd/yyyy is decided from the data, and refused when the
      file cannot tell (every day and month <= 12).
    """
    if pd.api.types.is_numeric_dtype(values):
        unit = "ms" if values.abs().max() > 1e11 else "s"
        return pd.Series(pd.to_datetime(values, unit=unit))
    text = values.astype(str).str.strip()
    text = text.str.replace(_OFFSET, r"\1", regex=True).str.strip()
    dayfirst = False
    parts = text.str.extract(_DMY)
    if parts[0].notna().any():
        first, second = parts[0].dropna().astype(int), parts[1].dropna().astype(int)
        if (first > 12).any():
            dayfirst = True
        elif not (second > 12).any():
            raise ValueError(f"{path}: dates like {text.iloc[0]!r} can be day/month or month/day; "
                             "use year-month-day (2024-01-05)")
    stamps = pd.Series(pd.to_datetime(text, dayfirst=dayfirst, format="mixed"))
    if (stamps.dt.year < 1900).any():
        raise ValueError(f"{path}: dates like {text.iloc[0]!r} parse to year {stamps.dt.year.min()}: unknown date format")
    return stamps


def save_bars_csv(df: pd.DataFrame, path: str | Path) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    out = df[[c for c in OHLC + ["volume"] if c in df.columns]].copy()
    out.index.name = "time"
    out.to_csv(path, float_format="%.5f")


def _check(df: pd.DataFrame, path: Path) -> None:
    if df.empty:
        raise ValueError(f"{path}: no bars in the selected range")
    bad = (df["high"] < df[["open", "close"]].max(axis=1)) | (df["low"] > df[["open", "close"]].min(axis=1))
    if bad.any():
        raise ValueError(f"{path}: {int(bad.sum())} bars with high/low outside open/close, e.g. {df.index[bad][0]}")
