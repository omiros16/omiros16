"""Phase 1: run all 85 pre-registered trials on the discovery sets and apply rules 1-2.

Interpretation fixed before running (also stated in RESEARCH.md):
  * "best trial" of a hypothesis = highest Sharpe averaged over the discovery
    sets it can run on; its DSR is computed per set (N = 85, Sharpe variance
    across all trials on that set) and averaged.
  * "neighbourhood" of a trial = the trials of the same hypothesis that
    differ from it in exactly one numeric parameter by one grid step.
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

import datasets as ds
from hypotheses import Ctx, all_trials, run_trial
from lab import Costs, deflated_sharpe, stats

OUT = Path(__file__).resolve().parent / "output"
DISCOVERY = ("SPX-D", "COMP-D")
N_TRIALS = 33  # Amendment 1: Li & Ji effective number of trials (pre-registered value was 85)


def context(set_id: str, costs: Costs = Costs()) -> Ctx:
    df, start, real_open, has_range = ds.load(set_id)
    return Ctx(df, start, ds.rf_aligned(df.index), ds.vix_aligned(df.index), real_open, has_range, costs)


def evaluate(set_ids, trials, costs: Costs = Costs()) -> tuple[pd.DataFrame, dict]:
    rows, series = [], {}
    for set_id in set_ids:
        ctx = context(set_id, costs)
        for tr in trials:
            out = run_trial(tr, ctx)
            if out is None:
                continue
            ret, trades, expo = out
            st = stats(ret, trades, expo)
            rows.append({"set": set_id, "hyp": tr.hyp, "trial": tr.name, **st})
            series[(set_id, tr.name)] = ret
    return pd.DataFrame(rows), series


def neighbours(trials) -> dict[str, list[str]]:
    by_hyp: dict[str, list] = {}
    for t in trials:
        by_hyp.setdefault(t.hyp, []).append(t)
    out = {}
    for hyp, group in by_hyp.items():
        grids = {k: sorted({t.params[k] for t in group}, key=lambda v: (str(type(v)), v)) for k in group[0].params}
        for t in group:
            nb = []
            for u in group:
                if u is t:
                    continue
                diff = [k for k in t.params if t.params[k] != u.params[k]]
                if len(diff) != 1:
                    continue
                k = diff[0]
                if isinstance(t.params[k], (bool, str)) or not isinstance(t.params[k], (int, float)):
                    continue
                g = grids[k]
                if abs(g.index(t.params[k]) - g.index(u.params[k])) == 1:
                    nb.append(u.name)
            out[t.name] = nb
    return out


def main() -> None:
    OUT.mkdir(exist_ok=True)
    trials = all_trials()
    res, _ = evaluate(DISCOVERY, trials)

    # Deflated Sharpe per set, N = 85, variance of per-period Sharpe across all trials on that set.
    res["dsr"] = np.nan
    for set_id, g in res.groupby("set"):
        var = float(np.nanvar(g["sr_daily"], ddof=1))
        for i, row in g.iterrows():
            res.loc[i, "dsr"] = deflated_sharpe(row["sr_daily"], int(row["n"]), row["skew"], row["kurt"], N_TRIALS, var)
    res.to_csv(OUT / "discovery_trials.csv", index=False)

    per_trial = res.groupby(["hyp", "trial"]).agg(sharpe=("sharpe", "mean"), dsr=("dsr", "mean"), sets=("set", "count"),
                                                  cagr=("cagr", "mean"), maxdd=("maxdd", "min"),
                                                  exposure=("exposure", "mean"), tpy=("trades_per_year", "mean"))
    per_trial = per_trial.reset_index()
    nb = neighbours(trials)
    sharpe_of = dict(zip(per_trial["trial"], per_trial["sharpe"]))
    per_trial["nb_score"] = [
        float(np.median([sharpe_of[t]] + [sharpe_of[u] for u in nb[t] if u in sharpe_of])) for t in per_trial["trial"]
    ]

    summary = []
    chosen = {}
    for hyp, g in per_trial.groupby("hyp", sort=False):
        best = g.loc[g["sharpe"].idxmax()]
        median_sharpe = float(g["sharpe"].median())
        advance = median_sharpe > 0.3 and best["dsr"] > 0.90
        pick = g.loc[g["nb_score"].idxmax()]
        summary.append({
            "hyp": hyp, "trials": len(g), "median_sharpe": round(median_sharpe, 3),
            "best_trial": best["trial"], "best_sharpe": round(best["sharpe"], 3), "best_dsr": round(best["dsr"], 3),
            "advance": advance, "frozen_choice": pick["trial"] if advance else "",
            "choice_sharpe": round(pick["sharpe"], 3) if advance else np.nan,
        })
        if advance:
            chosen[hyp] = pick["trial"]
    summary = pd.DataFrame(summary)
    summary.to_csv(OUT / "discovery_summary.csv", index=False)
    per_trial.sort_values(["hyp", "sharpe"], ascending=[True, False]).to_csv(OUT / "discovery_per_trial.csv", index=False)
    (OUT / "frozen_rules.json").write_text(json.dumps(chosen, indent=2))

    pd.set_option("display.width", 200)
    print(summary.to_string(index=False))
    print("\nFrozen for validation:", json.dumps(chosen, indent=2))


if __name__ == "__main__":
    main()
