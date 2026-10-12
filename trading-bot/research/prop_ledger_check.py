"""Independent re-implementation of prop_ledger.run_ledger (tickets as dicts, runner-style loop).

Used by test_lab.py to check that the fast simulator does what the docstring says.
"""

from __future__ import annotations

import numpy as np


def ref_run(a, x, start, end, lev, ref_kind, guard_pct, close_order, realize, guard_now=False, int_pct=0.03, kill_pct=0.09,
            h=1e-4, slip=5e-4, min_change=0.02, cont=None, rebase=None, stop_on_outcome=False, target=np.inf,
            min_days=4):
    """Returns (equity at each close, internal stops, guard stops, kills, FTMO daily breaches, outcome, outcome bar).

    cont[t]: the market traded through the night before bar t, so a stop fills at its level.
    rebase[t]: at the boundary of bar t the account restarts at the current equity (kill reset).
    stop_on_outcome: stop at the first pass (equity >= base*(1+target) after min_days trading
    days, outcome 1), FTMO breach (-1) or kill (-2).
    """
    O, L, C, nights, rate = a["O"], a["L"], a["C"], a["nights"], a["rate"]
    tickets = []  # dicts: units, entry, swap, seq
    bal, base, killed, seq = 1.0, 1.0, False, 0
    eqs, stops, guards, kills, ftmo = [], 0, 0, 0, 0
    trade_days, outcome, out_bar = 0, 0, -1

    def fpnl(bid):
        return sum(tk["units"] * (bid - tk["entry"]) + tk["swap"] for tk in tickets)

    def price_at(level):
        """Bid at which equity == level."""
        tot = sum(tk["units"] for tk in tickets)
        return (level - bal - sum(tk["swap"] for tk in tickets) + sum(tk["units"] * tk["entry"] for tk in tickets)) / tot

    for t in range(start, end):
        first = t == start
        traded = False
        if not first and realize == 1 and tickets:
            tot = sum(tk["units"] for tk in tickets)
            bal += fpnl(C[t - 1] * (1 - h))
            seq += 1
            tickets = [{"units": tot, "entry": C[t - 1] * (1 + h), "swap": 0.0, "seq": seq}]
            traded = True
        if not first and realize == 3:
            for tk in tickets:
                pl = tk["units"] * (C[t - 1] * (1 - h) - tk["entry"]) + tk["swap"]
                if pl < 0:
                    bal += pl
                    tk["entry"], tk["swap"] = C[t - 1] * (1 + h), 0.0
                    traded = True
        e0 = bal + (fpnl(C[t - 1] * (1 - h)) if not first else 0.0)
        if rebase is not None and rebase[t] and not first:
            base, killed = e0, False
        b0 = bal
        floors = []
        if ref_kind == 1:
            floors.append((max(b0, e0) - int_pct * base, "int"))
        elif ref_kind == 2:
            floors.append((e0 - int_pct * base, "int"))
        if guard_pct > 0:
            floors.append((b0 - guard_pct * base, "guard"))
        fday = max([f for f, _ in floors], default=-1e18)
        fkill = base * (1 - kill_pct)
        fftmo = b0 - 0.05 * base
        fmax = base * 0.90
        paused = False
        worst = e0

        def flatten(price):
            nonlocal bal, tickets
            bal += fpnl(price)
            tickets = []

        if tickets:
            eo = bal + fpnl(O[t] * (1 - h))
            etr = min(e0, eo)
            if not killed and (etr <= fkill or etr <= fday):
                fl0 = max(fday, fkill)
                if cont is not None and cont[t] and e0 > fl0:  # the stop filled overnight at its level
                    hit_kill = fkill >= fday
                    flatten(price_at(fl0) * (1 - slip))
                elif cont is not None and cont[t]:  # already through the floor at the last close
                    hit_kill = e0 <= fkill
                    flatten(C[t - 1] * (1 - h) * (1 - slip))
                else:
                    hit_kill = etr <= fkill
                    flatten(O[t] * (1 - h) * (1 - slip))
                traded = True
                if hit_kill:
                    killed, kills = True, kills + 1
                else:
                    kind = max(floors)[1]
                    stops += kind == "int"
                    guards += kind == "guard"
                paused = True
                worst = min(worst, bal)
            else:
                worst = min(worst, eo)
        if not killed and t > 0:
            bid, ask = O[t] * (1 - h), O[t] * (1 + h)
            eo = bal + fpnl(bid)
            tgt = min(lev * x[t - 1], 3.0) * eo / ask
            if realize == 2:
                if tickets:
                    flatten(bid)
                    traded = True
                if tgt > 0 and not paused:
                    seq += 1
                    tickets = [{"units": tgt, "entry": ask, "swap": 0.0, "seq": seq}]
                    traded = True
            else:
                cur = sum(tk["units"] for tk in tickets)
                diff = tgt - cur
                if abs(diff) >= min_change * eo / ask - 1e-12 or (tgt == 0 and cur > 0):
                    if diff > 0 and not paused:
                        seq += 1
                        tickets.append({"units": diff, "entry": ask, "swap": 0.0, "seq": seq})
                        traded = True
                    elif diff < 0:
                        traded = True
                        q = -diff
                        if close_order == 2:
                            order = sorted(tickets, key=lambda d: (-d["entry"], d["seq"]))
                        else:
                            order = sorted(tickets, key=lambda d: d["seq"], reverse=close_order == 0)
                        for tk in order:
                            if q <= 1e-15:
                                break
                            c = min(tk["units"], q)
                            share = c / tk["units"]
                            bal += c * (bid - tk["entry"]) + tk["swap"] * share
                            tk["swap"] *= 1 - share
                            tk["units"] -= c
                            q -= c
                        tickets = [tk for tk in tickets if tk["units"] > 1e-15]
        if tickets:
            if guard_now and guard_pct > 0:
                floors = [(f, n) for f, n in floors if n != "guard"] + [(max(b0, bal) - guard_pct * base, "guard")]
                fday = max(f for f, _ in floors)
            el = bal + fpnl(L[t] * (1 - h))
            fl = max(fday, fkill)
            if not killed and el <= fl:
                flatten(price_at(fl) * (1 - slip))
                traded = True
                if fkill >= fday:
                    killed, kills = True, kills + 1
                else:
                    kind = max(floors)[1]
                    stops += kind == "int"
                    guards += kind == "guard"
                worst = min(worst, bal)
            else:
                worst = min(worst, el)
        breach = worst <= fftmo + 1e-12
        ftmo += breach
        trade_days += traded
        ec = bal + fpnl(C[t] * (1 - h))
        eqs.append(ec)
        if stop_on_outcome:
            if breach or worst <= fmax + 1e-12:
                outcome = -1
            elif killed:
                outcome = -2
            elif ec >= base * (1 + target) and trade_days >= min_days:
                outcome = 1
            if outcome:
                out_bar = t - start
                break
        for tk in tickets:
            tk["swap"] -= tk["units"] * C[t] * rate[t] * nights[t]
    return np.array(eqs), stops, guards, kills, ftmo, outcome, out_bar
