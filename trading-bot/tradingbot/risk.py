"""Risk management: position sizing, loss limits, kill switch, prop firm rules.

The same RiskManager runs in the backtest and in the live bot, so a backtest
shows what the limits would have done.

Loss limits follow the prop firm convention: they are a percentage of the
*initial* balance. The daily limit is measured from the higher of balance and
equity at the start of the day (the stricter reading). Set the internal limits
below the firm's: the bot stops itself before the firm would.
"""

from __future__ import annotations

import math
from dataclasses import asdict, dataclass, field
from datetime import datetime, time
from zoneinfo import ZoneInfo

from .models import CostModel, InstrumentSpec, Position

WEEKDAYS = {"MON": 0, "TUE": 1, "WED": 2, "THU": 3, "FRI": 4, "SAT": 5, "SUN": 6}


@dataclass(frozen=True)
class FirmRules:
    """Hard limits of a prop firm account type. None = no such rule / unknown.

    Values as of 11/10/2026. Confirm them on the firm's site and in your
    account dashboard before going live.
    """

    name: str
    daily_loss_pct: float | None = None
    max_loss_pct: float | None = None
    max_requests_per_day: int | None = None
    max_positions_per_day: int | None = None
    max_open_positions: int | None = None
    max_lots_per_ticket: float | None = None
    weekend_holding: bool = True
    notes: str = ""


PROP_FIRMS: dict[str, FirmRules] = {
    "none": FirmRules("No prop firm"),
    "ftmo_swing": FirmRules(
        "FTMO Swing",
        daily_loss_pct=5.0,
        max_loss_pct=10.0,
        max_positions_per_day=2000,
        max_open_positions=200,
        weekend_holding=True,
        notes="Own bot only, no third-party EA. Avoid hyperactivity.",
    ),
    "ftmo_standard": FirmRules(
        "FTMO Standard",
        daily_loss_pct=5.0,
        max_loss_pct=10.0,
        max_positions_per_day=2000,
        max_open_positions=200,
        weekend_holding=False,
        notes="Funded Standard accounts must be flat over the weekend.",
    ),
    "e8_one": FirmRules(
        "E8 One",
        max_requests_per_day=2000,
        max_open_positions=100,
        max_lots_per_ticket=50.0,
        weekend_holding=True,
        notes="Set daily/max drawdown from your dashboard. SL/TP changes count as requests. "
        "At most 50% of trades under 1 minute. Never risk the whole daily drawdown on one trade.",
    ),
    "e8_signature": FirmRules(
        "E8 Signature",
        max_requests_per_day=2000,
        max_open_positions=100,
        max_lots_per_ticket=50.0,
        weekend_holding=False,
        notes="Positions are closed at 23:00 before the weekend. Set drawdown limits from your dashboard.",
    ),
}


@dataclass
class RiskLimits:
    risk_per_trade_pct: float = 0.5
    daily_loss_limit_pct: float = 3.0
    max_drawdown_pct: float = 8.0
    drawdown_mode: str = "static"  # static | trailing
    max_trade_risk_share_of_daily: float = 0.5
    max_open_positions: int = 2
    max_trades_per_day: int = 4
    max_requests_per_day: int = 200
    max_lots_per_ticket: float | None = None
    max_exposure: float = 3.0  # notional / equity cap for exposure-based strategies
    close_before_weekend: bool = False
    weekend_close_utc: str = "FRI 20:00"
    no_new_trades_after_utc: str = "FRI 18:00"
    day_reset_timezone: str = "Europe/Prague"

    def validate(self) -> None:
        if not 0 < self.risk_per_trade_pct <= 5:
            raise ValueError("risk_per_trade_pct must be in (0, 5]")
        if self.daily_loss_limit_pct <= 0 or self.max_drawdown_pct <= 0:
            raise ValueError("loss limits must be > 0")
        if self.drawdown_mode not in ("static", "trailing"):
            raise ValueError("drawdown_mode must be 'static' or 'trailing'")
        if not 0 < self.max_trade_risk_share_of_daily <= 1:
            raise ValueError("max_trade_risk_share_of_daily must be in (0, 1]")
        if not 0 < self.max_exposure <= 20:
            raise ValueError("max_exposure must be in (0, 20]")
        parse_weekly_time(self.weekend_close_utc)
        parse_weekly_time(self.no_new_trades_after_utc)
        ZoneInfo(self.day_reset_timezone)


