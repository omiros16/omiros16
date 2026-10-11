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

from ..models import Position, Signal, Trade
from ..strategies import create_strategy
from ..strategies.base import Strategy
from .engine import BacktestConfig, BacktestResult, run_backtest
from .metrics import compute_metrics, daily_returns, sharpe_per_period


def _score_sharpe(res: BacktestResult) -> float:
    return sharpe_per_period(daily_returns(res.tradable_equity(), res.initial_balance))


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
    best_params: dict | None
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
    risk_events: list[str] = field(default_factory=list)

    def folds_frame(self) -> pd.DataFrame:
        return pd.DataFrame([f.__dict__ for f in self.folds])


class _FoldSchedule(Strategy):
    """Runs each fold's chosen parameters in its out-of-sample window, on one account.

    `segments` holds (index of the first bar the fold may fill on, strategy);
    None as the strategy means no parameter set qualified: stay flat.
    """

    name = "walk_forward"

    def __init__(self, segments: list[tuple[int, Strategy | None]]) -> None:
        self.segments = segments
        self.params = {}
        self._prepared: list[pd.DataFrame | None] = []

    @property
    def warmup_bars(self) -> int:
        return 0

    def prepare(self, bars: pd.DataFrame) -> pd.DataFrame:
        self._prepared = [s.prepare(bars) if s is not None else None for _, s in self.segments]
        return bars

    def on_bar(self, data: pd.DataFrame, i: int, position: Position | None) -> Signal | None:
        k = next((k for k in range(len(self.segments) - 1, -1, -1) if self.segments[k][0] <= i + 1), None)
        if k is None:
            return None
        strat = self.segments[k][1]
        if strat is None:
            return Signal.close("walk-forward: no parameter set qualified in this fold") if position else None
        if i < strat.warmup_bars - 1:
            return None
        return strat.on_bar(self._prepared[k], i, position)


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
    """Choose parameters on each in-sample window, then trade them on the next window.

    The in-sample scoring uses a fresh account per run. The out-of-sample
    windows are traded one after the other on a single account, as live
    trading would be: the balance, any open position, the loss limits and a
    kill carry over from fold to fold, and only the parameters change.
    """
    if objective not in OBJECTIVES:
        raise ValueError(f"objective must be one of {sorted(OBJECTIVES)}")
    if in_sample_bars <= 0 or out_sample_bars <= 0:
        raise ValueError("in_sample_bars and out_sample_bars must be positive")
    score_fn = OBJECTIVES[objective]
    combos = param_grid(grid)
    n = len(bars)
    if in_sample_bars + out_sample_bars > n:
        raise ValueError(f"need at least {in_sample_bars + out_sample_bars} bars, have {n}")

    plan: list[tuple[int, int, int, dict | None, float]] = []  # is start, oos begin, oos end, params, score
    trial_sharpes: list[list[float]] = []
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
            plan.append((start, oos_begin, oos_end, None, float("nan")))
        else:
            plan.append((start, oos_begin, oos_end, best[1], best[0]))
        start += out_sample_bars

    # Out of sample: one continuous account over all folds.
    strategies = [create_strategy(strategy_name, {**base_params, **p}) if p is not None else None
                  for _, _, _, p, _ in plan]
    warmup = max((s.warmup_bars for s in strategies if s is not None), default=0)
    first_oos = plan[0][1]
    warm_from = max(0, first_oos - warmup - 1)
    segments = [(oos_begin - warm_from, strat) for (_, oos_begin, _, _, _), strat in zip(plan, strategies)]
    oos_cfg = replace(cfg, trade_from=bars.index[first_oos])
    res = run_backtest(bars.iloc[warm_from:], _FoldSchedule(segments), oos_cfg, symbol)

    equity = res.equity[res.equity.index >= bars.index[first_oos]]
    trades = [t for t in res.trades if t.entry_time >= bars.index[first_oos]]
    folds: list[Fold] = []
    for (is_start, oos_begin, oos_end, params, score) in plan:
        t0, t1 = bars.index[oos_begin], bars.index[oos_end - 1]
        before = res.equity[res.equity.index < t0]
        eq0 = float(before.iloc[-1]) if len(before) else cfg.initial_balance
        eq1 = float(res.equity[res.equity.index <= t1].iloc[-1])
        folds.append(
            Fold(
                is_start=bars.index[is_start],
                oos_start=t0,
                oos_end=t1,
                best_params=params,
                is_score=round(score, 4) if math.isfinite(score) else float("nan"),
                oos_return_pct=round((eq1 / eq0 - 1) * 100, 2),
                oos_trades=sum(1 for t in trades if t0 <= t.entry_time <= t1),
            )
        )

    # Variance of the per-period Sharpe across parameter sets, averaged over folds.
    variances = [np.nanvar(s, ddof=1) for s in trial_sharpes if np.isfinite(s).sum() > 1]
    sr_var = float(np.nanmean(variances)) if variances else 0.0
    result = WalkForwardResult(folds, equity, trades, cfg.initial_balance, len(combos), sr_var,
                               risk_events=list(res.risk_events))
    in_pos = res.in_position[res.in_position.index >= bars.index[first_oos]]
    result.metrics = compute_metrics(equity, trades, cfg.initial_balance, in_pos, len(combos), sr_var)
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
        # Prop firms measure the maximum loss from the starting balance (static)
        # or from the equity peak (trailing): report both.
        below_start = (paths.min(axis=1) - 1) * 100
        out[f"prob_loss_from_start_beyond_{dd_limit_pct:g}pct"] = round(
            float((below_start <= -dd_limit_pct).mean() * 100), 1)
        out[f"prob_peak_to_trough_beyond_{dd_limit_pct:g}pct"] = round(float((dd <= -dd_limit_pct).mean() * 100), 1)
    return out
