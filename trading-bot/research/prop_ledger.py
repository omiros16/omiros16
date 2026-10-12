"""Ticket-level daily-bar simulator of the live trading bot (mr_ensemble on a US index CFD at FTMO).

This replaces the return-based prop_sim.py / prop_sim2.py for the FTMO numbers in RESEARCH.md §5.
Those scripts applied the daily stop to the previous close's equity; the bot (and FTMO) measure
the day from the balance at midnight, so a floating loss carried overnight counts against the new
day. Here every ticket is tracked with its entry price, as in MT5 and tradingbot/runner.py.

What it reproduces (tradingbot/runner.py + tradingbot/risk.py), per bar t:
  1. FTMO day boundary just before the open of bar t (Prague midnight = NQ 18:00 ET session open).
     midnight balance B, day-start equity E (tickets marked at the bid of close t-1, swaps included).
     Optional realisation just before midnight (priced at C[t-1]): realize=1 all tickets closed and
     the same volume reopened (B == E minus the spread); realize=3 only the losing tickets.
  2. Floors for the day (money; `base` = initial balance):
       bot internal daily stop  ref_kind 1: max(B, E) - int_pct*base   (risk.py today)
                                ref_kind 2: E - int_pct*base            (what research simulated)
       FTMO-mirror guard        B - guard_pct*base (guard_pct 0 = off); guard_now=True uses
                                max(B, current balance) - guard_pct*base, i.e. also the floor that
                                FTMO will apply after the next midnight
       bot kill (-9%)           base*(1 - kill_pct)
       FTMO daily loss          B - 0.05*base   (floating P/L included)
       FTMO max loss            base*(1 - 0.10)
  3. Open O[t]: if min(E, equity at bid(O)) is already through the bot's floor (the bot checks at its
     first poll after midnight while the market is still closed, and again at the open), flatten at
     the open and pause for the day (kill if through the kill floor).
  4. Rebalance at O[t] to target x[t-1]*equity (decided at close t-1), runner._rebalance rules:
     keep if |diff| < min_change*equity/ask (unless target 0), add one ticket at ask, or close
     partially at bid in `close_order` (0 LIFO = runner today, 1 FIFO, 2 highest entry price first).
     No increase while paused or killed. realize=2 (D4b) closes all and reopens the target here.
  5. Intraday low L[t]: positions are long, so the low is the worst equity of the day. If the
     bot floor is crossed, exit at the floor price minus `slip` (fraction of price), pause.
  6. FTMO checks on the worst equity of the day (day start, open, stop fill or low).
  7. Close C[t]: equity marked at bid; challenge passes when equity >= base*(1+target) with at
     least `min_days` trading days; swaps for the calendar nights to the next bar posted to the
     tickets (realised into the balance only when a ticket closes, as in MT5).
Prices are mid; bid = mid*(1-h), ask = mid*(1+h) (h = half of the 2 bp round trip).
"""

from __future__ import annotations

import numpy as np

try:
    from numba import njit
except ImportError:  # pure Python: same results, roughly 100x slower (pip install numba)
    def njit(*args, **kwargs):
        if args and callable(args[0]):
            return args[0]
        return lambda f: f

KMAX = 1024
NCOUNT = 16


@njit(cache=True)
def _float_pnl(u, e, sw, k, bid):
    s = 0.0
    for i in range(k):
        s += u[i] * (bid - e[i]) + sw[i]
    return s


@njit(cache=True)
def _units(u, k):
    s = 0.0
    for i in range(k):
        s += u[i]
    return s


@njit(cache=True)
def _remove(u, e, sw, k, i):
    for j in range(i, k - 1):
        u[j] = u[j + 1]
        e[j] = e[j + 1]
        sw[j] = sw[j + 1]
    return k - 1


