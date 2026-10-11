"""Synthetic index-like bars for demos and tests. Not for drawing conclusions."""

from __future__ import annotations

import numpy as np
import pandas as pd


def make_bars(
    n: int = 2000,
    start: str = "2015-01-02",
    freq: str = "B",
    start_price: float = 4000.0,
    annual_drift: float = 0.08,
    annual_vol: float = 0.20,
    seed: int = 42,
) -> pd.DataFrame:
    """Random walk with slowly switching trend regimes and volatility clusters."""
    rng = np.random.default_rng(seed)
    periods = 252 if freq in ("B", "D") else 252 * 6
    mu = annual_drift / periods
    sigma = annual_vol / np.sqrt(periods)

    regime = np.cumsum(rng.normal(0, 0.02, n))
    regime = np.tanh(regime - regime.mean()) * 2 * mu
    vol = np.empty(n)
    v = sigma
    for i in range(n):
        v = 0.94 * v + 0.06 * sigma * rng.lognormal(0, 0.5)
        vol[i] = v
    rets = mu + regime + vol * rng.standard_normal(n)
    close = start_price * np.exp(np.cumsum(rets))
    open_ = np.concatenate([[start_price], close[:-1]]) * np.exp(rng.normal(0, 0.002, n))
    span = np.abs(rng.normal(0, 1, n)) * vol * close
    high = np.maximum(open_, close) + span * rng.uniform(0.2, 1.0, n)
    low = np.minimum(open_, close) - span * rng.uniform(0.2, 1.0, n)
    index = pd.date_range(start=start, periods=n, freq=freq, name="time")
    return pd.DataFrame({"open": open_, "high": high, "low": low, "close": close}, index=index)
