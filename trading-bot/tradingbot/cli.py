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
import math
import os
import sys
from dataclasses import replace
from datetime import datetime, timezone
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
    # urllib3 logs request URLs at DEBUG level, and Telegram URLs contain the bot token.
    logging.getLogger("urllib3").setLevel(logging.WARNING)


def _bt_config(cfg: BotConfig, symbol: str) -> BacktestConfig:
    inst = cfg.instruments[symbol]
    return BacktestConfig(spec=inst.spec, costs=inst.costs, limits=cfg.limits, initial_balance=cfg.initial_balance)


def _strategy(name: str, params: dict):
    try:
        return create_strategy(name, params)
    except (TypeError, ValueError) as exc:
        raise ConfigError(str(exc)) from exc


def _json_params(text: str | None) -> dict:
    if not text:
        return {}
    try:
        value = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ConfigError(f"--params is not valid JSON ({exc}). Example: --params '{{\"leverage\": 1.0}}' "
                          "(Windows cmd: --params \"{\\\"leverage\\\": 1.0}\")") from exc
    if not isinstance(value, dict):
        raise ConfigError("--params must be a JSON object, e.g. {\"leverage\": 1.0}")
    return value


def _load_with_warmup(path: str, start: str | None, end: str | None, warmup: int) -> tuple[pd.DataFrame, pd.Timestamp | None]:
    """Bars for a backtest that trades from `start`, keeping `warmup` earlier bars for the indicators."""
    try:
        bars = load_bars_csv(path, None, end)
    except (OSError, ValueError) as exc:
        raise ConfigError(str(exc)) from exc
    if not start:
        return bars, None
    trade_from = pd.Timestamp(start)
    first = int(bars.index.searchsorted(trade_from))
    if first >= len(bars):
        raise ConfigError(f"{path}: no bars after --start {start}")
    if first < warmup:
        print(f"note: only {first} bars before --start; the strategy needs {warmup} to warm up, "
              f"so trading starts at bar {warmup} ({bars.index[min(warmup, len(bars) - 1)].date()})")
    return bars.iloc[max(0, first - warmup):], trade_from