@njit(cache=True)
def run_ledger(O, H, L, C, x, nights, rate, cont, start, end, lev,
               ref_kind, int_pct, guard_pct, guard_now, kill_pct, close_order, realize,
               target, min_days, stop_on_outcome, rebase,
               h, slip, min_change, max_exp, ftmo_daily, ftmo_max):
    """Simulate bars start..end-1. x[t] is the 1x target exposure decided at close t.

    rebase[t] (real-path mode): at the boundary of bar t the 'initial balance' becomes the
    day-start equity and the kill is reset (a fresh account of the current size).
    Returns (eq_close[n], counts[NCOUNT], outcome, out_day, final_eq).
      counts: 0 internal stops, 1 guard stops, 2 kills, 3 FTMO daily breaches, 4 FTMO max-loss
      breaches, 5 trade days, 6 spread cost, 7 swap cost, 8 tickets opened, 9 gap exits,
      10 days paused, 11 max tickets open, 12 FTMO-daily breaches already at midnight,
      13 ... at the open gap, 14 ... intraday
    outcome: 1 pass, -1 FTMO breach, -2 bot kill, 0 undecided.
    """
    n = end - start
    eq_close = np.full(n, np.nan)
    counts = np.zeros(NCOUNT)
    u = np.zeros(KMAX)
    e = np.zeros(KMAX)
    sw = np.zeros(KMAX)
    k = 0
    base = 1.0
    bal = 1.0
    killed = False
    outcome = 0
    out_day = -1
    final_eq = 1.0
    trade_days = 0
    big = 1e18
    for j in range(n):
        t = start + j
        traded = False
        # ---------------- 1. day boundary ----------------
        if j > 0:
            cbid = C[t - 1] * (1 - h)
            if realize == 1 and k > 0:
                tot = _units(u, k)
                bal += _float_pnl(u, e, sw, k, cbid)
                u[0] = tot
                e[0] = C[t - 1] * (1 + h)
                sw[0] = 0.0
                k = 1
                counts[6] += tot * C[t - 1] * 2 * h / base
                traded = True  # (on the previous day, before midnight)
            elif realize == 3 and k > 0:
                for i in range(k):
                    pl = u[i] * (cbid - e[i]) + sw[i]
                    if pl < 0:
                        bal += pl
                        e[i] = C[t - 1] * (1 + h)
                        sw[i] = 0.0
                        counts[6] += u[i] * C[t - 1] * 2 * h / base
                        traded = True
            E0 = bal + _float_pnl(u, e, sw, k, cbid)
        else:
            E0 = bal
        if rebase[t] and j > 0:
            base = E0
            killed = False
        B0 = bal
        if ref_kind == 1:
            f_int = max(B0, E0) - int_pct * base
        elif ref_kind == 2:
            f_int = E0 - int_pct * base
        else:
            f_int = -big
        f_guard = B0 - guard_pct * base if guard_pct > 0 else -big
        f_kill = base * (1 - kill_pct)
        f_day = max(f_int, f_guard)
        f_ftmo = B0 - ftmo_daily * base
        f_max = base * (1 - ftmo_max)
        paused = False
        worst = E0
        mech = 0
        if E0 <= f_ftmo + 1e-12:
            mech = 12
        # ---------------- 2. open: gap check ----------------
        obid = O[t] * (1 - h)
        if k > 0:
            Eo = bal + _float_pnl(u, e, sw, k, obid)
            # The bot checks at its first poll after midnight (market still closed, equity E0)
            # and again at the open; the close order fills at the open either way.
            Etr = min(E0, Eo)
            if (not killed) and (Etr <= f_kill or Etr <= f_day):
                fill = obid * (1 - slip)
                if cont[t]:
                    fl0 = max(f_day, f_kill)
                    if E0 <= fl0:
                        fill = C[t - 1] * (1 - h) * (1 - slip)
                    else:
                        tot = _units(u, k)
                        ue = 0.0
                        ss = 0.0
                        for i in range(k):
                            ue += u[i] * e[i]
                            ss += sw[i]
                        fill = (fl0 - bal - ss + ue) / tot * (1 - slip)
                bal += _float_pnl(u, e, sw, k, fill)
                k = 0
                traded = True
                counts[9] += 1
                if Etr <= f_kill:
                    killed = True
                    counts[2] += 1
                elif f_guard >= f_int:
                    counts[1] += 1
                else:
                    counts[0] += 1
                paused = True
                worst = min(worst, bal)
            else:
                worst = min(worst, Eo)
            if mech == 0 and worst <= f_ftmo + 1e-12:
                mech = 13
        # ---------------- 3. rebalance at the open ----------------
        if (not killed) and t > 0:
            Eo = bal + _float_pnl(u, e, sw, k, obid)
            ask = O[t] * (1 + h)
            tx = min(lev * x[t - 1], max_exp)
            if tx < 0:
                tx = 0.0
            tgt = tx * Eo / ask
            if realize == 2:
                if k > 0:
                    bal += _float_pnl(u, e, sw, k, obid)
                    k = 0
                    traded = True
                if tgt > 0 and not paused:
                    u[0] = tgt
                    e[0] = ask
                    sw[0] = 0.0
                    k = 1
                    counts[6] += tgt * O[t] * 2 * h / base
                    counts[8] += 1
                    traded = True
            else:
                cur = _units(u, k)
                diff = tgt - cur
                minv = min_change * Eo / ask
                if abs(diff) >= minv - 1e-12 or (tgt == 0 and cur > 0):
                    if diff > 0:
                        if not paused:
                            if k >= KMAX:  # never happens in practice: merge the two oldest
                                ue = u[0] * e[0] + u[1] * e[1]
                                u[1] += u[0]
                                e[1] = ue / u[1]
                                sw[1] += sw[0]
                                k = _remove(u, e, sw, k, 0)
                            u[k] = diff
                            e[k] = ask
                            sw[k] = 0.0
                            k += 1
                            counts[6] += diff * O[t] * 2 * h / base
                            counts[8] += 1
                            traded = True
                    elif diff < 0:
                        q = -diff
                        while q > 1e-15 and k > 0:
                            if close_order == 0:
                                i = k - 1
                            elif close_order == 1:
                                i = 0
                            else:
                                i = 0
                                for i2 in range(1, k):
                                    if e[i2] > e[i]:
                                        i = i2
                            c_ = min(u[i], q)
                            share = c_ / u[i]
                            bal += c_ * (obid - e[i]) + sw[i] * share
                            sw[i] -= sw[i] * share
                            u[i] -= c_
                            q -= c_
                            if u[i] <= 1e-15:
                                k = _remove(u, e, sw, k, i)
                        traded = True
        # ---------------- 4. intraday low ----------------
        if k > 0:
            if guard_now and guard_pct > 0:
                f_guard = max(B0, bal) - guard_pct * base
                f_day = max(f_int, f_guard)
            lbid = L[t] * (1 - h)
            El = bal + _float_pnl(u, e, sw, k, lbid)
            fl = max(f_day, f_kill)
            if (not killed) and El <= fl:
                tot = _units(u, k)
                ue = 0.0
                ss = 0.0
                for i in range(k):
                    ue += u[i] * e[i]
                    ss += sw[i]
                pbid = (fl - bal - ss + ue) / tot  # bid price at which equity == fl
                fill = pbid * (1 - slip)
                bal += _float_pnl(u, e, sw, k, fill)
                k = 0
                traded = True
                if f_kill >= f_day:
                    killed = True
                    counts[2] += 1
                elif f_guard >= f_int:
                    counts[1] += 1
                else:
                    counts[0] += 1
                paused = True
                worst = min(worst, bal)
            else:
                worst = min(worst, El)
        if paused:
            counts[10] += 1
        if traded:
            trade_days += 1
        # ---------------- 5. FTMO checks ----------------
        breach_d = worst <= f_ftmo + 1e-12
        breach_m = worst <= f_max + 1e-12
        if breach_d:
            counts[3] += 1
            counts[mech if mech > 0 else 14] += 1
        if breach_m:
            counts[4] += 1
        # ---------------- 6. close ----------------
        cbid = C[t] * (1 - h)
        Ec = bal + _float_pnl(u, e, sw, k, cbid)
        eq_close[j] = Ec
        if stop_on_outcome:
            if breach_d or breach_m:
                outcome = -1
                out_day = j
                final_eq = worst
                break
            if killed:
                outcome = -2
                out_day = j
                final_eq = bal
                break
            if Ec >= base * (1 + target) and trade_days >= min_days:
                outcome = 1
                out_day = j
                final_eq = Ec
                break
        # ---------------- 7. swaps for the nights to the next bar ----------------
        if k > 0:
            cost = 0.0
            for i in range(k):
                s_ = u[i] * C[t] * rate[t] * nights[t]
                sw[i] -= s_
                cost += s_
            counts[7] += cost / base
            if k > counts[11]:
                counts[11] = k
        final_eq = Ec
    counts[5] = trade_days
    return eq_close, counts, outcome, out_day, final_eq


