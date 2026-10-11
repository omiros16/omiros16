"""Live bar feeds for the runner: MT5 (real) and replay (CSV/synthetic, for dry runs)."""

from __future__ import annotations

from typing import Protocol

import pandas as pd

from ..brokers.base import Quote
from ..mt5_client import MT5Client, MT5Error, rates_to_frame


class Feed(Protocol):
    def bars(self, symbol: str, timeframe: str, count: int) -> pd.DataFrame:
        """The last `count` CLOSED bars (the bar still forming is excluded)."""
        ...

    def quote(self, symbol: str) -> Quote: ...


class MT5Feed:
    def __init__(self, client: MT5Client) -> None:
        self.client = client

    def bars(self, symbol: str, timeframe: str, count: int) -> pd.DataFrame:
        self.client.ensure()
        self.client.select(symbol)
        # start_pos=1 skips bar 0, the one still forming.
        rates = self.client.mt5.copy_rates_from_pos(symbol, self.client.timeframe(timeframe), 1, count)
        if rates is None:
            raise MT5Error(f"copy_rates_from_pos({symbol}) failed: {self.client.mt5.last_error()}")
        return rates_to_frame(rates)

    def history(self, symbol: str, timeframe: str, start: pd.Timestamp, end: pd.Timestamp) -> pd.DataFrame:
        self.client.ensure()
        self.client.select(symbol)
        rates = self.client.mt5.copy_rates_range(
            symbol, self.client.timeframe(timeframe), start.to_pydatetime(), end.to_pydatetime()
        )
        if rates is None:
            raise MT5Error(f"copy_rates_range({symbol}) failed: {self.client.mt5.last_error()}")
        return rates_to_frame(rates)

    def quote(self, symbol: str) -> Quote:
        self.client.ensure()
        tick = self.client.mt5.symbol_info_tick(symbol)
        if tick is None:
            raise MT5Error(f"no tick for {symbol}: {self.client.mt5.last_error()}")
        return Quote(tick.bid, tick.ask, pd.Timestamp(tick.time, unit="s"))


class ReplayFeed:
    """Feeds stored bars one at a time, to dry-run the live loop offline.

    Each call to `advance()` reveals one more bar per symbol.
    """

    def __init__(self, data: dict[str, pd.DataFrame], start: int, spread: dict[str, float] | None = None) -> None:
        self.data = data
        self.cursor = start
        self.spread = spread or {}

    def advance(self, n: int = 1) -> bool:
        self.cursor += n
        return all(self.cursor <= len(df) for df in self.data.values())

    def bars(self, symbol: str, timeframe: str, count: int) -> pd.DataFrame:
        df = self.data[symbol]
        end = min(self.cursor, len(df))
        return df.iloc[max(0, end - count) : end]

    def quote(self, symbol: str) -> Quote:
        df = self.data[symbol]
        last = df.iloc[min(self.cursor, len(df)) - 1]
        bid = float(last["close"])
        return Quote(bid, bid + self.spread.get(symbol, 0.0), last.name)
