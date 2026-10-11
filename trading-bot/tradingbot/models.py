"""Core data types shared by the backtester and the live bot."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum, IntEnum

import pandas as pd


class Side(IntEnum):
    LONG = 1
    SHORT = -1

    @property
    def label(self) -> str:
        return "LONG" if self is Side.LONG else "SHORT"


class SignalKind(str, Enum):
    OPEN = "open"
    CLOSE = "close"
    MODIFY = "modify"
    TARGET = "target"


@dataclass(frozen=True)
class Signal:
    """What a strategy wants to happen at the next opportunity.

    Prices are absolute. Every OPEN must carry a stop loss: the risk manager
    sizes the trade from the distance to it and rejects trades without one.

    TARGET signals come from exposure-based strategies: `exposure` is the
    wanted long position as a multiple of equity (0.5 = notional worth half
    the account). The executor resizes the position when the difference is
    at least `min_change` (also in multiples of equity).
    """

    kind: SignalKind
    side: Side | None = None
    stop_loss: float | None = None
    take_profit: float | None = None
    reason: str = ""
    exposure: float | None = None
    min_change: float = 0.0

    @staticmethod
    def open(side: Side, stop_loss: float, take_profit: float | None = None, reason: str = "") -> "Signal":
        return Signal(SignalKind.OPEN, side, stop_loss, take_profit, reason)

    @staticmethod
    def close(reason: str = "") -> "Signal":
        return Signal(SignalKind.CLOSE, reason=reason)

    @staticmethod
    def modify(stop_loss: float | None, take_profit: float | None = None, reason: str = "") -> "Signal":
        return Signal(SignalKind.MODIFY, stop_loss=stop_loss, take_profit=take_profit, reason=reason)

    @staticmethod
    def target(exposure: float, min_change: float = 0.0, reason: str = "") -> "Signal":
        return Signal(SignalKind.TARGET, side=Side.LONG, exposure=max(0.0, exposure), min_change=min_change,
                      reason=reason)


@dataclass
class Position:
    symbol: str
    side: Side
    volume: float
    entry_price: float
    entry_time: pd.Timestamp
    stop_loss: float | None = None
    take_profit: float | None = None
    ticket: int | None = None
    magic: int = 0
    comment: str = ""
    # Accumulated financing (swap) and commission, in account currency.
    swap: float = 0.0
    commission: float = 0.0
    initial_risk: float = 0.0

    def unrealized(self, price: float, value_per_point: float) -> float:
        return (price - self.entry_price) * int(self.side) * self.volume * value_per_point

    def loss_if_stopped(self, price: float, value_per_point: float) -> float:
        """Further loss (>= 0) from `price` if the stop loss is hit."""
        if self.stop_loss is None:
            return float("inf")
        move = (price - self.stop_loss) * int(self.side)
        return max(0.0, move) * self.volume * value_per_point


@dataclass(frozen=True)
class InstrumentSpec:
    """Contract details. In live mode these come from the broker (MT5 symbol_info)."""

    symbol: str
    # Account currency gained/lost for a 1.0 price move with 1 lot.
    value_per_point: float = 1.0
    volume_min: float = 0.01
    volume_max: float = 100.0
    volume_step: float = 0.01
    digits: int = 2


@dataclass(frozen=True)
class CostModel:
    """Trading costs.

    Spread and slippage are either fixed price units (`spread`, `slippage`, index
    points) or, when set, basis points of the current price (`spread_bp`,
    `slippage_bp`). Swap is either a fixed amount per lot per night
    (`swap_*_per_lot`) or, when set, an annual percentage of the position's
    notional (`swap_*_pct`, charged /360 per calendar night; negative = you pay).
    The percentage forms keep costs realistic across decades of price history:
    a fixed 1.5-point spread is 0.6 bp at 25,000 but 40 bp at 350.
    """

    spread: float = 1.0
    slippage: float = 0.5
    # Per lot, per side, in account currency.
    commission_per_lot: float = 0.0
    # Per lot, per night held, in account currency (negative = you pay).
    swap_long_per_lot: float = 0.0
    swap_short_per_lot: float = 0.0
    spread_bp: float | None = None
    slippage_bp: float | None = None
    swap_long_pct: float | None = None
    swap_short_pct: float | None = None

    def __post_init__(self) -> None:
        for name in ("spread", "slippage", "commission_per_lot", "spread_bp", "slippage_bp"):
            value = getattr(self, name)
            if value is not None and (not isinstance(value, (int, float)) or value < 0):
                raise ValueError(f"costs.{name} must be a number >= 0, got {value!r}")
        for name in ("swap_long_per_lot", "swap_short_per_lot", "swap_long_pct", "swap_short_pct"):
            value = getattr(self, name)
            if value is not None and not isinstance(value, (int, float)):
                raise ValueError(f"costs.{name} must be a number, got {value!r}")

    def spread_at(self, price: float) -> float:
        """Spread in price units at this price level."""
        return price * self.spread_bp / 1e4 if self.spread_bp is not None else self.spread

    def slippage_at(self, price: float) -> float:
        return price * self.slippage_bp / 1e4 if self.slippage_bp is not None else self.slippage

    def swap_per_lot_night(self, side: "Side", price: float, value_per_point: float) -> float:
        """Account currency per lot per night (negative = paid)."""
        pct = self.swap_long_pct if side is Side.LONG else self.swap_short_pct
        if pct is not None:
            return pct / 100 / 360 * price * value_per_point
        return self.swap_long_per_lot if side is Side.LONG else self.swap_short_per_lot


@dataclass
class AccountInfo:
    balance: float
    equity: float
    currency: str = "USD"
    login: int | None = None


@dataclass
class Trade:
    symbol: str
    side: Side
    volume: float
    entry_time: pd.Timestamp
    entry_price: float
    exit_time: pd.Timestamp
    exit_price: float
    gross_pnl: float
    commission: float
    swap: float
    reason: str
    initial_risk: float = 0.0
    balance_after: float = 0.0
    extra: dict = field(default_factory=dict)

    @property
    def net_pnl(self) -> float:
        return self.gross_pnl - self.commission + self.swap

    @property
    def r_multiple(self) -> float:
        return self.net_pnl / self.initial_risk if self.initial_risk > 0 else float("nan")
