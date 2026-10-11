"""Time-series momentum / trend following on an index (reading list, group 1).

Hypothesis (Moskowitz, Ooi & Pedersen 2012; Hurst, Ooi & Pedersen 2017):
an index that went up over the last 1, 3 and 12 months tends to keep going
up. The score is the average sign of the returns over several lookbacks,
as in Hurst et al.'s blend of 1-, 3- and 12-month signals.

Rules (on closed bars, default D1):
  score    = mean(sign(close / close[-k] - 1) for k in lookbacks)
  enter    long if score > entry_threshold (and close > SMA(trend_ma) if set)
           short mirror only if allow_short
  stop     entry - stop_atr * ATR (protective stop, sizes the trade)
  trail    highest close since entry - trail_atr * ATR, moved only when it
           improves by at least trail_min_step_atr * ATR (fewer server requests)
  exit     score < exit_threshold (long) / score > -exit_threshold (short)
"""

from __future__ import annotations

import numpy as np
import pandas as pd

from ..indicators import atr, lookback_return, sma
from ..models import Position, Side, Signal
from .base import Strategy, entry_index


class TrendFollowing(Strategy):
    name = "trend_following"
    defaults = {
        "lookbacks": [21, 63, 252],
        "entry_threshold": 0.0,
        "exit_threshold": 0.0,
        "trend_ma": 0,
        "atr_period": 20,
        "stop_atr": 3.0,
        "trail_atr": 4.0,
        "trail_min_step_atr": 0.5,
        "allow_short": False,
    }

    def validate(self) -> None:
        p = self.params
        if not p["lookbacks"] or min(p["lookbacks"]) < 1:
            raise ValueError("lookbacks must be positive integers")
        if p["stop_atr"] <= 0:
            raise ValueError("stop_atr must be > 0")
        if p["exit_threshold"] > p["entry_threshold"]:
            raise ValueError("exit_threshold must be <= entry_threshold")

    @property
    def warmup_bars(self) -> int:
        p = self.params
        return max(max(p["lookbacks"]) + 1, int(p["trend_ma"] or 0), p["atr_period"] + 1)

    def prepare(self, bars: pd.DataFrame) -> pd.DataFrame:
        p = self.params
        data = bars.copy()
        signs = [np.sign(lookback_return(data["close"], k)) for k in p["lookbacks"]]
        data["score"] = pd.concat(signs, axis=1).mean(axis=1, skipna=False)
        data["atr"] = atr(data, p["atr_period"])
        if p["trend_ma"]:
            data["ma"] = sma(data["close"], int(p["trend_ma"]))
        return data

    def on_bar(self, data: pd.DataFrame, i: int, position: Position | None) -> Signal | None:
        p = self.params
        score = data["score"].iat[i]
        a = data["atr"].iat[i]
        close = data["close"].iat[i]
        if np.isnan(score) or np.isnan(a):
            return None
        ma = data["ma"].iat[i] if p["trend_ma"] else np.nan

        if position is None:
            if score > p["entry_threshold"] and (np.isnan(ma) or close > ma):
                return Signal.open(Side.LONG, close - p["stop_atr"] * a, reason=f"momentum score {score:+.2f}")
            if p["allow_short"] and score < -p["entry_threshold"] and (np.isnan(ma) or close < ma):
                return Signal.open(Side.SHORT, close + p["stop_atr"] * a, reason=f"momentum score {score:+.2f}")
            return None

        if position.side is Side.LONG and score < p["exit_threshold"]:
            return Signal.close(f"momentum faded, score {score:+.2f}")
        if position.side is Side.SHORT and score > -p["exit_threshold"]:
            return Signal.close(f"momentum faded, score {score:+.2f}")

        if p["trail_atr"] > 0:
            closes = data["close"].iloc[entry_index(data, position.entry_time) : i + 1]
            step = p["trail_min_step_atr"] * a
            if position.side is Side.LONG:
                new_sl = closes.max() - p["trail_atr"] * a
                if position.stop_loss is None or new_sl > position.stop_loss + step:
                    return Signal.modify(new_sl, position.take_profit, "trailing stop")
            else:
                new_sl = closes.min() + p["trail_atr"] * a
                if position.stop_loss is None or new_sl < position.stop_loss - step:
                    return Signal.modify(new_sl, position.take_profit, "trailing stop")
        return None
