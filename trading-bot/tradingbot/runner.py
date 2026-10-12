"""The live loop: poll, check risk, decide on new closed bars, execute until done.

Order of checks on every poll:
  1. account checks (wrong account / reset requests), kill switch file
  2. new day                              -> reset daily counters, refresh backup stops
  3. equity vs daily loss / max drawdown  -> flatten (and kill on max drawdown)
  4. weekend close (if required)          -> flatten
  5. per strategy slot: on a new closed bar the strategy DECIDES; the decision is
     stored and EXECUTED, retried with backoff until it succeeds (the D1 bar
     closes at 17:00 New York, when US index CFDs are in their daily break, so
     the first attempt often meets a closed market)
  6. daily heartbeat to Telegram

Decisions are stored separately from executions: a TARGET decision is
idempotent (the executor compares the wanted size with the actual tickets), so
it is safe to retry after any failure. State (risk counters, last decided bar,
pending decisions, known tickets) is saved to JSON after every poll.
"""

from __future__ import annotations

import csv
import json
import logging
import math
import os
import time
from dataclasses import asdict, fields
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Callable

import pandas as pd

from .alerts import Alerts
from .brokers.base import Broker, BrokerError, MarketClosedError, UnknownOutcomeError
from .config import BotConfig, Slot
from .data.feeds import Feed
from .models import CostModel, InstrumentSpec, Position, Side, Signal, SignalKind
from .mt5_client import TIMEFRAMES
from .risk import RiskManager, RiskState, day_key
from .strategies import create_strategy

log = logging.getLogger(__name__)

RETRY_DELAYS = (60, 120, 240, 480, 900)  # seconds between execution attempts
RESET_FILE = "RESET_KILL"


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _aggregate(positions: list[Position]) -> Position | None:
    """One combined view of a slot's tickets (volume-weighted entry, earliest entry time)."""
    if not positions:
        return None
    if len(positions) == 1:
        return positions[0]
    vol = sum(p.volume for p in positions)
    first = min(positions, key=lambda p: p.entry_time)
    entry = sum(p.entry_price * p.volume for p in positions) / vol
    return Position(**{**first.__dict__, "volume": round(vol, 8), "entry_price": entry})


def _encode(sig: Signal) -> dict:
    return {
        "kind": sig.kind.value,
        "side": int(sig.side) if sig.side is not None else None,
        "stop_loss": sig.stop_loss,
        "take_profit": sig.take_profit,
        "reason": sig.reason,
        "exposure": sig.exposure,
        "min_change": sig.min_change,
    }


def _decode(d: dict) -> Signal:
    return Signal(
        SignalKind(d["kind"]),
        Side(d["side"]) if d.get("side") is not None else None,
        d.get("stop_loss"),
        d.get("take_profit"),
        d.get("reason", ""),
        d.get("exposure"),
        d.get("min_change", 0.0),
    )


def _bar_day(bar_time, tz: str) -> str:
    """Trading day of a bar's start. Naive bar times (MT5 server time, CSV dates) are read
    as UTC, which never puts a bar on an earlier day than the real one."""
    ts = pd.Timestamp(bar_time)
    return day_key(ts.tz_localize("UTC") if ts.tzinfo is None else ts, tz)


def _floor_step(x: float, step: float) -> float:
    return round(math.floor(x / step + 1e-9) * step, 8)


