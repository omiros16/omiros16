"""Performance metrics, including the Deflated Sharpe Ratio.

The DSR (Bailey & López de Prado, 2014) is the probability that the true
Sharpe ratio is above zero after accounting for how many strategy variants
were tried, the sample length, skew and fat tails. With one trial it equals
the Probabilistic Sharpe Ratio. Read it as: below ~0.95, the result is not
distinguishable from luck.
"""

from __future__ import annotations

import math
from statistics import NormalDist

import numpy as np
import pandas as pd

from ..models import Trade

EULER_GAMMA = 0.5772156649015329
TRADING_DAYS = 252


def daily_returns(equity: pd.Series, initial_balance: float) -> pd.Series:
    daily = equity.groupby(equity.index.normalize()).last()
    prev = daily.shift(1)
    prev.iloc[0] = initial_balance
    return daily / prev - 1.0


def max_drawdown(equity: pd.Series) -> float:
    """Largest peak-to-trough decline as a negative fraction."""
    if equity.empty:
        return 0.0
    peak = equity.cummax()
    return float((equity / peak - 1.0).min())


def deflated_sharpe(
    sr: float, n_obs: int, skew: float, kurtosis: float, n_trials: int = 1, sr_variance: float = 0.0
) -> float:
    """DSR for a per-period (not annualised) Sharpe ratio.

    kurtosis is the raw (Pearson) kurtosis, 3 for a normal distribution.
    sr_variance is the variance of the per-period Sharpe ratios across the
    trials that were run (e.g. all parameter sets of a sweep).
    """
    if n_obs < 3 or not math.isfinite(sr):
        return float("nan")
    norm = NormalDist()
    sr0 = 0.0
    if n_trials > 1 and sr_variance > 0:
        sr0 = math.sqrt(sr_variance) * (
            (1 - EULER_GAMMA) * norm.inv_cdf(1 - 1 / n_trials)
            + EULER_GAMMA * norm.inv_cdf(1 - 1 / (n_trials * math.e))
        )
    denom = 1 - skew * sr + (kurtosis - 1) / 4 * sr**2
    if denom <= 0:
        return float("nan")
    z = (sr - sr0) * math.sqrt(n_obs - 1) / math.sqrt(denom)
    return norm.cdf(z)


def sharpe_per_period(returns: pd.Series) -> float:
    std = returns.std(ddof=1)
    if len(returns) < 2 or not std or not math.isfinite(std):
        return float("nan")
    return float(returns.mean() / std)


def compute_metrics(
    equity: pd.Series,
    trades: list[Trade],
    initial_balance: float,
    in_position: pd.Series | None = None,
    n_trials: int = 1,
    sr_variance: float = 0.0,
) -> dict:
    m: dict = {}
    if equity.empty:
        return m
    rets = daily_returns(equity, initial_balance)
    final = float(equity.iloc[-1])
    years = max((equity.index[-1] - equity.index[0]).days / 365.25, 1e-9)

    m["start"] = str(equity.index[0].date())
    m["end"] = str(equity.index[-1].date())
    m["final_equity"] = round(final, 2)
    m["total_return_pct"] = round((final / initial_balance - 1) * 100, 2)
    m["cagr_pct"] = round(((final / initial_balance) ** (1 / years) - 1) * 100, 2) if final > 0 else -100.0
    m["max_drawdown_pct"] = round(max_drawdown(pd.concat([pd.Series([initial_balance]), equity.reset_index(drop=True)])) * 100, 2)

    sr = sharpe_per_period(rets)
    downside = rets[rets < 0].std(ddof=1)
    m["sharpe"] = round(sr * math.sqrt(TRADING_DAYS), 3) if math.isfinite(sr) else float("nan")
    m["sortino"] = (
        round(rets.mean() / downside * math.sqrt(TRADING_DAYS), 3) if downside and math.isfinite(downside) else float("nan")
    )
    # Prop firm view: daily P&L as a share of the initial balance.
    daily_pnl = rets * equity.groupby(equity.index.normalize()).last().shift(1).fillna(initial_balance)
    m["worst_day_pct"] = round(float(daily_pnl.min() / initial_balance * 100), 2)

    skew = float(rets.skew()) if len(rets) > 2 else 0.0
    kurt = float(rets.kurt()) + 3 if len(rets) > 3 else 3.0
    m["psr"] = round(deflated_sharpe(sr, len(rets), skew, kurt), 3)
    m["dsr"] = round(deflated_sharpe(sr, len(rets), skew, kurt, n_trials, sr_variance), 3)
    m["n_trials"] = n_trials

    m["trades"] = len(trades)
    if trades:
        pnl = np.array([t.net_pnl for t in trades])
        wins, losses = pnl[pnl > 0], pnl[pnl <= 0]
        r = np.array([t.r_multiple for t in trades])
        r = r[np.isfinite(r)]
        m["win_rate_pct"] = round(len(wins) / len(pnl) * 100, 1)
        m["profit_factor"] = round(wins.sum() / -losses.sum(), 2) if losses.sum() < 0 else float("inf")
        m["avg_trade"] = round(float(pnl.mean()), 2)
        m["avg_r"] = round(float(r.mean()), 3) if len(r) else float("nan")
        m["total_costs"] = round(float(sum(t.commission - t.swap for t in trades)), 2)
        hold = [(t.exit_time - t.entry_time).total_seconds() / 86400 for t in trades]
        m["avg_hold_days"] = round(float(np.mean(hold)), 2)
        m["trades_per_year"] = round(len(trades) / years, 1)
    if in_position is not None and len(in_position):
        m["exposure_pct"] = round(float(in_position.mean() * 100), 1)
    return m


def format_metrics(m: dict) -> str:
    labels = {
        "start": "Start",
        "end": "End",
        "final_equity": "Final equity",
        "total_return_pct": "Total return %",
        "cagr_pct": "CAGR %",
        "max_drawdown_pct": "Max drawdown %",
        "worst_day_pct": "Worst day % (of initial)",
        "sharpe": "Sharpe (annual)",
        "sortino": "Sortino (annual)",
        "psr": "Probabilistic Sharpe",
        "dsr": "Deflated Sharpe",
        "n_trials": "Trials (for DSR)",
        "trades": "Trades",
        "trades_per_year": "Trades / year",
        "win_rate_pct": "Win rate %",
        "profit_factor": "Profit factor",
        "avg_trade": "Avg trade (net)",
        "avg_r": "Avg R",
        "avg_hold_days": "Avg hold (days)",
        "total_costs": "Costs (comm. - swap)",
        "exposure_pct": "Time in market %",
    }
    width = max(len(v) for v in labels.values())
    return "\n".join(f"  {labels[k]:<{width}}  {m[k]}" for k in labels if k in m)
