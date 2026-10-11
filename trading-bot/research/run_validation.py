"""Phase 2: MR-ENS (Amendment 2) on discovery (for reference) and validation, once.

Validation rule 3: Sharpe >= 0.4 on SPX-V, > 0 on COMP-V, and positive
total return under stressed costs (4 bp round trip, swap markup +1%).
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

from hypotheses import Ctx, all_trials
from lab import Costs, simulate, stats
from run_discovery import context

OUT = Path(__file__).resolve().parent / "output"
FAMILY = ("H3", "H4", "H5", "H6", "H7")


def family_trials():
    return [t for t in all_trials() if t.hyp in FAMILY]


def ensemble_positions(ctx: Ctx, trials=None) -> tuple[np.ndarray, np.ndarray]:
    trials = trials or family_trials()
    nights, days = [], []
    for t in trials:
        night, day, _ = t.build(ctx)
        nights.append(np.nan_to_num(night))
        days.append(np.nan_to_num(day))
    return np.mean(nights, axis=0), np.mean(days, axis=0)


def run_ensemble(ctx: Ctx, mode: str = "A", trials=None):
    night, day = ensemble_positions(ctx, trials)
    if mode == "B":  # same decisions, filled at the next open
        night = np.concatenate([[0.0], day[:-1]])
    ret, trades = simulate(ctx.df, night, day, ctx.rf, ctx.costs, ctx.start, close_exec=(mode == "A"))
    mask = ctx.df.index >= ctx.start
    exposure = float(np.mean(day[mask]))
    return ret, trades, exposure, day[mask]


def report(set_ids, label, mode="A", costs=Costs()):
    rows = []
    for s in set_ids:
        ctx = context(s, costs)
        ret, trades, expo, pos = run_ensemble(ctx, mode)
        st = stats(ret, trades, expo)
        st["avg_pos_when_in"] = float(pos[pos > 0].mean()) if (pos > 0).any() else 0.0
        st["time_in_market"] = float((pos > 0).mean())
        rows.append({"set": s, "variant": label, **st})
    return rows


def main() -> None:
    OUT.mkdir(exist_ok=True)
    rows = []
    rows += report(("SPX-D", "COMP-D"), "MR-ENS discovery (reference)")
    rows += report(("SPX-V", "COMP-V"), "MR-ENS validation")
    rows += report(("SPX-V", "COMP-V"), "MR-ENS validation, stressed costs", costs=Costs().stressed())
    rows += report(("SPX-V", "COMP-V"), "MR-ENS validation, mode B (next open)", mode="B")
    df = pd.DataFrame(rows)
    df.to_csv(OUT / "validation_mr_ens.csv", index=False)

    v = df[df.variant == "MR-ENS validation"].set_index("set")
    st = df[df.variant == "MR-ENS validation, stressed costs"].set_index("set")
    passed = bool(
        v.loc["SPX-V", "sharpe"] >= 0.4
        and v.loc["COMP-V", "sharpe"] > 0
        and (st["cagr"] > 0).all()
    )
    verdict = {"rule3_passed": passed,
               "spx_v_sharpe": round(float(v.loc["SPX-V", "sharpe"]), 3),
               "comp_v_sharpe": round(float(v.loc["COMP-V", "sharpe"]), 3),
               "stressed_cagr": {k: round(float(x), 4) for k, x in st["cagr"].items()}}
    (OUT / "validation_verdict.json").write_text(json.dumps(verdict, indent=2))

    # Information only: one rule-2 pick per family (not used for decisions).
    per_trial = pd.read_csv(OUT / "discovery_per_trial.csv")
    picks = per_trial[per_trial.hyp.isin(FAMILY)].sort_values("nb_score", ascending=False).groupby("hyp").head(1)
    info = []
    trials = {t.name: t for t in all_trials()}
    for _, row in picks.iterrows():
        for s in ("SPX-V", "COMP-V"):
            ctx = context(s)
            ret, trades, expo, _ = run_ensemble(ctx, "A", [trials[row["trial"]]])
            info.append({"set": s, "trial": row["trial"], **stats(ret, trades, expo)})
    pd.DataFrame(info).to_csv(OUT / "validation_family_picks_info.csv", index=False)

    pd.set_option("display.width", 220)
    cols = ["set", "variant", "sharpe", "cagr", "vol", "maxdd", "tstat", "time_in_market", "avg_pos_when_in",
            "trades_per_year", "worst_day"]
    print(df[cols].round(3).to_string(index=False))
    print("\nVerdict:", json.dumps(verdict, indent=2))
    print("\nInformation only, per-family picks:")
    print(pd.DataFrame(info)[["set", "trial", "sharpe", "cagr", "maxdd", "tstat"]].round(3).to_string(index=False))


if __name__ == "__main__":
    main()
