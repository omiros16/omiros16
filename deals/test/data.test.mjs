// Checks a freshly written latest.json before it is published.   node deals/test/data.test.mjs <data dir>
import { readFileSync } from 'node:fs';
import { normSettings, plan, baskets, alerts, savings, LEVERS } from '../app/js/core.js';

const dir = process.argv[2] || 'deals-data';
const data = JSON.parse(readFileSync(`${dir}/latest.json`, 'utf8'));
const problems = [];
const bad = (m) => problems.length < 40 && problems.push(m);

if (!data.snapshot?.date) bad('no snapshot date');
if (!(data.snapshot?.products > 5000)) bad(`snapshot has only ${data.snapshot?.products} products`);
let priced = 0;
for (const p of data.cfg.products) {
  if (!p.match) continue;
  const items = data.items[p.id] || [];
  if (items.length) priced++;
  for (const it of items) {
    for (const [c, x] of Object.entries(it.prices)) {
      if (!(x.u > 0 && x.u < 200 && isFinite(x.u))) bad(`${p.id} ${it.name} ${c}: unit price ${x.u}`);
      if (!(x.p > 0)) bad(`${p.id} ${it.name} ${c}: price ${x.p}`);
    }
  }
}
// a source change that breaks matching shows up as most products losing their prices
if (priced < data.cfg.products.filter((p) => p.match).length - 3) bad(`only ${priced} products found in the snapshot`);

// every combination of levers must give a finite plan
for (let mask = 0; mask < 1 << LEVERS.length; mask++) {
  const s = normSettings({ ...data.settings, levers: Object.fromEntries(LEVERS.map((k, i) => [k, !!(mask & (1 << i))])) });
  const pl = plan(data, s, data.today);
  if (!isFinite(pl.total) || pl.total <= 0) bad(`levers ${mask}: total ${pl.total}`);
  const b = baskets(data, s, data.today);
  if (!b.single.length) bad(`levers ${mask}: no shop basket`);
  if (!isFinite(savings(data, s, data.today).month)) bad(`levers ${mask}: savings`);
  alerts(data, s, data.today);
}

if (problems.length) {
  console.error(problems.join('\n'));
  process.exit(1);
}
console.log(`data ok: snapshot ${data.snapshot.date}, ${priced} products priced`);
