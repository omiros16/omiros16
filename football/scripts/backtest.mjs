// Walk-forward test: refit every week on the matches before it, predict the next 7 days,
// and score the predictions against what happened and against the closing market odds.
import { F, fitGoals, scoreMatrix, outcome1x2, h2hAdjust, fitRatings, weightFn, nbPmf, convolve } from '../js/model.js';

const WINDOW = 500;     // days of history used for each fit
const TEST_DAYS = 330;  // how far back the test period starts

const res = (m) => (m[F.HG] > m[F.AG] ? 0 : m[F.HG] === m[F.AG] ? 1 : 2);
const implied = (o) => { const inv = o.map((x) => 1 / x), s = inv[0] + inv[1] + inv[2]; return inv.map((x) => x / s); };

// lg: { id, matches (sorted tuples), nTeams, season (array of season labels per match), odds (array of {x12:[h,d,a], ou:[o,u]} | null) }
export function prepare(lg) {
  const bySeason = new Map();
  lg.matches.forEach((m, i) => {
    const s = lg.season[i];
    if (!bySeason.has(s)) bySeason.set(s, { start: m[F.D], teams: new Set() });
    const b = bySeason.get(s);
    b.start = Math.min(b.start, m[F.D]); b.teams.add(m[F.H]); b.teams.add(m[F.A]);
  });
  const seasons = [...bySeason.entries()].sort((a, b) => a[1].start - b[1].start);
  const promotedIn = new Map();
  seasons.forEach(([s, b], i) => {
    const prev = i > 0 ? seasons[i - 1][1].teams : null;
    promotedIn.set(s, prev ? [...b.teams].filter((t) => !prev.has(t)) : []);
  });
  const pairs = new Map();
  lg.matches.forEach((m, i) => {
    const k = Math.min(m[F.H], m[F.A]) * 1000 + Math.max(m[F.H], m[F.A]);
    if (!pairs.has(k)) pairs.set(k, []);
    pairs.get(k).push(i);
  });
  return { ...lg, seasons, promotedIn, pairs };
}

export function meetings(lg, h, a, before) {
  const k = Math.min(h, a) * 1000 + Math.max(h, a);
  return (lg.pairs.get(k) || []).map((i) => lg.matches[i]).filter((m) => m[F.D] < before).slice(-8);
}

function seasonAt(lg, day) {
  let cur = lg.seasons[0][0];
  for (const [s, b] of lg.seasons) if (b.start <= day + 3) cur = s;
  return cur;
}

export function promotedAt(lg, day) {
  return lg.promotedIn.get(seasonAt(lg, day)) || [];
}

// Predicts every test match for each parameter set. Returns rows of per-match predictions.
export function runLeague(lg, grid, refDay, h2hWeights) {
  const start = refDay - TEST_DAYS;
  const rows = grid.map(() => h2hWeights.map(() => []));
  for (let s = start; s < refDay; s += 7) {
    const test = lg.matches.map((m, i) => [m, i]).filter(([m]) => m[F.D] >= s && m[F.D] < s + 7 && m[F.D] < refDay);
    if (!test.length) continue;
    const train = lg.matches.filter((m) => m[F.D] < s && m[F.D] >= s - WINDOW);
    if (train.length < 80) continue;
    const promoted = promotedAt(lg, s);
    grid.forEach((params, gi) => {
      const fit = fitGoals(train, lg.nTeams, s, params, promoted);
      const g = fit.goals;
      const homeAdv = (g.bh - g.ba);
      for (const [m, i] of test) {
        // a side with no matches in the window has no rating yet
        if (!(g.n[m[F.H]] > 0.5 && g.n[m[F.A]] > 0.5)) continue;
        const lh = g.bh * g.att[m[F.H]] * g.def[m[F.A]], la = g.ba * g.att[m[F.A]] * g.def[m[F.H]];
        h2hWeights.forEach((hw, hi) => {
          const adj = h2hAdjust(meetings(lg, m[F.H], m[F.A], m[F.D]), m[F.H], m[F.A], lh, la, homeAdv, hw);
          const M = scoreMatrix(lh * adj.fh, la * adj.fa, fit.rho);
          const p = outcome1x2(M);
          let o25 = 0;
          for (let x = 0; x <= 10; x++) for (let y = 0; y <= 10; y++) if (x + y > 2) o25 += M[x][y];
          rows[gi][hi].push({ i, p, o25, r: res(m), tot: m[F.HG] + m[F.AG] });
        });
      }
    });
  }
  return rows;
}

