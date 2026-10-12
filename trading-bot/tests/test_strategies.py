import pandas as pd
import pytest

from tradingbot.data.synthetic import make_bars
from tradingbot.models import Position, Side, SignalKind
from tradingbot.strategies import STRATEGIES, create_strategy

CONFIGS = {
    "mr_ensemble": [{}, {"leverage": 2.0, "regime_max_autocorr": None}],
    "trend_following": [{}, {"allow_short": True, "trend_ma": 100, "lookbacks": [20, 60]}],
    "quarters_sweep": [{"quarter_size": 50}, {"quarter_size": 100, "level_source": "swings", "trend_ma": 0}],
}


def test_every_strategy_is_covered():
    assert set(STRATEGIES) - {"scripted"} <= set(CONFIGS)


@pytest.mark.parametrize("name,params", [(n, p) for n, ps in CONFIGS.items() for p in ps])
def test_prepare_is_causal(name, params):
    """Indicators at row i must not change when later bars are added."""
    bars = make_bars(800, start_price=4000, seed=11)
    strat = create_strategy(name, params)
    full = strat.prepare(bars)
    for cut in (strat.warmup_bars + 5, 500, 799):
        part = strat.prepare(bars.iloc[:cut])
        pd.testing.assert_frame_equal(full.iloc[:cut], part, check_freq=False)


@pytest.mark.parametrize("name,params", [(n, p) for n, ps in CONFIGS.items() for p in ps])
def test_signals_do_not_depend_on_future_bars(name, params):
    bars = make_bars(700, start_price=4000, seed=5)
    strat = create_strategy(name, params)
    full = strat.prepare(bars)
    seen = 0
    for i in range(strat.warmup_bars, 650, 7):
        part = strat.prepare(bars.iloc[: i + 1])
        a, b = strat.on_bar(full, i, None), strat.on_bar(part, i, None)
        assert a == b
        seen += a is not None
    assert seen > 0, "strategy never signalled on test data; adjust the test"


@pytest.mark.parametrize("name", sorted(CONFIGS))
def test_open_signals_have_a_protective_stop(name):
    bars = make_bars(900, start_price=4000, seed=8)
    strat = create_strategy(name, CONFIGS[name][0])
    data = strat.prepare(bars)
    for i in range(strat.warmup_bars, len(data)):
        sig = strat.on_bar(data, i, None)
        if sig is not None and sig.kind is SignalKind.OPEN:
            close = data["close"].iat[i]
            assert sig.stop_loss is not None
            assert (close - sig.stop_loss) * sig.side > 0
            if sig.take_profit is not None:
                assert (sig.take_profit - close) * sig.side > 0


def test_unknown_parameter_is_rejected():
    with pytest.raises(ValueError, match="unknown parameter"):
        create_strategy("trend_following", {"lookback": 10})
    with pytest.raises(ValueError, match="unknown strategy"):
        create_strategy("magic_money")


def test_trend_trailing_stop_only_moves_up():
    bars = make_bars(600, start_price=4000, seed=2)
    strat = create_strategy("trend_following", {"trail_atr": 2.0, "trail_min_step_atr": 0.0})
    data = strat.prepare(bars)
    moved = 0
    for i in range(300, 590):
        pos = Position("X", Side.LONG, 1.0, data["close"].iat[i - 50], data.index[i - 50], stop_loss=0.0)
        sig = strat.on_bar(data, i, pos)
        if sig is None or sig.kind is not SignalKind.MODIFY:
            continue
        moved += 1
        assert 0 < sig.stop_loss < data["close"].iat[i]
        pos.stop_loss = sig.stop_loss
        # With the stop already there, the same bar must not move it again (and never down).
        again = strat.on_bar(data, i, pos)
        assert again is None or again.kind is not SignalKind.MODIFY or again.stop_loss > pos.stop_loss
        lower = Position("X", Side.LONG, 1.0, pos.entry_price, pos.entry_time, stop_loss=sig.stop_loss + 1e6)
        assert strat.on_bar(data, i, lower) is None or strat.on_bar(data, i, lower).kind is not SignalKind.MODIFY
    assert moved > 10


def test_sweep_time_stop():
    bars = make_bars(300, start_price=4000, seed=2)
    strat = create_strategy("quarters_sweep", {"quarter_size": 50, "max_bars": 5})
    data = strat.prepare(bars)
    pos = Position("X", Side.LONG, 1.0, 4000, data.index[200], stop_loss=1.0)
    assert strat.on_bar(data, 203, pos) is None
    assert strat.on_bar(data, 204, pos).kind is SignalKind.CLOSE
