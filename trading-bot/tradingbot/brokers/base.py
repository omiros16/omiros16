"""Broker interface used by the live runner."""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass

import pandas as pd

from ..models import AccountInfo, InstrumentSpec, Position, Side


class BrokerError(RuntimeError):
    pass


class MarketClosedError(BrokerError):
    """The order was rejected because the market (or the symbol's trade session) is closed."""


class UnknownOutcomeError(BrokerError):
    """The request may or may not have been executed (e.g. no reply from the server).

    Never blindly resend a market order after this: reconcile with the open
    positions first.
    """


@dataclass(frozen=True)
class Fill:
    price: float
    volume: float


@dataclass(frozen=True)
class Quote:
    bid: float
    ask: float
    time: pd.Timestamp | None = None


class Broker(ABC):
    """Everything the bot needs from a trading account.

    `magic` identifies the bot's own positions; positions opened by hand or by
    other EAs are never touched.
    """

    # True for brokers that simulate stops / swaps from bars (see on_bars).
    simulated = False

    @abstractmethod
    def account(self) -> AccountInfo: ...

    @abstractmethod
    def spec(self, symbol: str) -> InstrumentSpec: ...

    @abstractmethod
    def quote(self, symbol: str) -> Quote: ...

    @abstractmethod
    def positions(self, magic: int | None = None) -> list[Position]: ...

    @abstractmethod
    def open(
        self, symbol: str, side: Side, volume: float, sl: float, tp: float | None, magic: int, comment: str
    ) -> Position: ...

    @abstractmethod
    def close(self, position: Position, comment: str = "", volume: float | None = None) -> Fill:
        """Close the position (or `volume` lots of it) at market. Returns the price and the lots closed."""

    @abstractmethod
    def modify(self, position: Position, sl: float | None, tp: float | None) -> None: ...

    def on_bars(self, symbol: str, bars: pd.DataFrame) -> list[tuple[Position, float, str]]:
        """Hook for simulated brokers to process stops on new bars.

        Returns positions closed by stop/target as (position, price, reason).
        Real brokers handle stops server-side and return nothing.
        """
        return []

    def shutdown(self) -> None:
        pass
