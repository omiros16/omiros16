// Prediction model shared by the app (browser) and the weekly data build (Node).
// Goals: time-weighted Poisson ratings (attack/defence) with Dixon-Coles low-score correction.
// Corners, cards, shots, fouls: the same multiplicative ratings with negative-binomial counts.

// Match tuple layout used in the data files (null = not recorded).
export const F = {
  D: 0, H: 1, A: 2, HG: 3, AG: 4, HHG: 5, HAG: 6, HS: 7, AS: 8, HST: 9, AST: 10,
  HF: 11, AF: 12, HC: 13, AC: 14, HY: 15, AY: 16, HR: 17, AR: 18, HXG: 19, AXG: 20, REF: 21,
};

export const STATS = {
  corners: [F.HC, F.AC],
  yellows: [F.HY, F.AY],
  reds: [F.HR, F.AR],
  shots: [F.HS, F.AS],
  sot: [F.HST, F.AST],
  fouls: [F.HF, F.AF],
};

const LN2 = Math.LN2;
const num = (v) => v !== null && v !== undefined && !Number.isNaN(v);

export function weightFn(refDay, halfLife) {
  return (d) => Math.exp((-LN2 * (refDay - d)) / halfLife);
}

// Least squares goals ~ a*SOT + b*(shots - SOT): turns shot counts into an xG-like number
// for matches where real xG is missing.
export function fitShotProxy(matches) {
  let s11 = 0, s12 = 0, s22 = 0, t1 = 0, t2 = 0;
  for (const m of matches) {
    for (const [g, s, st] of [[m[F.HG], m[F.HS], m[F.HST]], [m[F.AG], m[F.AS], m[F.AST]]]) {
      if (!num(g) || !num(s) || !num(st)) continue;
      const x1 = st, x2 = Math.max(0, s - st);
      s11 += x1 * x1; s12 += x1 * x2; s22 += x2 * x2; t1 += x1 * g; t2 += x2 * g;
    }
  }
  const det = s11 * s22 - s12 * s12;
  if (det <= 0) return { a: 0.3, b: 0.03 };
  const a = (t1 * s22 - t2 * s12) / det, b = (s11 * t2 - s12 * t1) / det;
  if (!(a > 0.1 && a < 0.6) || !(b >= 0 && b < 0.1)) return { a: 0.3, b: 0.03 };
  return { a, b };
}

// Goal target for one side: blend of real goals and xG (or the shot proxy).
export function goalTarget(m, side, wxg, proxy) {
  const g = side === 0 ? m[F.HG] : m[F.AG];
  if (!wxg) return g;
  let x = side === 0 ? m[F.HXG] : m[F.AXG];
  if (!num(x)) {
    const s = side === 0 ? m[F.HS] : m[F.AS], st = side === 0 ? m[F.HST] : m[F.AST];
    if (num(s) && num(st)) x = proxy.a * st + proxy.b * Math.max(0, s - st);
    else return g;
  }
  return (1 - wxg) * g + wxg * x;
}

