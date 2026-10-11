"""Regression tests for bugs found in the code review."""

from __future__ import annotations

import json
import logging
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import numpy as np
import pandas as pd
import pytest

from conftest import make_frame
from tradingbot.alerts import Alerts
from tradingbot.backtest import BacktestConfig, compute_metrics, run_backtest, walk_forward
from tradingbot.backtest.metrics import deflated_sharpe
from tradingbot.brokers.base import Fill, MarketClosedError, UnknownOutcomeError
from tradingbot.brokers.paper import PaperBroker
from tradingbot.cli import main
from tradingbot.config import ConfigError, load_env_file, parse_config
from tradingbot.data.csv_feed import load_bars_csv
from tradingbot.data.feeds import ReplayFeed, SeededFeed, splice_history
from tradingbot.models import CostModel, InstrumentSpec, Side, Signal
from tradingbot.mt5_client import MT5Client, MT5Error
from tradingbot.risk import RiskLimits, RiskManager, RiskState, us_friday_holiday
from tradingbot.runner import RESET_FILE, BotRunner
from tradingbot.strategies import STRATEGIES, create_strategy
from tradingbot.strategies.base import Strategy, bars_held

SYMBOL = "US100.cash"
UTC = timezone.utc


class TargetPlan(Strategy):
    """Exposure targets keyed by bar timestamp (ISO string)."""

    name = "target_plan_fix"
    defaults = {"plan": {}}

    @property
    def warmup_bars(self) -> int:
        return 1

    def prepare(self, bars):
        return bars.copy()

    def on_bar(self, data, i, position):
        x = self.params["plan"].get(data.index[i].isoformat())
        return None if x is None else Signal.target(x, reason="plan")


@pytest.fixture
def target_registered():
    STRATEGIES[TargetPlan.name] = TargetPlan
    yield TargetPlan
    STRATEGIES.pop(TargetPlan.name, None)


def _cfg(tmp_path, plan, strategy="target_plan_fix", risk=None, extra=None, params=None):
    raw = {
        "account": {"prop_firm": "ftmo_swing", "initial_balance": 100_000},
        "risk": risk or {},
        "instruments": {"US100": {"broker_symbol": SYMBOL, "volume_max": 1000,
                                  "costs": {"spread": 0.0, "slippage": 0.0}}},
        "strategies": [{"id": "s1", "symbol": "US100", "timeframe": "D1", "strategy": strategy,
                        "magic": 9, "params": {"plan": plan} if params is None else params}],
        "runtime": {"state_dir": str(tmp_path / "s"), "log_dir": str(tmp_path / "l"),
                    "kill_switch_file": str(tmp_path / "K")},
        **(extra or {}),
    }
    return parse_config(raw)


class Recorder:
    def __init__(self):
        self.sent = []

    def send(self, text, dedupe_seconds=0):
        self.sent.append(text)


class Flaky(PaperBroker):
    """Paper broker whose next `fail` order calls raise the given errors."""

    def __init__(self, *a, **kw):
        super().__init__(*a, **kw)
        self.fail: list[Exception] = []

    def open(self, *a, **kw):
        if self.fail:
            raise self.fail.pop(0)
        return super().open(*a, **kw)


# Runner -------------------------------------------------------------------------
def test_market_closed_decision_is_retried_not_lost(tmp_path, target_registered):
    bars = make_frame([(100, 101, 99, 100)] * 5)
    cfg = _cfg(tmp_path, {bars.index[0].isoformat(): 1.0})
    feed = ReplayFeed({SYMBOL: bars}, start=1)
    broker = Flaky({SYMBOL: cfg.instruments["US100"].spec}, {SYMBOL: cfg.instruments["US100"].costs},
                   100_000, quote_source=feed.quote)
    broker.fail = [MarketClosedError("10018 market closed"), UnknownOutcomeError("timeout")]
    now = [datetime(2024, 1, 2, 0, 1, tzinfo=UTC)]
    alerts = Recorder()
    runner = BotRunner(cfg, feed, broker, alerts, clock=lambda: now[0])
    runner.step()
    assert broker.positions() == [] and runner.state["pending"]["s1"]["attempts"] == 1
    assert not any("δεν εκτελέστηκε" in m for m in alerts.sent)  # market closed is expected: no alarm
    runner.step()  # retry delay not over: nothing happens
    assert runner.state["pending"]["s1"]["attempts"] == 1
    now[0] += timedelta(minutes=2)
    runner.step()  # second failure (unknown outcome)
    assert runner.state["pending"]["s1"]["attempts"] == 2 and broker.positions() == []
    now[0] += timedelta(minutes=5)
    runner.step()
    assert sum(p.volume for p in broker.positions()) == pytest.approx(1000.0)
    assert "s1" not in runner.state["pending"]


