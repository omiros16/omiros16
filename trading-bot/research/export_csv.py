"""Write research datasets as bot-ready CSVs into data/ (for `python -m tradingbot backtest`)."""

from pathlib import Path

import datasets as ds

OUT = Path(__file__).resolve().parents[1] / "data"


def main() -> None:
    OUT.mkdir(exist_ok=True)
    for name, df in {
        # NQ futures without roll gaps and carry: behaves like the US100 CFD (datasets.nq_ohlc_spot).
        "US100_D1_NQ_2019_2026.csv": ds.nq_ohlc_spot(),
        "NDX_D1_2019_2026.csv": ds.ndx_ohlc(),
        # Before 2014 the S&P "open" is often just the previous close (no real opening gap),
        # which a next-open backtest cannot use.
        "SPX_D1_2014_2019.csv": ds.spx_ohlc().loc["2014":],
        "NASDAQCOMP_D1_1999_2018.csv": ds.comp_ohlc(),
    }.items():
        df.rename_axis("time").to_csv(OUT / name, float_format="%.4f")
        print(f"{name}: {len(df)} bars {df.index[0].date()} -> {df.index[-1].date()}")


if __name__ == "__main__":
    main()
