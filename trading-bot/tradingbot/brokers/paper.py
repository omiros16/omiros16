"""Simulated broker for paper trading on live (or replayed) prices.

Fills market orders at the current quote with the configured slippage and
checks stops/targets on every new closed bar, the same way the backtester
does. State is saved to JSON so a restart keeps the paper positions.

For the most realistic paper trading, run the real MT5 broker against a demo
or free-trial account instead; use this when no MT5 account is available.
"""

from __future__ import annotations

import json
import logging
import os
from dataclasses import replace
from pathlib import Path
from typing import Callable

import pandas as pd

from ..models import AccountInfo, CostModel, InstrumentSpec, Position, Side
from .base import Broker, BrokerError, Fill, Quote

log = logging.getLogger(__name__)


class PaperBroker(Broker):
    simulated = True

    def __init__(
        self,
        specs: dict[str, InstrumentSpec],
        costs: dict[str, CostModel],
        initial_balance: float = 100_000.0,
        state_path: str | Path | None = None,
        quote_source: Callable[[str], Quote] | None = None,
        currency: str = "USD",
    ) -> None:
        self.specs = specs
        self.costs = costs
        self.currency = currency
        self.quote_source = quote_source
        self.state_path = Path(state_path) if state_path else None
        self.balance = initial_balance
        self.next_ticket = 1
        self._positions: dict[int, Position] = {}
        self._last_bar: dict[str, pd.Timestamp] = {}
        self._last_close: dict[str, float] = {}
        # Day up to which each position's swap has been charged (by ticket).
        self._swap_day: dict[int, pd.Timestamp] = {}
        self._load()

    # Persistence ---------------------------------------------------------------
    def _load(self) -> None:
        if not self.state_path or not self.state_path.exists():
            return
        raw = json.loads(self.state_path.read_text(encoding="utf-8"))
        self.balance = raw["balance"]
        self.next_ticket = raw["next_ticket"]
        self._last_bar = {k: pd.Timestamp(v) for k, v in raw.get("last_bar", {}).items()}
        self._last_close = raw.get("last_close", {})
        swap_day = raw.get("swap_day", {})
        for p in raw["positions"]:
            pos = Position(
                symbol=p["symbol"],
                side=Side(p["side"]),
                volume=p["volume"],
                entry_price=p["entry_price"],
                entry_time=pd.Timestamp(p["entry_time"]),
                stop_loss=p["stop_loss"],
                take_profit=p["take_profit"],
                ticket=p["ticket"],
                magic=p["magic"],
                comment=p["comment"],
                swap=p["swap"],
                commission=p["commission"],
            )
            self._positions[pos.ticket] = pos
            last = self._last_bar.get(pos.symbol, pos.entry_time)
            self._swap_day[pos.ticket] = pd.Timestamp(swap_day.get(str(pos.ticket), max(last, pos.entry_time))).normalize()

    def _save(self) -> None:
        if not self.state_path:
            return
        data = {
            "balance": self.balance,
            "next_ticket": self.next_ticket,
            "last_bar": {k: v.isoformat() for k, v in self._last_bar.items()},
            "last_close": self._last_close,
            "swap_day": {str(k): v.isoformat() for k, v in self._swap_day.items()},
            "positions": [
                {
                    "symbol": p.symbol,
                    "side": int(p.side),
                    "volume": p.volume,
                    "entry_price": p.entry_price,
                    "entry_time": p.entry_time.isoformat(),
                    "stop_loss": p.stop_loss,
                    "take_profit": p.take_profit,
                    "ticket": p.ticket,
                    "magic": p.magic,
                    "comment": p.comment,
                    "swap": p.swap,
                    "commission": p.commission,
                }
                for p in self._positions.values()
            ],
        }
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        os.replace(tmp, self.state_path)

    # Broker API ----------------------------------------------------------------
    def spec(self, symbol: str) -> InstrumentSpec:
        try:
            return self.specs[symbol]
        except KeyError:
            raise BrokerError(f"no instrument spec for {symbol}") from None

    def quote(self, symbol: str) -> Quote:
        if self.quote_source is not None:
            return self.quote_source(symbol)
        if symbol not in self._last_close:
            raise BrokerError(f"no price for {symbol} yet")
        bid = self._last_close[symbol]
        return Quote(bid, bid + self.costs[symbol].spread_at(bid), self._last_bar.get(symbol))

    def _mark(self, pos: Position) -> float:
        q = self.quote(pos.symbol)
        return q.bid if pos.side is Side.LONG else q.ask

    def account(self) -> AccountInfo:
        equity = self.balance
        for pos in self._positions.values():
            try:
                equity += pos.unrealized(self._mark(pos), self.spec(pos.symbol).value_per_point)
            except BrokerError:
                pass
        return AccountInfo(self.balance, equity, self.currency)

    def positions(self, magic: int | None = None) -> list[Position]:
        return [replace(p) for p in self._positions.values() if magic is None or p.magic == magic]

    def open(self, symbol, side, volume, sl, tp, magic, comment) -> Position:
        q = self.quote(symbol)
        slip = self.costs[symbol].slippage_at(q.bid)
        price = q.ask + slip if side is Side.LONG else q.bid - slip
        commission = self.costs[symbol].commission_per_lot * volume
        self.balance -= commission
        pos = Position(
            symbol=symbol,
            side=side,
            volume=volume,
            entry_price=price,
            entry_time=q.time or pd.Timestamp.now(tz="UTC").tz_localize(None),
            stop_loss=sl,
            take_profit=tp,
            ticket=self.next_ticket,
            magic=magic,
            comment=comment,
            commission=commission,
        )
        self._positions[pos.ticket] = pos
        self._swap_day[pos.ticket] = pos.entry_time.normalize()
        self.next_ticket += 1
        self._save()
        log.info("PAPER open %s %s %.2f @ %.2f sl=%s tp=%s", symbol, side.label, volume, price, sl, tp)
        return replace(pos)

    def close(self, position: Position, comment: str = "", volume: float | None = None) -> Fill:
        pos = self._positions.get(position.ticket)
        if pos is None:
            raise BrokerError(f"paper position {position.ticket} not found")
        q = self.quote(pos.symbol)
        if q.time is not None:
            self._charge_swap(pos, pd.Timestamp(q.time), q.bid)  # nights since the last bar processed
        slip = self.costs[pos.symbol].slippage_at(q.bid)
        price = q.bid - slip if pos.side is Side.LONG else q.ask + slip
        if volume is not None and volume < pos.volume - 1e-9:
            vpp = self.spec(pos.symbol).value_per_point
            part = replace(pos, volume=volume)
            self.balance += part.unrealized(price, vpp) - self.costs[pos.symbol].commission_per_lot * volume
            pos.volume = round(pos.volume - volume, 8)
            self._save()
            return Fill(price, volume)
        closed = pos.volume
        self._settle(pos, price)
        return Fill(price, closed)

    def modify(self, position: Position, sl: float | None, tp: float | None) -> None:
        pos = self._positions.get(position.ticket)
        if pos is None:
            raise BrokerError(f"paper position {position.ticket} not found")
        pos.stop_loss, pos.take_profit = sl, tp
        self._save()

    def _settle(self, pos: Position, price: float) -> None:
        vpp = self.spec(pos.symbol).value_per_point
        commission = self.costs[pos.symbol].commission_per_lot * pos.volume
        self.balance += pos.unrealized(price, vpp) - commission
        del self._positions[pos.ticket]
        self._swap_day.pop(pos.ticket, None)
        self._save()

    def _charge_swap(self, pos: Position, t: pd.Timestamp, price: float) -> None:
        """Financing for every rollover (midnight, server time) between the last charge and `t`."""
        day = t.normalize()
        since = self._swap_day.get(pos.ticket, pos.entry_time.normalize())
        if day <= since or pos.entry_time >= t:
            return
        cost = self.costs[pos.symbol]
        nights = (day - since).days
        swap = nights * cost.swap_per_lot_night(pos.side, price, self.spec(pos.symbol).value_per_point) * pos.volume
        pos.swap += swap
        self.balance += swap
        self._swap_day[pos.ticket] = day

    def on_bars(self, symbol: str, bars: pd.DataFrame) -> list[tuple[Position, float, str]]:
        """Apply swaps and stops/targets for bars closed since the last call."""
        if bars.empty:
            return []
        last = self._last_bar.get(symbol)
        new = bars if last is None else bars[bars.index > last]
        closed: list[tuple[Position, float, str]] = []
        if last is not None:
            cost = self.costs[symbol]
            spacing = _bar_spacing(bars.index)
            for t, bar in new.iterrows():
                spread, slip = cost.spread_at(bar["open"]), cost.slippage_at(bar["open"])
                for pos in [p for p in self._positions.values() if p.symbol == symbol]:
                    self._charge_swap(pos, t, bar["open"])
                    if pos.entry_time >= t + spacing:
                        continue  # the whole bar happened before the fill
                    # A position filled during this bar is checked against the whole bar
                    # (pessimistic: the low may have come before the fill).
                    hit = _check_exit(pos, bar, spread, slip)
                    if hit:
                        price, reason = hit
                        closed.append((replace(pos), price, reason))
                        self._settle(pos, price)
        self._last_bar[symbol] = bars.index[-1]
        self._last_close[symbol] = float(bars["close"].iloc[-1])
        self._save()
        return closed


def _bar_spacing(index: pd.DatetimeIndex) -> pd.Timedelta:
    """Typical bar length (median gap), used to tell which bar a fill happened in."""
    if len(index) < 2:
        return pd.Timedelta(days=1)
    return pd.Series(index).diff().median()


def _check_exit(pos: Position, bar: pd.Series, spread: float, slip: float) -> tuple[float, str] | None:
    side = pos.side
    shift = 0.0 if side is Side.LONG else spread
    o, h, low = bar["open"] + shift, bar["high"] + shift, bar["low"] + shift
    adverse = low if side is Side.LONG else h
    favorable = h if side is Side.LONG else low
    sl, tp = pos.stop_loss, pos.take_profit
    if sl is not None and (o - sl) * side <= 0:
        return o - slip * side, "stop loss (gap)"
    if tp is not None and (o - tp) * side >= 0:
        return o, "take profit (gap)"
    if sl is not None and (adverse - sl) * side <= 0:
        return sl - slip * side, "stop loss"
    if tp is not None and (favorable - tp) * side >= 0:
        return tp, "take profit"
    return None
