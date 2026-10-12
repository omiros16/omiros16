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


def _synthetic(seed=0, n=900):
    rng = np.random.default_rng(seed)
    c = 100 * np.exp(np.cumsum(rng.normal(0.0003, 0.012, n)))
    o = c * np.exp(rng.normal(0, 0.003, n))
    df = frame(c, o)
    df["high"] = np.maximum(df["open"], df["close"]) * (1 + rng.uniform(0, 0.01, n))
    df["low"] = np.minimum(df["open"], df["close"]) * (1 - rng.uniform(0, 0.01, n))
    return df, 15 + 5 * rng.random(n)


def _perturbed(df, vix, cut, rng):
    """Same history up to row `cut - 1`, different prices and VIX from row `cut` on."""
    n = len(df)
    d2, v2 = df.copy(), vix.copy()
    f = np.exp(rng.normal(0, 0.05, n - cut))
    for col in ("open", "high", "low", "close"):
        d2.iloc[cut:, d2.columns.get_loc(col)] = d2[col].to_numpy()[cut:] * f * (1 + rng.uniform(-0.01, 0.01, n - cut))
    d2["high"] = d2[["open", "high", "low", "close"]].max(axis=1)
    d2["low"] = d2[["open", "high", "low", "close"]].min(axis=1)
    v2[cut:] = v2[cut:] * np.exp(rng.normal(0, 0.3, n - cut))
    return d2, v2


def is_causal(trial, cuts=range(320, 890, 9)) -> bool:
    """Perturb the data from row `cut` on. The positions held through day `cut` were decided
    at close cut-1 and must not change; nor may the returns before day `cut`. Unlike
    truncating the sample, perturbing the future also catches a look-ahead of one bar."""
    df, vix = _synthetic()
    n = len(df)
    start = df.index[300]
    rng = np.random.default_rng(99)
    base = Ctx(df, start, np.full(n, 2.0), vix, True, True)
    ref = trial.build(base) if trial.custom is None else run_trial(trial, base)[0]
    for cut in cuts:
        d2, v2 = _perturbed(df, vix, cut, rng)
        other = Ctx(d2, start, np.full(n, 2.0), v2, True, True)
        if trial.custom is not None:
            a, b = ref, run_trial(trial, other)[0]
            m = a.index < df.index[cut]
            if not np.allclose(a[m], b[m], atol=1e-12):
                return False
        else:
            got = trial.build(other)
            for x, y in zip(ref[:2], got[:2]):
                if not np.allclose(np.nan_to_num(x[:cut + 1]), np.nan_to_num(y[:cut + 1]), atol=1e-12):
                    return False
    return True


def test_all_trials_run_and_are_causal():
    df, vix = _synthetic()
    n = len(df)
    for trial in all_trials():
        out = run_trial(trial, Ctx(df, df.index[300], np.full(n, 2.0), vix, True, True))
        assert out is not None and np.isfinite(out[0].to_numpy()).all(), trial.name
        assert is_causal(trial), trial.name


def test_causality_check_catches_one_bar_look_ahead():
    from hypotheses import Trial, _mode_a

    def peek(x):
        future_up = pd.Series(x.c).shift(-1).to_numpy() > x.c
        return _mode_a(future_up.astype(float))

    assert not is_causal(Trial("PEEK", {"L": 1}, peek))