// Generic multiplicative rating fit.
// E[home value] = bh * att[home] * def[away];  E[away value] = ba * att[away] * def[home].
// Each team starts from k pseudo-matches at its prior (att0/def0), so thin samples shrink to it.
export function fitRatings(matches, nTeams, getH, getA, w, opt = {}) {
  const k = opt.k ?? 4, iters = opt.iters ?? 40;
  const att0 = opt.att0 || null, def0 = opt.def0 || null;
  const rows = [];
  let sw = 0, sh = 0, sa = 0;
  for (const m of matches) {
    const yh = getH(m), ya = getA(m);
    if (!num(yh) || !num(ya)) continue;
    const wt = w(m[F.D]);
    rows.push([m[F.H], m[F.A], yh, ya, wt]);
    sw += wt; sh += wt * yh; sa += wt * ya;
  }
  const att = new Float64Array(nTeams).fill(1), def = new Float64Array(nTeams).fill(1);
  const n = new Float64Array(nTeams);
  if (!rows.length || sw === 0) return { bh: NaN, ba: NaN, att, def, n, rows: 0 };
  let bh = sh / sw, ba = sa / sw;
  for (const [h, a, , , wt] of rows) { n[h] += wt; n[a] += wt; }
  const na = new Float64Array(nTeams), da = new Float64Array(nTeams);
  for (let it = 0; it < iters; it++) {
    const e = (bh + ba) / 2;
    for (let i = 0; i < nTeams; i++) { na[i] = k * e * (att0 ? att0[i] : 1); da[i] = k * e; }
    for (const [h, a, yh, ya, wt] of rows) {
      na[h] += wt * yh; da[h] += wt * bh * def[a];
      na[a] += wt * ya; da[a] += wt * ba * def[h];
    }
    for (let i = 0; i < nTeams; i++) att[i] = na[i] / da[i];
    for (let i = 0; i < nTeams; i++) { na[i] = k * e * (def0 ? def0[i] : 1); da[i] = k * e; }
    for (const [h, a, yh, ya, wt] of rows) {
      na[a] += wt * yh; da[a] += wt * bh * att[h];
      na[h] += wt * ya; da[h] += wt * ba * att[a];
    }
    for (let i = 0; i < nTeams; i++) def[i] = na[i] / da[i];
    let eh = 0, ea = 0;
    for (const [h, a, , , wt] of rows) { eh += wt * att[h] * def[a]; ea += wt * att[a] * def[h]; }
    bh = sh / eh; ba = sa / ea;
  }
  return { bh, ba, att, def, n, rows: rows.length };
}

// Negative-binomial size from residuals (Infinity = plain Poisson).
export function fitDispersion(matches, fit, getH, getA, w) {
  let s2 = 0, sm = 0, sw = 0;
  for (const m of matches) {
    const yh = getH(m), ya = getA(m);
    if (!num(yh) || !num(ya)) continue;
    const wt = w(m[F.D]);
    const mh = fit.bh * fit.att[m[F.H]] * fit.def[m[F.A]], ma = fit.ba * fit.att[m[F.A]] * fit.def[m[F.H]];
    s2 += wt * ((yh - mh) ** 2 - mh + (ya - ma) ** 2 - ma);
    sm += wt * (mh * mh + ma * ma);
    sw += wt;
  }
  if (sw === 0 || s2 <= 0) return Infinity;
  const r = sm / s2;
  return r > 200 ? Infinity : Math.max(r, 2);
}

// ---------------------------------------------------------------- distributions

export function poissonPmf(l, max) {
  const p = new Float64Array(max + 1);
  p[0] = Math.exp(-l);
  for (let k = 1; k <= max; k++) p[k] = (p[k - 1] * l) / k;
  return p;
}

export function nbPmf(mean, r, max) {
  if (!Number.isFinite(r)) return poissonPmf(mean, max);
  const p = new Float64Array(max + 1);
  const q = mean / (r + mean);
  p[0] = Math.pow(r / (r + mean), r);
  for (let k = 0; k < max; k++) p[k + 1] = (p[k] * (k + r) * q) / (k + 1);
  return p;
}

export function convolve(a, b) {
  const out = new Float64Array(a.length + b.length - 1);
  for (let i = 0; i < a.length; i++) for (let j = 0; j < b.length; j++) out[i + j] += a[i] * b[j];
  return out;
}

export function dcTau(i, j, l, m, rho) {
  if (i === 0 && j === 0) return 1 - l * m * rho;
  if (i === 0 && j === 1) return 1 + l * rho;
  if (i === 1 && j === 0) return 1 + m * rho;
  if (i === 1 && j === 1) return 1 - rho;
  return 1;
}

export const MAXG = 10;

