"""Descriptive analyses quoted in RESEARCH.md §2-§4 that are not part of the pre-registered chain.

Run after run_discovery.py (needs output/discovery_trials.csv). Writes:
  results/vt_vs_raw.csv          MR-ENS with and without volatility targeting, per data set (Amendment 3)
  results/guard_effect.csv       the regime guard exactly as the bot ships it (MREnsemble.prepare)
  results/regime_years.csv       calendar-year returns vs the S&P 500 autocorrelation of the 2 years before
  results/ensemble_dsr.csv       Deflated Sharpe of MR-ENS on the discovery sets (it was not pre-registered)
  results/us_correlations.csv    correlation of the strategy's daily returns on US indices, 2010-2018
  results/underwater.csv         longest spell without a new equity high, per out-of-sample set
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

import datasets as ds
from hypotheses import Ctx
from lab import Costs, deflated_sharpe, simulate, stats
from run_crossmarket import returns
from run_discovery import N_TRIALS, context
from run_holdout import final_positions

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
sys.path.insert(0, str(HERE.parent))
from tradingbot.strategies.mr_ensemble import MREnsemble  # noqa: E402


def vt_vs_raw() -> pd.DataFrame:
    rows = []
    for s in ("SPX-D", "COMP-D", "SPX-V", "COMP-V", "NDX-H", "NQ-H"):
        for vt in (False, True):
            st = stats(returns(s, vt=vt))
            rows.append({"set": s, "vol_targeting": vt, "sharpe": st["sharpe"], "cagr": st["cagr"],
                         "maxdd": st["maxdd"], "worst_day": st["worst_day"]})
    return pd.DataFrame(rows)


def shipped_guard(full: pd.DataFrame, index: pd.DatetimeIndex) -> np.ndarray:
    """regime_ok from the bot's own MREnsemble.prepare, decided at close t, applied to day t+1."""
    data = MREnsemble().prepare(full.loc[: index[-1]])
    ok = data["regime_ok"].reindex(index).fillna(True).astype(float).to_numpy()
    return np.concatenate([[1.0], ok[:-1]])


def guard_effect() -> pd.DataFrame:
    sources = {"SPX-E": ds.spx_ohlc, "SPX-D": ds.spx_ohlc, "SPX-V": ds.spx_ohlc, "COMP-D": ds.comp_ohlc,
               "COMP-V": ds.comp_ohlc, "NDX-H": ds.ndx_ohlc, "NQ-H": ds.nq_ohlc}
    rows = []
    for s, loader in sources.items():
        ctx = context(s)
        night, day = final_positions(ctx, vt=True, mode="A")
        g = shipped_guard(loader(), ctx.df.index)
        mask = ctx.df.index >= ctx.start
        for label, factor in (("no guard", 1.0), ("guard as shipped", g)):
            r, trades = simulate(ctx.df, night * factor, day * factor, ctx.rf, ctx.costs, ctx.start, close_exec=True)
            st = stats(r, trades)
            yearly = (1 + r).groupby(r.index.year).prod() - 1
            rows.append({"set": s, "variant": label, "sharpe": st["sharpe"], "cagr": st["cagr"], "maxdd": st["maxdd"],
                         "negative_years": int((yearly < 0).sum()), "years": len(yearly),
                         "share_of_days_off": float(1 - np.mean(np.broadcast_to(factor, mask.shape)[mask]))})
    # Before about 1980 the S&P 500 highs and lows are rarely the day's real extremes, which mutes
    # the range-based rules (IBS, N-day low exit): repeat 1962-1989 with the close-only rules.
    from run_validation import ensemble_positions, family_trials
    from run_holdout import vol_scale
    ctx = context("SPX-E")
    night, day = ensemble_positions(ctx, [t for t in family_trials() if not t.needs_range])
    s_ = vol_scale(ctx)
    r, trades = simulate(ctx.df, night * s_, day * s_, ctx.rf, ctx.costs, ctx.start, close_exec=True)
    st = stats(r, trades)
    yearly = (1 + r).groupby(r.index.year).prod() - 1
    rows.append({"set": "SPX-E", "variant": "no guard, close-only rules", "sharpe": st["sharpe"], "cagr": st["cagr"],
                 "maxdd": st["maxdd"], "negative_years": int((yearly < 0).sum()), "years": len(yearly),
                 "share_of_days_off": 0.0})
    return pd.DataFrame(rows)


