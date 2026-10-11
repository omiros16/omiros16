"""Load and save OHLC bars as CSV.

Accepts the common layouts:
  * time,open,high,low,close[,volume]            (this bot's own format)
  * Date,Open,High,Low,Close,...                 (Yahoo / most exports)
  * <DATE>\t<TIME>\t<OPEN>\t<HIGH>\t<LOW>\t<CLOSE>  (MT5 "Export bars")
"""

from __future__ import annotations

from pathlib import Path

import pandas as pd

OHLC = ["open", "high", "low", "close"]


def load_bars_csv(path: str | Path, start: str | None = None, end: str | None = None) -> pd.DataFrame:
    path = Path(path)
    with path.open("r", encoding="utf-8-sig") as fh:
        first = fh.readline()
    sep = "\t" if "\t" in first else ("," if "," in first else ";")
    raw = pd.read_csv(path, sep=sep)
    raw.columns = [c.strip().strip("<>").lower() for c in raw.columns]

    if "date" in raw.columns and "time" in raw.columns:
        stamps = pd.to_datetime(raw["date"].astype(str) + " " + raw["time"].astype(str))
    else:
        col = next((c for c in ("time", "datetime", "date", "timestamp") if c in raw.columns), None)
        if col is None:
            raise ValueError(f"{path}: no time/date column in {list(raw.columns)}")
        stamps = pd.to_datetime(raw[col], utc=False)
    if getattr(stamps.dt, "tz", None) is not None:
        stamps = stamps.dt.tz_convert(None)

    missing = [c for c in OHLC if c not in raw.columns]
    if missing:
        raise ValueError(f"{path}: missing columns {missing}")
    cols = OHLC + (["volume"] if "volume" in raw.columns else ["tickvol"] if "tickvol" in raw.columns else [])
    df = raw[cols].astype(float)
    df.columns = OHLC + (["volume"] if len(cols) == 5 else [])
    df.index = pd.DatetimeIndex(stamps, name="time")
    df = df[~df.index.duplicated(keep="last")].sort_index().dropna(subset=OHLC)
    if start:
        df = df[df.index >= pd.Timestamp(start)]
    if end:
        df = df[df.index <= pd.Timestamp(end)]
    _check(df, path)
    return df


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
