"""FTMO 2-step with the bot as built: ticket ledger, real paths and a block bootstrap of bars.

Writes
  results/prop_ledger_real.csv      every design and leverage on the real out-of-sample paths
  results/prop_ledger_boot.csv      bootstrap: pass / fail / time per phase, funded year, retries
  results/prop_ledger_variants.csv  the bot's design (D1) with other kill and stop levels

Usage: python run_prop_ledger.py [N_SAMPLES=4000]   (numba makes it ~100x faster: pip install numba)

Model (see prop_ledger.py for the bar-by-bar rules):
  * The target exposure is the bot's own mr_ensemble (tradingbot.strategies), decided at the close
    and executed at the next open; tickets are added and reduced like runner._rebalance.
  * FTMO: +10% / +5%, at least 4 trading days, equity never below midnight balance - 5% of the
    initial balance (floating P/L counts), never below 90% of it. The bot's -9% kill counts as a
    failure. "No cap" runs each phase for up to 10 years; "2y" stops each phase after 504 days.
  * Costs: 2 bp round trip, stop fills 5 bp of price beyond the floor, swap (risk-free + 2.5%)/360
    of notional per calendar night.
  * Bootstrap: 20-bar blocks of relative OHLC from S&P 500 2010-19, NASDAQ 2010-18 and NQ 2019-26
    (roll-adjusted, datasets.nq_ohlc_spot); the strategy is re-run on every synthetic path after
    520 warm-up bars. Phases 1 and 2 use independent paths. Funded = one year from a fresh account.
  * Cash-index bars show the whole overnight move as an opening gap, while the CFD trades for
    23 hours: on weekday nights the bot's stop is assumed to fill at its level (weekend gaps stay
    gaps). NQ bars already cover the night session.
"""

from __future__ import annotations

import sys
import time
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

import datasets as ds
import prop_ledger as pl

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
sys.path.insert(0, str(HERE.parent))
from tradingbot.strategies import create_strategy  # noqa: E402

OHLC = ["open", "high", "low", "close"]
SWAP_MARKUP = 2.5  # % a year on top of the risk-free rate (research Costs)
LEVS = (0.5, 0.75, 1.0, 1.25, 1.5)
BLOCK, WARM, H_PHASE, CAP, H_FUNDED = 20, 520, 2520, 504, 252
SEED = 20261011
D1 = "D1 bot: max(B,E)-3%, LIFO"


# Data -------------------------------------------------------------------------------
def _clean(df: pd.DataFrame) -> pd.DataFrame:
    df = df[OHLC].astype(float).copy()
    df["high"] = df[OHLC].max(axis=1)
    df["low"] = df[OHLC].min(axis=1)
    return df


def _splice_warmup(df: pd.DataFrame, warm: pd.DataFrame, n: int = 700) -> pd.DataFrame:
    """Older bars of another series (ratio-scaled) before df, so the 514-bar warm-up is ready."""
    w = warm[warm.index < df.index[0]].iloc[-n:]
    return pd.concat([w * (df["open"].iloc[0] / w["close"].iloc[-1]), df])


def strategy_x(bars: pd.DataFrame) -> np.ndarray:
    """Target exposure at leverage 1, decided at each close (the bot's own mr_ensemble)."""
    return create_strategy("mr_ensemble", {"leverage": 1.0}).prepare(bars[OHLC])["target_exposure"].to_numpy(float)


def arrays(bars: pd.DataFrame, cash: bool) -> dict:
    idx = bars.index
    nights = np.ones(len(idx))
    nights[:-1] = (idx[1:] - idx[:-1]).days
    rf = ds.rf_aligned(idx)
    cont = np.zeros(len(idx), dtype=bool)
    if cash:
        cont[1:] = nights[:-1] == 1
    return {"O": bars["open"].to_numpy(float), "H": bars["high"].to_numpy(float), "L": bars["low"].to_numpy(float),
            "C": bars["close"].to_numpy(float), "nights": nights, "rate": (rf + SWAP_MARKUP) / 100 / 360,
            "index": idx, "rf": rf, "cont": cont}


def real_paths() -> dict:
    comp, spx = _clean(ds.comp_ohlc()), _clean(ds.spx_ohlc())
    specs = {
        "NQ 2019-2026 (roll-adjusted)": (_splice_warmup(_clean(ds.nq_ohlc_spot()), comp), "2019-01-02", "2026-10-09", False),
        "NASDAQ-100 cash 2019-2026": (_splice_warmup(_clean(ds.ndx_ohlc()), comp), "2019-01-02", "2026-10-09", True),
        "S&P 500 2010-2019": (spx[spx.index >= "2005-01-01"], "2010-01-04", "2019-12-23", True),
        "NASDAQ 2010-2018": (comp[comp.index >= "2005-01-01"], "2010-01-04", "2018-12-31", True),
    }
    out = {}
    for name, (bars, s, e, cash) in specs.items():
        bars = bars[bars.index <= pd.Timestamp(e)]
        out[name] = (arrays(bars, cash), strategy_x(bars), int(bars.index.searchsorted(pd.Timestamp(s))), len(bars))
    return out


