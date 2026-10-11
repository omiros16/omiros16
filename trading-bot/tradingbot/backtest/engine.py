"""Bar-by-bar backtester with spread, slippage, commission and swaps.

Conventions (same as MT5 data):
  * Bars are BID prices. Buying pays the spread: ask = bid + spread.
  * A signal produced at the close of bar i is filled at the open of bar i+1.
  * At each open the equity is checked against the loss limits before any
    order is filled, as the live bot does, and orders are sized from the
    equity at that open.
  * Stops fill with slippage; take-profits fill at the limit price. Gaps
    through a level fill at the open.
  * When a stop and a target are both inside one bar, the stop is assumed to
    be hit first (pessimistic).
  * The risk manager's equity floor (daily loss / max drawdown) acts like a
    hard stop inside the bar, as the live bot's equity check would.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace

import numpy as np
import pandas as pd

from ..models import CostModel, InstrumentSpec, Position, Side, Signal, SignalKind, Trade
from ..risk import RiskLimits, RiskManager, RiskState
from ..strategies.base import Strategy


@dataclass
class BacktestConfig:
    spec: InstrumentSpec
    costs: CostModel
    limits: RiskLimits
    initial_balance: float = 100_000.0
    # No entry fills before this time (warm-up section, walk-forward folds).
    trade_from: pd.Timestamp | None = None
    close_at_end: bool = True
    # Hours to subtract from bar timestamps to get UTC, for the weekend rules
    # (FTMO / E8 MT5 servers run on UTC+2 / UTC+3; date-only daily files: 0).
    utc_offset_hours: float = 0.0


@dataclass
class BacktestResult:
    trades: list[Trade]
    equity: pd.Series
    in_position: pd.Series
    initial_balance: float
    risk_events: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    params: dict = field(default_factory=dict)
    # Close of the last bar before the first possible fill: metrics start here,
    # so the warm-up (during which the strategy cannot trade) does not dilute them.
    start: pd.Timestamp | None = None

    def tradable_equity(self) -> pd.Series:
        return self.equity if self.start is None else self.equity[self.equity.index >= self.start]

    def metrics(self, n_trials: int = 1, sr_variance: float = 0.0) -> dict:
        from .metrics import compute_metrics

        in_pos = self.in_position if self.start is None else self.in_position[self.in_position.index >= self.start]
        return compute_metrics(self.tradable_equity(), self.trades, self.initial_balance, in_pos, n_trials, sr_variance)

    def trades_frame(self) -> pd.DataFrame:
        return trades_to_frame(self.trades)


def trades_to_frame(trades: list[Trade]) -> pd.DataFrame:
    rows = [
        {
            "symbol": t.symbol,
            "side": t.side.label,
            "volume": t.volume,
            "entry_time": t.entry_time,
            "entry_price": t.entry_price,
            "exit_time": t.exit_time,
            "exit_price": t.exit_price,
            "gross_pnl": round(t.gross_pnl, 2),
            "commission": round(t.commission, 2),
            "swap": round(t.swap, 2),
            "net_pnl": round(t.net_pnl, 2),
            "r_multiple": round(t.r_multiple, 3),
            "reason": t.reason,
            "balance_after": round(t.balance_after, 2),
        }
        for t in trades
    ]
    return pd.DataFrame(rows)


def _crosses_weekend(cur: pd.Timestamp, nxt: pd.Timestamp) -> bool:
    days = (nxt.normalize() - cur.normalize()).days
    return any((cur.weekday() + k) % 7 == 5 for k in range(1, days + 1))


class _Sim:
    """Mutable simulation state for one backtest run."""

    def __init__(self, cfg: BacktestConfig, symbol: str) -> None:
        self.cfg = cfg
        self.symbol = symbol
        self.vpp = cfg.spec.value_per_point
        self.balance = cfg.initial_balance
        self.pos: Position | None = None
        # Exposure-based positions are built from several fills; like the live
        # bot's tickets they are reduced newest first (LIFO). Each lot is
        # [entry price, volume, entry time]. Which lots are closed decides the
        # realised balance, and with it the daily loss reference.
        self.lots: list[list] = []
        self.trades: list[Trade] = []
        self.skipped: list[str] = []
        self.risk = RiskManager(cfg.limits, RiskState(initial_balance=cfg.initial_balance))

    # Prices -----------------------------------------------------------------
    def buy_price(self, bid: float, slip: bool = True) -> float:
        costs = self.cfg.costs
        return bid + costs.spread_at(bid) + (costs.slippage_at(bid) if slip else 0.0)

    def sell_price(self, bid: float, slip: bool = True) -> float:
        return bid - (self.cfg.costs.slippage_at(bid) if slip else 0.0)

    def mark(self, bid: float) -> float:
        """Price at which the open position would be closed now (no slippage)."""
        assert self.pos is not None
        return bid if self.pos.side is Side.LONG else bid + self.cfg.costs.spread_at(bid)

    def equity(self, bid: float) -> float:
        if self.pos is None:
            return self.balance
        return self.balance + self.pos.unrealized(self.mark(bid), self.vpp)

    # Actions ----------------------------------------------------------------
    def open(self, sig: Signal, t: pd.Timestamp, bid_open: float, equity_now: float) -> None:
        side = sig.side
        assert side is not None and sig.stop_loss is not None
        fill = self.buy_price(bid_open) if side is Side.LONG else self.sell_price(bid_open)
        if (fill - sig.stop_loss) * side <= 0:
            self.skipped.append(f"{t}: gapped through stop, entry skipped")
            return
        if sig.take_profit is not None and (sig.take_profit - fill) * side <= 0:
            self.skipped.append(f"{t}: gapped through target, entry skipped")
            return
        size = self.risk.size(equity_now, fill, sig.stop_loss, self.cfg.spec, self.cfg.costs)
        if size.volume <= 0:
            self.skipped.append(f"{t}: {size.reason}")
            return
        commission = self.cfg.costs.commission_per_lot * size.volume
        self.balance -= commission
        self.pos = Position(
            symbol=self.symbol,
            side=side,
            volume=size.volume,
            entry_price=fill,
            entry_time=t,
            stop_loss=sig.stop_loss,
            take_profit=sig.take_profit,
            commission=commission,
            initial_risk=size.risk_amount,
            comment=sig.reason,
        )
        self.risk.register_trade()
        self.risk.register_request()

    def close(self, t: pd.Timestamp, price: float, reason: str, volume: float | None = None) -> None:
        """Close the position, or `volume` lots of it (newest fills first), at `price`."""
        pos = self.pos
        assert pos is not None
        vol = pos.volume if volume is None else min(volume, pos.volume)
        pieces = []  # (entry price, volume, entry time)
        if self.lots:
            left = vol
            vmin = self.cfg.spec.volume_min
            while left > 1e-9 and self.lots:
                lot = self.lots[-1]
                take = min(lot[1], left)
                if lot[1] - take < vmin - 1e-9:
                    take = lot[1]  # like the executor: never leave a ticket below the broker minimum
                pieces.append((lot[0], take, lot[2]))
                lot[1] = round(lot[1] - take, 8)
                left = round(left - take, 8)
                if lot[1] <= 1e-9:
                    self.lots.pop()
                if left < vmin - 1e-9:
                    break
            vol = min(pos.volume, sum(v for _, v, _ in pieces))
        else:
            pieces.append((pos.entry_price, vol, pos.entry_time))
        total = pos.volume
        for entry, v, when in pieces:
            share = v / total
            commission = self.cfg.costs.commission_per_lot * v
            gross = (price - entry) * int(pos.side) * v * self.vpp
            self.balance += gross - commission
            self.trades.append(
                Trade(
                    symbol=pos.symbol,
                    side=pos.side,
                    volume=v,
                    entry_time=when,
                    entry_price=entry,
                    exit_time=t,
                    exit_price=price,
                    gross_pnl=gross,
                    commission=pos.commission * share + commission,
                    swap=pos.swap * share,
                    reason=reason,
                    initial_risk=pos.initial_risk * share,
                    balance_after=self.balance,
                    extra={"entry_reason": pos.comment},
                )
            )
        if vol >= pos.volume - 1e-9:
            self.pos = None
            self.lots = []
        else:
            share = vol / pos.volume
            pos.volume = round(pos.volume - vol, 8)
            pos.commission *= 1 - share
            pos.swap *= 1 - share
            pos.initial_risk *= 1 - share
            if self.lots:
                pos.entry_price = sum(e * v for e, v, _ in self.lots) / sum(v for _, v, _ in self.lots)

    def rebalance(self, sig: Signal, t: pd.Timestamp, bid_open: float, equity_now: float, now_utc=None) -> None:
        """Resize the long position to sig.exposure x equity at this open."""
        spec, costs = self.cfg.spec, self.cfg.costs
        ask = self.buy_price(bid_open, slip=False)
        target = self.risk.target_volume(sig.exposure or 0.0, equity_now, ask, spec)
        current = self.pos.volume if self.pos is not None else 0.0
        diff = round(target - current, 8)
        # Same thresholds as the live executor (runner._rebalance).
        min_vol = max(spec.volume_step, spec.volume_min, sig.min_change * equity_now / (ask * self.vpp))
        if abs(diff) < min_vol - 1e-9 and not (target < spec.volume_min and current > 0):
            return
        if diff > 0:
            ok, why = self.risk.can_increase(now_utc)
            if not ok:
                self.skipped.append(f"{t}: increase skipped: {why}")
                return
            fill = self.buy_price(bid_open)
            commission = costs.commission_per_lot * diff
            self.balance -= commission
            self.lots.append([fill, round(diff, 8), t])
            if self.pos is None:
                self.pos = Position(self.symbol, Side.LONG, round(diff, 8), fill, t, commission=commission,
                                    comment=sig.reason)
                self.risk.register_trade()
            else:
                pos = self.pos
                pos.entry_price = (pos.entry_price * pos.volume + fill * diff) / (pos.volume + diff)
                pos.volume = round(pos.volume + diff, 8)
                pos.commission += commission
        else:
            self.close(t, self.sell_price(bid_open), "rebalance" if target > 0 else "target 0", volume=-diff)
        self.risk.register_request()

    def close_market(self, t: pd.Timestamp, bid: float, reason: str) -> None:
        assert self.pos is not None
        price = self.sell_price(bid) if self.pos.side is Side.LONG else self.buy_price(bid)
        self.close(t, price, reason)
        self.risk.register_request()

    def floor_price(self) -> tuple[float, str]:
        """Exit price (as the position's close price) where equity hits the risk floor."""
        pos = self.pos
        assert pos is not None
        floor = self.risk.equity_floor()
        reason = "max drawdown limit" if floor == self.risk.drawdown_floor() else "daily loss limit"
        price = pos.entry_price + (floor - self.balance) / (pos.volume * self.vpp) * int(pos.side)
        return price, reason

    def intrabar_exit(self, t: pd.Timestamp, o: float, h: float, low: float) -> None:
        """Stops, targets and the equity floor within one bar."""
        pos = self.pos
        assert pos is not None
        costs = self.cfg.costs
        slip = costs.slippage_at(o)
        floor_px, floor_reason = self.floor_price()
        side = pos.side
        # Exit-side prices: a long exits on the bid, a short on the ask.
        if side is Side.LONG:
            x_open, x_high, x_low = o, h, low
        else:
            x_open, x_high, x_low = o + costs.spread_at(o), h + costs.spread_at(h), low + costs.spread_at(low)

        stop_px, stop_reason = floor_px, floor_reason
        if pos.stop_loss is not None and (pos.stop_loss - floor_px) * side >= 0:
            stop_px, stop_reason = pos.stop_loss, "stop loss"
        tp = pos.take_profit

        adverse = x_low if side is Side.LONG else x_high
        favorable = x_high if side is Side.LONG else x_low
        if (x_open - stop_px) * side <= 0:
            self.close(t, x_open - slip * side, stop_reason + " (gap)")
        elif tp is not None and (x_open - tp) * side >= 0:
            self.close(t, x_open, "take profit (gap)")
        elif (adverse - stop_px) * side <= 0:
            self.close(t, stop_px - slip * side, stop_reason)
        elif tp is not None and (favorable - tp) * side >= 0:
            self.close(t, tp, "take profit")
        else:
            # Still open: the live bot polls equity during the bar, so a trailing
            # drawdown peak follows the best price of the bar. The adverse
            # extreme is assumed to come first (pessimistic for the stops).
            best = self.balance + pos.unrealized(favorable, self.vpp)
            self.risk.state.peak_equity = max(self.risk.state.peak_equity, best)
            return
        self.risk.register_request()
        self.risk.check_equity(self.balance)


def run_backtest(bars: pd.DataFrame, strategy: Strategy, cfg: BacktestConfig, symbol: str = "") -> BacktestResult:
    data = strategy.prepare(bars)
    n = len(data)
    idx = data.index
    o = data["open"].to_numpy(float)
    h = data["high"].to_numpy(float)
    low = data["low"].to_numpy(float)
    c = data["close"].to_numpy(float)
    sim = _Sim(cfg, symbol or cfg.spec.symbol)
    risk = sim.risk
    costs = cfg.costs
    equity_curve = np.empty(n)
    in_pos = np.zeros(n, dtype=bool)
    pending: Signal | None = None
    warmup = strategy.warmup_bars
    utc_offset = pd.Timedelta(hours=cfg.utc_offset_hours)
    # Signals at bar i fill at bar i+1: the first decision bar is the one before
    # the first allowed fill.
    first = max(warmup - 1, 0)
    if cfg.trade_from is not None:
        first = max(first, int(idx.searchsorted(cfg.trade_from)) - 1)

    for i in range(n):
        t = idx[i]
        # 1) New day: financing for the nights crossed, then reset daily limits.
        if i > 0 and t.normalize() != idx[i - 1].normalize():
            if sim.pos is not None:
                nights = (t.normalize() - idx[i - 1].normalize()).days
                rate = costs.swap_per_lot_night(sim.pos.side, c[i - 1], sim.vpp)
                swap = nights * rate * sim.pos.volume
                sim.pos.swap += swap
                sim.balance += swap
        # The live bot takes the day's reference at its first poll of the day,
        # when the new daily bar has just opened: equity at this open.
        risk.start_day(t.strftime("%Y-%m-%d"), sim.balance, sim.equity(o[i]) if sim.pos is not None else sim.balance)

        # 2) The open: loss limits first (a gap can breach them), then the
        #    orders decided at the previous close, sized from the equity now.
        if sim.pos is not None:
            sim.intrabar_exit(t, o[i], o[i], o[i])  # stop / target / loss floor gapped through
        if pending is not None:
            now_utc = (t - utc_offset).to_pydatetime()
            equity_open = sim.equity(o[i]) if sim.pos is not None else sim.balance
            if pending.kind is SignalKind.OPEN and sim.pos is None:
                ok, why = risk.can_open(0, now_utc)
                if ok:
                    sim.open(pending, t, o[i], equity_open)
                else:
                    sim.skipped.append(f"{t}: {why}")
            elif pending.kind is SignalKind.CLOSE and sim.pos is not None:
                sim.close_market(t, o[i], pending.reason or "strategy exit")
            elif pending.kind is SignalKind.MODIFY and sim.pos is not None:
                _apply_modify(sim, pending, o[i])
            elif pending.kind is SignalKind.TARGET:
                sim.rebalance(pending, t, o[i], equity_open, now_utc)
            pending = None

        # 3) Stops / targets / equity floor inside the bar.
        if sim.pos is not None:
            sim.intrabar_exit(t, o[i], h[i], low[i])

        # 4) Mark to market at the close.
        equity = sim.equity(c[i])
        event = risk.check_equity(equity)
        if event and sim.pos is not None:
            sim.close_market(t, c[i], event.replace("_", " ") + " limit")
            equity = sim.balance

        # 5) Flat before the weekend if required.
        if (
            sim.pos is not None
            and cfg.limits.close_before_weekend
            and i + 1 < n
            and _crosses_weekend(t, idx[i + 1])
        ):
            sim.close_market(t, c[i], "weekend close")
            equity = sim.balance

        # 6) Strategy decides at the close; executes at the next open.
        if i + 1 < n and i >= warmup - 1:
            sig = strategy.on_bar(data, i, sim.pos)
            if sig is not None:
                may_enter = cfg.trade_from is None or idx[i + 1] >= cfg.trade_from
                if sig.kind is SignalKind.OPEN:
                    if sig.stop_loss is None or sig.side is None:
                        raise ValueError(f"{strategy.name}: OPEN signal without side/stop at {t}")
                    if sim.pos is None and may_enter:
                        pending = sig
                elif sig.kind is SignalKind.TARGET:
                    if may_enter or sim.pos is not None:
                        pending = sig
                elif sim.pos is not None:
                    pending = sig

        equity_curve[i] = equity
        in_pos[i] = sim.pos is not None

    if cfg.close_at_end and sim.pos is not None and n:
        sim.close_market(idx[-1], c[-1], "end of data")
        equity_curve[-1] = sim.balance

    return BacktestResult(
        trades=sim.trades,
        equity=pd.Series(equity_curve, index=idx, name="equity"),
        in_position=pd.Series(in_pos, index=idx, name="in_position"),
        initial_balance=cfg.initial_balance,
        risk_events=list(risk.state.events),
        skipped=sim.skipped,
        params=dict(strategy.params),
        start=idx[first] if first < n else None,
    )


def _apply_modify(sim: _Sim, sig: Signal, bid_open: float) -> None:
    pos = sim.pos
    assert pos is not None
    price = sim.mark(bid_open)
    sl, tp = sig.stop_loss, sig.take_profit
    # A stop already beyond the current price would be rejected by the broker.
    if sl is not None and (price - sl) * pos.side <= 0:
        return
    if tp is not None and (tp - price) * pos.side <= 0:
        return
    sim.pos = replace(pos, stop_loss=sl, take_profit=tp)
    sim.risk.register_request()
