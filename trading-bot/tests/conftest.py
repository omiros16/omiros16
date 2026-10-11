from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tradingbot.models import Side, Signal  # noqa: E402
from tradingbot.strategies import STRATEGIES  # noqa: E402
from tradingbot.strategies.base import Strategy  # noqa: E402


def make_frame(rows: list[tuple[float, float, float, float]], start: str = "2024-01-01", freq: str = "D") -> pd.DataFrame:
    """rows of (open, high, low, close). 2024-01-01 is a Monday."""
    index = pd.date_range(start, periods=len(rows), freq=freq, name="time")
    return pd.DataFrame(rows, columns=["open", "high", "low", "close"], index=index, dtype=float)


class Scripted(Strategy):
    """Emits pre-planned signals keyed by bar timestamp (ISO string)."""

    name = "scripted"
    defaults = {"plan": {}}

    @property
    def warmup_bars(self) -> int:
        return 1

    def prepare(self, bars: pd.DataFrame) -> pd.DataFrame:
        return bars.copy()

    def on_bar(self, data, i, position):
        step = self.params["plan"].get(data.index[i].isoformat())
        if step is None:
            return None
        kind = step[0]
        if kind == "long":
            return Signal.open(Side.LONG, step[1], step[2] if len(step) > 2 else None, "plan")
        if kind == "short":
            return Signal.open(Side.SHORT, step[1], step[2] if len(step) > 2 else None, "plan")
        if kind == "close":
            return Signal.close("plan")
        if kind == "modify":
            return Signal.modify(step[1], step[2] if len(step) > 2 else None, "plan")
        raise ValueError(kind)


@pytest.fixture
def scripted_registered():
    STRATEGIES["scripted"] = Scripted
    yield Scripted
    STRATEGIES.pop("scripted", None)


class FakeMT5:
    """The subset of the MetaTrader5 module the bot uses."""

    TRADE_ACTION_DEAL = 1
    TRADE_ACTION_SLTP = 6
    ORDER_TYPE_BUY = 0
    ORDER_TYPE_SELL = 1
    POSITION_TYPE_BUY = 0
    POSITION_TYPE_SELL = 1
    ORDER_TIME_GTC = 0
    ORDER_FILLING_FOK = 0
    ORDER_FILLING_IOC = 1
    ORDER_FILLING_RETURN = 2
    TIMEFRAME_D1 = 16408
    TIMEFRAME_H4 = 16388

    def __init__(self) -> None:
        self.requests: list[dict] = []
        self.retcodes: list[int] = []
        self.open_positions: list[SimpleNamespace] = []
        self.filling_mode = 2
        self.rates_calls: list[tuple] = []

    def initialize(self, **kwargs):
        self.init_kwargs = kwargs
        return True

    def shutdown(self):
        pass

    def last_error(self):
        return (1, "fake error")

    def terminal_info(self):
        return SimpleNamespace(trade_allowed=True)

    def account_info(self):
        return SimpleNamespace(login=1, server="Fake-Demo", currency="USD", balance=100_000.0, equity=99_500.0, trade_mode=0)

    def symbol_select(self, symbol, enable):
        return True

    def symbol_info(self, symbol):
        return SimpleNamespace(
            trade_tick_value=0.1, trade_tick_size=0.01, volume_min=0.01, volume_max=50.0,
            volume_step=0.01, digits=2, filling_mode=self.filling_mode,
        )

    def symbol_info_tick(self, symbol):
        return SimpleNamespace(bid=100.0, ask=101.0, time=1_700_000_000)

    def positions_get(self, **kwargs):
        return tuple(self.open_positions)

    def order_send(self, request):
        self.requests.append(dict(request))
        code = self.retcodes.pop(0) if self.retcodes else 10009
        return SimpleNamespace(retcode=code, order=555, deal=777, price=request.get("price", 0.0),
                               volume=request.get("volume", 0.0), comment="fake")

    def copy_rates_from_pos(self, symbol, timeframe, start, count):
        self.rates_calls.append((symbol, timeframe, start, count))
        dtype = [("time", "i8"), ("open", "f8"), ("high", "f8"), ("low", "f8"), ("close", "f8"),
                 ("tick_volume", "i8"), ("spread", "i4"), ("real_volume", "i8")]
        base = 1_700_000_000
        rows = [(base + k * 86400, 100 + k, 101 + k, 99 + k, 100.5 + k, 10, 1, 0) for k in range(count)]
        return np.array(rows, dtype=dtype)


@pytest.fixture
def fake_mt5():
    return FakeMT5()
