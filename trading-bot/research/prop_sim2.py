"""Prop-firm sizing study for MR-ENS-VT: intraday equity stop + drawdown-aware (CPPI) sizing.

This changes only *how much* is traded, never *when*: the signal is the frozen
MR-ENS-VT. Results are a sizing analysis on the validation + holdout pools,
not new evidence for the edge.

Intraday stop: the system is long-only and positions are held from the
previous close, so the day's low is the worst point of the day. If
leverage x position x (low / prev close - 1) breaches -S, the bot's equity stop
fires: exit at the open if the open already gapped through, else at -S minus
slippage. The rest of that day and the next night are flat (the risk manager
pauses until the next trading day).

CPPI (Black & Perold 1992): leverage_t = clip(m x (equity - floor), 0, cap),
so risk shrinks as equity approaches the max-loss floor and grows with profit.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd

from lab import Costs, simulate
from run_discovery import context
from run_holdout import final_positions

RESULTS = Path(__file__).resolve().parent / "results"
POOLS = ("SPX-V", "COMP-V", "NQ-H")
STOP_SLIP = 0.0005  # 5 bp of equity worse than the stop level


def pool_arrays(set_id: str):
    """Per day: net return at 1x (close to close), worst intraday and open returns of the held position."""
    ctx = context(set_id, Costs())
    night, day = final_positions(ctx, vt=True, mode="A")
    ret, _ = simulate(ctx.df, night, day, ctx.rf, ctx.costs, ctx.start, close_exec=True)
    df = ctx.df
    prev_c = df["close"].shift(1).to_numpy()
    worst = day * (df["low"].to_numpy() / prev_c - 1)
    opn = day * (df["open"].to_numpy() / prev_c - 1)
    mask = df.index >= ctx.start
    real_open = ctx.real_open
    if not real_open:
        opn = np.zeros_like(opn)
    return np.c_[ret.to_numpy(), np.nan_to_num(worst[mask]), np.nan_to_num(opn[mask])]


def block_idx(n_pool: int, n_days: int, n_paths: int, block: int, rng) -> np.ndarray:
    starts = rng.integers(0, n_pool - block, size=(n_paths, n_days // block + 1))
    return (starts[:, :, None] + np.arange(block)[None, None, :]).reshape(n_paths, -1)[:, :n_days]


def play(pool: np.ndarray, idx: np.ndarray, target: float, sizing: dict, stop: float | None,
         daily_limit=0.05, max_loss=0.10, min_days=4):
    """Simulate paths day by day. Returns outcome (1 pass, -1 fail, 0 none), day, final equity, stop count."""
    n_paths, n_days = idx.shape
    eq = np.ones(n_paths)
    alive = np.ones(n_paths, bool)
    outcome = np.zeros(n_paths, int)
    when = np.full(n_paths, n_days)
    skip = np.zeros(n_paths, bool)  # flat the day after an intraday stop
    stops = np.zeros(n_paths)
    for d in range(n_days):
        r, w, o = pool[idx[:, d], 0], pool[idx[:, d], 1], pool[idx[:, d], 2]
        if sizing["kind"] == "fixed":
            lev = np.full(n_paths, sizing["lev"])
        else:
            lev = np.clip(sizing["m"] * (eq - sizing["floor"]), 0, sizing["cap"])
        lev = np.where(skip, 0.0, lev)
        day_ret = lev * r
        hit = np.zeros(n_paths, bool)
        if stop is not None:
            hit = lev * w <= -stop
            gap = lev * o <= -stop
            day_ret = np.where(hit, np.where(gap, lev * o, -stop - STOP_SLIP), day_ret)
        new_eq = eq * (1 + day_ret)
        # The firm checks equity at every moment: the day's worst point is the low
        # (or the stop exit when the stop fired).
        worst_eq = np.where(hit, new_eq, np.minimum(new_eq, eq * (1 + lev * w)))
        loss_vs_start = eq - worst_eq  # in units of the initial balance
        failed = alive & ((loss_vs_start >= daily_limit) | (worst_eq <= 1 - max_loss))
        passed = alive & ~failed & (new_eq >= 1 + target) & (d + 1 >= min_days)
        outcome[failed], when[failed] = -1, d + 1
        outcome[passed], when[passed] = 1, d + 1
        stops += hit & alive
        eq = np.where(alive, new_eq, eq)  # failed paths keep their failure-day equity
        alive &= ~(failed | passed)
        skip = hit
        if not alive.any():
            break
    return outcome, when, eq, stops


def main() -> None:
    RESULTS.mkdir(exist_ok=True)
    pool = np.vstack([pool_arrays(s) for s in POOLS])
    rng = np.random.default_rng(7)
    n_paths, horizon = 6000, 2 * 252
    variants = [
        ("fixed 1.0x, no stop", {"kind": "fixed", "lev": 1.0}, None),
        ("fixed 1.0x, 3% stop", {"kind": "fixed", "lev": 1.0}, 0.03),
        ("fixed 1.5x, 3% stop", {"kind": "fixed", "lev": 1.5}, 0.03),
        ("fixed 2.0x, 3% stop", {"kind": "fixed", "lev": 2.0}, 0.03),
        ("fixed 2.0x, 2% stop", {"kind": "fixed", "lev": 2.0}, 0.02),
        ("fixed 3.0x, 2% stop", {"kind": "fixed", "lev": 3.0}, 0.02),
        ("CPPI m=15 floor .92 cap 3, 2% stop", {"kind": "cppi", "m": 15, "floor": 0.92, "cap": 3.0}, 0.02),
        ("CPPI m=25 floor .92 cap 3, 2% stop", {"kind": "cppi", "m": 25, "floor": 0.92, "cap": 3.0}, 0.02),
        ("CPPI m=25 floor .92 cap 4, 2.5% stop", {"kind": "cppi", "m": 25, "floor": 0.92, "cap": 4.0}, 0.025),
        ("CPPI m=40 floor .93 cap 4, 2.5% stop", {"kind": "cppi", "m": 40, "floor": 0.93, "cap": 4.0}, 0.025),
    ]
    rows = []
    for name, sizing, stop in variants:
        idx1 = block_idx(len(pool), horizon, n_paths, 20, rng)
        o1, w1, _, s1 = play(pool, idx1, 0.10, sizing, stop)
        idx2 = block_idx(len(pool), horizon, n_paths, 20, rng)
        o2, w2, _, _ = play(pool, idx2, 0.05, sizing, stop)
        idxf = block_idx(len(pool), 252, n_paths, 20, rng)
        of, _, eqf, sf = play(pool, idxf, 10.0, sizing, stop)
        p1, p2 = (o1 == 1).mean(), (o2 == 1).mean()
        rows.append({
            "sizing": name,
            "P1 pass %": 100 * p1, "P1 fail %": 100 * (o1 == -1).mean(),
            "P1 median days": float(np.median(w1[o1 == 1])) if p1 else np.nan,
            "P2 pass %": 100 * p2, "P2 median days": float(np.median(w2[o2 == 1])) if p2 else np.nan,
            "both %": 100 * p1 * p2,
            "funded 1y blowup %": 100 * (of == -1).mean(),
            "funded 1y median %": 100 * float(np.median(eqf - 1)),
            "funded 1y p10 %": 100 * float(np.percentile(eqf - 1, 10)),
            "stops / year": float(sf.mean()),
        })
    df = pd.DataFrame(rows)
    df.to_csv(RESULTS / "prop_ftmo_sizing.csv", index=False)
    pd.set_option("display.width", 250)
    print(df.round(1).to_string(index=False))


if __name__ == "__main__":
    main()
