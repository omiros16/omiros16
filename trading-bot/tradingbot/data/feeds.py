"""Live bar feeds for the runner: MT5 (real) and replay (CSV/synthetic, for dry runs)."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Protocol

import pandas as pd

from ..brokers.base import Quote
from ..models import CostModel
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

    def history(self, symbol: str, timeframe: str, start: pd.Timestamp, end: pd.Timestamp | None = None) -> pd.DataFrame:
        """Closed bars from `start` to `end` (default: up to the last closed bar)."""
        self.client.ensure()
        self.client.select(symbol)
        tf = self.client.timeframe(timeframe)
        # MetaTrader5 reads naive datetimes as local time: pass UTC explicitly.
        since = _utc(start)
        until = _utc(end) if end is not None else datetime.now(timezone.utc) + timedelta(days=1)
        rates = self.client.mt5.copy_rates_range(symbol, tf, since, until)
        if rates is None:
            raise MT5Error(f"copy_rates_range({symbol}) failed: {self.client.mt5.last_error()}")
        df = rates_to_frame(rates)
        current = self.client.mt5.copy_rates_from_pos(symbol, tf, 0, 1)
        if current is not None and len(current):
            df = df[df.index < pd.Timestamp(int(current[0]["time"]), unit="s")]  # drop the bar still forming
        return df

    def quote(self, symbol: str) -> Quote:
        self.client.ensure()
        tick = self.client.mt5.symbol_info_tick(symbol)
        if tick is None:
            raise MT5Error(f"no tick for {symbol}: {self.client.mt5.last_error()}")
        return Quote(tick.bid, tick.ask, pd.Timestamp(tick.time, unit="s"))


def _utc(ts) -> datetime:
    ts = pd.Timestamp(ts)
    ts = ts.tz_localize("UTC") if ts.tzinfo is None else ts.tz_convert("UTC")
    return ts.to_pydatetime()


class SeededFeed:
    """Completes a short broker history with older bars from CSV files.

    New symbols (e.g. FTMO's rolling US100.sim since 2026) may have fewer daily
    bars than a strategy needs to warm up. Older bars of the same market (for
    US100: NQ futures or the cash NASDAQ-100, daily bars ending 17:00 New York) are
    prepended, scaled so that their closes match the broker's closes on the
    overlapping days (no artificial jump at the seam). Only used when the broker
    returns fewer bars than requested.
    """

    def __init__(self, feed: Feed, seeds: dict[str, pd.DataFrame]) -> None:
        self.feed = feed
        self.seeds = seeds

    def bars(self, symbol: str, timeframe: str, count: int) -> pd.DataFrame:
        live = self.feed.bars(symbol, timeframe, count)
        seed = self.seeds.get(symbol)
        if seed is None or timeframe != "D1" or len(live) >= count or live.empty:
            return live
        return splice_history(seed, live).tail(count)

    def quote(self, symbol: str) -> Quote:
        return self.feed.quote(symbol)

    def __getattr__(self, name):
        return getattr(self.feed, name)


def splice_history(seed: pd.DataFrame, live: pd.DataFrame) -> pd.DataFrame:
    """Older `seed` bars before the first `live` bar, rescaled to the live price level."""
    cols = ["open", "high", "low", "close"]
    overlap = seed.index.intersection(live.index)
    if len(overlap):
        ratio = float((live.loc[overlap, "close"] / seed.loc[overlap, "close"]).tail(20).median())
    else:
        before = seed[seed.index < live.index[0]]
        if before.empty:
            return live
        ratio = float(live["close"].iloc[0] / before["close"].iloc[-1])
    older = seed[seed.index < live.index[0]][cols] * ratio
    if "volume" in live.columns:
        older = older.assign(volume=0.0)
    return pd.concat([older, live[older.columns.intersection(live.columns).tolist() or cols]]).sort_index()


class ReplayFeed:
    """Feeds stored bars one at a time, to dry-run the live loop offline.

    Each call to `advance()` reveals one more bar per symbol. The live bot sees
    a bar as closed once the next one has started, so quotes are, by default,
    the next bar's open at the next bar's start time (as in the backtest, where
    a decision fills at the next open). With `at_close` set, quotes are the
    last close at the end of its bar (used for polls before a weekend).
    """

    def __init__(
        self,
        data: dict[str, pd.DataFrame],
        start: int,
        spread: dict[str, float] | None = None,
        costs: dict[str, CostModel] | None = None,
    ) -> None:
        self.data = data
        self.cursor = start
        self.spread = spread or {}
        self.costs = costs or {}
        self.at_close = False

    def advance(self, n: int = 1) -> bool:
        self.cursor += n
        return all(self.cursor <= len(df) for df in self.data.values())

    def bars(self, symbol: str, timeframe: str, count: int) -> pd.DataFrame:
        df = self.data[symbol]
        end = min(self.cursor, len(df))
        return df.iloc[max(0, end - count) : end]

    def quote(self, symbol: str) -> Quote:
        df = self.data[symbol]
        i = min(self.cursor, len(df)) - 1
        spacing = df.index[1] - df.index[0] if len(df) > 1 else pd.Timedelta(days=1)
        if i + 1 < len(df) and not self.at_close:
            when, bid = df.index[i + 1], float(df["open"].iat[i + 1])
        else:
            when = df.index[i] + min(spacing, pd.Timedelta(days=1)) - pd.Timedelta(minutes=1)
            bid = float(df["close"].iat[i])
        costs = self.costs.get(symbol)
        spread = costs.spread_at(bid) if costs is not None else self.spread.get(symbol, 0.0)
        return Quote(bid, bid + spread, when)
