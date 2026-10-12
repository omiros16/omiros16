"""Amendment 4: the frozen MR-ENS-VT system on markets it was never designed on (run once)."""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from lab import Costs, simulate, stats
from run_discovery import context
from run_holdout import final_positions

RESULTS = Path(__file__).resolve().parent / "results"
MARKETS = ("N225-X", "HSI-X", "DJIA-X", "NIFTY-X")


def returns(set_id: str, costs: Costs = Costs(), vt: bool = True, with_trades: bool = False):
    ctx = context(set_id, costs)
    night, day = final_positions(ctx, vt=vt, mode="A")
    ret, trades = simulate(ctx.df, night, day, ctx.rf, ctx.costs, ctx.start, close_exec=True)
    return (ret, trades) if with_trades else ret


def main() -> None:
    rows, series = [], {}
    for m in MARKETS:
        for label, costs in (("normal", Costs()), ("stressed", Costs().stressed())):
            r, trades = returns(m, costs, with_trades=True)
            s = stats(r, trades)
            rows.append({"market": m, "costs": label, **s})
            if label == "normal":
                series[m] = r
    df = pd.DataFrame(rows)
    df.to_csv(RESULTS / "crossmarket.csv", index=False)

    # Correlation with the US versions over the common window (2010-2018).
    us = {"SPX": returns("SPX-V"), "NASDAQ": returns("COMP-V")}
    allr = pd.concat({**series, **us}, axis=1, sort=True).loc["2010":"2018"]
    corr = allr.corr().round(2)
    corr.to_csv(RESULTS / "crossmarket_corr.csv")
    yearly = pd.concat(series, axis=1, sort=True)
    # Years without data stay empty (not 0%).
    yearly = yearly.groupby(yearly.index.year).apply(lambda g: (1 + g).prod(min_count=1) - 1) * 100

    pd.set_option("display.width", 220)
    cols = ["market", "costs", "sharpe", "cagr", "vol", "maxdd", "tstat", "worst_day", "trades_per_year"]
    print(df[cols].round(3).to_string(index=False))
    print("\nCorrelation of daily strategy returns, 2010-2018:")
    print(corr.to_string())
    print("\nCalendar-year returns %:")
    print(yearly.round(1).to_string())


if __name__ == "__main__":
    main()
