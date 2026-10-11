from pathlib import Path

import numpy as np
import pandas as pd
import pytest

from tradingbot.backtest import BacktestConfig, deflated_sharpe, monte_carlo, run_backtest, walk_forward
from tradingbot.backtest.metrics import compute_metrics, max_drawdown
from tradingbot.config import ConfigError, load_config, parse_config
from tradingbot.data.csv_feed import load_bars_csv, save_bars_csv
from tradingbot.data.synthetic import make_bars
from tradingbot.models import CostModel, InstrumentSpec
from tradingbot.risk import RiskLimits
from tradingbot.strategies import create_strategy

ROOT = Path(__file__).resolve().parents[1]


def test_example_config_loads():
    cfg = load_config(ROOT / "config" / "config.example.yaml")
    assert cfg.prop_firm == "ftmo_swing"
    assert {s.id for s in cfg.slots} == {"us100_trend", "us500_trend", "us100_sweep"}
    assert cfg.instruments["US100"].broker_symbol == "US100.cash"


def test_config_errors_are_specific():
    base = {"account": {"prop_firm": "ftmo_swing"}, "instruments": {"US100": {}}, "strategies": []}
    with pytest.raises(ConfigError, match="below FTMO"):
        parse_config({**base, "risk": {"daily_loss_limit_pct": 6}})
    with pytest.raises(ConfigError, match="unknown key"):
        parse_config({**base, "risk": {"risk_per_trade": 1}})
    with pytest.raises(ConfigError, match="not defined"):
        parse_config({**base, "strategies": [{"id": "a", "symbol": "US500", "timeframe": "D1",
                                              "strategy": "trend_following", "magic": 1}]})
    with pytest.raises(ConfigError, match="unknown parameter"):
        parse_config({**base, "strategies": [{"id": "a", "symbol": "US100", "timeframe": "D1",
                                              "strategy": "trend_following", "magic": 1, "params": {"x": 1}}]})


def test_csv_roundtrip_and_mt5_export(tmp_path):
    bars = make_bars(50)
    save_bars_csv(bars, tmp_path / "a.csv")
    loaded = load_bars_csv(tmp_path / "a.csv")
    np.testing.assert_allclose(loaded["close"].to_numpy(), bars["close"].to_numpy(), atol=1e-4)

    mt5 = tmp_path / "mt5.csv"
    mt5.write_text(
        "<DATE>\t<TIME>\t<OPEN>\t<HIGH>\t<LOW>\t<CLOSE>\t<TICKVOL>\t<VOL>\t<SPREAD>\n"
        "2024.01.02\t00:00:00\t16800.5\t16900.0\t16700.0\t16850.2\t1000\t0\t10\n"
        "2024.01.03\t00:00:00\t16850.2\t16870.0\t16600.0\t16650.0\t1000\t0\t10\n"
    )
    df = load_bars_csv(mt5)
    assert df.index[0] == pd.Timestamp("2024-01-02") and df["close"].iloc[1] == 16650.0


def test_csv_rejects_broken_bars(tmp_path):
    p = tmp_path / "bad.csv"
    p.write_text("time,open,high,low,close\n2024-01-02,10,9,8,9.5\n")
    with pytest.raises(ValueError, match="high/low"):
        load_bars_csv(p)


def test_dsr_penalises_many_trials():
    single = deflated_sharpe(0.1, 1000, 0.0, 3.0)
    many = deflated_sharpe(0.1, 1000, 0.0, 3.0, n_trials=100, sr_variance=0.002)
    assert 0.99 < single <= 1.0
    assert many < single
    assert deflated_sharpe(0.0, 1000, 0.0, 3.0) == pytest.approx(0.5)


def test_metrics_basics():
    idx = pd.date_range("2024-01-01", periods=4, freq="D")
    eq = pd.Series([100_000, 110_000, 99_000, 105_000], index=idx, dtype=float)
    assert max_drawdown(eq) == pytest.approx(-0.1)
    m = compute_metrics(eq, [], 100_000)
    assert m["total_return_pct"] == 5.0
    assert m["worst_day_pct"] == pytest.approx(-11.0)


def test_walk_forward_trades_only_out_of_sample():
    bars = make_bars(1400, start_price=4000, seed=9)
    cfg = BacktestConfig(InstrumentSpec("X"), CostModel(spread=0.5, slippage=0.2), RiskLimits())
    res = walk_forward(bars, "trend_following", {}, {"stop_atr": [2.0, 3.0]}, cfg, 700, 200)
    first_oos = bars.index[700]
    assert res.folds[0].oos_start == first_oos
    assert all(t.entry_time >= first_oos for t in res.trades)
    assert res.equity.index[0] == first_oos and res.n_trials == 2
    assert "dsr" in res.metrics


def test_monte_carlo_shapes():
    bars = make_bars(1500, start_price=4000, seed=4)
    cfg = BacktestConfig(InstrumentSpec("X"), CostModel(), RiskLimits())
    res = run_backtest(bars, create_strategy("quarters_sweep", {"quarter_size": 50}), cfg)
    mc = monte_carlo(res.trades, n_sims=500, dd_limit_pct=8)
    assert mc["max_dd_99_pct"] <= mc["max_dd_95_pct"] <= mc["max_dd_median_pct"] <= 0
    assert "prob_dd_beyond_8pct" in mc
