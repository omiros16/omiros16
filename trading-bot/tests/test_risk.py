from datetime import datetime, timezone

import pytest

from tradingbot.models import CostModel, InstrumentSpec, Position, Side
from tradingbot.risk import PROP_FIRMS, RiskLimits, RiskManager, RiskState, apply_firm_rules, day_key

SPEC = InstrumentSpec("US100", value_per_point=1.0, volume_min=0.01, volume_max=100, volume_step=0.01)


def manager(**kw) -> RiskManager:
    rm = RiskManager(RiskLimits(**kw), RiskState(initial_balance=100_000))
    rm.start_day("2026-10-12", 100_000, 100_000)
    return rm


def test_size_risks_the_configured_share():
    rm = manager(risk_per_trade_pct=0.5)
    res = rm.size(100_000, entry=20_000, stop=19_900, spec=SPEC)
    assert res.volume == 5.0  # 500 / (100 points * 1.0)
    assert res.risk_amount == pytest.approx(500)


def test_size_includes_costs_and_rounds_down():
    rm = manager(risk_per_trade_pct=0.5)
    res = rm.size(100_000, 20_000, 19_900, SPEC, CostModel(spread=2.0, slippage=1.0))
    assert res.volume == 4.85  # 500 / 103 = 4.854 -> 4.85
    assert res.risk_amount <= 500
    with_comm = rm.size(100_000, 20_000, 19_900, SPEC, CostModel(spread=2.0, slippage=1.0, commission_per_lot=3.5))
    assert with_comm.volume == 4.54  # 500 / (103 + 7) = 4.545


def test_size_below_minimum_is_rejected():
    rm = manager(risk_per_trade_pct=0.01)
    res = rm.size(100_000, 20_000, 10_000, SPEC)
    assert res.volume == 0 and "minimum" in res.reason


def test_single_trade_capped_by_share_of_daily_limit():
    rm = manager(risk_per_trade_pct=5.0, daily_loss_limit_pct=2.0, max_trade_risk_share_of_daily=0.5)
    res = rm.size(100_000, 20_000, 19_000, SPEC)
    assert res.risk_amount == pytest.approx(1_000)  # 50% of 2,000


def test_open_positions_reduce_headroom():
    rm = manager(risk_per_trade_pct=1.0, daily_loss_limit_pct=3.0, max_trade_risk_share_of_daily=1.0)
    open_pos = Position("US100", Side.LONG, 25.0, 20_000, None, stop_loss=19_900)  # 2,500 at risk
    res = rm.size(100_000, 5_000, 4_900, SPEC, open_positions=[(open_pos, 20_000, 1.0)])
    assert res.risk_amount == pytest.approx(500)  # only 3,000 - 2,500 left


def test_lots_capped_per_ticket():
    rm = manager(risk_per_trade_pct=5.0, max_lots_per_ticket=2.0, max_trade_risk_share_of_daily=1.0)
    assert rm.size(100_000, 20_000, 19_990, SPEC).volume == 2.0


def test_daily_loss_halts_until_next_day():
    rm = manager(daily_loss_limit_pct=3.0)
    assert rm.check_equity(97_500) is None
    assert rm.check_equity(96_999) == "daily_loss"
    assert rm.can_open(0) == (False, "daily loss limit hit, trading paused until tomorrow")
    assert rm.check_equity(96_000) is None  # reported once
    rm.start_day("2026-10-13", 96_000, 96_000)
    assert rm.can_open(0)[0]
    assert rm.daily_floor() == pytest.approx(93_000)


def test_daily_reference_is_higher_of_balance_and_equity():
    rm = manager()
    rm.start_day("2026-10-13", 100_000, 101_000)
    assert rm.daily_floor() == pytest.approx(98_000)


def test_max_drawdown_kills():
    rm = manager(max_drawdown_pct=8.0, daily_loss_limit_pct=3.0)
    rm.start_day("2026-10-13", 93_000, 93_000)
    assert rm.check_equity(91_999) == "max_drawdown"
    assert rm.state.killed
    rm.start_day("2026-10-14", 92_000, 92_000)
    assert not rm.can_open(0)[0]  # a new day does not revive a killed bot
    rm.reset_kill()
    assert rm.can_open(0)[0]


def test_trailing_drawdown_locks_at_initial_balance():
    rm = manager(max_drawdown_pct=5.0, drawdown_mode="trailing")
    rm.check_equity(103_000)
    assert rm.drawdown_floor() == pytest.approx(98_000)
    rm.check_equity(110_000)
    assert rm.drawdown_floor() == pytest.approx(100_000)


def test_limits_on_trades_and_requests():
    rm = manager(max_trades_per_day=1, max_requests_per_day=10, max_open_positions=1)
    assert rm.can_open(1)[1].startswith("max open positions")
    rm.register_trade()
    assert rm.can_open(0)[1].startswith("max trades per day")
    rm.start_day("2026-10-13", 100_000, 100_000)
    rm.register_request(8)
    assert "request budget" in rm.can_open(0)[1]


def test_weekend_rules():
    rm = manager(close_before_weekend=True, weekend_close_utc="FRI 20:00", no_new_trades_after_utc="FRI 18:00")
    thu = datetime(2026, 10, 15, 21, 0, tzinfo=timezone.utc)
    fri_noon = datetime(2026, 10, 16, 12, 0, tzinfo=timezone.utc)
    fri_late = datetime(2026, 10, 16, 20, 30, tzinfo=timezone.utc)
    assert not rm.must_close_for_weekend(thu)
    assert not rm.must_close_for_weekend(fri_noon)
    assert rm.must_close_for_weekend(fri_late)
    assert rm.can_open(0, fri_noon)[0]
    assert not rm.can_open(0, datetime(2026, 10, 16, 18, 5, tzinfo=timezone.utc))[0]
    swing = manager(close_before_weekend=False)
    assert not swing.must_close_for_weekend(fri_late)


def test_firm_rules_reject_looser_internal_limits():
    with pytest.raises(ValueError, match="below FTMO"):
        apply_firm_rules(RiskLimits(daily_loss_limit_pct=5.0), PROP_FIRMS["ftmo_swing"])
    with pytest.raises(ValueError, match="max_drawdown_pct"):
        apply_firm_rules(RiskLimits(max_drawdown_pct=10.0), PROP_FIRMS["ftmo_standard"])


def test_firm_rules_tighten_limits():
    limits, notes = apply_firm_rules(
        RiskLimits(max_requests_per_day=5000, max_open_positions=500), PROP_FIRMS["e8_signature"]
    )
    assert limits.max_requests_per_day == 2000
    assert limits.max_open_positions == 100
    assert limits.max_lots_per_ticket == 50.0
    assert limits.close_before_weekend is True
    assert any("drawdown" in n for n in notes)


def test_day_key_uses_reset_timezone():
    # 23:30 UTC on 12/10 is already 13/10 in Prague (UTC+2 in October).
    assert day_key(datetime(2026, 10, 12, 23, 30, tzinfo=timezone.utc), "Europe/Prague") == "2026-10-13"
