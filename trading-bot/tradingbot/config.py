"""YAML configuration -> typed objects. Secrets come from the environment / .env."""

from __future__ import annotations

import os
from dataclasses import dataclass, field, fields
from pathlib import Path
from typing import Any

import yaml

from .models import CostModel, InstrumentSpec
from .risk import PROP_FIRMS, RiskLimits, apply_firm_rules
from .strategies import create_strategy
from .mt5_client import TIMEFRAMES


class ConfigError(ValueError):
    pass


@dataclass
class Instrument:
    name: str
    broker_symbol: str
    spec: InstrumentSpec
    costs: CostModel
    # Optional CSV with older daily bars of the same market, used when the broker's
    # history is shorter than a strategy's warm-up (see data.feeds.SeededFeed).
    history_csv: str | None = None


@dataclass
class Slot:
    """One strategy running on one instrument and timeframe."""

    id: str
    symbol: str
    timeframe: str
    strategy: str
    magic: int
    enabled: bool = True
    params: dict[str, Any] = field(default_factory=dict)
    grid: dict[str, list[Any]] = field(default_factory=dict)


@dataclass
class Runtime:
    poll_seconds: int = 30
    state_dir: str = "state"
    log_dir: str = "logs"
    kill_switch_file: str = "KILL_SWITCH"
    heartbeat_hour_utc: int = 21


@dataclass
class BotConfig:
    prop_firm: str
    initial_balance: float
    currency: str
    limits: RiskLimits
    firm_notes: list[str]
    instruments: dict[str, Instrument]
    slots: list[Slot]
    walkforward: dict[str, Any]
    broker: dict[str, Any]
    mt5: dict[str, Any]
    runtime: Runtime
    alerts: dict[str, Any]

    def slot(self, slot_id: str) -> Slot:
        for s in self.slots:
            if s.id == slot_id:
                return s
        raise ConfigError(f"no strategy slot {slot_id!r}; have {[s.id for s in self.slots]}")


def load_env_file(path: str | Path = ".env") -> None:
    """Minimal .env loader: KEY=VALUE lines; existing environment variables win.

    Handles a UTF-8 BOM, an optional 'export ' prefix, matching quotes around the
    value, and ' #' comments after unquoted values.
    """
    path = Path(path)
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip()
        if key.startswith("export "):
            key = key[len("export "):].strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        elif " #" in value:
            value = value.split(" #", 1)[0].rstrip()
        if key:
            os.environ.setdefault(key, value)


def _build(cls, raw: dict | None, where: str):
    raw = raw or {}
    if not isinstance(raw, dict):
        raise ConfigError(f"{where}: expected a mapping, got {raw!r}")
    allowed = {f.name for f in fields(cls)}
    unknown = set(raw) - allowed
    if unknown:
        raise ConfigError(f"{where}: unknown key(s) {sorted(unknown)}; allowed: {sorted(allowed)}")
    try:
        return cls(**raw)
    except (TypeError, ValueError) as exc:
        raise ConfigError(f"{where}: {exc}") from exc


def _check_keys(raw: dict, allowed: set[str], where: str) -> None:
    if not isinstance(raw, dict):
        raise ConfigError(f"{where}: expected a mapping, got {raw!r}")
    unknown = set(raw) - allowed
    if unknown:
        raise ConfigError(f"{where}: unknown key(s) {sorted(unknown)}; allowed: {sorted(allowed)}")


def _number(value, where: str, minimum: float | None = None, integer: bool = False) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ConfigError(f"{where} must be a number, got {value!r}")
    if integer and int(value) != value:
        raise ConfigError(f"{where} must be a whole number, got {value!r}")
    if minimum is not None and value < minimum:
        raise ConfigError(f"{where} must be >= {minimum}, got {value!r}")
    return value


TICKETS_PER_EXPOSURE_SLOT = 10
TOP_LEVEL = {"account", "risk", "instruments", "strategies", "walkforward", "broker", "mt5", "runtime", "alerts"}


