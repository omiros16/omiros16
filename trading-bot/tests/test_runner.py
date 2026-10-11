from datetime import timedelta, timezone

import pytest

from conftest import make_frame
from tradingbot.brokers.paper import PaperBroker
from tradingbot.config import parse_config
from tradingbot.data.feeds import ReplayFeed
from tradingbot.models import Side
from tradingbot.runner import BotRunner

SYMBOL = "US100.cash"


def build(tmp_path, plan_steps, rows, limits=None, start=1):
    bars = make_frame(rows)
    plan = {bars.index[k].isoformat(): v for k, v in plan_steps.items()}
    raw = {
        "account": {"prop_firm": "ftmo_swing", "initial_balance": 100_000},
        "risk": {"risk_per_trade_pct": 1.0, **(limits or {})},
        "instruments": {"US100": {"broker_symbol": SYMBOL, "value_per_point": 1.0,
                                  "costs": {"spread": 1.0, "slippage": 0.0}}},
        "strategies": [{"id": "s1", "symbol": "US100", "timeframe": "D1", "strategy": "scripted",
                        "magic": 11, "params": {"plan": plan}}],
        "runtime": {"state_dir": str(tmp_path / "state"), "log_dir": str(tmp_path / "logs"),
                    "kill_switch_file": str(tmp_path / "KILL_SWITCH")},
    }
    cfg = parse_config(raw)
    feed = ReplayFeed({SYMBOL: bars}, start=start, spread={SYMBOL: 1.0})
    broker = PaperBroker({SYMBOL: cfg.instruments["US100"].spec}, {SYMBOL: cfg.instruments["US100"].costs},
                         100_000, tmp_path / "state" / "paper.json", quote_source=feed.quote)

    def clock():
        return (feed.quote(SYMBOL).time + timedelta(hours=22)).to_pydatetime().replace(tzinfo=timezone.utc)

    return cfg, feed, broker, clock


FLAT = [(100, 101, 99, 100)] * 8


def test_opens_and_closes_on_plan(tmp_path, scripted_registered):
    cfg, feed, broker, clock = build(tmp_path, {0: ("long", 90, 130), 2: ("close",)}, FLAT)
    runner = BotRunner(cfg, feed, broker, clock=clock)
    runner.step()
    (pos,) = broker.positions()
    assert pos.side is Side.LONG and pos.stop_loss == 90 and pos.magic == 11
    assert pos.volume == pytest.approx(1000 / 11, abs=0.01)  # 10 points + 1 slippage buffer
    feed.advance()
    runner.step()
    assert broker.positions()
    feed.advance()
    runner.step()
    assert not broker.positions()
    journal = (tmp_path / "logs" / "trades.csv").read_text()
    assert "s1" in journal and "plan" in journal


def test_same_bar_is_not_acted_on_twice_after_restart(tmp_path, scripted_registered):
    cfg, feed, broker, clock = build(tmp_path, {0: ("long", 90, 130)}, FLAT)
    BotRunner(cfg, feed, broker, clock=clock).step()
    assert len(broker.positions()) == 1
    broker2 = PaperBroker(broker.specs, broker.costs, 100_000, tmp_path / "state" / "paper.json",
                          quote_source=feed.quote)
    BotRunner(cfg, feed, broker2, clock=clock).step()
    assert len(broker2.positions()) == 1  # restored, not doubled


def test_kill_switch_flattens_and_blocks(tmp_path, scripted_registered):
    cfg, feed, broker, clock = build(tmp_path, {0: ("long", 90, 130), 2: ("long", 90, 130)}, FLAT)
    runner = BotRunner(cfg, feed, broker, clock=clock)
    runner.step()
    assert broker.positions()
    (tmp_path / "KILL_SWITCH").write_text("stop")
    feed.advance()
    runner.step()
    assert not broker.positions() and runner.risk.state.killed
    feed.advance()
    runner.step()
    assert not broker.positions()


def test_stop_hit_in_paper_is_reported(tmp_path, scripted_registered):
    rows = [(100, 101, 99, 100), (100, 101, 99.5, 100), (95, 96, 85, 88), (88, 89, 87, 88)]
    cfg, feed, broker, clock = build(tmp_path, {0: ("long", 90, 130)}, rows)
    runner = BotRunner(cfg, feed, broker, clock=clock)
    runner.step()
    feed.advance()
    runner.step()
    feed.advance()
    runner.step()
    assert not broker.positions()
    assert "stop loss" in (tmp_path / "logs" / "trades.csv").read_text()
    assert broker.account().balance == pytest.approx(100_000 - 90.9 * 11, abs=1.0)


def test_daily_loss_closes_positions(tmp_path, scripted_registered):
    # The stop is moved far away, then a crash takes equity through the 2% daily limit.
    rows = [(100, 101, 99, 100), (100, 101, 99.5, 100), (45, 46, 44, 45), (45, 46, 44, 45)]
    cfg, feed, broker, clock = build(
        tmp_path, {0: ("long", 50, 200), 1: ("modify", 10, 200)}, rows,
        limits={"risk_per_trade_pct": 2.0, "daily_loss_limit_pct": 2.0, "max_trade_risk_share_of_daily": 1.0},
    )
    runner = BotRunner(cfg, feed, broker, clock=clock)
    runner.step()
    feed.advance()
    runner.step()
    assert broker.positions()[0].stop_loss == 10
    feed.advance()
    runner.step()
    assert not broker.positions()
    assert runner.risk.state.halted_today
    assert "risk limit" in (tmp_path / "logs" / "trades.csv").read_text()
