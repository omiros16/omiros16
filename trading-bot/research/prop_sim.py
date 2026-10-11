"""Phase 4 (pre-registered rule 6): FTMO 2-step pass probability for MR-ENS-VT.

Block-bootstraps the frozen system's daily returns from the validation
(2010-2019) and holdout (2019-2026) periods and plays the FTMO rules:
  phase 1: +10% target, phase 2: +5% target, at least 4 trading days each,
  fail on a day losing >= 5% of the initial balance (measured from the
  previous close, a stand-in for the start-of-day balance) or on equity
  <= 90% of the initial balance. No time limit at FTMO; we cap each phase at
  2 years and report how long passing takes.
Daily close-to-close returns understate intraday swings; the internal 3%
daily stop of the bot covers part of that gap (not modelled here).
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from lab import Costs, simulate, stats
from run_discovery import context
from run_holdout import final_positions

OUT = Path(__file__).resolve().parent / "output"
RESULTS = Path(__file__).resolve().parent / "results"


def system_returns(set_id: str, mode: str) -> pd.Series:
    ctx = context(set_id, Costs())
    night, day = final_positions(ctx, vt=True, mode=mode)
    ret, _ = simulate(ctx.df, night, day, ctx.rf, ctx.costs, ctx.start, close_exec=(mode == "A"))
    return ret


def block_bootstrap(pool: np.ndarray, n_days: int, n_paths: int, block: int, rng) -> np.ndarray:
    starts = rng.integers(0, len(pool) - block, size=(n_paths, n_days // block + 1))
    idx = (starts[:, :, None] + np.arange(block)[None, None, :]).reshape(n_paths, -1)[:, :n_days]
    return pool[idx]


def play_phase(paths: np.ndarray, target: float, daily_limit=0.05, max_loss=0.10, min_days=4):
    """-> outcome per path (1 pass, -1 fail, 0 undecided) and day of the outcome."""
    n_paths, n_days = paths.shape
    eq = np.cumprod(1 + paths, axis=1)
    prev = np.concatenate([np.ones((n_paths, 1)), eq[:, :-1]], axis=1)
    day_loss = prev - eq  # in units of the initial balance (initial = 1)
    fail = (day_loss >= daily_limit) | (eq <= 1 - max_loss)
    hit = (eq >= 1 + target) & (np.arange(1, n_days + 1) >= min_days)
    first_fail = np.where(fail.any(axis=1), fail.argmax(axis=1), n_days + 1)
    first_hit = np.where(hit.any(axis=1), hit.argmax(axis=1), n_days + 1)
    outcome = np.where(first_hit < first_fail, 1, np.where(first_fail < first_hit, -1, 0))
    when = np.minimum(first_hit, first_fail)
    return outcome, when


def main() -> None:
    RESULTS.mkdir(exist_ok=True)
    pools = {
        "validation SPX 2010-2019": system_returns("SPX-V", "A"),
        "validation NASDAQ 2010-2018": system_returns("COMP-V", "A"),
        "holdout NQ 2019-2026 (next open)": system_returns("NQ-H", "B"),
    }
    for k, r in pools.items():
        s = stats(r)
        print(f"{k:34s} Sharpe {s['sharpe']:.2f} CAGR {s['cagr']:.2%} maxDD {s['maxdd']:.2%} worst {s['worst_day']:.2%}")
    pool = np.concatenate([r.to_numpy() for r in pools.values()])

    rng = np.random.default_rng(20261011)
    horizon = 2 * 252
    rows = []
    for lev in (1.0, 1.5, 2.0, 2.5, 3.0, 4.0):
        paths1 = block_bootstrap(pool * lev, horizon, 10_000, 20, rng)
        o1, w1 = play_phase(paths1, 0.10)
        paths2 = block_bootstrap(pool * lev, horizon, 10_000, 20, rng)
        o2, w2 = play_phase(paths2, 0.05)
        p1, p2 = (o1 == 1).mean(), (o2 == 1).mean()
        # One funded year at the same leverage: probability of hitting the 10% max loss / 5% daily.
        paths_f = block_bootstrap(pool * lev, 252, 10_000, 20, rng)
        of, _ = play_phase(paths_f, target=10.0)  # target unreachable: only failures count
        yearly = np.prod(1 + paths_f, axis=1) - 1
        lr = pool * lev
        rows.append({
            "leverage": lev,
            "phase1_pass": p1, "phase1_fail": (o1 == -1).mean(),
            "phase1_median_days": float(np.median(w1[o1 == 1])) if p1 else np.nan,
            "phase2_pass": p2, "phase2_median_days": float(np.median(w2[o2 == 1])) if p2 else np.nan,
            "both_phases": p1 * p2,
            "funded_year_blowup": (of == -1).mean(),
            "funded_year_median_return": float(np.median(yearly)),
            "funded_year_p10_return": float(np.percentile(yearly, 10)),
            "days_loss_over_3pct_per_year": float((lr <= -0.03).mean() * 252),
        })
    df = pd.DataFrame(rows)
    df.to_csv(RESULTS / "prop_ftmo_bootstrap.csv", index=False)
    pd.set_option("display.width", 220)
    show = df.copy()
    for c in show.columns:
        if c.startswith(("phase1_pass", "phase1_fail", "phase2_pass", "both", "funded_year_b", "funded_year_m", "funded_year_p")):
            show[c] = (show[c] * 100).round(1)
    print(show.round(2).to_string(index=False))


if __name__ == "__main__":
    main()