def test_pending_survives_restart(tmp_path, target_registered):
    bars = make_frame([(100, 101, 99, 100)] * 5)
    cfg = _cfg(tmp_path, {bars.index[0].isoformat(): 1.0})
    feed = ReplayFeed({SYMBOL: bars}, start=1)
    broker = Flaky({SYMBOL: cfg.instruments["US100"].spec}, {SYMBOL: cfg.instruments["US100"].costs},
                   100_000, tmp_path / "s" / "paper.json", quote_source=feed.quote)
    broker.fail = [MarketClosedError("closed")]
    now = datetime(2024, 1, 2, 0, 1, tzinfo=UTC)
    BotRunner(cfg, feed, broker, Recorder(), clock=lambda: now).step()
    broker2 = PaperBroker(broker.specs, broker.costs, 100_000, tmp_path / "s" / "paper.json", quote_source=feed.quote)
    later = now + timedelta(minutes=5)
    BotRunner(cfg, feed, broker2, Recorder(), clock=lambda: later).step()
    assert sum(p.volume for p in broker2.positions()) == pytest.approx(1000.0)


def test_weekend_window_allows_sunday_reopen_and_blocks_friday_evening():
    rm = RiskManager(RiskLimits(close_before_weekend=True), RiskState(initial_balance=100_000))
    sunday_open = datetime(2024, 1, 7, 22, 5, tzinfo=UTC)
    assert rm.can_open(0, sunday_open)[0] and rm.can_increase(sunday_open)[0]
    assert not rm.must_close_for_weekend(sunday_open)
    friday = datetime(2024, 1, 5, 17, 0, tzinfo=UTC)
    assert not rm.can_increase(friday)[0] and rm.must_close_for_weekend(friday)
    assert rm.can_increase(datetime(2024, 1, 5, 15, 0, tzinfo=UTC))[0]


def test_holiday_friday_moves_the_weekend_close_to_thursday():
    assert us_friday_holiday(pd.Timestamp("2024-03-29").date())  # Good Friday
    assert us_friday_holiday(pd.Timestamp("2026-07-03").date())  # July 4th on a Saturday
    assert not us_friday_holiday(pd.Timestamp("2024-03-22").date())
    rm = RiskManager(RiskLimits(close_before_weekend=True), RiskState(initial_balance=100_000))
    assert rm.must_close_for_weekend(datetime(2024, 3, 28, 17, 0, tzinfo=UTC))  # Thursday before Good Friday
    assert not rm.must_close_for_weekend(datetime(2024, 3, 21, 17, 0, tzinfo=UTC))


def test_reset_request_file_is_applied_by_the_runner(tmp_path, target_registered):
    bars = make_frame([(100, 101, 99, 100)] * 4)
    cfg = _cfg(tmp_path, {})
    feed = ReplayFeed({SYMBOL: bars}, start=1)
    broker = PaperBroker({SYMBOL: cfg.instruments["US100"].spec}, {SYMBOL: cfg.instruments["US100"].costs},
                         100_000, quote_source=feed.quote)
    alerts = Recorder()
    runner = BotRunner(cfg, feed, broker, alerts, clock=lambda: datetime(2024, 1, 2, 1, tzinfo=UTC))
    runner.step()
    runner.risk.kill("test")
    runner.step()
    (tmp_path / "s" / RESET_FILE).write_text("reset")
    runner.step()
    assert not runner.risk.state.killed and not (tmp_path / "s" / RESET_FILE).exists()
    # Equity below the max-drawdown floor: a plain reset is refused, a rebase is not.
    runner.risk.kill("dd")
    runner.risk.state.initial_balance = 200_000
    (tmp_path / "s" / RESET_FILE).write_text("reset")
    runner.step()
    assert runner.risk.state.killed and any("ΑΠΟΡΡΙΦΘΗΚΕ" in m for m in alerts.sent)
    (tmp_path / "s" / RESET_FILE).write_text("rebase")
    runner.step()
    assert not runner.risk.state.killed and runner.risk.state.initial_balance == 100_000


