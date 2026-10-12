import sys
from datetime import timedelta, timezone
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from conftest import make_frame
from tradingbot.backtest import BacktestConfig, run_backtest
from tradingbot.brokers.paper import PaperBroker
from tradingbot.config import parse_config
from tradingbot.data.feeds import ReplayFeed
from tradingbot.models import CostModel, InstrumentSpec, Position, Side, Signal
from tradingbot.risk import RiskLimits, RiskManager, RiskState
from tradingbot.runner import BotRunner
from tradingbot.strategies import create_strategy
from tradingbot.strategies.base import Strategy
from tradingbot.strategies.mr_ensemble import rule_states

RESEARCH = Path(__file__).resolve().parents[1] / "research"


def random_ohlc(n=1500, seed=3):
    rng = np.random.default_rng(seed)
    c = 100 * np.exp(np.cumsum(rng.normal(0.0003, 0.012, n)))
    o = c * np.exp(rng.normal(0, 0.003, n))
    idx = pd.bdate_range("2010-01-04", periods=n)
    df = pd.DataFrame({"open": o, "close": c}, index=idx)
    df["high"] = np.maximum(df.open, df.close) * (1 + rng.uniform(0, 0.01, n))
    df["low"] = np.minimum(df.open, df.close) * (1 - rng.uniform(0, 0.01, n))
    return df[["open", "high", "low", "close"]]


def test_matches_the_research_implementation():
    """The bot must trade exactly what was tested in research/ (regime guard off)."""
    sys.path.insert(0, str(RESEARCH))
    try:
        from hypotheses import Ctx
        from run_holdout import final_positions
    finally:
        sys.path.remove(str(RESEARCH))
    df = random_ohlc()
    ctx = Ctx(df, df.index[0], np.full(len(df), 2.0), np.full(len(df), 15.0), True, True)
    _, research_day = final_positions(ctx, vt=True, mode="A")
    data = create_strategy("mr_ensemble", {"regime_max_autocorr": None}).prepare(df)
    bot = np.concatenate([[0.0], data["target_exposure"].to_numpy()[:-1]])
    np.testing.assert_allclose(bot, research_day, atol=1e-12)


def test_rule_count_and_range():
    states = rule_states(random_ohlc(600))
    assert states.shape[1] == 52
    assert set(np.unique(states.to_numpy())) <= {0.0, 1.0}


def test_leverage_scales_exposure_and_guard_can_switch_off():
    df = random_ohlc(900)
    base = create_strategy("mr_ensemble", {"regime_max_autocorr": None}).prepare(df)["target_exposure"]
    double = create_strategy("mr_ensemble", {"regime_max_autocorr": None, "leverage": 2.0}).prepare(df)["target_exposure"]
    np.testing.assert_allclose(double, 2 * base)
    # A trending (positively autocorrelated) series must trip the guard.
    rng = np.random.default_rng(1)
    r = np.zeros(900)
    for t in range(1, 900):
        r[t] = 0.6 * r[t - 1] + rng.normal(0, 0.01)
    c = 100 * np.exp(np.cumsum(r))
    trend = pd.DataFrame({"open": c, "high": c * 1.005, "low": c * 0.995, "close": c}, index=df.index)
    data = create_strategy("mr_ensemble").prepare(trend)
    assert data["regime_ok"].iloc[-100:].eq(0).all()
    assert data["target_exposure"].iloc[-100:].eq(0).all()


class TargetPlan(Strategy):
    name = "target_plan"
    defaults = {"plan": {}}

    @property
    def warmup_bars(self) -> int:
        return 1

    def prepare(self, bars):
        return bars.copy()

    def on_bar(self, data, i, position):
        x = self.params["plan"].get(data.index[i].isoformat())
        return None if x is None else Signal.target(x, 0.0, "plan")