def apply_firm_rules(limits: RiskLimits, firm: FirmRules) -> tuple[RiskLimits, list[str]]:
    """Tighten internal limits to the firm's hard limits.

    Raises if an internal loss limit is looser than the firm's: that is a
    config mistake, not something to fix silently.
    """
    limits.validate()
    notes: list[str] = []
    if firm.daily_loss_pct is not None and limits.daily_loss_limit_pct >= firm.daily_loss_pct:
        raise ValueError(
            f"daily_loss_limit_pct {limits.daily_loss_limit_pct}% must be below {firm.name}'s {firm.daily_loss_pct}%"
        )
    if firm.max_loss_pct is not None and limits.max_drawdown_pct >= firm.max_loss_pct:
        raise ValueError(f"max_drawdown_pct {limits.max_drawdown_pct}% must be below {firm.name}'s {firm.max_loss_pct}%")
    if firm.daily_loss_pct is None and firm.name != "No prop firm":
        notes.append(f"{firm.name}: drawdown limits are not built in; check your internal limits against your dashboard")

    out = RiskLimits(**asdict(limits))
    if firm.max_requests_per_day is not None and out.max_requests_per_day > firm.max_requests_per_day:
        out.max_requests_per_day = firm.max_requests_per_day
        notes.append(f"max_requests_per_day capped to {firm.max_requests_per_day}")
    if firm.max_positions_per_day is not None and out.max_trades_per_day > firm.max_positions_per_day:
        out.max_trades_per_day = firm.max_positions_per_day
    if firm.max_open_positions is not None and out.max_open_positions > firm.max_open_positions:
        out.max_open_positions = firm.max_open_positions
        notes.append(f"max_open_positions capped to {firm.max_open_positions}")
    if firm.max_lots_per_ticket is not None:
        if out.max_lots_per_ticket is None or out.max_lots_per_ticket > firm.max_lots_per_ticket:
            out.max_lots_per_ticket = firm.max_lots_per_ticket
    if not firm.weekend_holding and not out.close_before_weekend:
        out.close_before_weekend = True
        notes.append(f"{firm.name} does not allow weekend holding: close_before_weekend forced on")
    if firm.notes:
        notes.append(firm.notes)
    return out, notes


def parse_weekly_time(text: str) -> tuple[int, time]:
    """'FRI 20:00' -> (4, time(20, 0))."""
    try:
        day, hhmm = text.strip().upper().split()
        hour, minute = (int(x) for x in hhmm.split(":"))
        return WEEKDAYS[day], time(hour, minute)
    except (KeyError, ValueError):
        raise ValueError(f"expected e.g. 'FRI 20:00', got {text!r}") from None


def day_key(now_utc: datetime, tz: str) -> str:
    return now_utc.astimezone(ZoneInfo(tz)).strftime("%Y-%m-%d")


def _at_or_after(now_utc: datetime, weekly: str) -> bool:
    weekday, t = parse_weekly_time(weekly)
    return now_utc.weekday() == weekday and now_utc.time() >= t


@dataclass
class RiskState:
    """Everything the risk manager must remember across restarts."""

    initial_balance: float
    day: str = ""
    day_start_ref: float = 0.0
    peak_equity: float = 0.0
    trades_today: int = 0
    requests_today: int = 0
    halted_today: bool = False
    killed: bool = False
    kill_reason: str = ""
    events: list[str] = field(default_factory=list)


@dataclass
class SizeResult:
    volume: float
    risk_amount: float
    reason: str = ""