def test_runner_refuses_to_trade_another_account(tmp_path, target_registered):
    bars = make_frame([(100, 101, 99, 100)] * 4)
    cfg = _cfg(tmp_path, {bars.index[0].isoformat(): 1.0})
    feed = ReplayFeed({SYMBOL: bars}, start=1)
    broker = PaperBroker({SYMBOL: cfg.instruments["US100"].spec}, {SYMBOL: cfg.instruments["US100"].costs},
                         100_000, quote_source=feed.quote)
    (tmp_path / "s").mkdir()
    (tmp_path / "s" / "bot_state.json").write_text(json.dumps({
        "risk": {"initial_balance": 100_000}, "account": 111}), encoding="utf-8")
    broker.account = lambda: SimpleNamespace(balance=100_000.0, equity=100_000.0, login=222)
    alerts = Recorder()
    runner = BotRunner(cfg, feed, broker, alerts, clock=lambda: datetime(2024, 1, 2, 1, tzinfo=UTC))
    runner.step()
    assert broker.positions() == [] and any("ΣΤΟΠ" in m for m in alerts.sent)


def test_history_shortage_is_alerted(tmp_path):
    bars = make_frame([(100, 101, 99, 100)] * 30)
    cfg = _cfg(tmp_path, {}, strategy="mr_ensemble", params={})
    feed = ReplayFeed({SYMBOL: bars}, start=30)
    broker = PaperBroker({SYMBOL: cfg.instruments["US100"].spec}, {SYMBOL: cfg.instruments["US100"].costs},
                         100_000, quote_source=feed.quote)
    alerts = Recorder()
    runner = BotRunner(cfg, feed, broker, alerts, clock=lambda: datetime(2024, 2, 1, 1, tzinfo=UTC))
    runner.step()
    assert any("ΔΕΝ ΚΑΝΩ ΣΥΝΑΛΛΑΓΕΣ" in m for m in alerts.sent)
    assert runner.state["history"]["s1"]["have"] == 30


def test_alert_errors_never_leak_the_token(caplog):
    alerts = Alerts("123:SECRET", "42")
    import requests

    def boom(*a, **kw):
        raise requests.ConnectionError("HTTPSConnectionPool url=/bot123:SECRET/sendMessage")

    original = requests.post
    requests.post = boom
    try:
        with caplog.at_level(logging.WARNING):
            alerts._post("hello")
    finally:
        requests.post = original
    assert "SECRET" not in caplog.text and "<token>" in caplog.text


# Brokers / MT5 ------------------------------------------------------------------
def test_mt5_market_closed_and_unknown_outcome(fake_mt5):
    from tradingbot.brokers.mt5_broker import MT5Broker

    client = MT5Client(login=1, password="x", server="Fake", mt5_module=fake_mt5)
    client.connect()
    broker = MT5Broker(client, retry_delay=0)
    fake_mt5.retcodes = [10018]
    with pytest.raises(MarketClosedError):
        broker.open(SYMBOL, Side.LONG, 1.0, 90.0, None, 7, "x")
    fake_mt5.order_send = lambda request: None
    with pytest.raises(UnknownOutcomeError):
        broker.open(SYMBOL, Side.LONG, 1.0, 90.0, None, 7, "x")


def test_mt5_client_refuses_a_switched_account(fake_mt5):
    client = MT5Client(mt5_module=fake_mt5)
    client.connect()
    assert client.expected_login == 1
    fake_mt5.account_info = lambda: SimpleNamespace(login=2, server="Other", currency="USD")
    with pytest.raises(MT5Error, match="refusing to trade"):
        client.ensure()


def test_paper_close_returns_fill_and_percent_costs():
    spec = InstrumentSpec(SYMBOL, 1.0, 0.01, 100, 0.01)
    costs = CostModel(spread_bp=2.0, slippage_bp=1.0)
    from tradingbot.brokers.base import Quote

    b = PaperBroker({SYMBOL: spec}, {SYMBOL: costs}, 10_000,
                    quote_source=lambda s: Quote(20_000.0, 20_000.0, pd.Timestamp("2024-01-02")))
    pos = b.open(SYMBOL, Side.LONG, 1.0, 19_000, None, 7, "t")
    assert pos.entry_price == pytest.approx(20_002.0)  # ask + 1 bp slippage
    fill = b.close(pos, "x", volume=0.4)
    assert isinstance(fill, Fill) and fill.volume == 0.4


# Costs, backtest, metrics ---------------------------------------------------------
def test_cost_model_percent_forms():
    c = CostModel(spread_bp=1.0, slippage_bp=0.5, swap_long_pct=-7.2)
    assert c.spread_at(20_000) == pytest.approx(2.0) and c.slippage_at(400) == pytest.approx(0.02)
    assert c.swap_per_lot_night(Side.LONG, 20_000, 1.0) == pytest.approx(-20_000 * 0.072 / 360)
    with pytest.raises(ValueError):
        CostModel(spread_bp=-1)


def _bt(**limits):
    return BacktestConfig(InstrumentSpec("X", 1.0, 0.01, 1e6, 0.01), CostModel(spread=0, slippage=0),
                          RiskLimits(**limits))