def test_engine_rebalances_and_books_partial_closes():
    bars = make_frame([(100, 101, 99, 100)] * 2 + [(110, 111, 109, 110)] * 2 + [(120, 121, 119, 120)] * 2)
    plan = {bars.index[0].isoformat(): 1.0, bars.index[2].isoformat(): 0.5, bars.index[4].isoformat(): 0.0}
    cfg = BacktestConfig(InstrumentSpec("X", 1.0, 0.01, 1e6, 0.01), CostModel(spread=0, slippage=0), RiskLimits())
    res = run_backtest(bars, TargetPlan(plan=plan), cfg)
    # 1,000 lots bought at 100 (signal at bar 0, fill at bar 1 open). The bar-2 signal fills at
    # the bar-3 open (110): target 0.5 x 110,000 / 110 = 500 lots, so 500 are sold; the rest at 120.
    first, second = res.trades
    assert first.volume == pytest.approx(500.0) and first.exit_price == 110
    assert second.volume == pytest.approx(500.0) and second.exit_price == 120
    assert first.net_pnl == pytest.approx(500 * 10) and second.net_pnl == pytest.approx(500 * 20)
    total = sum(t.net_pnl for t in res.trades)
    assert res.equity.iloc[-1] == pytest.approx(100_000 + total)
    assert first.volume + second.volume == pytest.approx(1000.0)


def test_engine_close_matches_research_on_random_data():
    """Event-driven engine (next-open fills, lot rounding) vs research lab (mode B)."""
    sys.path.insert(0, str(RESEARCH))
    try:
        from hypotheses import Ctx
        from lab import Costs, simulate
        from run_holdout import final_positions
    finally:
        sys.path.remove(str(RESEARCH))
    df = random_ohlc(1200, seed=11)
    ctx = Ctx(df, df.index[300], np.zeros(len(df)), np.full(len(df), 15.0), True, True, Costs(0.0, 0.0, 0.0))
    night, day = final_positions(ctx, vt=True, mode="B")
    lab_ret, _ = simulate(df, night, day, ctx.rf, ctx.costs, ctx.start, close_exec=False)
    strat = create_strategy("mr_ensemble", {"regime_max_autocorr": None, "min_change": 0.0})
    cfg = BacktestConfig(InstrumentSpec("X", 1.0, 0.0001, 1e9, 0.0001), CostModel(0, 0, 0, 0, 0),
                         RiskLimits(daily_loss_limit_pct=50, max_drawdown_pct=90, risk_per_trade_pct=1))
    # The strategy warms up for 260 bars; compare from the research start on.
    res = run_backtest(df, strat, cfg)
    eng_ret = res.equity.pct_change().loc[lab_ret.index[1:]]
    a, b = eng_ret.fillna(0).to_numpy(), lab_ret.iloc[1:].to_numpy()
    assert np.corrcoef(a, b)[0, 1] > 0.97
    # Same size too, not only the same timing: a 2x or 0.5x sizing error must fail.
    slope = float(np.dot(a, b) / np.dot(b, b))
    assert 0.95 < slope < 1.05


def test_risk_target_volume_caps():
    rm = RiskManager(RiskLimits(max_exposure=2.0), RiskState(initial_balance=100_000))
    spec = InstrumentSpec("US100", 1.0, 0.01, 50.0, 0.01)
    assert rm.target_volume(1.0, 100_000, 25_000, spec) == 4.0
    assert rm.target_volume(5.0, 100_000, 25_000, spec) == 8.0  # capped at 2x
    assert rm.target_volume(2.0, 10_000_000, 25_000, spec) == 800.0  # above volume_max: split into tickets later
    assert rm.target_volume(0.0, 100_000, 25_000, spec) == 0.0
    rm.state.halted_today = True
    assert not rm.can_increase()[0]


