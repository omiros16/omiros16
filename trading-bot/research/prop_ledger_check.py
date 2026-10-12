"""Independent re-implementation of prop_ledger.run_ledger (tickets as dicts, runner-style loop).

Used by test_lab.py to check that the fast simulator does what the docstring says.
"""

from __future__ import annotations

import numpy as np


def ref_run(a, x, start, end, lev, ref_kind, guard_pct, close_order, realize, guard_now=False, int_pct=0.03, kill_pct=0.09,
            h=1e-4, slip=5e-4, min_change=0.02):
    O, L, C, nights, rate = a["O"], a["L"], a["C"], a["nights"], a["rate"]
    tickets = []  # dicts: units, entry, swap, seq
    bal, base, killed, seq = 1.0, 1.0, False, 0
    eqs, stops, guards, kills, ftmo = [], 0, 0, 0, 0

    def fpnl(bid):
        return sum(tk["units"] * (bid - tk["entry"]) + tk["swap"] for tk in tickets)

    for t in range(start, end):
        first = t == start
        if not first and realize == 1 and tickets:
            tot = sum(tk["units"] for tk in tickets)
            bal += fpnl(C[t - 1] * (1 - h))
            seq += 1
            tickets = [{"units": tot, "entry": C[t - 1] * (1 + h), "swap": 0.0, "seq": seq}]
        if not first and realize == 3:
            for tk in tickets:
                pl = tk["units"] * (C[t - 1] * (1 - h) - tk["entry"]) + tk["swap"]
                if pl < 0:
                    bal += pl
                    tk["entry"], tk["swap"] = C[t - 1] * (1 + h), 0.0
        e0 = bal + (fpnl(C[t - 1] * (1 - h)) if not first else 0.0)
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
                flatten(O[t] * (1 - h) * (1 - slip))
                if etr <= fkill:
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
                if tgt > 0 and not paused:
                    seq += 1
                    tickets = [{"units": tgt, "entry": ask, "swap": 0.0, "seq": seq}]
            else:
                cur = sum(tk["units"] for tk in tickets)
                diff = tgt - cur
                if abs(diff) >= min_change * eo / ask - 1e-12 or (tgt == 0 and cur > 0):
                    if diff > 0 and not paused:
                        seq += 1
                        tickets.append({"units": diff, "entry": ask, "swap": 0.0, "seq": seq})
                    elif diff < 0:
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
                tot = sum(tk["units"] for tk in tickets)
                # solve for the bid price where equity == fl
                pb = (fl - bal - sum(tk["swap"] for tk in tickets) + sum(tk["units"] * tk["entry"] for tk in tickets)) / tot
                flatten(pb * (1 - slip))
                if fkill >= fday:
                    killed, kills = True, kills + 1
                else:
                    kind = max(floors)[1]
                    stops += kind == "int"
                    guards += kind == "guard"
                worst = min(worst, bal)
            else:
                worst = min(worst, el)
        ftmo += worst <= fftmo + 1e-12
        eqs.append(bal + fpnl(C[t] * (1 - h)))
        for tk in tickets:
            tk["swap"] -= tk["units"] * C[t] * rate[t] * nights[t]
    return np.array(eqs), stops, guards, kills, ftmo
