"""Strategy registry. Add a new strategy class to STRATEGIES to use it in config."""

from __future__ import annotations

from typing import Any

from .base import Strategy
from .mr_ensemble import MREnsemble
from .quarters_sweep import QuartersSweep
from .trend_following import TrendFollowing

STRATEGIES: dict[str, type[Strategy]] = {cls.name: cls for cls in (MREnsemble, TrendFollowing, QuartersSweep)}


def create_strategy(name: str, params: dict[str, Any] | None = None) -> Strategy:
    try:
        cls = STRATEGIES[name]
    except KeyError:
        raise ValueError(f"unknown strategy {name!r}; available: {sorted(STRATEGIES)}") from None
    return cls(**(params or {}))


__all__ = ["STRATEGIES", "Strategy", "create_strategy"]
