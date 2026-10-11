"""Order execution on MetaTrader 5 with `order_send`."""

from __future__ import annotations

import logging
import time

import pandas as pd

from ..models import AccountInfo, InstrumentSpec, Position, Side
from ..mt5_client import MT5Client
from .base import Broker, BrokerError, Quote

log = logging.getLogger(__name__)

# MQL5 trade server return codes (ENUM_TRADE_RETURN_CODES).
RETCODE_DONE = 10009
RETCODE_DONE_PARTIAL = 10010
RETRYABLE = {
    10004: "requote",
    10020: "prices changed",
    10021: "no quotes",
    10024: "too many requests",
    10031: "no connection",
}
# Bits of symbol_info().filling_mode (SYMBOL_FILLING_FOK / SYMBOL_FILLING_IOC).
SYMBOL_FILLING_FOK = 1
SYMBOL_FILLING_IOC = 2


class MT5Broker(Broker):
    def __init__(
        self,
        client: MT5Client,
        deviation_points: int = 20,
        filling: str = "auto",
        retries: int = 3,
        retry_delay: float = 1.0,
    ) -> None:
        if filling not in ("auto", "fok", "ioc", "return"):
            raise ValueError("filling must be auto, fok, ioc or return")
        self.client = client
        self.deviation = deviation_points
        self.filling = filling
        self.retries = retries
        self.retry_delay = retry_delay
        self.requests_sent = 0

    @property
    def mt5(self):
        return self.client.mt5

    # Read-only -----------------------------------------------------------------
    def account(self) -> AccountInfo:
        self.client.ensure()
        info = self.mt5.account_info()
        if info is None:
            raise BrokerError(f"account_info failed: {self.mt5.last_error()}")
        return AccountInfo(float(info.balance), float(info.equity), info.currency)

    def spec(self, symbol: str) -> InstrumentSpec:
        self.client.ensure()
        self.client.select(symbol)
        si = self.mt5.symbol_info(symbol)
        if si is None:
            raise BrokerError(f"symbol_info({symbol}) failed: {self.mt5.last_error()}")
        if not si.trade_tick_size:
            raise BrokerError(f"{symbol}: tick size is zero")
        return InstrumentSpec(
            symbol=symbol,
            value_per_point=si.trade_tick_value / si.trade_tick_size,
            volume_min=si.volume_min,
            volume_max=si.volume_max,
            volume_step=si.volume_step,
            digits=si.digits,
        )

    def quote(self, symbol: str) -> Quote:
        self.client.ensure()
        tick = self.mt5.symbol_info_tick(symbol)
        if tick is None:
            raise BrokerError(f"no tick for {symbol}: {self.mt5.last_error()}")
        return Quote(tick.bid, tick.ask, pd.Timestamp(tick.time, unit="s"))

    def positions(self, magic: int | None = None) -> list[Position]:
        self.client.ensure()
        raw = self.mt5.positions_get()
        if raw is None:
            raise BrokerError(f"positions_get failed: {self.mt5.last_error()}")
        out = []
        for p in raw:
            if magic is not None and p.magic != magic:
                continue
            out.append(
                Position(
                    symbol=p.symbol,
                    side=Side.LONG if p.type == self.mt5.POSITION_TYPE_BUY else Side.SHORT,
                    volume=p.volume,
                    entry_price=p.price_open,
                    entry_time=pd.Timestamp(p.time, unit="s"),
                    stop_loss=p.sl or None,
                    take_profit=p.tp or None,
                    ticket=p.ticket,
                    magic=p.magic,
                    comment=p.comment,
                    swap=p.swap,
                )
            )
        return out

    # Trading -------------------------------------------------------------------
    def open(self, symbol, side, volume, sl, tp, magic, comment) -> Position:
        digits = self.spec(symbol).digits
        order_type = self.mt5.ORDER_TYPE_BUY if side is Side.LONG else self.mt5.ORDER_TYPE_SELL

        def build() -> dict:
            q = self.quote(symbol)
            return {
                "action": self.mt5.TRADE_ACTION_DEAL,
                "symbol": symbol,
                "volume": float(volume),
                "type": order_type,
                "price": q.ask if side is Side.LONG else q.bid,
                "sl": round(sl, digits),
                "tp": round(tp, digits) if tp else 0.0,
                "deviation": self.deviation,
                "magic": magic,
                "comment": _clean_comment(comment),
                "type_time": self.mt5.ORDER_TIME_GTC,
                "type_filling": self._filling(symbol),
            }

        result = self._send(build, f"open {side.label} {volume} {symbol}")
        return Position(
            symbol=symbol,
            side=side,
            volume=result.volume or volume,
            entry_price=result.price,
            entry_time=pd.Timestamp.now(),
            stop_loss=sl,
            take_profit=tp,
            ticket=result.order,
            magic=magic,
            comment=comment,
        )

    def close(self, position: Position, comment: str = "", volume: float | None = None) -> float:
        order_type = self.mt5.ORDER_TYPE_SELL if position.side is Side.LONG else self.mt5.ORDER_TYPE_BUY
        lots = float(position.volume if volume is None else min(volume, position.volume))

        def build() -> dict:
            q = self.quote(position.symbol)
            return {
                "action": self.mt5.TRADE_ACTION_DEAL,
                "symbol": position.symbol,
                "volume": lots,
                "type": order_type,
                "position": position.ticket,
                "price": q.bid if position.side is Side.LONG else q.ask,
                "deviation": self.deviation,
                "magic": position.magic,
                "comment": _clean_comment(comment),
                "type_time": self.mt5.ORDER_TIME_GTC,
                "type_filling": self._filling(position.symbol),
            }

        result = self._send(build, f"close #{position.ticket} {position.symbol}")
        return float(result.price)

    def modify(self, position: Position, sl: float | None, tp: float | None) -> None:
        digits = self.spec(position.symbol).digits

        def build() -> dict:
            return {
                "action": self.mt5.TRADE_ACTION_SLTP,
                "symbol": position.symbol,
                "position": position.ticket,
                "sl": round(sl, digits) if sl else 0.0,
                "tp": round(tp, digits) if tp else 0.0,
                "magic": position.magic,
            }

        self._send(build, f"modify #{position.ticket} sl={sl} tp={tp}")

    def shutdown(self) -> None:
        self.client.shutdown()

    # Internals -----------------------------------------------------------------
    def _filling(self, symbol: str) -> int:
        mt5 = self.mt5
        if self.filling != "auto":
            return {"fok": mt5.ORDER_FILLING_FOK, "ioc": mt5.ORDER_FILLING_IOC, "return": mt5.ORDER_FILLING_RETURN}[
                self.filling
            ]
        mode = mt5.symbol_info(symbol).filling_mode
        if mode & SYMBOL_FILLING_FOK:
            return mt5.ORDER_FILLING_FOK
        if mode & SYMBOL_FILLING_IOC:
            return mt5.ORDER_FILLING_IOC
        return mt5.ORDER_FILLING_RETURN

    def _send(self, build, what: str):
        self.client.ensure()
        last = None
        for attempt in range(1, self.retries + 1):
            request = build()
            self.requests_sent += 1
            result = self.mt5.order_send(request)
            if result is None:
                last = f"order_send returned None: {self.mt5.last_error()}"
            elif result.retcode in (RETCODE_DONE, RETCODE_DONE_PARTIAL):
                log.info("%s: done (deal %s, price %s)", what, getattr(result, "deal", "?"), result.price)
                return result
            elif result.retcode in RETRYABLE:
                last = f"{RETRYABLE[result.retcode]} ({result.retcode})"
            else:
                raise BrokerError(f"{what} rejected: {result.retcode} {result.comment}")
            log.warning("%s: attempt %d failed: %s", what, attempt, last)
            time.sleep(self.retry_delay * attempt)
        raise BrokerError(f"{what} failed after {self.retries} attempts: {last}")


def _clean_comment(text: str) -> str:
    """MT5 comments: ASCII, at most 31 characters."""
    return text.encode("ascii", "ignore").decode()[:31]