export function score(lg, rows) {
  let ll = 0, llM = 0, nM = 0, brier = 0, acc = 0, llOU = 0, llOUM = 0, nOU = 0;
  const bets = { n: 0, profit: 0, nOU: 0, profitOU: 0 };
  const cal = Array.from({ length: 10 }, () => [0, 0, 0]); // count, sum p, hits
  for (const x of rows) {
    const p = x.p;
    ll -= Math.log(Math.max(1e-9, p[x.r]));
    brier += p.reduce((s, q, k) => s + (q - (k === x.r ? 1 : 0)) ** 2, 0);
    if (p.indexOf(Math.max(...p)) === x.r) acc++;
    p.forEach((q, k) => { const b = cal[Math.min(9, Math.floor(q * 10))]; b[0]++; b[1] += q; b[2] += k === x.r ? 1 : 0; });
    const pou = x.tot > 2.5 ? x.o25 : 1 - x.o25;
    llOU -= Math.log(Math.max(1e-9, pou));
    const od = lg.odds[x.i];
    if (od && od.x12) {
      const q = implied(od.x12);
      llM -= Math.log(Math.max(1e-9, q[x.r])); nM++;
      for (let k = 0; k < 3; k++) {
        if (p[k] * od.x12[k] - 1 > 0.05) { bets.n++; bets.profit += k === x.r ? od.x12[k] - 1 : -1; }
      }
    }
    if (od && od.ou) {
      const s = 1 / od.ou[0] + 1 / od.ou[1], qo = 1 / od.ou[0] / s;
      llOUM -= Math.log(Math.max(1e-9, x.tot > 2.5 ? qo : 1 - qo)); nOU++;
      const po = [x.o25, 1 - x.o25], won = [x.tot > 2.5, x.tot < 2.5];
      for (let k = 0; k < 2; k++) if (po[k] * od.ou[k] - 1 > 0.05) { bets.nOU++; bets.profitOU += won[k] ? od.ou[k] - 1 : -1; }
    }
  }
  const n = rows.length || 1;
  return {
    n: rows.length, ll: ll / n, brier: brier / n, acc: acc / n, llOU: llOU / n,
    llMkt: nM ? llM / nM : null, nMkt: nM, llOUMkt: nOU ? llOUM / nOU : null,
    bets: bets.n, roi: bets.n ? bets.profit / bets.n : null, betsOU: bets.nOU, roiOU: bets.nOU ? bets.profitOU / bets.nOU : null,
    cal: cal.map(([c, sp, h]) => (c ? [c, +(sp / c).toFixed(3), +(h / c).toFixed(3)] : [0, 0, 0])),
  };
}

// Log-loss of over/under lines for corners and cards: model vs a league-average baseline.
export function statsCheck(lg, params, refDay) {
  const out = {};
  const specs = { corners: [F.HC, F.AC, 9.5], yellows: [F.HY, F.AY, 3.5] };
  for (const [name, [ih, ia, line]] of Object.entries(specs)) {
    let ll = 0, llB = 0, n = 0;
    for (let s = refDay - TEST_DAYS; s < refDay; s += 7) {
      const test = lg.matches.filter((m) => m[F.D] >= s && m[F.D] < s + 7 && m[F.D] < refDay && m[ih] !== null && m[ia] !== null);
      if (!test.length) continue;
      const train = lg.matches.filter((m) => m[F.D] < s && m[F.D] >= s - WINDOW && m[ih] !== null);
      if (train.length < 80) continue;
      const w = weightFn(s, params.halfLife);
      const fit = fitRatings(train, lg.nTeams, (m) => m[ih], (m) => m[ia], w, { k: name === 'yellows' ? 6 : params.k ?? 4 });
      let sw = 0, sum = 0;
      for (const m of train) { const wt = w(m[F.D]); sw += wt; sum += wt * (m[ih] + m[ia]); }
      const base = sum / sw;
      for (const m of test) {
        if (!(fit.n[m[F.H]] > 0.5 && fit.n[m[F.A]] > 0.5)) continue;
        const mh = fit.bh * fit.att[m[F.H]] * fit.def[m[F.A]], ma = fit.ba * fit.att[m[F.A]] * fit.def[m[F.H]];
        const pt = convolve(nbPmf(mh, Infinity, 40), nbPmf(ma, Infinity, 40));
        const pb = nbPmf(base, Infinity, 80);
        const over = (d) => d.reduce((s2, p, k) => s2 + (k > line ? p : 0), 0);
        const isOver = m[ih] + m[ia] > line;
        const po = over(pt), pbo = over(pb);
        ll -= Math.log(Math.max(1e-9, isOver ? po : 1 - po));
        llB -= Math.log(Math.max(1e-9, isOver ? pbo : 1 - pbo));
        n++;
      }
    }
    if (n) out[name] = { n, ll: +(ll / n).toFixed(4), llBase: +(llB / n).toFixed(4), line };
  }
  return out;
}