export function scoreMatrix(lh, la, rho) {
  const ph = poissonPmf(lh, MAXG), pa = poissonPmf(la, MAXG);
  const M = [];
  let s = 0;
  for (let i = 0; i <= MAXG; i++) {
    M.push(new Float64Array(MAXG + 1));
    for (let j = 0; j <= MAXG; j++) {
      const v = Math.max(0, ph[i] * pa[j] * dcTau(i, j, lh, la, rho));
      M[i][j] = v; s += v;
    }
  }
  for (const row of M) for (let j = 0; j <= MAXG; j++) row[j] /= s;
  return M;
}

// Most likely rho for the low-score correction given the fitted means.
export function fitRho(matches, lambdas, w) {
  const obs = [];
  matches.forEach((m, idx) => {
    const g1 = m[F.HG], g2 = m[F.AG];
    if (g1 <= 1 && g2 <= 1) obs.push([g1, g2, lambdas[idx][0], lambdas[idx][1], w(m[F.D])]);
  });
  const ll = (rho) => {
    let s = 0;
    for (const [i, j, l, mu, wt] of obs) {
      const t = dcTau(i, j, l, mu, rho);
      if (t <= 0) return -Infinity;
      s += wt * Math.log(t);
    }
    return s;
  };
  let lo = -0.25, hi = 0.15;
  for (let it = 0; it < 50; it++) {
    const m1 = lo + (hi - lo) / 3, m2 = hi - (hi - lo) / 3;
    if (ll(m1) < ll(m2)) lo = m1; else hi = m2;
  }
  return (lo + hi) / 2;
}

// ---------------------------------------------------------------- league fit

// Goal ratings for one league as of refDay. `promoted` lists teams new to the league,
// which start from weaker ratings instead of the league average.
export function fitGoals(matches, nTeams, refDay, params, promoted = []) {
  const hl = params.halfLife ?? 180, wxg = params.wxg ?? 0.5, k = params.k ?? 4;
  const w = weightFn(refDay, hl);
  const past = matches.filter((m) => m[F.D] < refDay && num(m[F.HG]) && num(m[F.AG]));
  const proxy = fitShotProxy(past);
  const att0 = new Float64Array(nTeams).fill(1), def0 = new Float64Array(nTeams).fill(1);
  for (const t of promoted) { att0[t] = 0.82; def0[t] = 1.18; }
  const goals = fitRatings(past, nTeams, (m) => goalTarget(m, 0, wxg, proxy), (m) => goalTarget(m, 1, wxg, proxy), w, { k, att0, def0 });
  const lambdas = past.map((m) => [goals.bh * goals.att[m[F.H]] * goals.def[m[F.A]], goals.ba * goals.att[m[F.A]] * goals.def[m[F.H]]]);
  const rho = fitRho(past, lambdas, w);
  // Share of goals scored before half-time.
  let g1 = 0, gt = 0;
  for (const m of past) {
    if (!num(m[F.HHG]) || !num(m[F.HAG])) continue;
    const wt = w(m[F.D]);
    g1 += wt * (m[F.HHG] + m[F.HAG]); gt += wt * (m[F.HG] + m[F.AG]);
  }
  return { past, w, proxy, rho, ht: gt > 0 ? g1 / gt : 0.445, goals, params: { halfLife: hl, wxg, k } };
}