def test_trade_from_allows_a_fill_exactly_at_trade_from(target_registered):
    bars = make_frame([(100, 101, 99, 100)] * 6)
    strat = create_strategy(TargetPlan.name, {"plan": {bars.index[2].isoformat(): 1.0}})
    cfg = _bt()
    cfg.trade_from = bars.index[3]
    res = run_backtest(bars, strat, cfg)
    assert res.in_position.iloc[3] and res.start == bars.index[2]


def test_pending_increase_refused_when_the_open_gaps_through_the_daily_floor(target_registered):
    rows = [(100, 101, 99, 100), (100, 101, 99, 100), (96, 97, 95, 96), (96, 97, 95, 96)]
    bars = make_frame(rows)
    plan = {bars.index[0].isoformat(): 1.5, bars.index[1].isoformat(): 3.0}
    res = run_backtest(bars, create_strategy(TargetPlan.name, {"plan": plan}),
                       _bt(daily_loss_limit_pct=3.0, max_exposure=3.0))
    # -4% gap at 1.5x = -6% > 3% daily: everything is closed at the open, nothing is added.
    assert len(res.trades) == 1 and "daily loss limit (gap)" in res.trades[0].reason
    assert res.trades[0].volume == pytest.approx(1500.0)


def test_backtest_respects_the_weekend_no_new_trades_window(target_registered):
    bars = make_frame([(100, 101, 99, 100)] * 30, start="2024-01-01", freq="4h")
    friday_eve = bars.index[bars.index == pd.Timestamp("2024-01-05 16:00")][0]
    strat = create_strategy(TargetPlan.name, {"plan": {friday_eve.isoformat(): 1.0}})
    res = run_backtest(bars, strat, _bt(close_before_weekend=True))
    assert res.trades == [] and any("weekend" in s for s in res.skipped)


def test_metrics_start_after_warmup_and_sortino_and_dsr():
    idx = pd.bdate_range("2020-01-01", periods=600)
    rng = np.random.default_rng(1)
    eq = pd.Series(100_000 * np.cumprod(1 + rng.normal(0.0004, 0.01, len(idx))), index=idx)
    m = compute_metrics(eq, [], 100_000)
    rets = eq / eq.shift(1).fillna(100_000) - 1
    dd = np.sqrt((np.minimum(rets, 0) ** 2).mean())
    assert m["sortino"] == pytest.approx(rets.mean() / dd * np.sqrt(252), rel=1e-3)
    m10 = compute_metrics(eq, [], 100_000, n_trials=10)
    assert m10["dsr"] < m10["psr"]
    assert deflated_sharpe(0.05, 500, 0, 3, 1) == deflated_sharpe(0.05, 500, 0, 3, 1, 0.0)
    short = pd.Series([100_000, 101_000, 102_000], index=pd.date_range("2024-01-02 10:00", periods=3, freq="h"))
    assert compute_metrics(short, [], 100_000)["cagr_pct"] != compute_metrics(short, [], 100_000)["cagr_pct"]  # NaN


def test_walk_forward_runs_one_account_and_validates(target_registered):
    bars = make_frame([(100, 101, 99, 100)] * 60)
    with pytest.raises(ValueError):
        walk_forward(bars, "trend_following", {}, None, _bt(), 20, 0)
    res = walk_forward(bars, "trend_following", {"lookbacks": [5]}, None, _bt(), 20, 10, min_trades=1000)
    # No parameter set reaches min_trades: stay flat instead of trading unselected parameters.
    assert all(f.best_params is None for f in res.folds) and res.trades == []
    assert res.equity.iloc[-1] == 100_000


def test_bars_held_does_not_wrap_when_entry_is_older_than_the_window():
    data = make_frame([(100, 101, 99, 100)] * 10)
    assert bars_held(data, 9, data.index[0] - pd.Timedelta(days=30)) >= 10
    assert create_strategy("quarters_sweep", {"trend_ma": 0, "max_bars": 100}).warmup_bars >= 102


def test_strategy_parameter_ranges():
    with pytest.raises(ValueError, match="fraction"):
        create_strategy("mr_ensemble", {"vol_target": 15})
    with pytest.raises(ValueError):
        create_strategy("mr_ensemble", {"regime_window": 1})
    with pytest.raises(ValueError):
        create_strategy("trend_following", {"atr_period": 0})


