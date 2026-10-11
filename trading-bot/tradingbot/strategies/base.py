"""Strategy interface. The same class runs in the backtest and in the live bot."""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, ClassVar

import pandas as pd

from ..models import Position, Signal


class Strategy(ABC):
    """A strategy turns closed bars into signals.

    Contract:
      * `prepare(bars)` adds indicator columns. They must be causal: the value
        in row i may only depend on rows <= i. tests/test_strategies.py
        checks this for every registered strategy.
      * `on_bar(data, i, position)` is called after bar i has closed. It may
        only read rows <= i. Orders it asks for are filled at the next price
        (the next bar's open in the backtest).
      * Every OPEN signal carries a stop loss.
    """

    name: ClassVar[str]
    defaults: ClassVar[dict[str, Any]] = {}

    def __init__(self, **params: Any) -> None:
        unknown = set(params) - set(self.defaults)
        if unknown:
            raise ValueError(f"{self.name}: unknown parameter(s) {sorted(unknown)}; allowed: {sorted(self.defaults)}")
        self.params: dict[str, Any] = {**self.defaults, **params}
        self.validate()

    def validate(self) -> None:
        """Override to check parameter values."""

    @property
    @abstractmethod
    def warmup_bars(self) -> int:
        """Bars needed before the first signal can be produced."""

    @abstractmethod
    def prepare(self, bars: pd.DataFrame) -> pd.DataFrame:
        ...

    @abstractmethod
    def on_bar(self, data: pd.DataFrame, i: int, position: Position | None) -> Signal | None:
        ...

    def __repr__(self) -> str:
        return f"{type(self).__name__}({self.params})"


def entry_index(data: pd.DataFrame, entry_time: pd.Timestamp) -> int:
    """Index of the bar during which the position was opened."""
    return max(int(data.index.searchsorted(entry_time, side="right")) - 1, 0)


def bars_held(data: pd.DataFrame, i: int, entry_time: pd.Timestamp) -> int:
    """Bars the position has been open for at the close of bar i (entry bar = 1)."""
    return i - entry_index(data, entry_time) + 1