// Every rating for one league: goals plus corners, cards, shots and fouls, and referees.
export function fitLeague(matches, nTeams, refDay, params, promoted = []) {
  const { past, w, proxy, rho, ht, goals, params: used } = fitGoals(matches, nTeams, refDay, params, promoted);
  const stats = {};
  for (const [name, [ih, ia]] of Object.entries(STATS)) {
    const getH = (m) => m[ih], getA = (m) => m[ia];
    const kk = name === 'reds' ? 12 : params.statK?.[name] ?? params.statK?.sot ?? used.k;
    const fit = fitRatings(past, nTeams, getH, getA, w, { k: kk });
    if (fit.rows < 30 || !(fit.bh >= 0)) continue;
    stats[name] = { bh: fit.bh, ba: fit.ba, att: Array.from(fit.att), def: Array.from(fit.def), r: fitDispersion(past, fit, getH, getA, w) };
  }

  // Referee factor on yellow cards (only leagues that publish referees).
  let refs = null;
  if (stats.yellows && past.some((m) => num(m[F.REF]))) {
    const y = stats.yellows, acc = new Map();
    for (const m of past) {
      if (!num(m[F.REF]) || !num(m[F.HY]) || !num(m[F.AY])) continue;
      const e = y.bh * y.att[m[F.H]] * y.def[m[F.A]] + y.ba * y.att[m[F.A]] * y.def[m[F.H]];
      const wt = w(m[F.D]);
      const a = acc.get(m[F.REF]) || [0, 0, 0];
      a[0] += wt * (m[F.HY] + m[F.AY]); a[1] += wt * e; a[2] += 1;
      acc.set(m[F.REF], a);
    }
    refs = {};
    const kr = 5 * (y.bh + y.ba);
    for (const [r, [o, e, cnt]] of acc) refs[r] = { f: (o + kr) / (e + kr), n: cnt };
  }

  return {
    refDay, params: { ...used, statK: params.statK }, proxy, rho, ht,
    goals: { bh: goals.bh, ba: goals.ba, att: Array.from(goals.att), def: Array.from(goals.def), n: Array.from(goals.n) },
    stats, refs,
  };
}

// ---------------------------------------------------------------- head to head

// Small shift of both scoring rates towards how these two sides have done against each other
// (venue-adjusted goal difference and total goals), shrunk by the number of meetings.
export function h2hAdjust(meetings, home, away, lh, la, homeAdv, weight) {
  if (!weight || !meetings.length) return { fh: 1, fa: 1, n: meetings.length };
  let gd = 0, tot = 0, n = 0;
  for (const m of meetings) {
    const hg = m[F.HG], ag = m[F.AG];
    if (!num(hg) || !num(ag)) continue;
    const sign = m[F.H] === home ? 1 : -1;
    gd += sign * (hg - ag) - sign * homeAdv; // goal difference with the venue edge removed
    tot += hg + ag; n++;
  }
  if (!n) return { fh: 1, fa: 1, n: 0 };
  const shrink = n / (n + 8);
  const expGd = lh - la - homeAdv, expTot = lh + la;
  const dGd = (gd / n - expGd) * shrink * weight;
  const dTot = (tot / n / expTot - 1) * shrink * weight;
  const clamp = (x) => Math.max(0.9, Math.min(1.1, x));
  const fTot = clamp(1 + dTot);
  return { fh: clamp(fTot * Math.exp(dGd / (2 * expTot))), fa: clamp(fTot * Math.exp(-dGd / (2 * expTot))), n };
}

// ---------------------------------------------------------------- match prediction

export function expectedGoals(fit, h, a) {
  const g = fit.goals;
  return [g.bh * g.att[h] * g.def[a], g.ba * g.att[a] * g.def[h]];
}

export function expectedStat(fit, name, h, a) {
  const s = fit.stats[name];
  if (!s) return null;
  return { mh: s.bh * s.att[h] * s.def[a], ma: s.ba * s.att[a] * s.def[h], r: s.r };
}

export function outcome1x2(M) {
  let p1 = 0, px = 0, p2 = 0;
  for (let i = 0; i <= MAXG; i++) for (let j = 0; j <= MAXG; j++) {
    if (i > j) p1 += M[i][j]; else if (i === j) px += M[i][j]; else p2 += M[i][j];
  }
  return [p1, px, p2];
}

