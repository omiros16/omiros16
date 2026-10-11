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
    """Minimal .env loader: KEY=VALUE lines, existing env vars win."""
    path = Path(path)
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def _build(cls, raw: dict | None, where: str):
    raw = raw or {}
    allowed = {f.name for f in fields(cls)}
    unknown = set(raw) - allowed
    if unknown:
        raise ConfigError(f"{where}: unknown key(s) {sorted(unknown)}; allowed: {sorted(allowed)}")
    return cls(**raw)


def load_config(path: str | Path) -> BotConfig:
    path = Path(path)
    if not path.exists():
        raise ConfigError(f"{path} not found (copy config/config.example.yaml to start)")
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    return parse_config(raw)


def parse_config(raw: dict) -> BotConfig:
    account = raw.get("account", {})
    firm_key = account.get("prop_firm", "none")
    if firm_key not in PROP_FIRMS:
        raise ConfigError(f"account.prop_firm must be one of {sorted(PROP_FIRMS)}")
    initial_balance = float(account.get("initial_balance", 100_000))

    try:
        limits = _build(RiskLimits, raw.get("risk"), "risk")
        limits, notes = apply_firm_rules(limits, PROP_FIRMS[firm_key])
    except (TypeError, ValueError) as exc:
        raise ConfigError(f"risk: {exc}") from exc

    instruments: dict[str, Instrument] = {}
    for name, inst in (raw.get("instruments") or {}).items():
        inst = dict(inst)
        costs = _build(CostModel, inst.pop("costs", None), f"instruments.{name}.costs")
        broker_symbol = inst.pop("broker_symbol", name)
        spec = _build(InstrumentSpec, {"symbol": broker_symbol, **inst}, f"instruments.{name}")
        instruments[name] = Instrument(name, broker_symbol, spec, costs)

    slots: list[Slot] = []
    for i, s in enumerate(raw.get("strategies") or []):
        slot = _build(Slot, s, f"strategies[{i}]")
        if slot.symbol not in instruments:
            raise ConfigError(f"strategies[{i}] ({slot.id}): instrument {slot.symbol!r} is not defined")
        slot.timeframe = slot.timeframe.upper()
        if slot.timeframe not in TIMEFRAMES:
            raise ConfigError(f"{slot.id}: timeframe must be one of {TIMEFRAMES}")
        try:
            create_strategy(slot.strategy, slot.params)
        except ValueError as exc:
            raise ConfigError(f"{slot.id}: {exc}") from exc
        slots.append(slot)
    ids = [s.id for s in slots]
    magics = [s.magic for s in slots]
    if len(set(ids)) != len(ids) or len(set(magics)) != len(magics):
        raise ConfigError("strategy ids and magic numbers must be unique")

    broker = {"type": "paper", "deviation_points": 20, "filling": "auto", **(raw.get("broker") or {})}
    if broker["type"] not in ("paper", "mt5"):
        raise ConfigError("broker.type must be 'paper' or 'mt5'")

    return BotConfig(
        prop_firm=firm_key,
        initial_balance=initial_balance,
        currency=account.get("currency", "USD"),
        limits=limits,
        firm_notes=notes,
        instruments=instruments,
        slots=slots,
        walkforward={"in_sample_bars": 1000, "out_sample_bars": 250, "objective": "sharpe", "min_trades": 5,
                     **(raw.get("walkforward") or {})},
        broker=broker,
        mt5=raw.get("mt5") or {},
        runtime=_build(Runtime, raw.get("runtime"), "runtime"),
        alerts=raw.get("alerts") or {},
    )
