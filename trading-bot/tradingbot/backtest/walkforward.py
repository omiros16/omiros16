"""Walk-forward optimisation and Monte Carlo resampling.

Walk-forward: pick the best parameters on an in-sample window, trade them on
the next, unseen window, roll forward, and judge the strategy only on the
stitched out-of-sample result. The Deflated Sharpe of that result accounts for
the number of parameter sets tried.
"""

from __future__ import annotations

import itertools
import math
from dataclasses import dataclass, field, replace
from typing import Any, Callable

import numpy as np
import pandas as pd

from ..models import Trade
from ..strategies import create_strategy
from .engine import BacktestConfig, BacktestResult, run_backtest
from .metrics import compute_metrics, daily_returns, sharpe_per_period


def _score_sharpe(res: BacktestResult) -> float:
    return sharpe_per_period(daily_returns(res.equity, res.initial_balance))


def _score_calmar(res: BacktestResult) -> float:
    m = res.metrics()
    dd = abs(m.get("max_drawdown_pct", 0.0))
    return m.get("cagr_pct", 0.0) / dd if dd > 0 else float("nan")


def _score_net(res: BacktestResult) -> float:
    return float(res.equity.iloc[-1] - res.initial_balance)


OBJECTIVES: dict[str, Callable[[BacktestResult], float]] = {
    "sharpe": _score_sharpe,
    "calmar": _score_calmar,
    "net_profit": _score_net,
}


def param_grid(grid: dict[str, list[Any]] | None) -> list[dict[str, Any]]:
    if not grid:
        return [{}]
    keys = list(grid)
    return [dict(zip(keys, values)) for values in itertools.product(*(grid[k] for k in keys))]


@dataclass
class Fold:
    is_start: pd.Timestamp
    oos_start: pd.Timestamp
    oos_end: pd.Timestamp
    best_params: dict
    is_score: float
    oos_return_pct: float
    oos_trades: int


@dataclass
class WalkForwardResult:
    folds: list[Fold]
    equity: pd.Series
    trades: list[Trade]
    initial_balance: float
    n_trials: int
    sr_variance: float
    metrics: dict = field(default_factory=dict)

    def folds_frame(self) -> pd.DataFrame:
        return pd.DataFrame([f.__dict__ for f in self.folds])


def walk_forward(
    bars: pd.DataFrame,
    strategy_name: str,
    base_params: dict[str, Any],
    grid: dict[str, list[Any]] | None,
    cfg: BacktestConfig,
    in_sample_bars: int,
    out_sample_bars: int,
    objective: str = "sharpe",
    min_trades: int = 5,
    symbol: str = "",
) -> WalkForwardResult:
    if objective not in OBJECTIVES:
        raise ValueError(f"objective must be one of {sorted(OBJECTIVES)}")
    score_fn = OBJECTIVES[objective]
    combos = param_grid(grid)
    n = len(bars)
    if in_sample_bars + out_sample_bars > n:
        raise ValueError(f"need at least {in_sample_bars + out_sample_bars} bars, have {n}")

    folds: list[Fold] = []
    pieces: list[pd.Series] = []
    trades: list[Trade] = []
    trial_sharpes: list[list[float]] = []
    growth = 1.0
    start = 0
    while start + in_sample_bars < n:
        oos_begin = start + in_sample_bars
        oos_end = min(oos_begin + out_sample_bars, n)
        is_bars = bars.iloc[start:oos_begin]

        best: tuple[float, dict] | None = None
        fold_sharpes: list[float] = []
        for combo in combos:
            strat = create_strategy(strategy_name, {**base_params, **combo})
            if strat.warmup_bars >= in_sample_bars:
                continue
            res = run_backtest(is_bars, strat, cfg, symbol)
            fold_sharpes.append(_score_sharpe(res))
            score = score_fn(res) if len(res.trades) >= min_trades else float("nan")
            if math.isfinite(score) and (best is None or score > best[0]):
                best = (score, combo)
        trial_sharpes.append(fold_sharpes)
        if best is None:
            best = (float("nan"), combos[0])

        params = {**base_params, **best[1]}
        strat = create_strategy(strategy_name, params)
        warm_from = max(0, oos_begin - strat.warmup_bars - 1)
        oos_cfg = replace(cfg, trade_from=bars.index[oos_begin])
        res = run_backtest(bars.iloc[warm_from:oos_end], strat, oos_cfg, symbol)

        seg = res.equity[res.equity.index >= bars.index[oos_begin]] / cfg.initial_balance
        pieces.append(seg * growth)
        growth *= float(seg.iloc[-1])
        oos_trades = [t for t in res.trades if t.entry_time >= bars.index[oos_begin]]
        trades.extend(oos_trades)
        folds.append(
            Fold(
                is_start=bars.index[start],
                oos_start=bars.index[oos_begin],
                oos_end=bars.index[oos_end - 1],
                best_params=best[1],
                is_score=round(best[0], 4) if math.isfinite(best[0]) else float("nan"),
                oos_return_pct=round((float(seg.iloc[-1]) - 1) * 100, 2),
                oos_trades=len(oos_trades),
            )
        )
        start += out_sample_bars

    equity = pd.concat(pieces) * cfg.initial_balance
    # Variance of the per-period Sharpe across parameter sets, averaged over folds.
    variances = [np.nanvar(s, ddof=1) for s in trial_sharpes if np.isfinite(s).sum() > 1]
    sr_var = float(np.nanmean(variances)) if variances else 0.0
    result = WalkForwardResult(folds, equity, trades, cfg.initial_balance, len(combos), sr_var)
    result.metrics = compute_metrics(equity, trades, cfg.initial_balance, None, len(combos), sr_var)
    return result


def monte_carlo(
    trades: list[Trade],
    n_sims: int = 5000,
    dd_limit_pct: float | None = None,
    seed: int = 7,
) -> dict:
    """Resample trade returns (with replacement) to see how bad drawdowns can get.

    Uses closed-trade returns only, so drawdowns inside trades are not
    included: treat the numbers as optimistic lower bounds.
    """
    if len(trades) < 5:
        return {"note": "too few trades for Monte Carlo"}
    rets = np.array([t.net_pnl / (t.balance_after - t.net_pnl) for t in trades])
    rng = np.random.default_rng(seed)
    samples = rng.choice(rets, size=(n_sims, len(rets)), replace=True)
    paths = np.cumprod(1 + samples, axis=1)
    paths = np.hstack([np.ones((n_sims, 1)), paths])
    dd = (paths / np.maximum.accumulate(paths, axis=1) - 1).min(axis=1) * 100
    final = (paths[:, -1] - 1) * 100
    out = {
        "simulations": n_sims,
        "trades_per_path": len(rets),
        "max_dd_median_pct": round(float(np.median(dd)), 2),
        "max_dd_95_pct": round(float(np.percentile(dd, 5)), 2),
        "max_dd_99_pct": round(float(np.percentile(dd, 1)), 2),
        "return_5th_pct": round(float(np.percentile(final, 5)), 2),
        "return_median_pct": round(float(np.median(final)), 2),
        "prob_loss_pct": round(float((final < 0).mean() * 100), 1),
    }
    if dd_limit_pct is not None:
        out[f"prob_dd_beyond_{dd_limit_pct:g}pct"] = round(float((dd <= -dd_limit_pct).mean() * 100), 1)
    return out