def _mr_vt(df: pd.DataFrame, start: str, real_open: bool) -> pd.Series:
    ctx = Ctx(df, pd.Timestamp(start), ds.rf_aligned(df.index), ds.vix_aligned(df.index), real_open, True, Costs())
    night, day = final_positions(ctx, vt=True, mode="A")
    r, _ = simulate(ctx.df, night, day, ctx.rf, ctx.costs, ctx.start, close_exec=True)
    return r


def regime_years() -> tuple[pd.DataFrame, pd.DataFrame]:
    """MR-ENS-VT per calendar year (S&P 500 1962-2019, NASDAQ-100 2020-2026) against the lag-1
    autocorrelation of S&P 500 daily returns over the two previous calendar years."""
    spx = ds.spx_ohlc()
    r_spx = _mr_vt(spx.loc["1960-06":], "1962-01-01", real_open=False)
    r_ndx = _mr_vt(ds.ndx_ohlc(), "2019-01-02", real_open=True)
    spx_ret = ds.spx_closes().pct_change().dropna()
    rows = []
    for y in range(1962, 2027):
        r = r_spx[r_spx.index.year == y] if y <= 2019 else r_ndx[r_ndx.index.year == y]
        window = spx_ret[(spx_ret.index.year >= y - 2) & (spx_ret.index.year <= y - 1)]
        rows.append({"year": y, "autocorr_prev_2y": window.autocorr(1), "return": float((1 + r).prod() - 1)})
    years = pd.DataFrame(rows)
    groups = []
    for label, g in (("negative (<= 0)", years[years.autocorr_prev_2y <= 0]), ("positive", years[years.autocorr_prev_2y > 0])):
        groups.append({"autocorrelation": label, "years": len(g), "mean_return": g["return"].mean(),
                       "share_positive": (g["return"] > 0).mean()})
    return years, pd.DataFrame(groups)


def ensemble_dsr() -> pd.DataFrame:
    trials = pd.read_csv(HERE / "output" / "discovery_trials.csv")
    ens = pd.read_csv(RESULTS / "validation_mr_ens.csv")
    rows = []
    for s in ("SPX-D", "COMP-D"):
        var = float(np.nanvar(trials.loc[trials["set"] == s, "sr_daily"], ddof=1))
        row = ens[(ens["set"] == s)].iloc[0]
        for n in (1, N_TRIALS, 85, 89):
            rows.append({"set": s, "n_trials": n, "sharpe": row["sharpe"],
                         "dsr": deflated_sharpe(row["sr_daily"], int(row["n"]), row["skew"], row["kurt"], n, var)})
    return pd.DataFrame(rows)


def us_correlations() -> pd.DataFrame:
    r = {name: returns(s) for name, s in (("S&P 500", "SPX-V"), ("NASDAQ", "COMP-V"), ("Dow", "DJIA-X"))}
    return pd.concat(r, axis=1, sort=True).loc["2010":"2018"].corr()


def underwater() -> pd.DataFrame:
    rows = []
    for s in ("SPX-V", "COMP-V", "NDX-H", "NQ-H"):
        r = returns(s)
        eq = (1 + r).cumprod()
        highs = eq.index[eq >= eq.cummax()]
        gaps = np.diff(np.r_[highs.values, eq.index[-1:].values]).astype("timedelta64[D]").astype(int)
        i = int(np.argmax(gaps))
        yearly = (1 + r).groupby(r.index.year).prod() - 1
        rows.append({"set": s, "longest_underwater_months": gaps[i] / 30.44, "from": highs[i].date(),
                     "negative_years": ", ".join(f"{y} {v:+.1%}" for y, v in yearly[yearly < 0].items())})
    return pd.DataFrame(rows)


def main() -> None:
    RESULTS.mkdir(exist_ok=True)
    pd.set_option("display.width", 220)
    out = {
        "vt_vs_raw": vt_vs_raw(),
        "guard_effect": guard_effect(),
        "ensemble_dsr": ensemble_dsr(),
        "us_correlations": us_correlations(),
        "underwater": underwater(),
    }
    years, groups = regime_years()
    out["regime_years"] = years
    for name, df in out.items():
        df.to_csv(RESULTS / f"{name}.csv", index=(name == "us_correlations"))
        print(f"\n== {name} ==")
        print(df.round(3).to_string())
    print("\n== regime groups ==")
    print(groups.round(3).to_string(index=False))
    groups.to_csv(RESULTS / "regime_groups.csv", index=False)


if __name__ == "__main__":
    main()