def test_ticket_ledger_matches_an_independent_implementation():
    """prop_ledger.run_ledger (fast, used for RESEARCH.md §5) against a plain re-implementation."""
    import prop_ledger as pl
    from prop_ledger_check import ref_run

    rng = np.random.default_rng(3)
    n = 900
    c = 1000 * np.exp(np.cumsum(rng.normal(0.0002, 0.013, n)))
    o = np.r_[c[0], c[:-1]] * np.exp(rng.normal(0, 0.004, n))
    h = np.maximum(o, c) * (1 + rng.uniform(0, 0.008, n))
    low = np.minimum(o, c) * (1 - rng.uniform(0, 0.008, n))
    idx = pd.bdate_range("2015-01-05", periods=n)
    nights = np.r_[(idx[1:] - idx[:-1]).days, 1].astype(float)
    a = {"O": o, "H": h, "L": low, "C": c, "nights": nights, "rate": np.full(n, 0.05 / 360)}
    x = np.clip(rng.normal(0.4, 0.5, n), 0, 2)  # any exposure path exercises the bookkeeping
    for name in ("D1 bot: max(B,E)-3%, LIFO", "D3 guard B-4.5% only", "D5 FIFO + D1 stop"):
        d = pl.DESIGNS[name]
        for lev in (0.75, 2.5):
            eq, cnt, *_ = pl.run(a, x, 10, n, lev, d)
            ref, stops, guards, kills, ftmo, *_ = ref_run(a, x, 10, n, lev, d["ref_kind"], d["guard_pct"],
                                                           d["close_order"], d["realize"], d["guard_now"])
            np.testing.assert_allclose(eq, ref, atol=1e-9, err_msg=f"{name} {lev}")
            assert (cnt[0], cnt[1], cnt[2], cnt[3]) == (stops, guards, kills, ftmo), (name, lev)

    # The options behind the RESEARCH.md §5 numbers: stops that fill overnight at their level
    # (cont), account restarts (rebase) and stopping at the first pass / breach / kill.
    a["cont"] = np.r_[False, nights[:-1] == 1]
    rebase = np.zeros(n, dtype=bool)
    rebase[[300, 600]] = True
    d = pl.DESIGNS["D1 bot: max(B,E)-3%, LIFO"]
    for lev in (1.0, 2.5, 4.0):
        eq, cnt, *_ = pl.run(a, x, 10, n, lev, d, rebase=rebase, over_cont=True)
        ref, stops, guards, kills, ftmo, *_ = ref_run(a, x, 10, n, lev, 1, 0.0, 0, 0, cont=a["cont"], rebase=rebase)
        np.testing.assert_allclose(eq, ref, atol=1e-9, err_msg=f"cont/rebase {lev}")
        assert (cnt[0], cnt[2], cnt[3]) == (stops, kills, ftmo), lev
        for target, kill in ((0.05, 0.09), (0.10, 0.09), (0.5, 0.045), (0.5, 0.03)):  # passes and kills
            eq, cnt, outcome, day, _ = pl.run(a, x, 10, n, lev, d, target=target, stop_on_outcome=True,
                                              over_cont=True, kill_pct=kill)
            ref, *_, ref_outcome, ref_day = ref_run(a, x, 10, n, lev, 1, 0.0, 0, 0, cont=a["cont"], kill_pct=kill,
                                                    stop_on_outcome=True, target=target)
            assert (outcome, day) == (ref_outcome, ref_day), (lev, target, kill)
            np.testing.assert_allclose(eq[: len(ref)], ref, atol=1e-9)


def test_ledger_overnight_stop_at_the_daily_floor_is_not_a_kill():
    """A weekday gap through the daily floor fills the stop at that floor (far above the kill floor)."""
    import prop_ledger as pl

    bars = {"O": np.array([100.0, 100.0, 96.5, 96.6]), "H": np.array([100.0, 100.5, 97.0, 97.0]),
            "L": np.array([100.0, 99.1, 96.0, 96.0]), "C": np.array([100.0, 99.2, 96.8, 96.9]),
            "nights": np.ones(4), "rate": np.zeros(4), "cont": np.array([False, False, True, True])}
    d = pl.DESIGNS["D1 bot: max(B,E)-3%, LIFO"]
    eq, cnt, outcome, *_ = pl.run(bars, np.full(4, 2.0), 1, 4, 1.5, d, target=0.10, stop_on_outcome=True,
                                  over_cont=True)
    assert (cnt[0], cnt[2], cnt[9]) == (1, 0, 1)  # one daily stop at the gap, no kill
    assert outcome == 0 and eq[1] > 0.96
