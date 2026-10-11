"""Bar-by-bar backtester with spread, slippage, commission and swaps.

Conventions (same as MT5 data):
  * Bars are BID prices. Buying pays the spread: ask = bid + spread.
  * A signal produced at the close of bar i is filled at the open of bar i+1.
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
    # Entries are ignored before this time (warm-up section in walk-forward).
    trade_from: pd.Timestamp | None = None
    close_at_end: bool = True


@dataclass
class BacktestResult:
    trades: list[Trade]
    equity: pd.Series
    in_position: pd.Series
    initial_balance: float
    risk_events: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    params: dict = field(default_factory=dict)

    def metrics(self, n_trials: int = 1, sr_variance: float = 0.0) -> dict:
        from .metrics import compute_metrics

        return compute_metrics(self.equity, self.trades, self.initial_balance, self.in_position, n_trials, sr_variance)

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
        self.trades: list[Trade] = []
        self.skipped: list[str] = []
        self.risk = RiskManager(cfg.limits, RiskState(initial_balance=cfg.initial_balance))

    # Prices -----------------------------------------------------------------
    def buy_price(self, bid: float, slip: bool = True) -> float:
        return bid + self.cfg.costs.spread + (self.cfg.costs.slippage if slip else 0.0)

    def sell_price(self, bid: float, slip: bool = True) -> float:
        return bid - (self.cfg.costs.slippage if slip else 0.0)

    def mark(self, bid: float) -> float:
        """Price at which the open position would be closed now (no slippage)."""
        assert self.pos is not None
        return bid if self.pos.side is Side.LONG else bid + self.cfg.costs.spread

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

    def close(self, t: pd.Timestamp, price: float, reason: str) -> None:
        pos = self.pos
        assert pos is not None
        commission = self.cfg.costs.commission_per_lot * pos.volume
        gross = (price - pos.entry_price) * int(pos.side) * pos.volume * self.vpp
        self.balance += gross - commission
        self.trades.append(
            Trade(
                symbol=pos.symbol,
                side=pos.side,
                volume=pos.volume,
                entry_time=pos.entry_time,
                entry_price=pos.entry_price,
                exit_time=t,
                exit_price=price,
                gross_pnl=gross,
                commission=pos.commission + commission,
                swap=pos.swap,
                reason=reason,
                initial_risk=pos.initial_risk,
                balance_after=self.balance,
                extra={"entry_reason": pos.comment},
            )
        )
        self.pos = None

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
        slip = self.cfg.costs.slippage
        spread = self.cfg.costs.spread
        floor_px, floor_reason = self.floor_price()
        side = pos.side
        # Exit-side prices: a long exits on the bid, a short on the ask.
        x_open, x_high, x_low = (o, h, low) if side is Side.LONG else (o + spread, h + spread, low + spread)

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
    last_equity = cfg.initial_balance
    warmup = strategy.warmup_bars

    for i in range(n):
        t = idx[i]
        # 1) New day: financing for the nights crossed, then reset daily limits.
        if i > 0 and t.normalize() != idx[i - 1].normalize():
            if sim.pos is not None:
                nights = (t.normalize() - idx[i - 1].normalize()).days
                rate = costs.swap_long_per_lot if sim.pos.side is Side.LONG else costs.swap_short_per_lot
                swap = nights * rate * sim.pos.volume
                sim.pos.swap += swap
                sim.balance += swap
                last_equity += swap
        risk.start_day(t.strftime("%Y-%m-%d"), sim.balance, last_equity)

        # 2) Orders decided at the previous close fill at this open.
        if pending is not None:
            if pending.kind is SignalKind.OPEN and sim.pos is None:
                ok, why = risk.can_open(0)
                if ok:
                    sim.open(pending, t, o[i], last_equity)
                else:
                    sim.skipped.append(f"{t}: {why}")
            elif pending.kind is SignalKind.CLOSE and sim.pos is not None:
                sim.close_market(t, o[i], pending.reason or "strategy exit")
            elif pending.kind is SignalKind.MODIFY and sim.pos is not None:
                _apply_modify(sim, pending, o[i])
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
                if sig.kind is SignalKind.OPEN:
                    if sig.stop_loss is None or sig.side is None:
                        raise ValueError(f"{strategy.name}: OPEN signal without side/stop at {t}")
                    if sim.pos is None and (cfg.trade_from is None or t >= cfg.trade_from):
                        pending = sig
                elif sim.pos is not None:
                    pending = sig

        equity_curve[i] = equity
        in_pos[i] = sim.pos is not None
        last_equity = equity

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