def _clean(value):
    """JSON-safe copy: NaN / infinity become null (strict JSON has no NaN)."""
    if isinstance(value, float) and not math.isfinite(value):
        return None
    if isinstance(value, dict):
        return {str(k): _clean(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [_clean(v) for v in value]
    return value


def _write_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(_clean(data), indent=2, default=str), encoding="utf-8")


def _print_events(events: list[str], limit: int = 30) -> None:
    if not events:
        return
    print(f"\nRisk events ({len(events)}):")
    if len(events) <= limit:
        shown = events
    else:
        head, tail = events[:10], events[-10:]
        kills = [e for e in events[10:-10] if "KILLED" in e]
        shown = head + [f"... {len(events) - 20 - len(kills)} more ..."] + kills + tail
    for e in shown:
        print("  " + e)


def _mc_report(trades, cfg: BotConfig) -> dict:
    mc = monte_carlo(trades, dd_limit_pct=cfg.limits.max_drawdown_pct)
    mode = cfg.limits.drawdown_mode
    if "note" not in mc:
        mc["max_loss_rule"] = (f"{mode}: the firm measures the {cfg.limits.max_drawdown_pct:g}% from "
                               + ("the starting balance" if mode == "static" else "the equity peak"))
    return mc


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
    if args.trials < 1:
        raise ConfigError("--trials must be >= 1")
    strat = _strategy(slot.strategy, {**slot.params, **_json_params(args.params)})
    bars, trade_from = _load_with_warmup(args.data, args.start, args.end, strat.warmup_bars)
    bt = replace(_bt_config(cfg, slot.symbol), trade_from=trade_from)
    res = run_backtest(bars, strat, bt, cfg.instruments[slot.symbol].broker_symbol)
    metrics = res.metrics(n_trials=args.trials)
    print(f"== {slot.id}: {slot.strategy} on {args.data} ==")
    print(f"params: {strat.params}")
    if res.start is not None:
        print(f"metrics from {res.start.date()}: the first {strat.warmup_bars} bars only warm up the indicators")
    print(format_metrics(metrics))
    mc = _mc_report(res.trades, cfg)
    print("\nMonte Carlo (trade resampling, closed trades only):")
    for k, v in mc.items():
        print(f"  {k}: {v}")
    _print_events(res.risk_events)
    out = _report_dir(args.out, slot.id)
    res.trades_frame().to_csv(out / "trades.csv", index=False)
    res.equity.to_csv(out / "equity.csv")
    (out / "risk_events.txt").write_text("\n".join(res.risk_events + ["", "skipped:"] + res.skipped), encoding="utf-8")
    _write_json(out / "metrics.json", {"params": strat.params, "metrics": metrics, "monte_carlo": mc,
                                       "risk_events": res.risk_events})
    _plot(res.tradable_equity(), out / "equity.png", f"{slot.id} equity")
    print(f"\nReport: {out}")
    return 0


def cmd_walkforward(args) -> int:
    cfg = load_config(args.config)
    slot = cfg.slot(args.slot)
    try:
        bars = load_bars_csv(args.data, args.start, args.end)
    except (OSError, ValueError) as exc:
        raise ConfigError(str(exc)) from exc
    wf = cfg.walkforward
    print(f"== Walk-forward {slot.id}: {len(bars)} bars, IS {wf['in_sample_bars']} / OOS {wf['out_sample_bars']}, "
          f"grid {slot.grid or '{}'} ==")
    try:
        res = walk_forward(
            bars, slot.strategy, slot.params, slot.grid, _bt_config(cfg, slot.symbol),
            int(wf["in_sample_bars"]), int(wf["out_sample_bars"]), wf["objective"], int(wf["min_trades"]),
            cfg.instruments[slot.symbol].broker_symbol,
        )
    except ValueError as exc:
        raise ConfigError(str(exc)) from exc
    print(res.folds_frame().to_string(index=False))
    print("\nOut-of-sample result (all folds traded on one account):")
    print(format_metrics(res.metrics))
    mc = _mc_report(res.trades, cfg)
    print("\nMonte Carlo (OOS trades):")
    for k, v in mc.items():
        print(f"  {k}: {v}")
    _print_events(res.risk_events)
    out = _report_dir(args.out, f"{slot.id}-wf")
    res.folds_frame().to_csv(out / "folds.csv", index=False)
    res.equity.to_csv(out / "oos_equity.csv")
    _write_json(out / "metrics.json", {"metrics": res.metrics, "monte_carlo": mc, "risk_events": res.risk_events})
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

        mt5_cfg = load_config(args.config).mt5 if Path(args.config).exists() else {}
        feed = MT5Feed(_mt5_client(mt5_cfg))
        try:
            feed.client.connect()
            df = feed.history(args.symbol, args.timeframe, pd.Timestamp(args.start),
                              pd.Timestamp(args.end) if args.end else None)
        finally:
            feed.client.shutdown()
    if df is None or df.empty:
        print(f"no bars returned for {args.symbol}; {out} was not written", file=sys.stderr)
        return 1
    tmp = out.with_name(out.name + ".tmp")
    save_bars_csv(df, tmp)
    os.replace(tmp, out)
    print(f"{len(df)} closed bars {df.index[0]} -> {df.index[-1]} saved to {out}")
    return 0


def _mt5_client(mt5_cfg: dict):
    from .mt5_client import MT5Client

    login = os.environ.get("MT5_LOGIN")
    try:
        login_id = int(login) if login else None
    except ValueError as exc:
        raise ConfigError(f"MT5_LOGIN must be the account number, got {login!r}") from exc
    return MT5Client(
        login=login_id,
        password=os.environ.get("MT5_PASSWORD"),
        server=os.environ.get("MT5_SERVER"),
        path=mt5_cfg.get("path") or os.environ.get("MT5_PATH"),
    )


def _history_seeds(cfg: BotConfig) -> dict[str, pd.DataFrame]:
    """Older daily bars per broker symbol (instruments.*.history_csv)."""
    seeds = {}
    for name, inst in cfg.instruments.items():
        if inst.history_csv:
            try:
                seeds[inst.broker_symbol] = load_bars_csv(inst.history_csv)
            except (OSError, ValueError) as exc:
                raise ConfigError(f"instruments.{name}.history_csv: {exc}") from exc
    return seeds


def _live_feed(cfg: BotConfig, client):
    from .data.feeds import MT5Feed, SeededFeed

    feed = MT5Feed(client)
    seeds = _history_seeds(cfg)
    return SeededFeed(feed, seeds) if seeds else feed


def cmd_check(args) -> int:
    cfg = load_config(args.config)
    print(f"Config OK. Prop firm preset: {cfg.prop_firm}, account size {cfg.initial_balance:,.2f} {cfg.currency}")
    lim = cfg.limits
    print(f"Risk/trade {lim.risk_per_trade_pct}% | daily stop {lim.daily_loss_limit_pct}% | max DD {lim.max_drawdown_pct}% "
          f"({lim.drawdown_mode}) | weekend close: {lim.close_before_weekend} | max exposure {lim.max_exposure}x")
    for note in cfg.firm_notes:
        print(f"  note: {note}")
    for s in cfg.slots:
        warm = _strategy(s.strategy, s.params).warmup_bars
        print(f"  slot {s.id}: {s.strategy} {s.symbol} {s.timeframe} magic {s.magic} "
              f"{'ON' if s.enabled else 'off'} (needs {warm} closed bars)")
    seeds = _history_seeds(cfg)
    for symbol, df in seeds.items():
        print(f"  history_csv for {symbol}: {len(df)} bars {df.index[0].date()} -> {df.index[-1].date()}")
    telegram = bool(os.environ.get("TELEGRAM_BOT_TOKEN") and os.environ.get("TELEGRAM_CHAT_ID"))
    print(f"Telegram: {'configured' if telegram else 'not configured (alerts only in the log)'}")
    live = cfg.broker["type"] == "mt5"
    print(f"Broker: {cfg.broker['type']}" + (f", orders allowed by config: {cfg.broker['allow_orders']}" if live else
                                             " (simulated orders, live MT5 prices)"))

    from .mt5_client import MT5Error

    client = _mt5_client(cfg.mt5)
    try:
        client.connect()
    except MT5Error as exc:
        print(f"MT5 connection test FAILED: {exc}")
        if not live:
            print("(backtests and dry runs work without MT5; `run` needs it, also in paper mode, for the prices)")
        return 1 if live else 0
    try:
        from .brokers.mt5_broker import MT5Broker

        broker = MT5Broker(client, cfg.broker["deviation_points"], cfg.broker["filling"])
        info = client.mt5.account_info()
        mode = {0: "DEMO", 1: "CONTEST", 2: "REAL"}.get(getattr(info, "trade_mode", -1), "?")
        print(f"MT5 account {info.login} on {info.server} [{mode}] balance {info.balance} {info.currency}")
        if abs(float(info.balance) - cfg.initial_balance) > 0.25 * cfg.initial_balance:
            print(f"  WARNING: balance differs a lot from account.initial_balance {cfg.initial_balance:,.2f}")
        terminal = client.mt5.terminal_info()
        print(f"Algo Trading enabled in terminal: {getattr(terminal, 'trade_allowed', '?')}")
        for name, inst in cfg.instruments.items():
            spec = broker.spec(inst.broker_symbol)
            q = broker.quote(inst.broker_symbol)
            print(f"  {name} -> {inst.broker_symbol}: value/point/lot {spec.value_per_point:g} "
                  f"(config {inst.spec.value_per_point:g}), lots {spec.volume_min}-{spec.volume_max} "
                  f"step {spec.volume_step}, bid {q.bid} ask {q.ask} spread {q.ask - q.bid:.2f}")
        feed = _live_feed(cfg, client)
        ok = True
        for s in (s for s in cfg.slots if s.enabled):
            warm = _strategy(s.strategy, s.params).warmup_bars
            symbol = cfg.instruments[s.symbol].broker_symbol
            have = len(feed.bars(symbol, s.timeframe, warm + 50))
            enough = have >= warm
            ok &= enough
            print(f"  history {s.id}: {have} closed {s.timeframe} bars, needs {warm}: "
                  + ("OK" if enough else f"NOT ENOUGH: set instruments.{s.symbol}.history_csv (README)"))
    finally:
        client.shutdown()
    return 0 if ok else 1


def _build_alerts(cfg: BotConfig):
    from .alerts import Alerts

    if not cfg.alerts.get("telegram", True):
        return Alerts()
    return Alerts(os.environ.get("TELEGRAM_BOT_TOKEN"), os.environ.get("TELEGRAM_CHAT_ID"),
                  prefix=cfg.alerts.get("prefix", "[bot] "))


def _disable_quick_edit() -> None:
    """Windows: a click in a console with QuickEdit on pauses the program at its next print."""
    if os.name != "nt":
        return
    try:
        import ctypes

        kernel32 = ctypes.windll.kernel32
        handle = kernel32.GetStdHandle(-10)  # STD_INPUT_HANDLE
        mode = ctypes.c_uint32()
        if kernel32.GetConsoleMode(handle, ctypes.byref(mode)):
            # Clear ENABLE_QUICK_EDIT_MODE (0x40), keep ENABLE_EXTENDED_FLAGS (0x80) set.
            kernel32.SetConsoleMode(handle, (mode.value & ~0x40) | 0x80)
    except Exception:  # not a console (Task Scheduler without a window): nothing to do
        pass


def cmd_run(args) -> int:
    from .brokers.paper import PaperBroker
    from .runner import BotRunner

    cfg = load_config(args.config)
    _disable_quick_edit()
    setup_logging(cfg.runtime.log_dir, args.verbose)
    alerts = _build_alerts(cfg)
    if cfg.broker["type"] == "mt5" and not (args.allow_orders or cfg.broker["allow_orders"]):
        msg = ("broker.type is mt5 but orders are not confirmed: the bot did NOT start. "
               "Run with --allow-orders or set broker.allow_orders: true in the config.")
        log.error(msg)
        alerts.send("Το bot ΔΕΝ ξεκίνησε: " + msg)
        alerts.close()
        return 2
    client = None
    try:
        client = _mt5_client(cfg.mt5)
        client.connect()
        feed = _live_feed(cfg, client)
        if cfg.broker["type"] == "mt5":
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
        runner = BotRunner(cfg, feed, broker, alerts)
    except Exception as exc:  # report why the bot is not running, then let run_bot.bat retry
        log.exception("startup failed")
        alerts.send(f"Το bot ΔΕΝ ξεκίνησε: {type(exc).__name__}: {exc}")
        alerts.close()
        if client is not None:
            client.shutdown()
        return 2 if isinstance(exc, ConfigError) else 1
    runner.run_forever()
    return 0


def _resolve_data_args(cfg: BotConfig, items: list[str] | None) -> dict[str, str]:
    """--data SYMBOL=path: SYMBOL is a config instrument name or broker symbol (any case)."""
    names = {}
    for name, inst in cfg.instruments.items():
        names[name.lower()] = name
        names[inst.broker_symbol.lower()] = name
    files: dict[str, str] = {}
    for item in items or []:
        if "=" not in item:
            raise ConfigError(f"--data {item!r}: expected SYMBOL=path.csv")
        key, path = item.split("=", 1)
        name = names.get(key.strip().lower())
        if name is None:
            raise ConfigError(f"--data {key!r} is not an instrument of the config ({sorted(cfg.instruments)})")
        files[name] = path
    return files


def _bar_spacing_ok(df: pd.DataFrame, timeframe: str) -> bool:
    minutes = {"M1": 1, "M5": 5, "M15": 15, "M30": 30, "H1": 60, "H4": 240, "D1": 1440, "W1": 10080}[timeframe]
    if len(df) < 3:
        return True
    step = pd.Series(df.index).diff().dropna().median() / pd.Timedelta(minutes=1)
    return 0.5 * minutes <= step <= 2 * minutes


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
    # Every dry run starts from a clean account: an earlier run's kill or open
    # positions must not carry over.
    for leftover in (workdir / "state" / "bot_state.json", workdir / "state" / "RESET_KILL",
                     workdir / "logs" / "trades.csv"):
        leftover.unlink(missing_ok=True)

    files = _resolve_data_args(cfg, args.data)
    enabled = [s for s in cfg.slots if s.enabled]
    data: dict[str, pd.DataFrame] = {}
    timeframes: dict[str, str] = {}
    for slot in enabled:
        inst = cfg.instruments[slot.symbol]
        if timeframes.setdefault(inst.broker_symbol, slot.timeframe) != slot.timeframe:
            raise ConfigError(f"dryrun replays one bar series per symbol: {inst.broker_symbol} has slots on "
                              f"{timeframes[inst.broker_symbol]} and {slot.timeframe}; disable one of them")
        if inst.broker_symbol in data:
            continue
        if slot.symbol in files:
            try:
                data[inst.broker_symbol] = load_bars_csv(files[slot.symbol])
            except (OSError, ValueError) as exc:
                raise ConfigError(str(exc)) from exc
            source = files[slot.symbol]
        else:
            data[inst.broker_symbol] = make_bars(n=args.bars, start_price=18000 if "100" in slot.symbol else 5000,
                                                 seed=sum(map(ord, slot.symbol)))
            source = f"synthetic {args.bars} bars"
        print(f"{slot.symbol} ({inst.broker_symbol}): {source}")
        if not _bar_spacing_ok(data[inst.broker_symbol], slot.timeframe):
            print(f"  WARNING: these bars do not look like {slot.timeframe} bars (slot {slot.id})")
    unused = set(files) - {s.symbol for s in enabled}
    if unused:
        print(f"note: no enabled slot trades {sorted(unused)}; those files are not used")
    costs = {i.broker_symbol: i.costs for i in cfg.instruments.values()}
    warmup = max(_strategy(s.strategy, s.params).warmup_bars for s in enabled) + 1
    if warmup >= min(len(d) for d in data.values()):
        raise ConfigError(f"need more than {warmup} bars for the warm-up")
    feed = ReplayFeed(data, start=warmup, costs=costs)
    broker = PaperBroker(
        {i.broker_symbol: i.spec for i in cfg.instruments.values()},
        costs,
        cfg.initial_balance,
        quote_source=feed.quote,
        currency=cfg.currency,
    )

    # The replay clock: just after the next bar opened (the live bot sees a bar
    # once the next one has started, and trades at that open), or the end of a
    # bar before a weekend (weekend close at that bar's close).
    override: list[datetime] = []

    def clock() -> datetime:
        if override:
            return override[-1]
        t = max(feed.quote(s).time for s in data)
        return (t + pd.Timedelta(minutes=1)).to_pydatetime().replace(tzinfo=timezone.utc)

    def weekend_poll_time() -> datetime | None:
        for df in data.values():
            i = min(feed.cursor, len(df)) - 1
            if i + 1 >= len(df):
                continue
            cur, nxt = df.index[i], df.index[i + 1]
            days = (nxt.normalize() - cur.normalize()).days
            if any((cur.weekday() + k) % 7 == 5 for k in range(1, days + 1)):
                spacing = pd.Series(df.index[max(0, i - 20): i + 1]).diff().median()
                spacing = spacing if spacing == spacing else pd.Timedelta(days=1)
                end = min(cur + spacing, cur.normalize() + pd.Timedelta(days=1)) - pd.Timedelta(minutes=1)
                return end.to_pydatetime().replace(tzinfo=timezone.utc)
        return None

    runner = BotRunner(cfg, feed, broker, Alerts(), clock=clock)
    steps = 0
    while True:
        when = weekend_poll_time() if cfg.limits.close_before_weekend else None
        if when is not None:
            override.append(when)
            feed.at_close = True
            runner.step()
            feed.at_close = False
            override.clear()
            steps += 1
        runner.step()
        steps += 1
        if not feed.advance():
            break
    acct = broker.account()
    print(f"Dry run: {steps} polls over {min(len(d) for d in data.values()) - warmup} bars")
    print(f"Paper balance {acct.balance:.2f}, equity {acct.equity:.2f} (start {cfg.initial_balance:.2f})")
    print(f"Risk state: killed={runner.risk.state.killed} {runner.risk.state.kill_reason}")
    print(f"Journal: {runner.journal_path}")
    return 0


def cmd_reset_kill(args) -> int:
    """Leave a reset request for the bot. The running bot owns its state file and applies it at its next poll."""
    from .runner import RESET_FILE

    cfg = load_config(args.config)
    state_dir = Path(cfg.runtime.state_dir)
    path = state_dir / "bot_state.json"
    if path.exists():
        risk = json.loads(path.read_text(encoding="utf-8")).get("risk", {})
        print(f"current state: killed={risk.get('killed')} ({risk.get('kill_reason') or '-'}), "
              f"initial balance {risk.get('initial_balance')}")
    else:
        print("no state file yet: the request applies when the bot starts")
    state_dir.mkdir(parents=True, exist_ok=True)
    tmp = state_dir / (RESET_FILE + ".tmp")
    tmp.write_text("rebase" if args.rebase else "reset", encoding="utf-8")
    os.replace(tmp, state_dir / RESET_FILE)
    if args.rebase:
        print(f"re-base requested: initial balance {cfg.initial_balance:,.2f} from the config, peak and day "
              "reference from the current equity, account = the logged-in one.")
    else:
        print("kill reset requested. It is refused if equity is still below the max-drawdown floor "
              "(use --rebase for a new or reset account).")
    kill_file = Path(cfg.runtime.kill_switch_file)
    if kill_file.exists():
        print(f"remove {kill_file} too, otherwise the bot stops again at once")
    print("The running bot applies it within one poll (or at its next start) and confirms on Telegram.")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="tradingbot", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("-v", "--verbose", action="store_true")
    sub = p.add_subparsers(dest="command", required=True)
    # -v also works after the command (python -m tradingbot run -v).
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-v", "--verbose", action="store_true", default=argparse.SUPPRESS)

    def with_config(sp):
        sp.add_argument("-c", "--config", default="config/config.yaml")
        return sp

    d = sub.add_parser("demo", help="backtest on synthetic data", parents=[common])
    d.add_argument("--seed", type=int, default=42)
    d.set_defaults(func=cmd_demo)

    for name, func in (("backtest", cmd_backtest), ("walkforward", cmd_walkforward)):
        sp = with_config(sub.add_parser(name, parents=[common]))
        sp.add_argument("--slot", required=True, help="strategy id from the config")
        sp.add_argument("--data", required=True, help="CSV with OHLC bars")
        sp.add_argument("--start", help="first date to trade (earlier bars are kept for the warm-up)"
                        if name == "backtest" else "first date of the data to use")
        sp.add_argument("--end")
        sp.add_argument("--out", default="reports")
        if name == "backtest":
            sp.add_argument("--params", help='JSON overrides, e.g. \'{"leverage": 1.0}\'')
            sp.add_argument("--trials", type=int, default=1,
                            help="how many variants you tried so far (for the Deflated Sharpe)")
        sp.set_defaults(func=func)

    dl = with_config(sub.add_parser("download", parents=[common]))
    dl.add_argument("--source", choices=("mt5", "yahoo"), required=True)
    dl.add_argument("--symbol", required=True, help="broker symbol (mt5) or US100/US500/ticker (yahoo)")
    dl.add_argument("--timeframe", default="D1")
    dl.add_argument("--start", default="2010-01-01")
    dl.add_argument("--end")
    dl.add_argument("--out", required=True)
    dl.set_defaults(func=cmd_download)

    with_config(sub.add_parser("check", parents=[common])).set_defaults(func=cmd_check)

    dr = with_config(sub.add_parser("dryrun", parents=[common]))
    dr.add_argument("--data", action="append", help="SYMBOL=path.csv (default: synthetic bars)")
    dr.add_argument("--bars", type=int, default=1500)
    dr.add_argument("--workdir", default="dryrun")
    dr.set_defaults(func=cmd_dryrun)

    r = with_config(sub.add_parser("run", parents=[common]))
    r.add_argument("--allow-orders", action="store_true",
                   help="confirm real orders when broker.type is mt5 (or set broker.allow_orders: true)")
    r.set_defaults(func=cmd_run)

    rk = with_config(sub.add_parser("reset-kill", parents=[common]))
    rk.add_argument("--rebase", action="store_true",
                    help="new or reset account: take initial balance, peak and account from now on")
    rk.set_defaults(func=cmd_reset_kill)
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