# Data -------------------------------------------------------------------------------
def test_csv_layouts(tmp_path):
    (tmp_path / "epoch.csv").write_text("time,open,high,low,close\n1704412800,1,2,0.5,1.5\n1704672000,1,2,0.5,1.5\n")
    assert list(load_bars_csv(tmp_path / "epoch.csv").index.day) == [5, 8]
    (tmp_path / "dmy.csv").write_text("Date,Open,High,Low,Close\n02/01/2024,1,2,0.5,1.5\n15/01/2024,1,2,0.5,1.5\n")
    assert list(load_bars_csv(tmp_path / "dmy.csv").index.month) == [1, 1]
    (tmp_path / "amb.csv").write_text("Date,Open,High,Low,Close\n02/01/2024,1,2,0.5,1.5\n03/01/2024,1,2,0.5,1.5\n")
    with pytest.raises(ValueError, match="day/month"):
        load_bars_csv(tmp_path / "amb.csv")
    (tmp_path / "tz.csv").write_text("Date,Open,High,Low,Close\n2024-01-05 00:00+02:00,1,2,0.5,1.5\n"
                                     "2024-07-09 00:00-04:00,1,2,0.5,1.5\n")
    assert list(load_bars_csv(tmp_path / "tz.csv").index.weekday) == [4, 1]  # wall time kept
    (tmp_path / "dup.csv").write_text("Date,Open,High,Low,Close\n2024-01-02,1,2,0.5,1.5\n2024-01-03,1,2,0.5,1.5\n"
                                      "2024-01-03,null,null,null,null\n")
    assert len(load_bars_csv(tmp_path / "dup.csv")) == 2


def test_splice_history_scales_older_bars_to_the_broker_level():
    seed = make_frame([(10, 11, 9, 10)] * 10, start="2024-01-01")
    live = make_frame([(20, 22, 18, 20)] * 5, start="2024-01-08")
    out = splice_history(seed, live)
    assert len(out) == 12 and out["close"].iloc[0] == pytest.approx(20.0)  # 7 older + 5 live
    assert (out.loc[live.index, "close"] == 20).all()

    class Short:
        def bars(self, symbol, timeframe, count):
            return live

        def quote(self, symbol):
            raise AssertionError

    feed = SeededFeed(Short(), {SYMBOL: seed})
    assert len(feed.bars(SYMBOL, "D1", 12)) == 12
    assert len(feed.bars(SYMBOL, "H4", 12)) == 5  # daily seeds only for D1


def test_replay_quote_is_timed_at_the_next_bar():
    bars = make_frame([(100, 101, 99, 100)] * 3)
    feed = ReplayFeed({SYMBOL: bars}, start=1)
    assert feed.quote(SYMBOL).time == bars.index[1]


# Config / CLI -------------------------------------------------------------------------
def test_env_file_bom_export_and_comments(tmp_path, monkeypatch):
    for key in ("FIX_A", "FIX_B", "FIX_C"):
        monkeypatch.delenv(key, raising=False)
    (tmp_path / ".env").write_bytes("﻿FIX_A=1 # comment\nexport FIX_B='x # y'\nFIX_C=\"q\"\n".encode("utf-8"))
    load_env_file(tmp_path / ".env")
    import os

    assert os.environ["FIX_A"] == "1" and os.environ["FIX_B"] == "x # y" and os.environ["FIX_C"] == "q"


def test_reset_kill_cli_writes_a_request(tmp_path, monkeypatch):
    import yaml

    cfg = yaml.safe_load(open("config/config.example.yaml", encoding="utf-8"))
    cfg["runtime"] = {"state_dir": str(tmp_path / "state"), "kill_switch_file": str(tmp_path / "K")}
    path = tmp_path / "c.yaml"
    path.write_text(yaml.safe_dump(cfg), encoding="utf-8")
    assert main(["reset-kill", "-c", str(path), "--rebase"]) == 0
    assert (tmp_path / "state" / RESET_FILE).read_text() == "rebase"


def test_cli_user_errors_are_clean(tmp_path, capsys):
    assert main(["backtest", "-c", "config/config.example.yaml", "--slot", "us100_mr",
                 "--data", "data/US100_D1_NQ_2019_2026.csv", "--params", "{bad json", "--out", str(tmp_path)]) == 2
    assert "not valid JSON" in capsys.readouterr().err
    assert main(["dryrun", "-c", "config/config.example.yaml", "--data", "NOPE=x.csv",
                 "--workdir", str(tmp_path)]) == 2
    assert "not an instrument" in capsys.readouterr().err


def test_parse_config_rejects_bad_types():
    base = {"account": {"prop_firm": "none", "initial_balance": 100_000},
            "instruments": {"US100": {"value_per_point": "1"}}}
    with pytest.raises(ConfigError, match="number"):
        parse_config(base)
