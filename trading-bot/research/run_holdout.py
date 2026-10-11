"""Phase 3: the frozen MR-ENS-VT system on the final holdout (run once)."""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from hypotheses import Ctx
from lab import Costs, simulate, stats
from run_discovery import context
from run_validation import ensemble_positions

OUT = Path(__file__).resolve().parent / "output"
VOL_TARGET = 0.15
VOL_CAP = 2.0


def vol_scale(ctx: Ctx) -> np.ndarray:
    """Scale decided at close t (shifted so it applies to day t+1, like the positions)."""
    r = ctx.close.pct_change()
    vol = (r.rolling(20, min_periods=20).std() * np.sqrt(252)).to_numpy()
    with np.errstate(divide="ignore", invalid="ignore"):
        scale = np.clip(VOL_TARGET / vol, 0, VOL_CAP)
    scale = np.nan_to_num(scale, nan=1.0)
    return np.concatenate([[1.0], scale[:-1]])


def final_positions(ctx: Ctx, vt: bool = True, mode: str = "A") -> tuple[np.ndarray, np.ndarray]:
    night, day = ensemble_positions(ctx)
    if vt:
        s = vol_scale(ctx)
        night, day = night * s, day * s
    if mode == "B":
        night = np.concatenate([[0.0], day[:-1]])
    return night, day


def run(ctx: Ctx, vt: bool, mode: str):
    night, day = final_positions(ctx, vt, mode)
    ret, trades = simulate(ctx.df, night, day, ctx.rf, ctx.costs, ctx.start, close_exec=(mode == "A"))
    mask = ctx.df.index >= ctx.start
    return ret, trades, day[mask]


def main() -> None:
    rows = []
    series = {}
    for s in ("NDX-H", "NQ-H"):
        for vt in (True, False):
            for mode in ("A", "B"):
                for cname, costs in (("normal", Costs()), ("stressed", Costs().stressed())):
                    if cname == "stressed" and mode == "B":
                        continue
                    ctx = context(s, costs)
                    ret, trades, pos = run(ctx, vt, mode)
                    st = stats(ret, trades)
                    label = f"{'MR-ENS-VT' if vt else 'MR-ENS raw'} mode {mode} {cname}"
                    rows.append({"set": s, "variant": label, **st, "avg_pos": float(pos.mean()),
                                 "max_pos": float(pos.max())})
                    series[(s, label)] = ret
    df = pd.DataFrame(rows)
    df.to_csv(OUT / "holdout.csv", index=False)

    # Calendar-year returns of the decision variant.
    yearly = {}
    for s in ("NDX-H", "NQ-H"):
        r = series[(s, "MR-ENS-VT mode A normal")]
        yearly[s] = (1 + r).groupby(r.index.year).prod() - 1
    yearly = pd.DataFrame(yearly)
    yearly.to_csv(OUT / "holdout_yearly.csv")
    for (s, label), r in series.items():
        if label == "MR-ENS-VT mode A normal":
            r.to_csv(OUT / f"holdout_returns_{s}.csv")

    pd.set_option("display.width", 220)
    cols = ["set", "variant", "sharpe", "cagr", "vol", "maxdd", "tstat", "worst_day", "avg_pos", "max_pos", "trades_per_year"]
    print(df[cols].round(3).to_string(index=False))
    print("\nCalendar years (MR-ENS-VT, mode A, normal costs):")
    print((yearly * 100).round(2).to_string())
    worst = series[("NQ-H", "MR-ENS-VT mode A normal")].nsmallest(5)
    print("\nWorst days NQ-H:", json.dumps({str(k.date()): round(v * 100, 2) for k, v in worst.items()}))


if __name__ == "__main__":
    main()
