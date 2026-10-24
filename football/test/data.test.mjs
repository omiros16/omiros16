// Checks freshly built data: every pairing of current teams in every league must give
// finite, consistent probabilities in every market.   node football/test/data.test.mjs [dataDir]
import { readFileSync } from 'node:fs';
import { analyze, teamForm, table } from '../js/analyze.js';
import { fairOdds } from '../js/markets.js';

const dir = process.argv[2] || 'football/data';
const index = JSON.parse(readFileSync(`${dir}/index.json`, 'utf8'));
const problems = [];
const bad = (msg) => { if (problems.length < 60) problems.push(msg); };
let pairs = 0, selections = 0;
const t0 = Date.now();

for (const meta of index.leagues) {
  const lg = JSON.parse(readFileSync(`${dir}/leagues/${meta.id}.json`, 'utf8'));
  const where = (h, a) => `${lg.id} ${lg.teams[h]}-${lg.teams[a]}`;
  if (!lg.cur.length) bad(`${lg.id}: no current teams`);
  for (const t of lg.cur) { teamForm(lg, t); }
  if (table(lg).length !== lg.cur.length) bad(`${lg.id}: table size`);
  for (const h of lg.cur) for (const a of lg.cur) {
    if (h === a) continue;
    pairs++;
    let r;
    try { r = analyze(lg, h, a, { params: index.params }); } catch (e) { bad(`${where(h, a)}: ${e.stack.split('\n').slice(0, 2).join(' ')}`); continue; }
    if (!(r.lh > 0.05 && r.lh < 6 && r.la > 0.05 && r.la < 6)) bad(`${where(h, a)}: goal means ${r.lh} ${r.la}`);
    const s = r.p[0] + r.p[1] + r.p[2];
    if (Math.abs(s - 1) > 1e-6) bad(`${where(h, a)}: 1X2 sums to ${s}`);
    for (const g of r.markets) {
      if (!g.items.length && g.id !== 'ah') bad(`${where(h, a)}: empty group ${g.id}`);
      for (const it of g.items) {
        selections++;
        if (!(it.pw >= -1e-9 && it.pw <= 1 + 1e-9) || !(it.pl >= -1e-9 && it.pl <= 1 + 1e-9) || it.pw + it.pl > 1 + 1e-6) bad(`${where(h, a)}: ${g.id} "${it.l}" pw=${it.pw} pl=${it.pl}`);
        if (Number.isNaN(fairOdds(it))) bad(`${where(h, a)}: ${g.id} "${it.l}" fair odds NaN`);
        if (/undefined|NaN|null/.test(it.l + (it.sub || ''))) bad(`${where(h, a)}: ${g.id} label "${it.l}" ${it.sub || ''}`);
      }
    }
    for (const st of Object.values(r.stats)) if (!(st.mh >= 0 && st.ma >= 0)) bad(`${where(h, a)}: stat mean ${JSON.stringify(st)}`);
  }
}
console.log(`${pairs} pairings, ${selections} selections checked in ${((Date.now() - t0) / 1000).toFixed(1)}s`);
if (problems.length) { console.log(problems.join('\n')); process.exit(1); }
console.log('all data checks passed');
