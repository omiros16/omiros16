// Checks the model on a simulated league where the true strengths are known.
//   node football/test/model.test.mjs
import assert from 'node:assert/strict';
import { F, fitLeague, scoreMatrix, outcome1x2, htft, squad, absenceEffect, playerMarkets, expectedGoals, expectedStat } from '../js/model.js';
import { buildMarkets, fairOdds, evAt } from '../js/markets.js';
import { prepare, runLeague, score } from '../scripts/backtest.mjs';

let seed = 7;
const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
const pois = (l) => { let k = 0, p = Math.exp(-l), s = p; const u = rnd(); while (u > s && k < 30) { k++; p *= l / k; s += p; } return k; };
const NT = 20;
const att = Array.from({ length: NT }, () => Math.exp((rnd() - 0.5) * 0.8));
const def = Array.from({ length: NT }, () => Math.exp((rnd() - 0.5) * 0.6));
const cor = Array.from({ length: NT }, () => Math.exp((rnd() - 0.5) * 0.5));
const matches = [], season = [], odds = [];
const start = 20000;
for (let s = 0; s < 3; s++) {
  let d = start + s * 365;
  for (let r = 0; r < 2; r++) for (let h = 0; h < NT; h++) for (let a = 0; a < NT; a++) {
    if (h === a || (r === 1) !== ((h + a) % 2 === 0)) continue;
    d += 0.9;
    const lh = 1.5 * att[h] * def[a], la = 1.15 * att[a] * def[h];
    const hg = pois(lh), ag = pois(la);
    let hhg = 0, hag = 0;
    for (let k = 0; k < hg; k++) if (rnd() < 0.45) hhg++;
    for (let k = 0; k < ag; k++) if (rnd() < 0.45) hag++;
    const hst = pois(lh * 3.3), ast = pois(la * 3.3);
    matches.push([Math.floor(d), h, a, hg, ag, hhg, hag, hst + pois(8), ast + pois(6), hst, ast, pois(11), pois(12), pois(5.4 * cor[h]), pois(4.4 * cor[a]), pois(1.8), pois(2.1), rnd() < 0.05 ? 1 : 0, rnd() < 0.06 ? 1 : 0, null, null, null]);
    season.push('S' + s);
    odds.push(null);
  }
}
matches.sort((x, y) => x[0] - y[0]);
const ref = matches[matches.length - 1][F.D] + 1;

// 1. fitted strengths track the true ones
const fit = fitLeague(matches, NT, ref, { halfLife: 400, wxg: 0, k: 4 });
const corr = (x, y) => {
  const mx = x.reduce((a, b) => a + b) / x.length, my = y.reduce((a, b) => a + b) / y.length;
  let sxy = 0, sx = 0, sy = 0;
  x.forEach((v, i) => { sxy += (v - mx) * (y[i] - my); sx += (v - mx) ** 2; sy += (y[i] - my) ** 2; });
  return sxy / Math.sqrt(sx * sy);
};
const ca = corr(fit.goals.att.map(Math.log), att.map(Math.log)), cd = corr(fit.goals.def.map(Math.log), def.map(Math.log));
const cc = corr(fit.stats.corners.att.map(Math.log), cor.map(Math.log));
console.log(`attack r=${ca.toFixed(3)} defence r=${cd.toFixed(3)} corners r=${cc.toFixed(3)} rho=${fit.rho.toFixed(3)} ht=${fit.ht.toFixed(3)} home=${fit.goals.bh.toFixed(2)}/${fit.goals.ba.toFixed(2)}`);
assert.ok(ca > 0.85 && cd > 0.75 && cc > 0.7, 'ratings should recover the simulated strengths');
assert.ok(Math.abs(fit.ht - 0.45) < 0.04);

