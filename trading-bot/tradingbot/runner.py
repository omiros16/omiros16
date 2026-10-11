"""The live loop: poll, check risk, run strategies on new closed bars, execute.

Order of checks on every poll:
  1. kill switch file / killed state      -> flatten, stop trading
  2. new day                              -> reset daily counters
  3. equity vs daily loss / max drawdown  -> flatten (and kill on max drawdown)
  4. weekend close (if required)          -> flatten
  5. per strategy slot, on a new closed bar -> strategy -> risk -> broker
  6. daily heartbeat to Telegram

State (risk counters, last processed bar, known tickets) is saved to JSON
after every poll, so a restart neither forgets the day's losses nor re-fires
a signal that was already acted on.
"""

from __future__ import annotations

import csv
import json
import logging
import os
import time
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

import pandas as pd

from .alerts import Alerts
from .brokers.base import Broker, BrokerError
from .config import BotConfig, Slot
from .data.feeds import Feed
from .models import CostModel, Position, Signal, SignalKind
from .mt5_client import TIMEFRAMES
from .risk import RiskManager, RiskState, day_key
from .strategies import create_strategy

log = logging.getLogger(__name__)


def utcnow() -> datetime:
    return datetime.now(timezone.utc)


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
        self.slots = [s for s in cfg.slots if s.enabled]
        if not self.slots:
            raise ValueError("no enabled strategy slots in the config")
        self.strategies = {s.id: create_strategy(s.strategy, s.params) for s in self.slots}
        self.magics = {s.magic: s for s in self.slots}
        # Simulated stops/swaps run once per symbol, on its finest timeframe.
        self.stop_slot: dict[str, str] = {}
        for s in sorted(self.slots, key=lambda s: TIMEFRAMES.index(s.timeframe), reverse=True):
            self.stop_slot[cfg.instruments[s.symbol].broker_symbol] = s.id

        rt = cfg.runtime
        self.state_path = Path(rt.state_dir) / "bot_state.json"
        self.kill_file = Path(rt.kill_switch_file)
        self.journal_path = Path(rt.log_dir) / "trades.csv"
        self.state = self._load_state()
        self.risk = RiskManager(cfg.limits, self.state["risk"])

    # State ---------------------------------------------------------------------
    def _load_state(self) -> dict:
        if self.state_path.exists():
            raw = json.loads(self.state_path.read_text(encoding="utf-8"))
            raw["risk"] = RiskState(**raw["risk"])
            raw["known"] = {int(k): v for k, v in raw.get("known", {}).items()}
            return raw
        return {
            "risk": RiskState(initial_balance=self.cfg.initial_balance),
            "last_bar": {},
            "known": {},
            "heartbeat_day": "",
        }

    def save_state(self) -> None:
        data = {**self.state, "risk": asdict(self.state["risk"])}
        data["risk"]["events"] = data["risk"]["events"][-200:]
        self.state_path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.state_path.with_suffix(".tmp")
        tmp.write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")
        os.replace(tmp, self.state_path)

    # Main loop -----------------------------------------------------------------
    def run_forever(self) -> None:
        poll = self.cfg.runtime.poll_seconds
        backoff = poll
        self.alerts.send(f"Bot ξεκίνησε: {', '.join(s.id for s in self.slots)} | firm: {self.cfg.prop_firm}")
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
            self.broker.shutdown()

    def step(self) -> None:
        now = self.clock()
        risk = self.risk

        if self.kill_file.exists() and not risk.state.killed:
            risk.kill(f"kill switch file {self.kill_file} found")
            self.alerts.send("KILL SWITCH: κλείνω όλες τις θέσεις και σταματάω τις συναλλαγές.")

        account = self.broker.account()
        if risk.start_day(day_key(now, self.cfg.limits.day_reset_timezone), account.balance, account.equity):
            log.info("new trading day %s, reference equity %.2f", risk.state.day, risk.state.day_start_ref)

        positions = self._my_positions()
        self._detect_external_closes(positions)

        event = risk.check_equity(account.equity)
        if event == "max_drawdown":
            self.alerts.send(f"MAX DRAWDOWN: equity {account.equity:.2f}. Κλείνω τα πάντα, το bot σταματάει μέχρι reset.")
        elif event == "daily_loss":
            self.alerts.send(f"Ημερήσιο όριο ζημιάς: equity {account.equity:.2f}. Κλείνω θέσεις, παύση ως αύριο.")

        if positions and (risk.state.killed or risk.state.halted_today):
            self._close_all(positions, "risk limit" if not risk.state.killed else "kill")
        elif positions and risk.must_close_for_weekend(now):
            self._close_all(positions, "weekend close")

        for slot in self.slots:
            try:
                self._process_slot(slot, now)
            except BrokerError as exc:
                log.error("%s: %s", slot.id, exc)
                self.alerts.send(f"{slot.id}: {exc}", dedupe_seconds=900)

        self._heartbeat(now)
        self.save_state()

    # Slots ---------------------------------------------------------------------
    def _process_slot(self, slot: Slot, now: datetime) -> None:
        strat = self.strategies[slot.id]
        inst = self.cfg.instruments[slot.symbol]
        symbol = inst.broker_symbol
        bars = self.feed.bars(symbol, slot.timeframe, strat.warmup_bars + 50)
        if self.stop_slot[symbol] == slot.id:
            for pos, price, reason in self.broker.on_bars(symbol, bars):
                self._on_closed(pos, price, reason)
        if len(bars) < strat.warmup_bars:
            log.warning("%s: only %d bars, need %d", slot.id, len(bars), strat.warmup_bars)
            return

        last = bars.index[-1].isoformat()
        if self.state["last_bar"].get(slot.id) == last:
            return
        # Mark first: at most one action per bar, even if execution fails below.
        self.state["last_bar"][slot.id] = last

        data = strat.prepare(bars)
        pos = next((p for p in self._my_positions() if p.magic == slot.magic), None)
        signal = strat.on_bar(data, len(data) - 1, pos)
        log.info("%s: bar %s close %.2f -> %s", slot.id, last, data["close"].iat[-1], signal or "no signal")
        if signal is not None:
            self._execute(slot, signal, pos, now)

    def _execute(self, slot: Slot, sig: Signal, pos: Position | None, now: datetime) -> None:
        inst = self.cfg.instruments[slot.symbol]
        symbol = inst.broker_symbol
        risk = self.risk

        if sig.kind is SignalKind.OPEN:
            if pos is not None or sig.side is None or sig.stop_loss is None:
                return
            ok, why = risk.can_open(len(self._my_positions()), now)
            if not ok:
                log.info("%s: entry skipped: %s", slot.id, why)
                return
            q = self.broker.quote(symbol)
            entry = q.ask if sig.side > 0 else q.bid
            if (entry - sig.stop_loss) * sig.side <= 0:
                log.info("%s: price already beyond the stop, entry skipped", slot.id)
                return
            spec = self.broker.spec(symbol)
            account = self.broker.account()
            # The live quote already includes the spread; keep a slippage buffer.
            costs = CostModel(spread=0.0, slippage=inst.costs.slippage)
            size = risk.size(account.equity, entry, sig.stop_loss, spec, costs, self._open_risk())
            if size.volume <= 0:
                log.info("%s: entry skipped: %s", slot.id, size.reason)
                return
            opened = self._counted(lambda: self.broker.open(
                symbol, sig.side, size.volume, sig.stop_loss, sig.take_profit, slot.magic, slot.id
            ))
            risk.register_trade()
            self.state["known"][int(opened.ticket)] = slot.id
            self.alerts.send(
                f"ΑΝΟΙΓΜΑ {sig.side.label} {symbol} {size.volume} lots @ {opened.entry_price:.2f}\n"
                f"SL {sig.stop_loss:.2f} | TP {sig.take_profit or '-'} | ρίσκο ~{size.risk_amount:.0f} {self.cfg.currency}\n"
                f"{slot.id}: {sig.reason}"
            )
        elif sig.kind is SignalKind.CLOSE and pos is not None:
            price = self._counted(lambda: self.broker.close(pos, sig.reason or "strategy exit"))
            self._on_closed(pos, price, sig.reason or "strategy exit")
        elif sig.kind is SignalKind.MODIFY and pos is not None:
            if risk.requests_left() < 2:
                log.info("%s: stop update skipped, request budget low", slot.id)
                return
            q = self.broker.quote(symbol)
            mark = q.bid if pos.side > 0 else q.ask
            if sig.stop_loss is not None and (mark - sig.stop_loss) * pos.side <= 0:
                return
            self._counted(lambda: self.broker.modify(pos, sig.stop_loss, sig.take_profit))
            log.info("%s: stop moved to %.2f (%s)", slot.id, sig.stop_loss or 0, sig.reason)

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

    def _open_risk(self) -> list[tuple[Position, float, float]]:
        out = []
        for p in self._my_positions():
            q = self.broker.quote(p.symbol)
            mark = q.bid if p.side > 0 else q.ask
            out.append((p, mark, self.broker.spec(p.symbol).value_per_point))
        return out

    def _close_all(self, positions: list[Position], reason: str) -> None:
        for pos in positions:
            try:
                price = self._counted(lambda: self.broker.close(pos, reason))
                self._on_closed(pos, price, reason)
            except BrokerError as exc:
                self.alerts.send(f"ΔΕΝ έκλεισε η θέση #{pos.ticket} {pos.symbol}: {exc}", dedupe_seconds=600)

    def _on_closed(self, pos: Position, price: float, reason: str) -> None:
        self.state["known"].pop(int(pos.ticket or 0), None)
        try:
            vpp = self.broker.spec(pos.symbol).value_per_point
        except BrokerError:
            vpp = 1.0
        pnl = pos.unrealized(price, vpp)
        slot_id = self.magics[pos.magic].id if pos.magic in self.magics else "?"
        self._journal(pos, price, pnl, reason, slot_id)
        self.alerts.send(
            f"ΚΛΕΙΣΙΜΟ {pos.side.label} {pos.symbol} {pos.volume} lots @ {price:.2f} "
            f"({reason}) | P&L ~{pnl:+.2f} {self.cfg.currency}"
        )

    def _detect_external_closes(self, positions: list[Position]) -> None:
        """Positions that vanished since the last poll were closed by SL/TP at the broker."""
        open_tickets = {int(p.ticket) for p in positions if p.ticket is not None}
        for ticket, slot_id in list(self.state["known"].items()):
            if ticket not in open_tickets:
                self.state["known"].pop(ticket)
                account = self.broker.account()
                self.alerts.send(f"Η θέση #{ticket} ({slot_id}) έκλεισε στον broker (SL/TP). Balance {account.balance:.2f}")
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
        status = "KILLED: " + s.kill_reason if s.killed else ("σε παύση (ημερήσιο όριο)" if s.halted_today else "OK")
        lines = [
            f"Ημερήσια αναφορά {today}",
            f"Balance {account.balance:.2f} | Equity {account.equity:.2f}",
            f"Σήμερα: {account.equity - s.day_start_ref:+.2f} | Από την αρχή: {account.equity - s.initial_balance:+.2f}",
            f"Όριο ημέρας σε equity {self.risk.daily_floor():.2f} | Max DD σε {self.risk.drawdown_floor():.2f}",
            f"Θέσεις: {len(positions)} | Trades σήμερα: {s.trades_today} | Requests: {s.requests_today}",
            f"Κατάσταση: {status}",
        ]
        lines += [f"  {p.symbol} {p.side.label} {p.volume} @ {p.entry_price:.2f} SL {p.stop_loss}" for p in positions]
        self.alerts.send("\n".join(lines))