class RiskManager:
    def __init__(self, limits: RiskLimits, state: RiskState) -> None:
        limits.validate()
        self.limits = limits
        self.state = state
        if state.peak_equity <= 0:
            state.peak_equity = state.initial_balance

    # -- limits in money ---------------------------------------------------
    @property
    def daily_limit_amount(self) -> float:
        return self.state.initial_balance * self.limits.daily_loss_limit_pct / 100

    @property
    def max_dd_amount(self) -> float:
        return self.state.initial_balance * self.limits.max_drawdown_pct / 100

    def daily_floor(self) -> float:
        return self.state.day_start_ref - self.daily_limit_amount

    def drawdown_floor(self) -> float:
        if self.limits.drawdown_mode == "trailing":
            # Trails the equity peak, locks at the initial balance.
            return min(self.state.peak_equity - self.max_dd_amount, self.state.initial_balance)
        return self.state.initial_balance - self.max_dd_amount

    def equity_floor(self) -> float:
        return max(self.daily_floor(), self.drawdown_floor())

    # -- state transitions -------------------------------------------------
    def start_day(self, key: str, balance: float, equity: float) -> bool:
        """Call on every tick/bar with the current day key. True on a new day."""
        if key == self.state.day:
            return False
        s = self.state
        s.day = key
        s.day_start_ref = max(balance, equity)
        s.trades_today = 0
        s.requests_today = 0
        s.halted_today = False
        return True

    def check_equity(self, equity: float) -> str | None:
        """Returns 'max_drawdown' or 'daily_loss' when a limit is breached."""
        s = self.state
        s.peak_equity = max(s.peak_equity, equity)
        if s.killed:
            return None
        if equity <= self.drawdown_floor():
            self.kill(f"max drawdown reached: equity {equity:.2f} <= {self.drawdown_floor():.2f}")
            return "max_drawdown"
        if not s.halted_today and equity <= self.daily_floor():
            s.halted_today = True
            s.events.append(f"{s.day}: daily loss limit hit at equity {equity:.2f}")
            return "daily_loss"
        return None

    def kill(self, reason: str) -> None:
        self.state.killed = True
        self.state.kill_reason = reason
        self.state.events.append(f"{self.state.day}: KILLED - {reason}")

    def reset_kill(self) -> None:
        self.state.killed = False
        self.state.kill_reason = ""

    def register_trade(self) -> None:
        self.state.trades_today += 1

    def register_request(self, n: int = 1) -> None:
        self.state.requests_today += n

    def requests_left(self) -> int:
        return self.limits.max_requests_per_day - self.state.requests_today

    # -- decisions ---------------------------------------------------------
    def must_close_for_weekend(self, now_utc: datetime) -> bool:
        return self.limits.close_before_weekend and _at_or_after(now_utc, self.limits.weekend_close_utc)

    def can_open(self, open_positions: int, now_utc: datetime | None = None) -> tuple[bool, str]:
        s, lim = self.state, self.limits
        if s.killed:
            return False, f"killed: {s.kill_reason}"
        if s.halted_today:
            return False, "daily loss limit hit, trading paused until tomorrow"
        if open_positions >= lim.max_open_positions:
            return False, f"max open positions ({lim.max_open_positions})"
        if s.trades_today >= lim.max_trades_per_day:
            return False, f"max trades per day ({lim.max_trades_per_day})"
        # Opening needs one request now and possibly a few later (SL moves, close).
        if self.requests_left() < 3:
            return False, "daily server request budget used up"
        if now_utc is not None and lim.close_before_weekend:
            weekday, _ = parse_weekly_time(lim.no_new_trades_after_utc)
            if _at_or_after(now_utc, lim.no_new_trades_after_utc) or now_utc.weekday() > weekday:
                return False, "too close to the weekend close"
        return True, ""

    def can_increase(self, now_utc: datetime | None = None) -> tuple[bool, str]:
        """Checks for adding exposure (exposure-based strategies). Reducing is always allowed."""
        s, lim = self.state, self.limits
        if s.killed:
            return False, f"killed: {s.kill_reason}"
        if s.halted_today:
            return False, "daily loss limit hit, trading paused until tomorrow"
        if self.requests_left() < 3:
            return False, "daily server request budget used up"
        if now_utc is not None and lim.close_before_weekend:
            weekday, _ = parse_weekly_time(lim.no_new_trades_after_utc)
            if _at_or_after(now_utc, lim.no_new_trades_after_utc) or now_utc.weekday() > weekday:
                return False, "too close to the weekend close"
        return True, ""

    def target_volume(self, exposure: float, equity: float, price: float, spec: InstrumentSpec) -> float:
        """Total lots for a long position worth `exposure` x equity, capped by max_exposure.

        volume_max / max_lots_per_ticket limit single orders, not the position:
        the executor splits larger positions into several tickets.
        """
        if exposure <= 0 or equity <= 0 or price <= 0:
            return 0.0
        exposure = min(exposure, self.limits.max_exposure)
        return _floor_to_step(exposure * equity / (price * spec.value_per_point), spec.volume_step)

    def size(
        self,
        equity: float,
        entry: float,
        stop: float,
        spec: InstrumentSpec,
        costs: CostModel | None = None,
        open_positions: list[tuple[Position, float, float]] | None = None,
    ) -> SizeResult:
        """Lots such that hitting the stop costs at most the allowed risk.

        `open_positions` holds (position, current price, value_per_point) so
        that all stops being hit together still stays above the equity floor.
        """
        lim = self.limits
        distance = abs(entry - stop)
        if distance <= 0:
            return SizeResult(0.0, 0.0, "stop distance is zero")
        if costs is not None:
            distance += costs.spread + costs.slippage
        loss_per_lot = distance * spec.value_per_point
        if costs is not None:
            loss_per_lot += 2 * costs.commission_per_lot  # round trip

        budget = equity * lim.risk_per_trade_pct / 100
        budget = min(budget, lim.max_trade_risk_share_of_daily * self.daily_limit_amount)
        open_loss = sum(p.loss_if_stopped(price, vpp) for p, price, vpp in (open_positions or []))
        headroom = equity - open_loss - self.equity_floor()
        budget = min(budget, headroom)
        if budget <= 0:
            return SizeResult(0.0, 0.0, "no risk headroom left before the loss limits")

        lots = _floor_to_step(budget / loss_per_lot, spec.volume_step)
        cap = spec.volume_max if lim.max_lots_per_ticket is None else min(spec.volume_max, lim.max_lots_per_ticket)
        lots = min(lots, cap)
        if lots < spec.volume_min:
            return SizeResult(0.0, 0.0, f"size {budget / loss_per_lot:.4f} lots is below the broker minimum {spec.volume_min}")
        return SizeResult(lots, lots * loss_per_lot)


def _floor_to_step(value: float, step: float) -> float:
    decimals = max(0, -int(math.floor(math.log10(step)))) if step < 1 else 0
    return round(math.floor(value / step + 1e-9) * step, decimals + 2)
