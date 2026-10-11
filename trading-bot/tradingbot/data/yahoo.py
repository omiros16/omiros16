"""Daily index history from Yahoo Finance (optional: pip install yfinance).

Good enough for D1 research on the cash index (^NDX for US100, ^GSPC for
US500). It is not your broker's CFD feed: no spread, different session times.
Use MT5 data for the final checks.
"""

from __future__ import annotations

import pandas as pd

TICKERS = {"US100": "^NDX", "US500": "^GSPC"}


def download_daily(ticker: str, start: str = "2000-01-01", end: str | None = None) -> pd.DataFrame:
    try:
        import yfinance as yf
    except ImportError as exc:  # pragma: no cover - optional dependency
        raise SystemExit("yfinance is not installed: pip install yfinance") from exc
    ticker = TICKERS.get(ticker.upper(), ticker)
    raw = yf.download(ticker, start=start, end=end, interval="1d", auto_adjust=False, progress=False)
    if raw.empty:
        raise SystemExit(f"no data for {ticker}")
    if isinstance(raw.columns, pd.MultiIndex):
        raw.columns = raw.columns.get_level_values(0)
    df = raw.rename(columns=str.lower)[["open", "high", "low", "close", "volume"]].astype(float)
    df.index = pd.DatetimeIndex(df.index.tz_localize(None) if df.index.tz else df.index, name="time")
    return df.dropna()
