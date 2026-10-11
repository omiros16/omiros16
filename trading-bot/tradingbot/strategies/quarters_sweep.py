"""Liquidity sweep of a quarter level / swing point, then reclaim (SMC + Quarters).

Hypothesis (reading list groups 4-5; Osler 2000/2003, Donaldson & Kim 1993):
stop orders cluster just beyond round numbers and swing points. When price
runs those stops and closes back inside within the same bar, the stop-driven
move was absorbed and price tends to return the other way. This is the
SMC/ICT "liquidity sweep" written as one exact, testable rule. It is a
hypothesis to backtest, not a known edge.

Rules (on closed bars, default H4), long side; short is the mirror:
  level    L = sweep level from smc.liquidity_sweeps (quarter level and/or
           latest confirmed swing low)
  sweep    prev close > L, low < L - min_penetration_atr * ATR, close > L
  depth    L - low <= max_penetration_atr * ATR (a sweep, not a breakdown)
  bias     close > SMA(trend_ma) if trend_ma > 0
  stop     low - sl_buffer_atr * ATR
  target   close + rr * (close - stop)
  exit     stop, target, or time stop after max_bars bars
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from ..indicators import atr, sma
from ..models import Position, Side, Signal
from ..smc import liquidity_sweeps
from .base import Strategy, bars_held

LEVEL_SOURCES = ("quarters", "swings", "both")


class QuartersSweep(Strategy):
    name = "quarters_sweep"
    defaults = {
        "quarter_size": 250.0,
        "level_source": "both",
        "swing_strength": 3,
        "atr_period": 14,
        "min_penetration_atr": 0.05,
        "max_penetration_atr": 1.0,
        "trend_ma": 200,
        "sl_buffer_atr": 0.2,
        "rr": 2.0,
        "max_bars": 20,
        "allow_short": True,
    }

    def validate(self) -> None:
        p = self.params
        if p["level_source"] not in LEVEL_SOURCES:
            raise ValueError(f"level_source must be one of {LEVEL_SOURCES}")
        if p["quarter_size"] <= 0:
            raise ValueError("quarter_size must be > 0")
        if p["rr"] <= 0:
            raise ValueError("rr must be > 0")
        if int(p["atr_period"]) < 1:
            raise ValueError("atr_period must be >= 1")
        if p["level_source"] != "quarters" and int(p["swing_strength"]) < 1:
            raise ValueError("swing_strength must be >= 1")
        if not 0 <= p["min_penetration_atr"] <= p["max_penetration_atr"]:
            raise ValueError("need 0 <= min_penetration_atr <= max_penetration_atr")
        if p["sl_buffer_atr"] < 0 or (p["max_bars"] or 0) < 0:
            raise ValueError("sl_buffer_atr and max_bars must be >= 0")

    @property
    def warmup_bars(self) -> int:
        p = self.params
        # The live bot sees warmup_bars + 50 bars: the time stop needs the entry bar in view.
        return max(int(p["trend_ma"] or 0), p["atr_period"] + 1, 2 * p["swing_strength"] + 2, int(p["max_bars"] or 0) + 2)

    def prepare(self, bars: pd.DataFrame) -> pd.DataFrame:
        p = self.params
        data = bars.copy()
        data["atr"] = atr(data, p["atr_period"])
        if p["trend_ma"]:
            data["ma"] = sma(data["close"], int(p["trend_ma"]))
        use_q = p["level_source"] in ("quarters", "both")
        use_s = p["level_source"] in ("swings", "both")
        sweeps = liquidity_sweeps(
            data,
            quarter_size=p["quarter_size"] if use_q else None,
            swing_strength=p["swing_strength"] if use_s else None,
            min_penetration=(data["atr"] * p["min_penetration_atr"]).fillna(np.inf),
        )
        return data.join(sweeps)

    def on_bar(self, data: pd.DataFrame, i: int, position: Position | None) -> Signal | None:
        p = self.params
        if position is not None:
            if p["max_bars"] and bars_held(data, i, position.entry_time) >= p["max_bars"]:
                return Signal.close(f"time stop after {p['max_bars']} bars")
            return None

        a = data["atr"].iat[i]
        if np.isnan(a) or a <= 0:
            return None
        close, low, high = data["close"].iat[i], data["low"].iat[i], data["high"].iat[i]
        ma = data["ma"].iat[i] if p["trend_ma"] else np.nan
        if p["trend_ma"] and np.isnan(ma):
            return None

        bull = data["sweep_bull_level"].iat[i]
        if not np.isnan(bull) and (np.isnan(ma) or close > ma) and bull - low <= p["max_penetration_atr"] * a:
            stop = low - p["sl_buffer_atr"] * a
            target = close + p["rr"] * (close - stop)
            return Signal.open(Side.LONG, stop, target, reason=f"swept {bull:g} and reclaimed")

        bear = data["sweep_bear_level"].iat[i]
        if (
            p["allow_short"]
            and not np.isnan(bear)
            and (np.isnan(ma) or close < ma)
            and high - bear <= p["max_penetration_atr"] * a
        ):
            stop = high + p["sl_buffer_atr"] * a
            target = close - p["rr"] * (stop - close)
            return Signal.open(Side.SHORT, stop, target, reason=f"swept {bear:g} and rejected")
        return None