// 2. probability tables are consistent
const M = scoreMatrix(1.7, 1.1, fit.rho);
const p = outcome1x2(M);
assert.ok(Math.abs(p[0] + p[1] + p[2] - 1) < 1e-9);
const hf = htft(1.7, 1.1, 0.45, M);
const sumT = hf.T.flat().reduce((a, b) => a + b);
assert.ok(Math.abs(sumT - 1) < 1e-3, 'HT/FT sums to 1');
for (let c = 0; c < 3; c++) assert.ok(Math.abs(hf.T[0][c] + hf.T[1][c] + hf.T[2][c] - p[c]) < 1e-6, 'HT/FT margins match FT');

// 3. markets: complementary pairs, sane fair odds
const [lh, la] = expectedGoals(fit, 0, 1);
const stats = {};
for (const k of ['corners', 'yellows', 'reds', 'shots', 'sot', 'fouls']) stats[k] = expectedStat(fit, k, 0, 1);
const players = [
  ['Striker A', 0, 'F', 10, 850, 7, 6.1, 1, 1.2, 30, 1, 0],
  ['Winger A', 0, 'M', 10, 700, 2, 2.4, 3, 2.2, 18, 2, 0],
  ['Back A', 0, 'D', 10, 900, 0, 0.4, 1, 0.5, 4, 3, 0],
  ['Keeper A', 0, 'G', 10, 900, 0, 0, 0, 0, 0, 0, 0],
];
const sq = squad(players, 0, 10);
const before = absenceEffect(sq);
sq[0].avail = 0;
const after = absenceEffect(sq);
assert.equal(before.att, 1);
assert.ok(after.att < 0.95 && after.att >= 0.75, `striker out lowers attack (${after.att})`);
const pm = playerMarkets(sq, lh, lh + la, stats.yellows.mh);
assert.ok(!pm.find((x) => x.name === 'Striker A'));
const G = buildMarkets({ M: scoreMatrix(lh, la, fit.rho), lh, la, ht: fit.ht, names: ['Home', 'Away'], stats, playersH: playerMarkets(squad(players, 0, 10), lh, lh + la, stats.yellows.mh), playersA: [] });
const g = (id) => G.find((x) => x.id === id);
assert.ok(Math.abs(g('1x2').items.reduce((s, i) => s + i.pw, 0) - 1) < 1e-9);
assert.ok(Math.abs(g('htft').items.reduce((s, i) => s + i.pw, 0) - 1) < 1e-3);
assert.ok(Math.abs(g('exact').items.reduce((s, i) => s + i.pw, 0) - 1) < 1e-6);
assert.ok(Math.abs(g('timing').items.slice(0, 7).reduce((s, i) => s + i.pw, 0) - 1) < 1e-9);
assert.ok(Math.abs(g('first').items.reduce((s, i) => s + i.pw, 0) - 1) < 1e-9);
for (const it of g('ah').items) assert.ok(it.pw >= 0 && it.pl >= 0 && it.pw + it.pl <= 1 + 1e-9);
for (const grp of G) for (const it of grp.items) assert.ok(it.pw >= -1e-12 && it.pw <= 1 + 1e-9, `${grp.id} ${it.l} ${it.pw}`);
const it = g('1x2').items[0];
assert.ok(Math.abs(evAt(it, fairOdds(it))) < 1e-9, 'EV at fair odds is zero');
console.log(`markets: ${G.length} groups, ${G.reduce((s, x) => s + x.items.length, 0)} selections`);

// 4. walk-forward backtest beats a coin flip on simulated data
const bt = prepare({ id: 'SIM', matches, nTeams: NT, season, odds });
const t0 = Date.now();
const rows = runLeague(bt, [{ halfLife: 240, wxg: 0, k: 4 }, { halfLife: 90, wxg: 0, k: 4 }], ref, [0, 1]);
const s0 = score(bt, rows[0][0]);
console.log(`backtest ${Date.now() - t0}ms: n=${s0.n} ll=${s0.ll.toFixed(4)} acc=${(s0.acc * 100).toFixed(1)}% (ln3=${Math.log(3).toFixed(4)})`);
assert.ok(s0.n > 300 && s0.ll < Math.log(3) - 0.03);
console.log('all model checks passed');