// Joint half-time / full-time table, rescaled so its full-time margins match the DC matrix.
export function htft(lh, la, ht, M) {
  const a = poissonPmf(lh * ht, MAXG), b = poissonPmf(la * ht, MAXG);
  const c = poissonPmf(lh * (1 - ht), MAXG), d = poissonPmf(la * (1 - ht), MAXG);
  const res = (x, y) => (x > y ? 0 : x === y ? 1 : 2);
  const T = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  const H2 = [0, 0, 0];
  const halves = { more1: 0, more2: 0, equal: 0 };
  const extra = { bothHalvesGoal: 0, hBoth: 0, aBoth: 0, hWinEither: 0, aWinEither: 0, hWinBoth: 0, aWinBoth: 0, bttsHT: 0, btts2H: 0 };
  const ht1 = [0, 0, 0];
  const htTot = new Float64Array(2 * MAXG + 1), h2Tot = new Float64Array(2 * MAXG + 1);
  for (let i = 0; i <= MAXG; i++) for (let j = 0; j <= MAXG; j++) {
    const p1 = a[i] * b[j];
    if (p1 < 1e-12) continue;
    const r1 = res(i, j);
    ht1[r1] += p1; htTot[i + j] += p1;
    for (let k = 0; k <= MAXG - i; k++) for (let l = 0; l <= MAXG - j; l++) {
      const p = p1 * c[k] * d[l];
      if (p < 1e-14) continue;
      const r2 = res(k, l);
      T[r1][res(i + k, j + l)] += p;
      H2[r2] += p; h2Tot[k + l] += p;
      const g1 = i + j, g2 = k + l;
      if (g1 > g2) halves.more1 += p; else if (g2 > g1) halves.more2 += p; else halves.equal += p;
      if (g1 > 0 && g2 > 0) extra.bothHalvesGoal += p;
      if (i > 0 && k > 0) extra.hBoth += p;
      if (j > 0 && l > 0) extra.aBoth += p;
      if (r1 === 0 || r2 === 0) extra.hWinEither += p;
      if (r1 === 2 || r2 === 2) extra.aWinEither += p;
      if (r1 === 0 && r2 === 0) extra.hWinBoth += p;
      if (r1 === 2 && r2 === 2) extra.aWinBoth += p;
      if (i > 0 && j > 0) extra.bttsHT += p;
      if (k > 0 && l > 0) extra.btts2H += p;
    }
  }
  const ft = outcome1x2(M);
  for (let c2 = 0; c2 < 3; c2++) {
    const s = T[0][c2] + T[1][c2] + T[2][c2];
    if (s > 0) for (let r = 0; r < 3; r++) T[r][c2] *= ft[c2] / s;
  }
  return { T, ht1, H2, halves, extra, htTot, h2Tot };
}

// Probability that the first goal comes in [t0, t1) minutes; scoring intensity is flat within
// each half, with the half-time share `ht` of the goals in the first 45'.
export function goalTiming(lt, ht) {
  const rate = (t) => (t <= 45 ? (lt * ht) / 45 : (lt * (1 - ht)) / 45);
  const cum = (t) => (t <= 45 ? rate(1) * t : lt * ht + rate(46) * (t - 45));
  const none = (t) => Math.exp(-cum(t));
  return { firstBefore: (t) => 1 - none(t), between: (t0, t1) => none(t0) - none(t1), none: none(90) };
}

// ---------------------------------------------------------------- players and absences

const POS_PRIOR = { F: 0.33, M: 0.11, D: 0.035, G: 0 };      // goals per 90 before evidence
const POS_APRIOR = { F: 0.14, M: 0.12, D: 0.05, G: 0.005 };  // assists per 90
const POS_YPRIOR = { F: 0.13, M: 0.2, D: 0.2, G: 0.05 };     // yellow cards per 90
const POS_DEF = { G: 0.07, D: 0.045, M: 0.02, F: 0.005 };    // extra goals conceded when a regular is out

// Player tuple: [name, team, pos, games, minutes, goals, xG|null, assists, xA|null, shots|null, yellows, reds]
export const P = { N: 0, T: 1, POS: 2, GP: 3, MIN: 4, G: 5, XG: 6, A: 7, XA: 8, SH: 9, Y: 10, R: 11 };

function rate90(evidence, minutes, prior, k) {
  const m90 = minutes / 90;
  return (evidence + k * prior) / (m90 + k);
}

