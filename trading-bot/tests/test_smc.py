import numpy as np
import pandas as pd

from conftest import make_frame
from tradingbot.data.synthetic import make_bars
from tradingbot.smc import fair_value_gaps, liquidity_sweeps, market_structure, quarter_above, quarter_below, swing_points


def test_swing_high_is_confirmed_only_after_strength_bars():
    highs = [10, 11, 15, 12, 11, 10, 9]
    df = make_frame([(h - 1, h, h - 2, h - 0.5) for h in highs])
    sw = swing_points(df, strength=2)
    assert sw["is_swing_high"].tolist() == [False, False, True, False, False, False, False]
    # Pivot at bar 2 is confirmed at bar 4.
    assert np.isnan(sw["last_swing_high"].iloc[3])
    assert sw["last_swing_high"].iloc[4] == 15


def test_equal_highs_mark_one_pivot():
    highs = [10, 11, 15, 15, 12, 11, 10]
    df = make_frame([(h - 1, h, h - 2, h - 0.5) for h in highs])
    assert swing_points(df, 2)["is_swing_high"].sum() == 1


def test_swings_are_causal():
    bars = make_bars(400, seed=3)
    full = swing_points(bars, 3)
    cut = swing_points(bars.iloc[:250], 3)
    pd.testing.assert_series_equal(full["last_swing_high"].iloc[:250], cut["last_swing_high"])
    pd.testing.assert_series_equal(full["last_swing_low"].iloc[:250], cut["last_swing_low"])


def test_bos_up_then_choch_down():
    # Up to a swing high at 20, pullback, break above it (BOS up), then break the swing low (CHoCH).
    closes = [10, 12, 14, 20, 16, 14, 13, 15, 21, 22, 18, 16, 15, 12, 11]
    df = make_frame([(c, c + 0.5, c - 0.5, c) for c in closes])
    ms = market_structure(df, strength=1)
    up = ms.index[ms["bos"] == 1]
    assert df.loc[up[0], "close"] == 21
    down = ms[(ms["bos"] == -1)]
    assert len(down) >= 1 and bool(down["choch"].iloc[0])
    assert ms["structure"].iloc[-1] == -1


def test_fair_value_gaps():
    df = make_frame([(10, 11, 9, 10.5), (10.5, 14, 10.4, 13.8), (13.8, 15, 12, 14.5), (14.5, 14.6, 13, 13.2)])
    fvg = fair_value_gaps(df)
    assert fvg["fvg_bull_bottom"].iloc[2] == 11 and fvg["fvg_bull_top"].iloc[2] == 12
    assert fvg["fvg_bull_bottom"].isna().sum() == 3


def test_quarter_levels():
    assert quarter_below(20130.0, 250) == 20000
    assert quarter_above(20130.0, 250) == 20250
    assert quarter_below(20250.0, 250) == 20250


def test_bullish_sweep_of_quarter_level():
    df = make_frame([
        (20080, 20120, 20060, 20100),  # above 20000
        (20100, 20110, 19980, 20040),  # dips to 19980, closes back above 20000
        (20040, 20050, 19900, 19950),  # breaks below and stays: no sweep
    ])
    sw = liquidity_sweeps(df, quarter_size=250, swing_strength=None, min_penetration=5.0)
    assert sw["sweep_bull_level"].iloc[1] == 20000
    assert np.isnan(sw["sweep_bull_level"].iloc[2])
    # 10 points through is not enough with min_penetration 25.
    assert np.isnan(liquidity_sweeps(df, 250, None, 25.0)["sweep_bull_level"].iloc[1])


def test_bearish_sweep_of_quarter_level():
    df = make_frame([(20200, 20220, 20150, 20210), (20210, 20270, 20190, 20230)])
    sw = liquidity_sweeps(df, quarter_size=250, swing_strength=None)
    assert sw["sweep_bear_level"].iloc[1] == 20250
