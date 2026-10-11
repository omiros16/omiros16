"""Sanity checks for the research engine (synthetic data only)."""

import numpy as np
import pandas as pd
import pytest

from hypotheses import Ctx, all_trials, run_trial
from lab import Costs, segments, simulate, state_machine


def frame(closes, opens=None):
    idx = pd.bdate_range("2020-01-06", periods=len(closes))
    c = np.asarray(closes, float)
    o = c if opens is None else np.asarray(opens, float)
    return pd.DataFrame({"open": o, "high": np.maximum(o, c) + 1, "low": np.minimum(o, c) - 1, "close": c}, index=idx)


ZERO = Costs(0.0, 0.0, 0.0)


def test_buy_and_hold_close_to_close():
    df = frame([100, 101, 99, 103])
    ones = np.ones(4)
    ones[0] = 0
    ret, trades = simulate(df, ones, ones, np.zeros(4), ZERO, df.index[0])
    assert (1 + ret).prod() == pytest.approx(1.03)
    assert trades == 1


def test_costs_and_close_extra():
    df = frame([100, 100, 100, 100])
    s = np.array([1, 1, 0, 0], float)  # decided at close 0, exited at close 2
    night, day = segments(s, "A")
    ret, trades = simulate(df, night, day, np.zeros(4), Costs(2.0, 1.0, 0.0), df.index[0])
    # Enter at close 0 and exit at close 2: 2 sides * (1 bp + 0.5 bp).
    assert ret.sum() == pytest.approx(-3e-4)
    assert trades == 1


def test_swap_counts_calendar_nights():
    df = frame([100] * 6)  # Mon..Mon
    s = np.array([1, 1, 1, 1, 1, 1], float)
    night, day = segments(s, "A")
    rf = np.full(6, 3.5)
    ret, _ = simulate(df, night, day, rf, Costs(0.0, 0.0, 2.5), df.index[0])
    # 5 trading-day steps cover 7 calendar nights (Fri -> Mon counts 3).
    assert -ret.sum() == pytest.approx(7 * 0.06 / 360)


def test_short_swap_and_overnight_segments():
    df = frame([100, 102, 101], opens=[100, 101, 103])
    night = np.array([0, 1, 0], float)  # bought at close 0, sold at open 1
    day = np.zeros(3)
    ret, trades = simulate(df, night, day, np.zeros(3), ZERO, df.index[0])
    assert ret.iloc[1] == pytest.approx(0.01)
    assert ret.iloc[2] == 0
    assert trades == 1


def test_mode_b_enters_at_next_open():
    df = frame([100, 110, 121], opens=[100, 105, 115])
    s = np.array([1, 1, 1], float)
    night, day = segments(s, "B")
    assert list(night) == [0, 0, 1] and list(day) == [0, 1, 1]
    ret, _ = simulate(df, night, day, np.zeros(3), ZERO, df.index[0])
    assert ret.iloc[1] == pytest.approx(110 / 105 - 1)


def test_state_machine_renews_and_max_hold():
    entry = np.array([1, 0, 0, 0, 1, 1, 0], bool)
    exit_ = np.zeros(7, bool)
    # A new entry signal while in a trade only renews it when the exit fires.
    assert list(state_machine(entry, exit_, max_hold=2)) == [1, 1, 0, 0, 1, 1, 0]
    nxt = np.ones(7, bool)
    assert list(state_machine(entry, nxt)) == [1, 0, 0, 0, 1, 1, 0]


def test_all_trials_run_and_are_causal():
    """Every trial's returns up to day k must not change when later data is added."""
    rng = np.random.default_rng(0)
    n = 900
    c = 100 * np.exp(np.cumsum(rng.normal(0.0003, 0.012, n)))
    o = c * np.exp(rng.normal(0, 0.003, n))
    df = frame(c, o)
    df["high"] = np.maximum(df["open"], df["close"]) * (1 + rng.uniform(0, 0.01, n))
    df["low"] = np.minimum(df["open"], df["close"]) * (1 - rng.uniform(0, 0.01, n))
    vix = 15 + 5 * rng.random(n)
    start = df.index[300]
    cut = 700
    for trial in all_trials():
        full = run_trial(trial, Ctx(df, start, np.full(n, 2.0), vix, True, True))
        part = run_trial(trial, Ctx(df.iloc[:cut], start, np.full(cut, 2.0), vix[:cut], True, True))
        # Calendar rules (H9/H10) use the exchange calendar, known in advance; the truncated
        # sample's last month is incomplete, so compare them up to a month before the cut.
        k = cut - 300 - (25 if trial.hyp in ("H9", "H10") else 1)
        a, b = full[0].iloc[:k], part[0].iloc[:k]
        np.testing.assert_allclose(a.to_numpy(), b.to_numpy(), atol=1e-12, err_msg=trial.name)