def test_paper_partial_close():
    spec = InstrumentSpec("US100.cash", 1.0, 0.01, 100, 0.01)
    costs = CostModel(spread=0.0, slippage=0.0)
    quotes = {"px": 100.0}
    from tradingbot.brokers.base import Quote

    b = PaperBroker({"US100.cash": spec}, {"US100.cash": costs}, 10_000,
                    quote_source=lambda s: Quote(quotes["px"], quotes["px"], pd.Timestamp("2024-01-02")))
    pos = b.open("US100.cash", Side.LONG, 10.0, 50.0, None, 7, "t")
    quotes["px"] = 110.0
    b.close(pos, "trim", volume=4.0)
    (left,) = b.positions()
    assert left.volume == 6.0
    assert b.balance == pytest.approx(10_000 + 40.0)


def test_mt5_partial_close_sends_volume(fake_mt5):
    from tradingbot.brokers.mt5_broker import MT5Broker
    from tradingbot.mt5_client import MT5Client

    client = MT5Client(mt5_module=fake_mt5)
    client.connect()
    broker = MT5Broker(client, retry_delay=0)
    broker.close(Position("US100.cash", Side.LONG, 3.0, 100.0, None, ticket=5, magic=1), "trim", volume=1.25)
    assert fake_mt5.requests[-1]["volume"] == 1.25 and fake_mt5.requests[-1]["position"] == 5


def test_runner_rebalances_to_zero_without_false_alerts(tmp_path):
    from tradingbot.strategies import STRATEGIES

    STRATEGIES["target_plan"] = TargetPlan
    try:
        bars = make_frame([(100, 101, 99, 100)] * 6)
        plan = {bars.index[0].isoformat(): 1.0, bars.index[1].isoformat(): 0.4, bars.index[2].isoformat(): 0.0}
        raw = {
            "account": {"prop_firm": "ftmo_swing", "initial_balance": 100_000},
            "risk": {"max_lots_per_ticket": 400, "max_open_positions": 10},
            "instruments": {"US100": {"broker_symbol": "US100.cash", "volume_max": 1000,
                                      "costs": {"spread": 0.0, "slippage": 0.0}}},
            "strategies": [{"id": "mr", "symbol": "US100", "timeframe": "D1", "strategy": "target_plan",
                            "magic": 9, "params": {"plan": plan}}],
            "runtime": {"state_dir": str(tmp_path / "s"), "log_dir": str(tmp_path / "l"),
                        "kill_switch_file": str(tmp_path / "K")},
        }
        cfg = parse_config(raw)
        feed = ReplayFeed({"US100.cash": bars}, start=1)
        broker = PaperBroker({"US100.cash": cfg.instruments["US100"].spec}, {"US100.cash": cfg.instruments["US100"].costs},
                             100_000, quote_source=feed.quote)
        sent = []

        class Rec:
            def send(self, text, dedupe_seconds=0):
                sent.append(text)

        def clock():
            return (feed.quote("US100.cash").time + timedelta(hours=22)).to_pydatetime().replace(tzinfo=timezone.utc)

        runner = BotRunner(cfg, feed, broker, Rec(), clock=clock)
        runner.step()
        assert sum(p.volume for p in broker.positions()) == pytest.approx(1000.0)
        assert len(broker.positions()) == 3  # split at 400 lots per ticket
        # Every ticket carries the broker-side backup stop, below the price, at the bot's loss floor:
        # 1000 lots x (100 - SL) = equity - floor (3% daily limit of 100k).
        stops = {p.stop_loss for p in broker.positions()}
        assert len(stops) == 1 and 0 < stops.pop() == pytest.approx(100 - 3000 / 1000, abs=0.01)
        feed.advance()
        runner.step()
        assert sum(p.volume for p in broker.positions()) == pytest.approx(400.0)
        # Smaller position, same room to the floor: the stop moves further away.
        assert all(p.stop_loss == pytest.approx(100 - 3000 / 400, abs=0.01) for p in broker.positions())
        feed.advance()
        runner.step()
        assert broker.positions() == []
        feed.advance()
        runner.step()
        assert not any("SL/TP" in m for m in sent)
    finally:
        STRATEGIES.pop("target_plan", None)