def load_config(path: str | Path) -> BotConfig:
    path = Path(path)
    if not path.exists():
        raise ConfigError(f"{path} not found (copy config/config.example.yaml to start)")
    try:
        raw = yaml.safe_load(path.read_text(encoding="utf-8-sig")) or {}
    except UnicodeDecodeError as exc:
        raise ConfigError(f"{path} is not UTF-8 text ({exc.reason} at byte {exc.start}): save it as UTF-8") from exc
    except yaml.YAMLError as exc:
        raise ConfigError(f"{path} is not valid YAML: {exc}") from exc
    return parse_config(raw)


def parse_config(raw: dict) -> BotConfig:
    _check_keys(raw, TOP_LEVEL, "config")
    account = raw.get("account") or {}
    _check_keys(account, {"prop_firm", "initial_balance", "currency"}, "account")
    firm_key = account.get("prop_firm", "none")
    if firm_key not in PROP_FIRMS:
        raise ConfigError(f"account.prop_firm must be one of {sorted(PROP_FIRMS)}")
    if "initial_balance" not in account:
        raise ConfigError("account.initial_balance is required: the account size the firm's % limits refer to")
    initial_balance = float(_number(account["initial_balance"], "account.initial_balance", minimum=1))

    try:
        limits = _build(RiskLimits, raw.get("risk"), "risk")
        limits, notes = apply_firm_rules(limits, PROP_FIRMS[firm_key])
    except (TypeError, ValueError) as exc:
        raise ConfigError(f"risk: {exc}") from exc

    instruments: dict[str, Instrument] = {}
    for name, inst in (raw.get("instruments") or {}).items():
        inst = dict(inst or {})
        costs = _build(CostModel, inst.pop("costs", None), f"instruments.{name}.costs")
        broker_symbol = str(inst.pop("broker_symbol", name))
        history_csv = inst.pop("history_csv", None)
        spec = _build(InstrumentSpec, {"symbol": broker_symbol, **inst}, f"instruments.{name}")
        for key in ("value_per_point", "volume_min", "volume_max", "volume_step"):
            _number(getattr(spec, key), f"instruments.{name}.{key}", minimum=1e-12)
        _number(spec.digits, f"instruments.{name}.digits", minimum=0, integer=True)
        if spec.volume_min > spec.volume_max:
            raise ConfigError(f"instruments.{name}: volume_min is above volume_max")
        if limits.max_lots_per_ticket is not None and limits.max_lots_per_ticket < spec.volume_min:
            raise ConfigError(f"risk.max_lots_per_ticket {limits.max_lots_per_ticket} is below "
                              f"instruments.{name}.volume_min {spec.volume_min}")
        instruments[name] = Instrument(name, broker_symbol, spec, costs, history_csv)

    slots: list[Slot] = []
    exposure_slots = 0
    for i, s in enumerate(raw.get("strategies") or []):
        slot = _build(Slot, s, f"strategies[{i}]")
        slot.id = str(slot.id)  # state files key slots by id as text
        # 'params:' with every line commented out is null in YAML: same as no parameters.
        slot.params = {} if slot.params is None else slot.params
        slot.grid = {} if slot.grid is None else slot.grid
        if not isinstance(slot.params, dict):
            raise ConfigError(f"{slot.id}.params must be a mapping (name: value), got {slot.params!r}")
        if not isinstance(slot.grid, dict) or not all(isinstance(v, list) and v for v in slot.grid.values()):
            raise ConfigError(f"{slot.id}.grid must map each parameter to a non-empty list of values")
        if slot.symbol not in instruments:
            raise ConfigError(f"strategies[{i}] ({slot.id}): instrument {slot.symbol!r} is not defined")
        _number(slot.magic, f"{slot.id}.magic", minimum=1, integer=True)
        if not isinstance(slot.enabled, bool):
            raise ConfigError(f"{slot.id}.enabled must be true or false")
        slot.timeframe = str(slot.timeframe).upper()
        if slot.timeframe not in TIMEFRAMES:
            raise ConfigError(f"{slot.id}: timeframe must be one of {TIMEFRAMES}")
        try:
            strategy = create_strategy(slot.strategy, slot.params)
        except (TypeError, ValueError) as exc:
            raise ConfigError(f"{slot.id}: {exc}") from exc
        if slot.enabled and strategy.exposure_based:
            exposure_slots += 1
        slots.append(slot)
    ids = [s.id for s in slots]
    magics = [s.magic for s in slots]
    if len(set(ids)) != len(ids) or len(set(magics)) != len(magics):
        raise ConfigError("strategy ids and magic numbers must be unique")
    # Exposure strategies add a ticket at every increase (MT5 hedging accounts cannot add to
    # one): the research saw up to 9 open per slot. The default of 2 is for one-shot trades.
    wanted = TICKETS_PER_EXPOSURE_SLOT * exposure_slots
    if wanted and limits.max_open_positions < wanted:
        firm_cap = PROP_FIRMS[firm_key].max_open_positions
        if "max_open_positions" not in (raw.get("risk") or {}):
            limits.max_open_positions = min(wanted, firm_cap or wanted)
        else:
            notes.append(f"max_open_positions {limits.max_open_positions} is low for {exposure_slots} exposure-based "
                         f"slot(s): increases beyond it are refused (use {wanted} or more)")

    broker = {"type": "paper", "deviation_points": 20, "filling": "auto", "allow_orders": False,
              **(raw.get("broker") or {})}
    _check_keys(broker, {"type", "deviation_points", "filling", "allow_orders"}, "broker")
    if broker["type"] not in ("paper", "mt5"):
        raise ConfigError("broker.type must be 'paper' or 'mt5'")
    if broker["filling"] not in ("auto", "fok", "ioc", "return"):
        raise ConfigError("broker.filling must be auto, fok, ioc or return (lower case)")
    _number(broker["deviation_points"], "broker.deviation_points", minimum=0, integer=True)
    if not isinstance(broker["allow_orders"], bool):
        raise ConfigError("broker.allow_orders must be true or false")

    mt5 = raw.get("mt5") or {}
    _check_keys(mt5, {"path"}, "mt5")
    alerts = raw.get("alerts") or {}
    _check_keys(alerts, {"telegram", "prefix"}, "alerts")
    walkforward = {"in_sample_bars": 1000, "out_sample_bars": 250, "objective": "sharpe", "min_trades": 5,
                   **(raw.get("walkforward") or {})}
    _check_keys(walkforward, {"in_sample_bars", "out_sample_bars", "objective", "min_trades"}, "walkforward")
    for key in ("in_sample_bars", "out_sample_bars"):
        _number(walkforward[key], f"walkforward.{key}", minimum=1, integer=True)
    runtime = _build(Runtime, raw.get("runtime"), "runtime")
    _number(runtime.poll_seconds, "runtime.poll_seconds", minimum=5, integer=True)
    if _number(runtime.heartbeat_hour_utc, "runtime.heartbeat_hour_utc", minimum=0, integer=True) > 23:
        raise ConfigError("runtime.heartbeat_hour_utc must be 0-23")
    for key in ("state_dir", "log_dir", "kill_switch_file"):
        value = getattr(runtime, key)
        if not isinstance(value, str) or not value.strip():
            raise ConfigError(f"runtime.{key} must be a file or folder name, got {value!r}")

    return BotConfig(
        prop_firm=firm_key,
        initial_balance=initial_balance,
        currency=str(account.get("currency", "USD")),
        limits=limits,
        firm_notes=notes,
        instruments=instruments,
        slots=slots,
        walkforward=walkforward,
        broker=broker,
        mt5=mt5,
        runtime=runtime,
        alerts=alerts,
    )
