"""Command line: python -m tradingbot <command> [options]

  demo         backtest both strategies on synthetic data (sanity check, no data needed)
  backtest     backtest one strategy slot on a CSV file
  walkforward  walk-forward optimisation + Monte Carlo on a CSV file
  download     fetch history to CSV (MT5 on Windows, or Yahoo daily)
  check        validate the config; with broker.type mt5 also test the connection
  dryrun       run the live loop offline on CSV/synthetic bars with the paper broker
  run          run the bot 24/7 (paper or MT5)
  reset-kill   clear the kill state after a kill switch / max drawdown stop
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import sys
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from logging.handlers import RotatingFileHandler
from pathlib import Path

import pandas as pd

from .backtest import BacktestConfig, format_metrics, monte_carlo, run_backtest, walk_forward
from .config import BotConfig, ConfigError, load_config, load_env_file
from .data.csv_feed import load_bars_csv, save_bars_csv
from .data.synthetic import make_bars
from .models import CostModel, InstrumentSpec
from .risk import RiskLimits
from .strategies import create_strategy

log = logging.getLogger("tradingbot")


def setup_logging(log_dir: str | None = None, verbose: bool = False, quiet: bool = False) -> None:
    # Windows consoles default to a legacy code page; Greek alert text must not break logging.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]
    if log_dir:
        Path(log_dir).mkdir(parents=True, exist_ok=True)
        handlers.append(RotatingFileHandler(Path(log_dir) / "bot.log", maxBytes=5_000_000, backupCount=10, encoding="utf-8"))
    logging.basicConfig(
        level=logging.DEBUG if verbose else (logging.WARNING if quiet else logging.INFO),
        format="%(asctime)s %(levelname)-7s %(name)s: %(message)s",
        handlers=handlers,
        force=True,
    )


def _bt_config(cfg: BotConfig, symbol: str) -> BacktestConfig:
    inst = cfg.instruments[symbol]
    return BacktestConfig(spec=inst.spec, costs=inst.costs, limits=cfg.limits, initial_balance=cfg.initial_balance)


def _report_dir(base: str, name: str) -> Path:
    path = Path(base) / f"{name}-{datetime.now():%Y%m%d-%H%M%S}"
    path.mkdir(parents=True, exist_ok=True)
    return path


def _plot(equity: pd.Series, path: Path, title: str) -> None:
    try:
        import matplotlib

        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
    except ImportError:
        return
    fig, ax = plt.subplots(figsize=(10, 4))
    equity.plot(ax=ax, lw=1)
    ax.set_title(title)
    ax.grid(alpha=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=120)
    plt.close(fig)


# Commands -------------------------------------------------------------------------
def cmd_demo(args) -> int:
    print("Synthetic data: a random walk. Results say nothing about real markets;\n"
          "this only shows that the pipeline runs end to end.\n")
    bars = make_bars(n=3000, start_price=18000, seed=args.seed)
    cfg = BacktestConfig(
        spec=InstrumentSpec("US100", value_per_point=1.0),
        costs=CostModel(spread=1.5, slippage=1.0, swap_long_per_lot=-5.0, swap_short_per_lot=-2.0),
        limits=RiskLimits(),
    )
    for name in ("trend_following", "quarters_sweep"):
        res = run_backtest(bars, create_strategy(name), cfg)
        print(f"== {name} ==")
        print(format_metrics(res.metrics()))
        print()
    return 0


def cmd_backtest(args) -> int:
    cfg = load_config(args.config)
    slot = cfg.slot(args.slot)
    bars = load_bars_csv(args.data, args.start, args.end)
    params = {**slot.params, **json.loads(args.params or "{}")}
    strat = create_strategy(slot.strategy, params)
    res = run_backtest(bars, strat, _bt_config(cfg, slot.symbol), cfg.instruments[slot.symbol].broker_symbol)
    metrics = res.metrics(n_trials=args.trials)
    print(f"== {slot.id}: {slot.strategy} on {args.data} ==")
    print(f"params: {strat.params}")
    print(format_metrics(metrics))
    mc = monte_carlo(res.trades, dd_limit_pct=cfg.limits.max_drawdown_pct)
    print("\nMonte Carlo (trade resampling):")
    for k, v in mc.items():
        print(f"  {k}: {v}")
    if res.risk_events:
        print("\nRisk events:")
        for e in res.risk_events[:20]:
            print("  " + e)
    out = _report_dir(args.out, slot.id)
    res.trades_frame().to_csv(out / "trades.csv", index=False)
    res.equity.to_csv(out / "equity.csv")
    (out / "metrics.json").write_text(json.dumps({"params": strat.params, "metrics": metrics, "monte_carlo": mc},
                                                 indent=2, default=str))
    _plot(res.equity, out / "equity.png", f"{slot.id} equity")
    print(f"\nReport: {out}")
    return 0


def cmd_walkforward(args) -> int:
    cfg = load_config(args.config)
    slot = cfg.slot(args.slot)
    bars = load_bars_csv(args.data, args.start, args.end)
    wf = cfg.walkforward
    print(f"== Walk-forward {slot.id}: {len(bars)} bars, IS {wf['in_sample_bars']} / OOS {wf['out_sample_bars']}, "
          f"grid {slot.grid or '{}'} ==")
    res = walk_forward(
        bars, slot.strategy, slot.params, slot.grid, _bt_config(cfg, slot.symbol),
        int(wf["in_sample_bars"]), int(wf["out_sample_bars"]), wf["objective"], int(wf["min_trades"]),
        cfg.instruments[slot.symbol].broker_symbol,
    )
    print(res.folds_frame().to_string(index=False))
    print("\nStitched out-of-sample result:")
    print(format_metrics(res.metrics))
    mc = monte_carlo(res.trades, dd_limit_pct=cfg.limits.max_drawdown_pct)
    print("\nMonte Carlo (OOS trades):")
    for k, v in mc.items():
        print(f"  {k}: {v}")
    out = _report_dir(args.out, f"{slot.id}-wf")
    res.folds_frame().to_csv(out / "folds.csv", index=False)
    res.equity.to_csv(out / "oos_equity.csv")
    (out / "metrics.json").write_text(json.dumps({"metrics": res.metrics, "monte_carlo": mc}, indent=2, default=str))
    _plot(res.equity, out / "oos_equity.png", f"{slot.id} walk-forward OOS equity")
    print(f"\nReport: {out}")
    dsr = res.metrics.get("dsr", float("nan"))
    print("\nVerdict: " + ("passes the DSR > 0.95 bar; next step is paper trading."
                          if dsr == dsr and dsr > 0.95 else
                          "does NOT pass DSR > 0.95. Do not trade it live."))
    return 0


def cmd_download(args) -> int:
    out = Path(args.out)
    if args.source == "yahoo":
        from .data.yahoo import download_daily

        df = download_daily(args.symbol, start=args.start, end=args.end)
    else:
        from .data.feeds import MT5Feed

        feed = MT5Feed(_mt5_client({}))
        start = pd.Timestamp(args.start)
        end = pd.Timestamp(args.end) if args.end else pd.Timestamp.now()
        df = feed.history(args.symbol, args.timeframe, start, end)
        feed.client.shutdown()
    save_bars_csv(df, out)
    print(f"{len(df)} bars {df.index[0]} -> {df.index[-1]} saved to {out}")
    return 0


def _mt5_client(mt5_cfg: dict):
    from .mt5_client import MT5Client

    login = os.environ.get("MT5_LOGIN")
    return MT5Client(
        login=int(login) if login else None,
        password=os.environ.get("MT5_PASSWORD"),
        server=os.environ.get("MT5_SERVER"),
        path=mt5_cfg.get("path") or os.environ.get("MT5_PATH"),
    )


def cmd_check(args) -> int:
    cfg = load_config(args.config)
    print(f"Config OK. Prop firm preset: {cfg.prop_firm}")
    lim = cfg.limits
    print(f"Risk/trade {lim.risk_per_trade_pct}% | daily stop {lim.daily_loss_limit_pct}% | max DD {lim.max_drawdown_pct}% "
          f"({lim.drawdown_mode}) | weekend close: {lim.close_before_weekend}")
    for note in cfg.firm_notes:
        print(f"  note: {note}")
    for s in cfg.slots:
        print(f"  slot {s.id}: {s.strategy} {s.symbol} {s.timeframe} magic {s.magic} {'ON' if s.enabled else 'off'}")
    telegram = bool(os.environ.get("TELEGRAM_BOT_TOKEN") and os.environ.get("TELEGRAM_CHAT_ID"))
    print(f"Telegram: {'configured' if telegram else 'not configured (alerts only in the log)'}")
    if cfg.broker["type"] != "mt5":
        print("broker.type is paper: no MT5 connection test.")
        return 0

    from .brokers.mt5_broker import MT5Broker

    client = _mt5_client(cfg.mt5)
    client.connect()
    broker = MT5Broker(client)
    info = client.mt5.account_info()
    mode = {0: "DEMO", 1: "CONTEST", 2: "REAL"}.get(getattr(info, "trade_mode", -1), "?")
    print(f"MT5 account {info.login} on {info.server} [{mode}] balance {info.balance} {info.currency}")
    terminal = client.mt5.terminal_info()
    print(f"Algo Trading enabled in terminal: {getattr(terminal, 'trade_allowed', '?')}")
    for name, inst in cfg.instruments.items():
        spec = broker.spec(inst.broker_symbol)
        q = broker.quote(inst.broker_symbol)
        print(f"  {name} -> {inst.broker_symbol}: value/point/lot {spec.value_per_point:g} (config {inst.spec.value_per_point:g}), "
              f"lots {spec.volume_min}-{spec.volume_max} step {spec.volume_step}, bid {q.bid} ask {q.ask} spread {q.ask - q.bid:.2f}")
    client.shutdown()
    return 0


def _build_alerts(cfg: BotConfig):
    from .alerts import Alerts

    if not cfg.alerts.get("telegram", True):
        return Alerts()
    return Alerts(os.environ.get("TELEGRAM_BOT_TOKEN"), os.environ.get("TELEGRAM_CHAT_ID"),
                  prefix=cfg.alerts.get("prefix", "[bot] "))


def cmd_run(args) -> int:
    from .brokers.paper import PaperBroker
    from .data.feeds import MT5Feed
    from .runner import BotRunner

    cfg = load_config(args.config)
    setup_logging(cfg.runtime.log_dir, args.verbose)
    client = _mt5_client(cfg.mt5)
    client.connect()
    feed = MT5Feed(client)
    if cfg.broker["type"] == "mt5":
        if not args.allow_orders:
            print("broker.type is mt5: the bot will send real orders to the logged-in account.\n"
                  "Run again with --allow-orders to confirm.")
            return 2
        from .brokers.mt5_broker import MT5Broker

        broker = MT5Broker(client, cfg.broker["deviation_points"], cfg.broker["filling"])
        info = client.mt5.account_info()
        if getattr(info, "trade_mode", None) == 2:
            log.warning("REAL MONEY ACCOUNT %s", info.login)
    else:
        broker = PaperBroker(
            {i.broker_symbol: i.spec for i in cfg.instruments.values()},
            {i.broker_symbol: i.costs for i in cfg.instruments.values()},
            cfg.initial_balance,
            Path(cfg.runtime.state_dir) / "paper_account.json",
            quote_source=feed.quote,
            currency=cfg.currency,
        )
    BotRunner(cfg, feed, broker, _build_alerts(cfg)).run_forever()
    return 0


def cmd_dryrun(args) -> int:
    """Replay bars through the real runner + paper broker, no MT5 needed."""
    from .alerts import Alerts
    from .brokers.paper import PaperBroker
    from .data.feeds import ReplayFeed
    from .runner import BotRunner

    cfg = load_config(args.config)
    workdir = Path(args.workdir)
    cfg.runtime = replace(cfg.runtime, state_dir=str(workdir / "state"), log_dir=str(workdir / "logs"),
                          kill_switch_file=str(workdir / "KILL_SWITCH"))
    setup_logging(None, args.verbose, quiet=True)

    files = dict(item.split("=", 1) for item in args.data or [])
    data: dict[str, pd.DataFrame] = {}
    for slot in cfg.slots:
        if not slot.enabled:
            continue
        inst = cfg.instruments[slot.symbol]
        if inst.broker_symbol in data:
            continue
        if slot.symbol in files:
            data[inst.broker_symbol] = load_bars_csv(files[slot.symbol])
        else:
            data[inst.broker_symbol] = make_bars(n=args.bars, start_price=18000 if "100" in slot.symbol else 5000,
                                                 seed=sum(map(ord, slot.symbol)))
    spreads = {i.broker_symbol: i.costs.spread for i in cfg.instruments.values()}
    warmup = max(create_strategy(s.strategy, s.params).warmup_bars for s in cfg.slots if s.enabled) + 1
    feed = ReplayFeed(data, start=warmup, spread=spreads)
    broker = PaperBroker(
        {i.broker_symbol: i.spec for i in cfg.instruments.values()},
        {i.broker_symbol: i.costs for i in cfg.instruments.values()},
        cfg.initial_balance,
        quote_source=feed.quote,
        currency=cfg.currency,
    )

    def clock() -> datetime:
        t = max(feed.quote(s).time for s in data)
        return (t + timedelta(hours=23, minutes=59)).to_pydatetime().replace(tzinfo=timezone.utc)

    runner = BotRunner(cfg, feed, broker, Alerts(), clock=clock)
    steps = 0
    while True:
        runner.step()
        steps += 1
        if not feed.advance():
            break
    acct = broker.account()
    print(f"Dry run: {steps} polls over {min(len(d) for d in data.values())} bars")
    print(f"Paper balance {acct.balance:.2f}, equity {acct.equity:.2f} (start {cfg.initial_balance:.2f})")
    print(f"Risk state: killed={runner.risk.state.killed} {runner.risk.state.kill_reason}")
    print(f"Journal: {runner.journal_path}")
    return 0


def cmd_reset_kill(args) -> int:
    cfg = load_config(args.config)
    path = Path(cfg.runtime.state_dir) / "bot_state.json"
    if not path.exists():
        print("no state file, nothing to reset")
        return 0
    state = json.loads(path.read_text(encoding="utf-8"))
    print(f"was killed: {state['risk']['killed']} ({state['risk']['kill_reason']})")
    state["risk"]["killed"] = False
    state["risk"]["kill_reason"] = ""
    path.write_text(json.dumps(state, indent=2), encoding="utf-8")
    kill_file = Path(cfg.runtime.kill_switch_file)
    if kill_file.exists():
        print(f"remove {kill_file} too, otherwise the bot stops again on start")
    print("kill state cleared")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="tradingbot", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="command", required=True)

    def with_config(sp):
        sp.add_argument("-c", "--config", default="config/config.yaml")
        return sp

    d = sub.add_parser("demo", help="backtest on synthetic data")
    d.add_argument("--seed", type=int, default=42)
    d.set_defaults(func=cmd_demo)

    for name, func in (("backtest", cmd_backtest), ("walkforward", cmd_walkforward)):
        sp = with_config(sub.add_parser(name))
        sp.add_argument("--slot", required=True, help="strategy id from the config")
        sp.add_argument("--data", required=True, help="CSV with OHLC bars")
        sp.add_argument("--start")
        sp.add_argument("--end")
        sp.add_argument("--out", default="reports")
        if name == "backtest":
            sp.add_argument("--params", help='JSON overrides, e.g. \'{"stop_atr": 2.5}\'')
            sp.add_argument("--trials", type=int, default=1,
                            help="how many variants you tried so far (for the Deflated Sharpe)")
        sp.set_defaults(func=func)

    dl = sub.add_parser("download")
    dl.add_argument("--source", choices=("mt5", "yahoo"), required=True)
    dl.add_argument("--symbol", required=True, help="broker symbol (mt5) or US100/US500/ticker (yahoo)")
    dl.add_argument("--timeframe", default="D1")
    dl.add_argument("--start", default="2010-01-01")
    dl.add_argument("--end")
    dl.add_argument("--out", required=True)
    dl.set_defaults(func=cmd_download)

    with_config(sub.add_parser("check")).set_defaults(func=cmd_check)

    dr = with_config(sub.add_parser("dryrun"))
    dr.add_argument("--data", action="append", help="SYMBOL=path.csv (default: synthetic bars)")
    dr.add_argument("--bars", type=int, default=1500)
    dr.add_argument("--workdir", default="dryrun")
    dr.set_defaults(func=cmd_dryrun)

    r = with_config(sub.add_parser("run"))
    r.add_argument("--allow-orders", action="store_true", help="required when broker.type is mt5")
    r.set_defaults(func=cmd_run)

    with_config(sub.add_parser("reset-kill")).set_defaults(func=cmd_reset_kill)
    return p


def main(argv: list[str] | None = None) -> int:
    load_env_file()
    args = build_parser().parse_args(argv)
    if args.command not in ("run", "dryrun"):
        setup_logging(None, args.verbose, quiet=True)
    try:
        return args.func(args)
    except ConfigError as exc:
        print(f"Config error: {exc}", file=sys.stderr)
        return 2