def _relative(df: pd.DataFrame) -> pd.DataFrame:
    pc = df["close"].shift(1)
    return pd.DataFrame({"ro": df["open"] / pc, "rh": df["high"] / pc, "rl": df["low"] / pc, "rc": df["close"] / pc},
                        index=df.index)


def pool_parts() -> list[pd.DataFrame]:
    comp, spx, nq = _clean(ds.comp_ohlc()), _clean(ds.spx_ohlc()), _clean(ds.nq_ohlc_spot())
    parts = []
    for bars, s, e, cash in ((spx, "2010-01-04", "2019-12-23", True), (comp, "2010-01-04", "2018-12-31", True),
                             (nq, "2019-01-03", "2026-10-09", False)):
        r = _relative(bars)
        a = arrays(bars, cash)
        r["nights"], r["rf"], r["cont"] = a["nights"], a["rf"], a["cont"]
        parts.append(r[(r.index >= pd.Timestamp(s)) & (r.index <= pd.Timestamp(e))].dropna())
    return parts


# Real paths -----------------------------------------------------------------------------
def run_real() -> pd.DataFrame:
    """Each calendar year is a fresh account of the current size (limits stay in % of the account).
    FTMO breaches are counted, not enforced; a kill leaves the account flat until the year ends."""
    rows = []
    for pname, (a, x, start, end) in real_paths().items():
        yr = a["index"].year.to_numpy()
        rebase = np.r_[False, yr[1:] != yr[:-1]]
        years = (end - start) / 252
        for dname, d in pl.DESIGNS.items():
            for lev in LEVS:
                eq, cnt, *_ = pl.run(a, x, start, end, lev, d, rebase=rebase, over_cont=True)
                eq = eq[~np.isnan(eq)]
                r = np.diff(np.r_[1.0, eq]) / np.r_[1.0, eq[:-1]]
                rows.append({
                    "path": pname, "design": dname, "lev": lev,
                    "CAGR %": 100 * (eq[-1] ** (1 / years) - 1),
                    "Sharpe": r.mean() / r.std() * np.sqrt(252) if r.std() > 0 else np.nan,
                    "maxDD %": 100 * (eq / np.maximum.accumulate(np.r_[1.0, eq])[1:] - 1).min(),
                    "bot stops / yr": (cnt[0] + cnt[1]) / years, "kills": int(cnt[2]),
                    "FTMO daily breaches": int(cnt[3]), "FTMO max-loss breaches": int(cnt[4]),
                    "spread % / yr": 100 * cnt[6] / years, "swap % / yr": 100 * cnt[7] / years,
                })
    return pd.DataFrame(rows)


# Bootstrap ---------------------------------------------------------------------------------
_G: dict = {}
COLS = ["design", "lev", "o1", "d1", "o2", "d2", "of", "fe", "stops1", "days1"]


def _init() -> None:
    parts = pool_parts()
    cat = pd.concat(parts)
    seg = np.concatenate([np.full(len(p), i) for i, p in enumerate(parts)])
    valid = np.array([i for i in range(len(cat) - BLOCK + 1) if seg[i] == seg[i + BLOCK - 1]])
    _G.update(valid=valid, **{k: cat[k].to_numpy() for k in ("ro", "rh", "rl", "rc", "nights", "rf")},
              cont=cat["cont"].to_numpy().astype(bool))