_B = dict(int_pct=0.03, guard_pct=0.0, guard_now=False, close_order=0, realize=0)
# name -> run_ledger keyword values.
DESIGNS = {
    # No internal daily stop, only the bot's -9% kill.
    "D0 no daily stop": {**_B, "ref_kind": 0},
    # The bot as built: risk.py's floor max(balance, equity at the day start) - 3%, LIFO reductions.
    "D1 bot: max(B,E)-3%, LIFO": {**_B, "ref_kind": 1},
    # Stop from day-start equity (what prop_sim2 assumed) plus a guard 0.5% before FTMO's floor.
    "D2 E-3% + guard B-4.5%": {**_B, "ref_kind": 2, "guard_pct": 0.045},
    # Only a guard mirroring FTMO's rule, 0.5% before it.
    "D3 guard B-4.5% only": {**_B, "ref_kind": 0, "guard_pct": 0.045},
    # Realise every ticket before midnight so that balance == equity (costs a spread a day).
    "D4 realise daily + 3%": {**_B, "ref_kind": 1, "realize": 1},
    # D1 with oldest-first reductions.
    "D5 FIFO + D1 stop": {**_B, "ref_kind": 1, "close_order": 1},
}

COMMON = dict(kill_pct=0.09, h=0.0001, slip=0.0005, min_change=0.02, max_exp=3.0, ftmo_daily=0.05, ftmo_max=0.10)


def run(bars: dict, x: np.ndarray, start: int, end: int, lev: float, design: dict, *, target=np.inf,
        min_days=4, stop_on_outcome=False, rebase=None, over_cont=False, **over):
    p = {**COMMON, **design, **over}
    if rebase is None:
        rebase = np.zeros(len(bars["C"]), dtype=np.bool_)
    cont = bars.get("cont")
    if cont is None or not over_cont:
        cont = np.zeros(len(bars["C"]), dtype=np.bool_)
    return run_ledger(bars["O"], bars["H"], bars["L"], bars["C"], x, bars["nights"], bars["rate"],
                      cont, start, end, lev, p["ref_kind"], p["int_pct"], p["guard_pct"], p["guard_now"],
                      p["kill_pct"], p["close_order"], p["realize"], target, min_days, stop_on_outcome,
                      rebase, p["h"], p["slip"], p["min_change"], p["max_exp"], p["ftmo_daily"], p["ftmo_max"])