class BotRunner:
    def __init__(
        self,
        cfg: BotConfig,
        feed: Feed,
        broker: Broker,
        alerts: Alerts | None = None,
        clock: Callable[[], datetime] = utcnow,
    ) -> None:
        self.cfg = cfg
        self.feed = feed
        self.broker = broker
        self.alerts = alerts or Alerts()
        self.clock = clock
        # With no enabled slot the bot still runs its risk loop (kill switch, loss limits,
        # weekend close) over the positions of the configured slots.
        self.slots = [s for s in cfg.slots if s.enabled]
        self.strategies = {s.id: create_strategy(s.strategy, s.params) for s in self.slots}
        # Risk protection (kill switch, daily loss, max drawdown, weekend) covers the
        # positions of EVERY configured slot, enabled or not: disabling a slot must
        # never leave its open position unprotected.
        self.magics = {s.magic: s for s in cfg.slots}
        self.exposure_magics = {s.magic for s in cfg.slots
                                if create_strategy(s.strategy, s.params).exposure_based}
        # Simulated stops/swaps run once per symbol, on its finest timeframe (enabled slots
        # first; a disabled slot's symbol is processed while it still has positions).
        self.stop_slot: dict[str, Slot] = {}
        for s in sorted(cfg.slots, key=lambda s: (s.enabled, -TIMEFRAMES.index(s.timeframe))):
            self.stop_slot[cfg.instruments[s.symbol].broker_symbol] = s  # the last write wins

        rt = cfg.runtime
        self.state_path = Path(rt.state_dir) / "bot_state.json"
        self.reset_path = Path(rt.state_dir) / RESET_FILE
        self.kill_file = Path(rt.kill_switch_file)
        self.journal_path = Path(rt.log_dir) / "trades.csv"
        self.state = self._load_state()
        self.risk = RiskManager(cfg.limits, self.state["risk"])
        self._started = False
        self._resized = False
        self._bars_cache: dict[tuple[str, str, int], pd.DataFrame] = {}
        self._account_blocked = ""

    # State ---------------------------------------------------------------------
    def _load_state(self) -> dict:
        state = {
            "risk": RiskState(initial_balance=self.cfg.initial_balance),
            "last_bar": {},
            "pending": {},
            "known": {},
            "history": {},
            "guard": {},
            "close_retry_at": "",
            "last_balance": None,
            "heartbeat_day": "",
            "account": None,
            "fresh": True,
        }
        if self.state_path.exists():
            raw = json.loads(self.state_path.read_text(encoding="utf-8"))
            names = {f.name for f in fields(RiskState)}
            raw["risk"] = RiskState(**{k: v for k, v in raw["risk"].items() if k in names})
            raw["known"] = {int(k): v for k, v in raw.get("known", {}).items()}
            state.update(raw)
            state["fresh"] = False
        return state

    def save_state(self) -> None:
        data = {**self.state, "risk": asdict(self.state["risk"])}
        data["risk"]["events"] = data["risk"]["events"][-200:]
        data.pop("fresh", None)
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")
        os.replace(tmp, self.state_path)

    # Main loop -----------------------------------------------------------------
    def run_forever(self) -> None:
        poll = self.cfg.runtime.poll_seconds
        backoff = poll
        self.alerts.send(f"Bot ξεκίνησε: {', '.join(s.id for s in self.slots) or 'κανένα ενεργό slot (μόνο έλεγχος ρίσκου)'}"
                         f" | firm: {self.cfg.prop_firm}")
        try:
            while True:
                try:
                    self.step()
                    backoff = poll
                except Exception as exc:  # keep running; report and back off
                    log.exception("step failed")
                    self.alerts.send(f"Σφάλμα: {type(exc).__name__}: {exc}", dedupe_seconds=1800)
                    backoff = min(backoff * 2, 300)
                time.sleep(backoff)
        except KeyboardInterrupt:
            log.info("stopped by user")
            self.alerts.send("Bot σταμάτησε (χειροκίνητα). Οι ανοιχτές θέσεις έμειναν με τα SL/TP τους.")
        finally:
            self.save_state()
            getattr(self.alerts, "close", lambda: None)()
            self.broker.shutdown()

    def step(self) -> None:
        now = self.clock()
        risk = self.risk
        self._bars_cache = {}
        self._resized = False
        # A simulated broker settles the stops hit during the bars that closed since
        # the last poll first: they belong to the day that just ended, before the
        # day reset below moves the stops.
        if self.broker.simulated:
            self._simulate_fills(now)

        # Any error reading the account (e.g. the MT5 terminal switched to another
        # account) aborts the poll before the risk state is touched.
        account = self.broker.account()
        if not self._started:
            self._startup_checks(account)
            self._started = True
        self._consume_reset_request(account, now)
        if self._account_blocked:
            self.alerts.send(self._account_blocked, dedupe_seconds=3600)
            self.save_state()
            return

        if self.kill_file.exists() and not risk.state.killed:
            risk.kill(f"kill switch file {self.kill_file} found")
            self.alerts.send("KILL SWITCH: κλείνω όλες τις θέσεις και σταματάω τις συναλλαγές.")

        new_day = risk.start_day(day_key(now, self.cfg.limits.day_reset_timezone), account.balance, account.equity,
                                 self._midnight_balance(now, account.balance))
        if new_day:
            log.info("new trading day %s, reference equity %.2f", risk.state.day, risk.state.day_start_ref)
        self.state["last_balance"] = [now.isoformat(), account.balance]

        positions = self._my_positions()
        self._detect_external_closes(positions)
        if not positions:  # a new close episode starts with the shortest retry delay
            self.state["close_attempts"] = 0
            self.state["close_retry_at"] = ""

        event = risk.check_equity(account.equity)
        if event == "max_drawdown":
            self.alerts.send(f"MAX DRAWDOWN: equity {account.equity:.2f}. Κλείνω τα πάντα, το bot σταματάει μέχρι reset.")
        elif event == "daily_loss":
            self.alerts.send(f"Ημερήσιο όριο ζημιάς: equity {account.equity:.2f}. Κλείνω θέσεις, παύση ως αύριο.")

        if positions and (risk.state.killed or risk.state.halted_today):
            self._close_all(positions, "kill" if risk.state.killed else "risk limit", now)
        elif positions and risk.must_close_for_weekend(now):
            self._close_all(positions, "weekend close", now)
        elif new_day and positions:
            self._refresh_stops(positions)

        for slot in self.slots:
            for action in (self._process_slot, self._try_pending):
                try:
                    action(slot, now)
                except Exception as exc:  # one slot's data or broker error must not stop the others
                    log.exception("%s: %s failed", slot.id, action.__name__)
                    self.alerts.send(f"{slot.id}: {type(exc).__name__}: {exc}", dedupe_seconds=900)
        if self._resized:
            # Backup stops share the room to the floor between slots by notional: after any
            # resize, recompute every slot's stop from the final sizes.
            try:
                self._refresh_stops(self._my_positions())
            except Exception:
                log.exception("backup stop refresh failed")

        self._heartbeat(now)
        self.save_state()

    def _midnight_balance(self, now: datetime, balance: float) -> float:
        """The firms' daily reference is the balance at midnight. If the bot was
        offline over midnight (a night, a weekend), a loss may have been realised since
        by a backup stop. While the bot is offline the balance can only fall, so the
        last balance it saw is a safe (high) estimate: use the higher of the two."""
        last = self.state.get("last_balance")
        if not last:
            return balance
        seen_at, seen = datetime.fromisoformat(last[0]), float(last[1])
        return max(balance, seen) if seen_at <= now else balance

    # Startup / account ---------------------------------------------------------
    def _startup_checks(self, account) -> None:
        s = self.risk.state
        if s.initial_balance != self.cfg.initial_balance:
            self.alerts.send(
                f"account.initial_balance άλλαξε από {s.initial_balance:.2f} σε {self.cfg.initial_balance:.2f}: "
                "τα όρια ζημιάς υπολογίζονται πλέον από τη νέα τιμή."
            )
            s.initial_balance = self.cfg.initial_balance
        if self.state.get("fresh") and abs(account.balance - s.initial_balance) > 0.25 * s.initial_balance:
            self.alerts.send(
                f"ΠΡΟΣΟΧΗ: balance {account.balance:.2f} απέχει πολύ από το account.initial_balance "
                f"{s.initial_balance:.2f}. Τα όρια ζημιάς βασίζονται στο initial_balance: έλεγξε το config."
            )
        login = getattr(account, "login", None)
        known = self.state.get("account")
        if login is not None:
            if known is not None and int(known) != int(login):
                self._account_blocked = (
                    f"ΣΤΟΠ: ο λογαριασμός είναι {login}, ενώ το state είναι του {known}. Δεν κάνω συναλλαγές. "
                    "Αν είναι νέος λογαριασμός: python -m tradingbot reset-kill --rebase"
                )
            elif known is None:
                self.state["account"] = int(login)
        orphans = [p for p in self.broker.positions() if p.magic in self.magics and not self.magics[p.magic].enabled]
        for p in orphans:
            self.alerts.send(
                f"Ανοιχτή θέση #{p.ticket} {p.symbol} {p.volume} lots του απενεργοποιημένου slot "
                f"{self.magics[p.magic].id}: δεν θα γίνει rebalance, μόνο έλεγχος ρίσκου. Κλείσ' τη ή ενεργοποίησε το slot."
            )

    def _consume_reset_request(self, account, now: datetime) -> None:
        """`python -m tradingbot reset-kill` leaves a request file; apply it here (the bot owns the state)."""
        if not self.reset_path.exists():
            return
        mode = self.reset_path.read_text(encoding="utf-8").strip()
        self.reset_path.unlink()
        s = self.risk.state
        if mode == "rebase":
            s.initial_balance = self.cfg.initial_balance
            s.peak_equity = max(account.equity, s.initial_balance)
            s.day_start_ref = max(account.balance, account.equity)
            s.firm_day_ref = account.balance
            s.halted_today = False
            # The new account's day starts now, from its own balance (not the old account's).
            s.day = day_key(now, self.cfg.limits.day_reset_timezone)
            s.trades_today = s.requests_today = 0
            self.state["last_balance"] = [now.isoformat(), account.balance]
            self.risk.reset_kill()
            old_tickets = sorted(self.state["known"])
            if old_tickets and getattr(account, "login", None) != self.state.get("account"):
                self.alerts.send(f"Προσοχή: tickets {old_tickets} έμειναν ανοιχτά στον προηγούμενο λογαριασμό "
                                 f"{self.state.get('account')}; το bot δεν τα παρακολουθεί πια.")
            self.state["known"] = {}
            self.state["account"] = getattr(account, "login", None)
            self.state["pending"] = {}
            self._account_blocked = ""
            self.alerts.send(
                f"Reset με rebase: initial balance {s.initial_balance:.2f}, equity {account.equity:.2f}. Το bot συνεχίζει."
            )
            return
        if account.equity <= self.risk.drawdown_floor():
            self.alerts.send(
                f"Το reset-kill ΑΠΟΡΡΙΦΘΗΚΕ: equity {account.equity:.2f} κάτω από το όριο max drawdown "
                f"{self.risk.drawdown_floor():.2f}. Για νέο/επαναφερμένο λογαριασμό: reset-kill --rebase."
            )
            return
        was = s.kill_reason
        self.risk.reset_kill()
        self.alerts.send(f"Kill state καθαρίστηκε (ήταν: {was or '-'}). Το bot συνεχίζει.")

    # Slots ---------------------------------------------------------------------
    def _process_slot(self, slot: Slot, now: datetime) -> None:
        """Decide on a new closed bar. Execution happens in _try_pending."""
        strat = self.strategies[slot.id]
        symbol = self.cfg.instruments[slot.symbol].broker_symbol
        bars = self._bars(slot)
        if len(bars) < strat.warmup_bars:
            self.state["history"][slot.id] = {"have": len(bars), "need": strat.warmup_bars}
            msg = (
                f"{slot.id}: ΔΕΝ ΚΑΝΩ ΣΥΝΑΛΛΑΓΕΣ: ο broker δίνει μόνο {len(bars)} κλεισμένα {slot.timeframe} bars "
                f"για {symbol}, η στρατηγική θέλει {strat.warmup_bars}. Βάλε instruments.{slot.symbol}.history_csv "
                "(δες README) ή περίμενε να γεμίσει το ιστορικό."
            )
            log.warning(msg)
            self.alerts.send(msg, dedupe_seconds=12 * 3600)
            return
        self.state["history"].pop(slot.id, None)

        last_ts = bars.index[-1]
        prev = self.state["last_bar"].get(slot.id)
        if prev is not None and last_ts <= pd.Timestamp(prev):
            return  # nothing new (or a stale feed briefly showing an older bar)

        data = strat.prepare(bars)
        self._watch_regime(slot, data)
        mine = [p for p in self._my_positions() if p.magic == slot.magic]
        signal = strat.on_bar(data, len(data) - 1, _aggregate(mine))
        self.state["last_bar"][slot.id] = last_ts.isoformat()
        log.info("%s: bar %s close %.2f -> %s", slot.id, last_ts, data["close"].iat[-1], signal or "no signal")

        pending = self.state["pending"].get(slot.id)
        if signal is None:
            # Entries and stop moves are only valid for the bar that produced them;
            # an unexecuted exit stays pending until it is done.
            if pending and pending["signal"]["kind"] in (SignalKind.OPEN.value, SignalKind.MODIFY.value):
                self.state["pending"].pop(slot.id)
            return
        if pending and pending["signal"]["kind"] == SignalKind.CLOSE.value and signal.kind is not SignalKind.TARGET:
            return  # an exit is still being executed; it takes precedence
        self.state["pending"][slot.id] = {"bar": last_ts.isoformat(), "signal": _encode(signal), "attempts": 0,
                                          "next_try": "", "last_error": ""}

    def _bars(self, slot: Slot) -> pd.DataFrame:
        """Closed bars for a slot, fetched once per poll."""
        symbol = self.cfg.instruments[slot.symbol].broker_symbol
        count = self.strategies[slot.id].warmup_bars + 50 if slot.id in self.strategies else 60
        key = (symbol, slot.timeframe, count)
        if key not in self._bars_cache:
            self._bars_cache[key] = self.feed.bars(*key)
        return self._bars_cache[key]

    def _simulate_fills(self, now: datetime) -> None:
        held = {p.symbol for p in self.broker.positions()}
        today = day_key(now, self.cfg.limits.day_reset_timezone)
        for symbol, slot in self.stop_slot.items():
            if slot.id not in self.strategies and symbol not in held:
                continue
            try:
                bars = self._bars(slot)
            except Exception:
                log.exception("%s: no bars for the paper stops", symbol)
                continue
            fills = self.broker.on_bars(symbol, bars)
            for pos, price, reason, _ in fills:
                self._on_closed(pos, price, reason)
            last = self.state.get("last_balance")
            if fills and last and all(_bar_day(bar, self.cfg.limits.day_reset_timezone) < today
                                      for *_, bar in fills):
                # Stops hit in bars that opened before today were realised before midnight:
                # the midnight balance already had those losses. A stop in a bar that
                # opened today may be after midnight, so the last balance seen stays
                # (the higher, conservative estimate).
                self.state["last_balance"] = [last[0], self.broker.account().balance]

    def _try_pending(self, slot: Slot, now: datetime) -> None:
        pending = self.state["pending"].get(slot.id)
        if not pending:
            return
        if pending["next_try"] and now < datetime.fromisoformat(pending["next_try"]):
            return
        sig = _decode(pending["signal"])
        mine = [p for p in self._my_positions() if p.magic == slot.magic]
        quiet = False
        try:
            if sig.kind is SignalKind.TARGET:
                done, why = self._rebalance(slot, sig, mine, now)
            else:
                done, why = self._execute(slot, sig, mine, now)
        except MarketClosedError as exc:
            done, why, quiet = False, f"market closed: {exc}", True
        except UnknownOutcomeError as exc:
            done, why = False, f"unknown outcome, will re-check the positions: {exc}"
        except BrokerError as exc:
            done, why = False, str(exc)
        if done:
            self.state["pending"].pop(slot.id, None)
            return
        pending["attempts"] += 1
        delay = RETRY_DELAYS[min(pending["attempts"] - 1, len(RETRY_DELAYS) - 1)]
        pending["next_try"] = (now + timedelta(seconds=delay)).isoformat()
        pending["last_error"] = why
        log.info("%s: decision of bar %s not executed yet (attempt %d): %s", slot.id, pending["bar"],
                 pending["attempts"], why)
        if not quiet and pending["attempts"] in (1, 6):
            self.alerts.send(f"{slot.id}: η απόφαση του bar {pending['bar']} δεν εκτελέστηκε ακόμα: {why}. "
                             "Ξαναδοκιμάζω.", dedupe_seconds=600)

    def _watch_regime(self, slot: Slot, data: pd.DataFrame) -> None:
        if "regime_ok" not in data:
            return
        ok = bool(data["regime_ok"].iat[-1])
        prev = self.state["guard"].get(slot.id)
        if prev is not None and prev != ok:
            ac = data["autocorr"].iat[-1] if "autocorr" in data else float("nan")
            self.alerts.send(
                f"{slot.id}: φρένο καθεστώτος {'ΕΝΕΡΓΟ: η θέση μηδενίζεται' if not ok else 'απενεργοποιήθηκε'} "
                f"(αυτοσυσχέτιση {ac:+.3f})"
            )
        self.state["guard"][slot.id] = ok

    def _rebalance(self, slot: Slot, sig: Signal, positions: list[Position], now: datetime) -> tuple[bool, str]:
        """Resize a long-only, exposure-based position to sig.exposure x equity. Returns (done, why)."""
        symbol = self.cfg.instruments[slot.symbol].broker_symbol
        risk = self.risk
        account = self.broker.account()
        spec = self.broker.spec(symbol)
        q = self.broker.quote(symbol)
        vpp = spec.value_per_point
        target = risk.target_volume(sig.exposure or 0.0, account.equity, q.ask, spec)
        # max_exposure is an account-wide cap: other slots' positions use part of it.
        other = sum(p.volume * self._mark(p) * self._vpp(p.symbol) for p in self._my_positions() if p.magic != slot.magic)
        room = max(0.0, self.cfg.limits.max_exposure * account.equity - other)
        target = min(target, _floor_step(room / (q.ask * vpp), spec.volume_step))
        current = round(sum(p.volume for p in positions), 8)
        diff = round(target - current, 8)
        min_vol = max(spec.volume_step, spec.volume_min, sig.min_change * account.equity / (q.ask * vpp))
        if abs(diff) < min_vol - 1e-9 and not (target < spec.volume_min and current > 0):
            log.info("%s: exposure %.2fx, position %.2f lots kept (target %.2f)", slot.id, sig.exposure, current, target)
            self._update_stops(slot, positions, account.equity, q.bid, spec)
            return True, ""

        if diff > 0:
            ok, why = risk.can_increase(now)
            if not ok:
                # Killed: nothing to do. Halted / weekend window / request budget: retry later.
                return risk.state.killed, f"increase refused: {why}"
            sl = self._backup_stop(account.equity, q.bid, current + diff, spec, slot.magic)
            if sl is None:
                return False, "increase refused: no room above the loss floor"
            cap = _floor_step(min(self.cfg.limits.max_lots_per_ticket or spec.volume_max, spec.volume_max),
                              spec.volume_step)
            if cap < spec.volume_min - 1e-9:
                return False, f"max_lots_per_ticket gives {cap} lots, below the broker minimum {spec.volume_min}"
            tickets_left = self.cfg.limits.max_open_positions - len(self._my_positions())
            opened_total, remaining, partial = 0.0, diff, False
            while remaining >= spec.volume_min - 1e-9 and tickets_left > 0:
                lots = min(cap, _floor_step(remaining, spec.volume_step))
                opened = self._counted(lambda: self.broker.open(symbol, Side.LONG, lots, sl, None, slot.magic, slot.id))
                self.state["known"][int(opened.ticket)] = slot.id
                self._resized = True
                tickets_left -= 1
                opened_total += opened.volume
                remaining = round(remaining - opened.volume, 8)
                if opened.volume < lots - 1e-9:
                    partial = True  # partly filled: the retry recomputes from the real positions
                    break
            if current == 0 and opened_total > 0:
                risk.register_trade()
            if opened_total > 0:
                self.alerts.send(
                    f"{slot.id}: {symbol} {current:.2f} -> {current + opened_total:.2f} lots (έκθεση {sig.exposure:.2f}x) "
                    f"@ ~{q.ask:.2f}\n{sig.reason}"
                )
            if partial:
                return False, "position only partly increased"
            if remaining >= spec.volume_min - 1e-9:
                self.alerts.send(f"{slot.id}: όριο {self.cfg.limits.max_open_positions} ανοιχτών tickets: η θέση "
                                 f"έμεινε {current + opened_total:.2f} αντί για {target:.2f} lots.", dedupe_seconds=3600)
        else:
            # A target below the broker minimum means flat (a smaller remainder is not tradable).
            to_close = current if target < spec.volume_min else -diff
            closed_total = 0.0
            # Newest tickets first.
            for pos in sorted(positions, key=lambda p: p.entry_time, reverse=True):
                if to_close < spec.volume_min - 1e-9:
                    break
                lots = min(pos.volume, round(to_close, 8))
                if pos.volume - lots < spec.volume_min - 1e-9:
                    lots = pos.volume  # never leave a remainder below the broker minimum
                full = lots >= pos.volume - 1e-9
                fill = self._counted(lambda: self.broker.close(pos, "rebalance", None if full else lots))
                self._resized = True
                part = Position(**{**pos.__dict__, "volume": fill.volume})
                fully_closed = fill.volume >= pos.volume - 1e-9
                self._on_closed(part, fill.price, "rebalance" if target > 0 else "target 0", alert=False,
                                partial=not fully_closed)
                closed_total += fill.volume
                to_close = round(to_close - fill.volume, 8)
                if fill.volume < lots - 1e-9:
                    break  # partially filled: the retry will recompute from the real positions
            self.alerts.send(
                f"{slot.id}: {symbol} {current:.2f} -> {current - closed_total:.2f} lots (έκθεση {sig.exposure:.2f}x) "
                f"@ ~{q.bid:.2f}\n{sig.reason}"
            )
            if to_close >= spec.volume_min - 1e-9:
                return False, "position only partly reduced"
        remaining = [p for p in self._my_positions() if p.magic == slot.magic]
        self._update_stops(slot, remaining, account.equity, q.bid, spec)
        return True, ""

    def _backup_stop(self, equity: float, bid: float, lots: float, spec: InstrumentSpec, magic: int) -> float | None:
        """Broker-side stop at the price where equity would reach the bot's own loss floor.

        It mirrors what the bot does when online (flatten at the daily-loss or
        max-drawdown floor), so it only matters if the bot is offline. The room to
        the floor is shared between all open positions in proportion to notional.
        """
        if lots <= 0:
            return None
        room = equity - self.risk.equity_floor()
        if room <= 0:
            return None
        mine = lots * bid * spec.value_per_point
        total = mine + sum(p.volume * self._mark(p) * self._vpp(p.symbol) for p in self._my_positions()
                           if p.magic != magic)
        distance = room * (mine / total) / (lots * spec.value_per_point)
        distance = min(max(distance, 0.002 * bid), 0.20 * bid)
        return round(bid - distance, spec.digits)

    def _update_stops(self, slot: Slot, positions: list[Position], equity: float, bid: float,
                      spec: InstrumentSpec) -> None:
        if not positions:
            return
        sl = self._backup_stop(equity, bid, sum(p.volume for p in positions), spec, slot.magic)
        for pos in positions:
            old = pos.stop_loss or 0.0
            if sl is None or abs(sl - old) < 0.001 * bid or self.risk.requests_left() < 3:
                continue
            self._counted(lambda: self.broker.modify(pos, sl, pos.take_profit))

    def _refresh_stops(self, positions: list[Position]) -> None:
        """Move every exposure position's backup stop to the current floor and sizes
        (after the day reset, and after any resize). Disabled slots are included."""
        account = self.broker.account()
        for slot in self.cfg.slots:
            mine = [p for p in positions if p.magic == slot.magic]
            if not mine or slot.magic not in self.exposure_magics:
                continue
            symbol = self.cfg.instruments[slot.symbol].broker_symbol
            try:
                self._update_stops(slot, mine, account.equity, self.broker.quote(symbol).bid, self.broker.spec(symbol))
            except BrokerError as exc:
                log.warning("%s: backup stop refresh failed: %s", slot.id, exc)

    def _execute(self, slot: Slot, sig: Signal, positions: list[Position], now: datetime) -> tuple[bool, str]:
        """OPEN / CLOSE / MODIFY signals of stop-based strategies. Returns (done, why)."""
        inst = self.cfg.instruments[slot.symbol]
        symbol = inst.broker_symbol
        risk = self.risk
        pos = positions[0] if positions else None

        if sig.kind is SignalKind.OPEN:
            if pos is not None or sig.side is None or sig.stop_loss is None:
                return True, ""  # already open (e.g. an earlier attempt went through)
            ok, why = risk.can_open(len(self._my_positions()), now)
            if not ok:
                log.info("%s: entry skipped: %s", slot.id, why)
                return True, ""
            q = self.broker.quote(symbol)
            entry = q.ask if sig.side > 0 else q.bid
            if (entry - sig.stop_loss) * sig.side <= 0:
                log.info("%s: price already beyond the stop, entry skipped", slot.id)
                return True, ""
            spec = self.broker.spec(symbol)
            account = self.broker.account()
            # The live quote already includes the spread; keep slippage and commission.
            costs = CostModel(spread=0.0, slippage=inst.costs.slippage_at(entry),
                              commission_per_lot=inst.costs.commission_per_lot)
            size = risk.size(account.equity, entry, sig.stop_loss, spec, costs, self._open_risk())
            if size.volume <= 0:
                log.info("%s: entry skipped: %s", slot.id, size.reason)
                return True, ""
            opened = self._counted(lambda: self.broker.open(
                symbol, sig.side, size.volume, sig.stop_loss, sig.take_profit, slot.magic, slot.id
            ))
            risk.register_trade()
            self.state["known"][int(opened.ticket)] = slot.id
            self.alerts.send(
                f"ΑΝΟΙΓΜΑ {sig.side.label} {symbol} {opened.volume} lots @ {opened.entry_price:.2f}\n"
                f"SL {sig.stop_loss:.2f} | TP {sig.take_profit or '-'} | ρίσκο ~{size.risk_amount:.0f} {self.cfg.currency}\n"
                f"{slot.id}: {sig.reason}"
            )
        elif sig.kind is SignalKind.CLOSE:
            for p in positions:
                fill = self._counted(lambda: self.broker.close(p, sig.reason or "strategy exit"))
                self._on_closed(Position(**{**p.__dict__, "volume": fill.volume}), fill.price,
                                sig.reason or "strategy exit", partial=fill.volume < p.volume - 1e-9)
            if any(p.magic == slot.magic for p in self._my_positions()):
                return False, "position not fully closed yet"
        elif sig.kind is SignalKind.MODIFY:
            if risk.requests_left() < 2:
                return True, "stop update skipped, request budget low"
            q = self.broker.quote(symbol)
            for p in positions:
                mark = q.bid if p.side > 0 else q.ask
                if sig.stop_loss is not None and (mark - sig.stop_loss) * p.side <= 0:
                    continue
                self._counted(lambda: self.broker.modify(p, sig.stop_loss, sig.take_profit))
            log.info("%s: stop moved to %.2f (%s)", slot.id, sig.stop_loss or 0, sig.reason)
        return True, ""

    # Helpers -------------------------------------------------------------------
    def _counted(self, action):
        """Run a broker action and charge its server requests (incl. retries)."""
        before = getattr(self.broker, "requests_sent", None)
        try:
            return action()
        finally:
            after = getattr(self.broker, "requests_sent", None)
            self.risk.register_request(after - before if before is not None else 1)

    def _my_positions(self) -> list[Position]:
        return [p for p in self.broker.positions() if p.magic in self.magics]

    def _mark(self, p: Position) -> float:
        q = self.broker.quote(p.symbol)
        return q.bid if p.side > 0 else q.ask

    def _vpp(self, symbol: str) -> float:
        return self.broker.spec(symbol).value_per_point

    def _open_risk(self) -> list[tuple[Position, float, float]]:
        return [(p, self._mark(p), self._vpp(p.symbol)) for p in self._my_positions()]

    def _close_all(self, positions: list[Position], reason: str, now: datetime) -> None:
        """Close every bot position; on failure retry with backoff instead of every poll."""
        retry_at = self.state.get("close_retry_at") or ""
        if retry_at and now < datetime.fromisoformat(retry_at):
            return
        failed = []
        for pos in positions:
            try:
                fill = self._counted(lambda: self.broker.close(pos, reason))
            except Exception as exc:  # keep closing the others
                failed.append(f"#{pos.ticket}: {exc}")
                continue
            self._on_closed(Position(**{**pos.__dict__, "volume": fill.volume}), fill.price, reason,
                            partial=fill.volume < pos.volume - 1e-9)
        if failed:
            attempts = int(self.state.get("close_attempts", 0)) + 1
            self.state["close_attempts"] = attempts
            delay = RETRY_DELAYS[min(attempts - 1, len(RETRY_DELAYS) - 1)]
            self.state["close_retry_at"] = (now + timedelta(seconds=delay)).isoformat()
            self.alerts.send(f"ΔΕΝ έκλεισαν θέσεις ({reason}): {'; '.join(failed)}. Ξαναδοκιμάζω.",
                             dedupe_seconds=600)
        else:
            self.state["close_retry_at"] = ""
            self.state["close_attempts"] = 0

    def _on_closed(self, pos: Position, price: float, reason: str, alert: bool = True, partial: bool = False) -> None:
        """Bookkeeping after the broker closed (part of) a ticket. Never raises."""
        if not partial:
            self.state["known"].pop(int(pos.ticket or 0), None)
        try:
            try:
                vpp = self._vpp(pos.symbol)
            except Exception:
                vpp = 1.0
            pnl = pos.unrealized(price, vpp)
            slot_id = self.magics[pos.magic].id if pos.magic in self.magics else "?"
            self._journal(pos, price, pnl, reason, slot_id)
            if alert:
                self.alerts.send(
                    f"ΚΛΕΙΣΙΜΟ {pos.side.label} {pos.symbol} {pos.volume} lots @ {price:.2f} "
                    f"({reason}) | P&L ~{pnl:+.2f} {self.cfg.currency}"
                )
        except Exception:
            log.exception("bookkeeping after closing #%s failed (the position IS closed)", pos.ticket)

    def _detect_external_closes(self, positions: list[Position]) -> None:
        """Tickets that vanished since the last poll were closed outside the bot (SL/TP or by hand)."""
        open_tickets = {int(p.ticket) for p in positions if p.ticket is not None}
        for ticket, slot_id in list(self.state["known"].items()):
            if ticket not in open_tickets:
                self.state["known"].pop(ticket)
                self.alerts.send(f"Η θέση #{ticket} ({slot_id}) έκλεισε εκτός bot (SL/TP ή χειροκίνητα).")
        for p in positions:
            if p.ticket is not None and int(p.ticket) not in self.state["known"]:
                self.state["known"][int(p.ticket)] = self.magics[p.magic].id

    def _journal(self, pos: Position, price: float, pnl: float, reason: str, slot_id: str) -> None:
        self.journal_path.parent.mkdir(parents=True, exist_ok=True)
        new = not self.journal_path.exists()
        with self.journal_path.open("a", newline="", encoding="utf-8") as fh:
            w = csv.writer(fh)
            if new:
                w.writerow(["closed_utc", "slot", "symbol", "side", "volume", "entry_time", "entry", "exit", "pnl_est", "reason"])
            w.writerow([
                self.clock().isoformat(timespec="seconds"), slot_id, pos.symbol, pos.side.label, pos.volume,
                pd.Timestamp(pos.entry_time).isoformat(), pos.entry_price, round(price, 2), round(pnl, 2), reason,
            ])

    def _heartbeat(self, now: datetime) -> None:
        today = now.strftime("%Y-%m-%d")
        if now.hour < self.cfg.runtime.heartbeat_hour_utc or self.state.get("heartbeat_day") == today:
            return
        self.state["heartbeat_day"] = today
        account = self.broker.account()
        s = self.risk.state
        positions = self._my_positions()
        problems = [f"{k}: μόνο {v['have']}/{v['need']} bars, ΔΕΝ κάνει συναλλαγές" for k, v in self.state["history"].items()]
        problems += [f"{k}: απόφαση {v['bar']} σε αναμονή ({v['attempts']} προσπάθειες: {v['last_error']})"
                     for k, v in self.state["pending"].items() if v["attempts"]]
        problems += [f"{k}: φρένο καθεστώτος ενεργό" for k, ok in self.state["guard"].items() if ok is False]
        if s.killed:
            status = "KILLED: " + s.kill_reason
        elif s.halted_today:
            status = "σε παύση (ημερήσιο όριο)"
        else:
            status = "OK" if not problems else "ΠΡΟΣΟΧΗ"
        lines = [
            f"Ημερήσια αναφορά {today}",
            f"Balance {account.balance:.2f} | Equity {account.equity:.2f}",
            f"Σήμερα: {account.equity - s.day_start_ref:+.2f} | Από την αρχή: {account.equity - s.initial_balance:+.2f}",
            f"Όριο ημέρας σε equity {self.risk.daily_floor():.2f} | Max DD σε {self.risk.drawdown_floor():.2f}",
            f"Θέσεις: {len(positions)} | Trades σήμερα: {s.trades_today} | Requests: {s.requests_today}",
            f"Κατάσταση: {status}",
        ]
        lines += [f"  ! {p}" for p in problems]
        lines += [f"  {p.symbol} {p.side.label} {p.volume} @ {p.entry_price:.2f} SL {p.stop_loss}" for p in positions]
        self.alerts.send("\n".join(lines))