def _path(rng, n_bars):
    st = rng.choice(_G["valid"], size=n_bars // BLOCK + 1)
    idx = (st[:, None] + np.arange(BLOCK)[None, :]).ravel()[:n_bars]
    c = 1000.0 * np.cumprod(_G["rc"][idx])
    pc = np.r_[1000.0, c[:-1]]
    bars = pd.DataFrame({"open": pc * _G["ro"][idx], "high": pc * _G["rh"][idx], "low": pc * _G["rl"][idx],
                         "close": c}, index=pd.bdate_range("2000-01-03", periods=n_bars))
    a = {"O": bars["open"].to_numpy(), "H": bars["high"].to_numpy(), "L": bars["low"].to_numpy(), "C": c,
         "nights": _G["nights"][idx].astype(float), "rate": (_G["rf"][idx] + SWAP_MARKUP) / 100 / 360,
         "cont": _G["cont"][idx]}
    return a, strategy_x(bars)


def _play(paths, variants):
    (A, B, F) = paths
    out = []
    for name, (design, over) in variants.items():
        for lev in LEVS:
            kw = dict(stop_on_outcome=True, over_cont=True, **over)
            _, c1, o1, d1, _ = pl.run(A[0], A[1], WARM, WARM + H_PHASE, lev, design, target=0.10, **kw)
            _, _, o2, d2, _ = pl.run(B[0], B[1], WARM, WARM + H_PHASE, lev, design, target=0.05, **kw)
            _, _, of, _, fe = pl.run(F[0], F[1], WARM, WARM + H_FUNDED, lev, design, target=np.inf, **kw)
            days1 = (d1 + 1) if o1 != 0 else H_PHASE
            out.append((name, lev, o1, d1, o2, d2, of, fe, c1[0] + c1[1], days1))
    return out


def _sample(job):
    seed, which = job
    rng = np.random.default_rng(seed)
    paths = (_path(rng, WARM + H_PHASE), _path(rng, WARM + H_PHASE), _path(rng, WARM + H_FUNDED))
    return _play(paths, VARIANTS if which == "variants" else {k: (v, {}) for k, v in pl.DESIGNS.items()})


VARIANTS = {
    "D1, kill -9%": (pl.DESIGNS[D1], {}),
    "D1, kill -9.5%": (pl.DESIGNS[D1], {"kill_pct": 0.095}),
    "D1, stop 2.5%": (pl.DESIGNS[D1], {"int_pct": 0.025}),
    "D1, stop 3.5%": (pl.DESIGNS[D1], {"int_pct": 0.035}),
    "D1, stop 4%": (pl.DESIGNS[D1], {"int_pct": 0.04}),
}


def summarize(rows) -> pd.DataFrame:
    df = pd.DataFrame(rows, columns=COLS)
    res = []
    for (name, lev), g in df.groupby(["design", "lev"], sort=False):
        o1, d1, o2, d2 = (g[c].to_numpy() for c in ("o1", "d1", "o2", "d2"))
        p1, f1, p2, f2 = (o1 == 1).mean(), (o1 < 0).mean(), (o2 == 1).mean(), (o2 < 0).mean()
        both = (o1 == 1) & (o2 == 1)
        p1c, f1c = ((o1 == 1) & (d1 < CAP)).mean(), ((o1 < 0) & (d1 < CAP)).mean()
        p2c, f2c = ((o2 == 1) & (d2 < CAP)).mean(), ((o2 < 0) & (d2 < CAP)).mean()
        # Retrying after every failure (Wald): expected attempts and time to a funded account.
        t1 = np.where(o1 != 0, d1 + 1, H_PHASE)
        t2 = np.where(o2 != 0, d2 + 1, H_PHASE)
        t = np.where(o1 == 1, t1 + t2, t1)
        p_both = both.mean()
        fe = g["fe"].to_numpy() - 1
        res.append({
            "design": name, "lev": lev,
            "P1 pass %": 100 * p1, "P1 fail %": 100 * f1, "P1 of which kill %": 100 * (o1 == -2).mean(),
            "P1 median months": np.median(d1[o1 == 1] + 1) / 21 if p1 else np.nan,
            "P2 pass %": 100 * p2, "P2 fail %": 100 * f2,
            "P2 median months": np.median(d2[o2 == 1] + 1) / 21 if p2 else np.nan,
            "both pass %": 100 * p1 * p2, "challenge fail %": 100 * (f1 + p1 * f2),
            "both median months": np.median(d1[both] + d2[both] + 2) / 21 if both.any() else np.nan,
            "2y both pass %": 100 * p1c * p2c, "2y challenge fail %": 100 * (f1c + p1c * f2c),
            "expected attempts": 1 / p_both if p_both else np.inf,
            "expected months to funded": t.mean() / p_both / 21 if p_both else np.inf,
            # pooled over all phase-1 days (a mean of per-path rates over-weights paths that end early)
            "P1 bot stops / yr": float(g["stops1"].sum() / g["days1"].sum() * 252),
            "funded FTMO breach %": 100 * (g["of"] == -1).mean(), "funded bot kill %": 100 * (g["of"] == -2).mean(),
            "funded median %": 100 * np.median(fe), "funded p10 %": 100 * np.percentile(fe, 10),
        })
    return pd.DataFrame(res)


def run_boot(n: int, which: str) -> pd.DataFrame:
    _init()
    a, x = _path(np.random.default_rng(0), WARM + 30)  # compile before forking
    pl.run(a, x, WARM, WARM + 30, 1.0, pl.DESIGNS[D1], target=0.1, stop_on_outcome=True)
    jobs = [(SEED * 1000 + i, which) for i in range(n)]  # the same paths for designs and variants
    with Pool(4, initializer=_init) as pool:
        chunks = pool.map(_sample, jobs, chunksize=8)
    return summarize([r for ch in chunks for r in ch])


def main() -> None:
    n = int(sys.argv[1]) if len(sys.argv) > 1 else 4000
    RESULTS.mkdir(exist_ok=True)
    pd.set_option("display.width", 300)
    pd.set_option("display.max_rows", 300)
    pd.set_option("display.max_columns", 40)
    t0 = time.time()
    real = run_real()
    real.to_csv(RESULTS / "prop_ledger_real.csv", index=False)
    print(real.round(2).to_string(index=False))
    for which, name in (("designs", "prop_ledger_boot.csv"), ("variants", "prop_ledger_variants.csv")):
        res = run_boot(n, which)
        res.to_csv(RESULTS / name, index=False)
        print(f"\n== {which}: {n} bootstrap samples ==")
        print(res.round(1).to_string(index=False))
    print(f"\n{time.time() - t0:.0f}s")


if __name__ == "__main__":
    main()
