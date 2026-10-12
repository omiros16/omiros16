import pytest

from conftest import Scripted, make_frame
from tradingbot.backtest import BacktestConfig, run_backtest
from tradingbot.data.synthetic import make_bars
from tradingbot.models import CostModel, InstrumentSpec
from tradingbot.risk import RiskLimits
from tradingbot.strategies import create_strategy

SPEC = InstrumentSpec("TEST", value_per_point=1.0, volume_min=0.01, volume_max=1000, volume_step=0.01)
COSTS = CostModel(spread=1.0, slippage=0.5, commission_per_lot=2.0, swap_long_per_lot=-1.0, swap_short_per_lot=-1.0)


def cfg(**limits) -> BacktestConfig:
    return BacktestConfig(SPEC, COSTS, RiskLimits(**{"risk_per_trade_pct": 1.0, **limits}))


def plan(bars, **steps):
    """steps keyed by bar number: b0=('long', sl, tp)."""
    return Scripted(plan={bars.index[int(k[1:])].isoformat(): v for k, v in steps.items()})


def test_take_profit_with_all_costs():
    bars = make_frame([(100, 101, 99, 100), (100, 105, 99, 104), (104, 121, 103, 118)])
    res = run_backtest(bars, plan(bars, b0=("long", 90, 120)), cfg())
    (t,) = res.trades
    assert t.entry_price == pytest.approx(101.5)  # open + spread + slippage
    # Risk budget 1,000 over the 11.5 points from the fill (spread and slippage already in it)
    # + 4.0 round-trip commission per lot: the same size the live executor computes.
    assert t.volume == pytest.approx(64.51)
    assert t.exit_price == 120 and t.reason == "take profit"
    assert t.gross_pnl == pytest.approx((120 - 101.5) * 64.51)
    assert t.commission == pytest.approx(2 * 2.0 * 64.51)
    assert t.swap == pytest.approx(-64.51)  # one night
    assert res.equity.iloc[-1] == pytest.approx(100_000 + t.net_pnl)


def test_stop_wins_when_both_levels_inside_one_bar():
    bars = make_frame([(100, 101, 99, 100), (100, 102, 99, 101), (101, 130, 80, 110)])
    (t,) = run_backtest(bars, plan(bars, b0=("long", 90, 120)), cfg()).trades
    assert t.reason == "stop loss"
    assert t.exit_price == pytest.approx(89.5)


def test_gap_through_stop_fills_at_open():
    bars = make_frame([(100, 101, 99, 100), (100, 102, 99, 101), (80, 82, 78, 81)])
    (t,) = run_backtest(bars, plan(bars, b0=("long", 90, 120)), cfg()).trades
    assert t.reason == "stop loss (gap)"
    assert t.exit_price == pytest.approx(79.5)


def test_short_trade_pays_spread_on_exit():
    bars = make_frame([(100, 101, 99, 100), (100, 101, 97, 98), (98, 99, 79, 80)])
    (t,) = run_backtest(bars, plan(bars, b0=("short", 110, 85)), cfg()).trades
    assert t.entry_price == pytest.approx(99.5)  # bid - slippage
    assert t.exit_price == 85  # ask (low + spread = 80) reached the target
    assert t.reason == "take profit"


def test_equity_floor_acts_as_hard_stop():
    bars = make_frame([(100, 101, 99, 100), (100, 105, 99, 100), (100, 101, 50, 55), (55, 56, 54, 55)])
    strat = plan(bars, b0=("long", 90, 200), b1=("modify", 10, 200))
    res = run_backtest(bars, strat, cfg(daily_loss_limit_pct=3.0))
    (t,) = res.trades
    assert t.reason == "daily loss limit"
    lots = t.volume
    balance_before = 100_000 - 2.0 * lots  # entry commission; the swap stays on the position (as on MT5)
    swap = -1.0 * lots  # one night
    floor = balance_before - 3_000
    assert t.exit_price == pytest.approx(101.5 + (floor - balance_before - swap) / lots - 0.5)
    assert t.swap == pytest.approx(swap) and res.equity.iloc[-1] == pytest.approx(100_000 + t.net_pnl)
    assert any("daily loss" in e for e in res.risk_events)


def test_no_new_entries_after_daily_limit_on_same_day():
    bars = make_frame(
        [(100, 101, 99, 100), (100, 101, 99.5, 100), (92, 93, 91, 92), (92, 93, 91, 92), (92, 93, 91, 92)],
        start="2024-01-01 09:00", freq="h",
    )
    # The gap loses more than the 3% daily limit but less than the 8% max drawdown.
    strat = plan(bars, b0=("long", 99, 150), b2=("long", 85, 120))
    res = run_backtest(bars, strat, cfg(risk_per_trade_pct=5.0, daily_loss_limit_pct=3.0,
                                        max_trade_risk_share_of_daily=1.0))
    assert len(res.trades) == 1 and res.trades[0].reason == "stop loss (gap)"
    assert any("daily loss limit" in s for s in res.skipped)
    assert not any("KILLED" in e for e in res.risk_events)


def test_weekend_close():
    # Mon..Fri then next Monday.
    bars = make_frame([(100, 101, 99, 100)] * 5 + [(100, 101, 99, 100)], start="2024-01-01", freq="B")
    res = run_backtest(bars, plan(bars, b1=("long", 90, 150)), cfg(close_before_weekend=True))
    (t,) = res.trades
    assert t.reason == "weekend close" and t.exit_time.weekday() == 4


def test_trade_from_blocks_earlier_entries():
    bars = make_frame([(100, 101, 99, 100)] * 6)
    c = cfg()
    c.trade_from = bars.index[3]
    res = run_backtest(bars, plan(bars, b1=("long", 90, 150), b4=("long", 90, 150)), c)
    assert len(res.trades) == 1 and res.trades[0].entry_time == bars.index[5]


def test_open_without_stop_is_a_bug():
    from tradingbot.models import Signal, SignalKind

    class NoStop(Scripted):
        def on_bar(self, data, i, position):
            return Signal(SignalKind.OPEN) if i == 1 else None

    bars = make_frame([(100, 101, 99, 100)] * 4)
    with pytest.raises(ValueError, match="without side/stop"):
        run_backtest(bars, NoStop(), cfg())


@pytest.mark.parametrize("name,params", [("trend_following", {}), ("quarters_sweep", {"quarter_size": 50})])
def test_backtest_has_no_lookahead(name, params):
    """Trades closed before the cut must be identical with or without later data."""
    bars = make_bars(1200, start_price=4000, seed=21)
    c = BacktestConfig(SPEC, COSTS, RiskLimits(), close_at_end=False)
    full = run_backtest(bars, create_strategy(name, params), c)
    cut = 900
    part = run_backtest(bars.iloc[:cut], create_strategy(name, params), c)
    done = [t for t in full.trades if t.exit_time < bars.index[cut - 1]]
    assert len(done) > 3
    assert [(t.entry_time, t.exit_time, t.volume, round(t.net_pnl, 6)) for t in done] == [
        (t.entry_time, t.exit_time, t.volume, round(t.net_pnl, 6)) for t in part.trades[: len(done)]
    ]
