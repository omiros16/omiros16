// Everything the app shows for one match, computed from a league data file.
import { F, scoreMatrix, outcome1x2, h2hAdjust, expectedGoals, expectedStat, squad, absenceEffect, playerMarkets } from './model.js';
import { buildMarkets } from './markets.js';

const num = (v) => v !== null && v !== undefined;

export function seasonGames(lg, t) {
  return lg.m.filter((m) => m[F.D] >= lg.seasonStart && (m[F.H] === t || m[F.A] === t)).length;
}

// Availability of each squad player: published absences, then the user's own changes.
export function availability(lg, team, overrides = {}) {
  const out = new Map();
  for (const [t, name, type, , , chance] of lg.inj || []) {
    if (t !== team) continue;
    out.set(name, type === 'd' ? (chance ?? 50) / 100 : Math.max(0, Math.min(1, (chance ?? 0) / 100)));
  }
  for (const [name, v] of Object.entries(overrides)) out.set(name, v);
  return out;
}

export function meetingsOf(lg, h, a) {
  const all = [...(lg.old || []), ...lg.m].filter((m) => (m[F.H] === h && m[F.A] === a) || (m[F.H] === a && m[F.A] === h));
  return all.sort((x, y) => x[F.D] - y[F.D]);
}

export function analyze(lg, h, a, opts = {}) {
  const fit = lg.fit;
  const params = opts.params || {};
  const [lh0, la0] = expectedGoals(fit, h, a);

  const squads = [h, a].map((t, side) => {
    const sq = squad(lg.players || [], t, Math.max(1, seasonGames(lg, t)));
    const av = availability(lg, t, (opts.overrides || {})[side] || {});
    for (const p of sq) if (av.has(p.name)) p.avail = av.get(p.name);
    return sq;
  });
  const eff = squads.map((sq) => absenceEffect(sq));
  const lh1 = lh0 * eff[0].att * eff[1].def, la1 = la0 * eff[1].att * eff[0].def;

  const meetings = meetingsOf(lg, h, a);
  const homeAdv = fit.goals.bh - fit.goals.ba;
  const adj = h2hAdjust(meetings.slice(-8), h, a, lh1, la1, homeAdv, params.h2h ?? 0);
  const lh = lh1 * adj.fh, la = la1 * adj.fa;
  const M = scoreMatrix(lh, la, fit.rho);

  const stats = {};
  for (const k of ['corners', 'yellows', 'reds', 'shots', 'sot', 'fouls']) {
    const s = expectedStat(fit, k, h, a);
    if (s) stats[k] = s;
  }
  let refFactor = 1;
  if (opts.referee && fit.refs && fit.refs[opts.referee]) refFactor = fit.refs[opts.referee].f;
  if (stats.yellows && refFactor !== 1) stats.yellows = { ...stats.yellows, mh: stats.yellows.mh * refFactor, ma: stats.yellows.ma * refFactor };

  const hasPlayers = squads.some((s) => s.length > 3);
  const playersH = hasPlayers ? playerMarkets(squads[0], lh, lh + la, stats.yellows?.mh) : [];
  const playersA = hasPlayers ? playerMarkets(squads[1], la, lh + la, stats.yellows?.ma) : [];
  const names = [lg.teams[h], lg.teams[a]];
  const markets = buildMarkets({ M, lh, la, ht: fit.ht, names, stats, playersH, playersA });

  const n = fit.goals.n || [];
  const warnings = [];
  if ((n[h] ?? 0) < 6 || (n[a] ?? 0) < 6) warnings.push('Λίγοι πρόσφατοι αγώνες για μία από τις ομάδες: οι πιθανότητες είναι λιγότερο σίγουρες.');
  for (const t of [h, a]) if ((lg.promoted || []).includes(lg.teams[t])) warnings.push(`Η ${lg.teams[t]} είναι νεοφώτιστη: ξεκίνησε με πιο αδύναμη εκτίμηση και προσαρμόζεται όσο παίζει.`);

  return {
    h, a, names, lh, la, lh0, la0, M, p: outcome1x2(M), markets, stats, eff, squads, playersH, playersA,
    meetings, h2h: adj, refFactor, warnings,
  };
}

// Recent results for a team: [{ d, home, opp, gf, ga, res, xgf, xga, cf, ca, yf, ya, sf, sa }]
export function teamForm(lg, t, venue = 'all', limit = 6) {
  const rows = [];
  for (let i = lg.m.length - 1; i >= 0 && rows.length < limit; i--) {
    const m = lg.m[i];
    const home = m[F.H] === t, away = m[F.A] === t;
    if (!home && !away) continue;
    if ((venue === 'home' && !home) || (venue === 'away' && !away)) continue;
    const s = (hi, ai) => (home ? [m[hi], m[ai]] : [m[ai], m[hi]]);
    const [gf, ga] = s(F.HG, F.AG), [xgf, xga] = s(F.HXG, F.AXG), [cf, ca] = s(F.HC, F.AC), [yf, ya] = s(F.HY, F.AY), [sf, sa] = s(F.HST, F.AST);
    rows.push({ d: m[F.D], home, opp: lg.teams[home ? m[F.A] : m[F.H]], gf, ga, res: gf > ga ? 'W' : gf === ga ? 'D' : 'L', xgf, xga, cf, ca, yf, ya, sf, sa });
  }
  return rows;
}

export function averages(rows) {
  const avg = (k) => { const v = rows.map((r) => r[k]).filter(num); return v.length ? v.reduce((s, x) => s + x, 0) / v.length : null; };
  return { gf: avg('gf'), ga: avg('ga'), xgf: avg('xgf'), xga: avg('xga'), cf: avg('cf'), ca: avg('ca'), yf: avg('yf'), sf: avg('sf'), n: rows.length,
    btts: rows.length ? rows.filter((r) => r.gf > 0 && r.ga > 0).length / rows.length : null,
    o25: rows.length ? rows.filter((r) => r.gf + r.ga > 2.5).length / rows.length : null,
    pts: rows.reduce((s, r) => s + (r.res === 'W' ? 3 : r.res === 'D' ? 1 : 0), 0) };
}

export function table(lg) {
  const T = new Map(lg.cur.map((t) => [t, { t, p: 0, w: 0, d: 0, l: 0, gf: 0, ga: 0, pts: 0 }]));
  for (const m of lg.m) {
    if (m[F.D] < lg.seasonStart) continue;
    const h = T.get(m[F.H]), a = T.get(m[F.A]);
    if (!h || !a) continue;
    h.p++; a.p++; h.gf += m[F.HG]; h.ga += m[F.AG]; a.gf += m[F.AG]; a.ga += m[F.HG];
    if (m[F.HG] > m[F.AG]) { h.w++; a.l++; h.pts += 3; } else if (m[F.HG] < m[F.AG]) { a.w++; h.l++; a.pts += 3; } else { h.d++; a.d++; h.pts++; a.pts++; }
  }
  return [...T.values()].sort((x, y) => y.pts - x.pts || (y.gf - y.ga) - (x.gf - x.ga) || y.gf - x.gf);
}
