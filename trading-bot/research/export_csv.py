"""Write research datasets as bot-ready CSVs into data/ (for `python -m tradingbot backtest`)."""

from pathlib import Path

import datasets as ds

OUT = Path(__file__).resolve().parents[1] / "data"


def main() -> None:
    OUT.mkdir(exist_ok=True)
    for name, df in {
        "US100_D1_NQ_2019_2026.csv": ds.nq_ohlc(),
        "NDX_D1_2019_2026.csv": ds.ndx_ohlc(),
        "SPX_D1_1990_2019.csv": ds.spx_ohlc().loc["1990":],
        "NASDAQCOMP_D1_1999_2018.csv": ds.comp_ohlc(),
    }.items():
        df.rename_axis("time").to_csv(OUT / name, float_format="%.4f")
        print(f"{name}: {len(df)} bars {df.index[0].date()} -> {df.index[-1].date()}")


if __name__ == "__main__":
    main()