// Per-player scoring/assist/card rates for one team, with each player's expected minutes.
export function squad(players, team, teamGames) {
  const out = [];
  for (const p of players) {
    if (p[P.T] !== team || !(p[P.MIN] > 0)) continue;
    const pos = (p[P.POS] || 'M')[0];
    const gEv = num(p[P.XG]) ? 0.7 * p[P.XG] + 0.3 * p[P.G] : p[P.G];
    const aEv = num(p[P.XA]) ? 0.7 * p[P.XA] + 0.3 * p[P.A] : p[P.A];
    const kg = num(p[P.XG]) ? 2.5 : 4;
    out.push({
      name: p[P.N], pos, games: p[P.GP], minutes: p[P.MIN], goals: p[P.G], assists: p[P.A], xg: p[P.XG],
      yellows: p[P.Y], reds: p[P.R],
      g90: rate90(gEv, p[P.MIN], POS_PRIOR[pos] ?? 0.1, kg),
      a90: rate90(aEv, p[P.MIN], POS_APRIOR[pos] ?? 0.1, kg),
      y90: rate90(p[P.Y] || 0, p[P.MIN], POS_YPRIOR[pos] ?? 0.15, 4),
      share: Math.min(1, p[P.MIN] / (90 * Math.max(teamGames, p[P.GP], 1))), // of the team's minutes
      perGame: Math.min(95, p[P.MIN] / Math.max(1, p[P.GP])),                 // minutes when he plays
      avail: 1,
    });
  }
  return out;
}

// Effect of missing players on a team's attack (multiplier on goals scored) and defence
// (multiplier on goals conceded). `avail` is each player's chance of playing (0..1).
export function absenceEffect(sq) {
  let massAll = 0, massLost = 0, defLost = 0;
  for (const p of sq) {
    const mass = (p.g90 + 0.5 * p.a90) * p.share;
    massAll += mass;
    const miss = 1 - p.avail;
    if (miss <= 0) continue;
    massLost += mass * miss;
    defLost += (POS_DEF[p.pos] ?? 0.02) * p.share * miss;
  }
  const attLoss = massAll > 0 ? (0.4 * massLost) / massAll : 0;
  return { att: Math.max(0.75, 1 - attLoss), def: Math.min(1.25, 1 + defLost), attLoss, defLost };
}

// Goalscorer, assist and booking probabilities for the players likely to feature.
// Probabilities assume the player takes part (bookmakers void the bet otherwise).
export function playerMarkets(sq, lTeam, lTotal, cardsTeam) {
  const live = sq.filter((p) => p.avail > 0);
  const gMass = live.reduce((s, p) => s + p.g90 * p.share * p.avail, 0);
  const aMass = live.reduce((s, p) => s + p.a90 * p.share * p.avail, 0);
  const yMass = live.reduce((s, p) => s + p.y90 * p.share * p.avail, 0);
  const goalsByPlayers = lTeam * 0.96; // the rest are own goals
  const assisted = lTeam * 0.72;
  const pAny = 1 - Math.exp(-lTotal);
  return live.map((p) => {
    const t = p.perGame / 90;
    const mg = gMass > 0 ? (goalsByPlayers * p.g90 * t) / gMass : 0;
    const ma = aMass > 0 ? (assisted * p.a90 * t) / aMass : 0;
    const my = yMass > 0 && cardsTeam ? (cardsTeam * p.y90 * t) / yMass : 0;
    return {
      ...p, mg, ma,
      anytime: 1 - Math.exp(-mg),
      first: lTotal > 0 ? (mg / lTotal) * pAny : 0,
      brace: 1 - Math.exp(-mg) * (1 + mg),
      hat: 1 - Math.exp(-mg) * (1 + mg + (mg * mg) / 2),
      assist: 1 - Math.exp(-ma),
      gOrA: 1 - Math.exp(-(mg + ma)),
      card: 1 - Math.exp(-my),
    };
  }).sort((x, y) => y.anytime - x.anytime);
}
