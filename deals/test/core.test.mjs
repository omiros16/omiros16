// Checks the list logic on a small made-up data file.   node deals/test/core.test.mjs
import { readFileSync } from 'node:fs';
import assert from 'node:assert/strict';
import { normSettings, quantities, options, plan, baskets, savings, alerts, stats, series, addDays } from '../app/js/core.js';
import { effectiveQty, matches, compileRules, extract, norm } from '../scripts/lib/match.mjs';

const cfg = JSON.parse(readFileSync(new URL('../config/products.json', import.meta.url), 'utf8'));
const today = '2026-11-04';
let n = 0;
const test = (name, fn) => { fn(); n++; console.log(`ok ${name}`); };
const S = (levers = {}, rest = {}) => normSettings({ levers, ...rest });
const near = (a, b, eps = 1e-9) => assert.ok(Math.abs(a - b) < eps, `${a} != ${b}`);

const item = (id, name, pl, prices, extra = {}) => ({ id, name, brand: null, pl, size: 1, bonus: null, drop: null, prices, ...extra });
const px = (u, d = 0) => ({ p: u, u, d, at: today });
const data = {
  cfg, manual: [], hist: { dates: [], chains: [], p: {} },
  items: {
    chicken_thighs: [item('t1', 'Μπούτι', false, { lidl: px(4.6), sklavenitis: px(5.0) })],
    chicken_whole: [item('w1', 'Ολόκληρο', false, { ab_vasilopoulos: px(2.5) })],
    eggs: [item('e1', 'Αυγά PL', true, { lidl: px(0.2) }), item('e2', 'Αυγά', false, { lidl: px(0.3), sklavenitis: px(0.28) })],
    milk: [item('m1', 'Γάλα', false, { sklavenitis: px(1.2), lidl: px(1.3), masoutis: px(0.5) })],
    yogurt: [item('y1', 'Γιαούρτι', false, { halkiadakis: px(3.5) })],
    legumes: [item('l1', 'Φακές', false, { sklavenitis: px(2.0) })],
    rice: [item('r1', 'Ρύζι', false, { lidl: px(1.5) })],
  },
};

test('base quantities follow the list', () => {
  const q = quantities(data, S());
  assert.equal(q.yogurt, 3);
  assert.equal(q.chicken_thighs, 2.1);
  assert.equal(q.chicken_whole, 0);
  assert.equal(q.legumes, 0);
  near(q.rice, 0.5);
  near(q.bananas, 1.2);
});

test('yogurt cuts do not add up blindly and never go below 700 g', () => {
  near(quantities(data, S({ protein: true })).yogurt, 1.5);
  near(quantities(data, S({ legumes: true })).yogurt, 1.8);
  near(quantities(data, S({ protein: true, legumes: true })).yogurt, 1.0);
  const q = quantities(data, S({ legumes: true }));
  near(q.legumes, 0.5);
  near(q.rice, 0.8);
  near(q.bananas, 1.2 * 3 / 7);
});

test('private label only counts with its lever, and only for its products', () => {
  const eggs = cfg.products.find((p) => p.id === 'eggs');
  assert.equal(options(data, eggs, S(), today)[0].item, 'e2');
  assert.equal(options(data, eggs, S({ pl: true }), today)[0].item, 'e1');
});

test('chains outside Heraklion are ignored unless switched on', () => {
  const milk = cfg.products.find((p) => p.id === 'milk');
  assert.equal(options(data, milk, S(), today)[0].store, 'sklavenitis');
  assert.equal(options(data, milk, S({}, { chains: { masoutis: true } }), today)[0].store, 'masoutis');
});

test('whole chicken is chosen on clean-meat cost and counted once', () => {
  const pl = plan(data, S({ whole: true }), today);
  const w = pl.lines.find((l) => l.p.id === 'chicken_whole'), t = pl.lines.find((l) => l.p.id === 'chicken_thighs');
  // 2.5 / 0.55 = 4.55 per kg of meat against 4.6 / 0.6 = 7.67
  assert.equal(w.alt, false);
  assert.equal(t.alt, true);
  near(pl.total, pl.lines.filter((l) => !l.alt && l.cost).reduce((a, l) => a + l.cost, 0));
  const b = baskets(data, S({ whole: true }), today);
  const lidl = b.single.find((s) => s.ids[0] === 'lidl');
  // Lidl has no whole chicken: it is still judged with its thighs, not as missing chicken
  assert.ok(lidl.pick.chicken_thighs && !lidl.missing.some((p) => p.id.startsWith('chicken')));
  assert.ok(b.pair.total <= Math.min(...b.single.map((s) => s.total)) + 1e-9 || b.pair.missing.length < b.single[0].missing.length);
});

test('manual prices join as their own shop and expire', () => {
  const d = { ...data, manual: [{ id: 'm1', product: 'chicken_thighs', place: 'Κρεοπωλείο', price: 3.9, date: '2026-11-01', days: 7 }] };
  const thighs = cfg.products.find((p) => p.id === 'chicken_thighs');
  assert.equal(options(d, thighs, S(), today)[0].store, 'm:Κρεοπωλείο');
  assert.equal(options(d, thighs, S(), '2026-11-09')[0].store, 'lidl');
  // a butcher is not a basket on its own, but can be half of the best pair
  const only = { ...d, items: { chicken_thighs: data.items.chicken_thighs, milk: data.items.milk } };
  const b = baskets(only, S(), today);
  assert.ok(!b.single.some((s) => s.ids[0].startsWith('m:')));
  assert.deepEqual([...b.pair.ids].sort(), ['m:Κρεοπωλείο', 'sklavenitis']);
});

test('savings compare with the basic version at this week\'s prices', () => {
  const s = savings(data, S({ protein: true }), today);
  near(s.base - s.cur, 1.5 * 3.5);
  near(s.month, 1.5 * 3.5 * 52 / 12);
});

test('threshold alerts, and adaptive limits once there is history', () => {
  const a = alerts(data, S(), today);
  assert.deepEqual(a.filter((x) => x.kind === 'now').map((x) => x.p.id).sort(), ['eggs', 'milk', 'rice', 'yogurt']);
  // 12 days of history where milk was usually 1.0: today's 1.2 is under the 2.30 threshold but not a deal
  const dates = Array.from({ length: 12 }, (_, i) => addDays(today, -3 * (i + 1)));
  const d = { ...data, hist: { dates, chains: ['sklavenitis'], p: { milk: dates.map((_, i) => [i, 0, 1.0, 1.0]) } } };
  assert.ok(!alerts(d, S(), today).some((x) => x.p.id === 'milk'));
  assert.ok(alerts(d, S({}, { auto: false }), today).some((x) => x.p.id === 'milk'));
  const st = stats(series(d, cfg.products.find((p) => p.id === 'milk'), S(), today).map((x) => x[1]));
  assert.equal(st.n, 12);
  assert.equal(st.p20, 1.0);
});

test('price cuts starting later are announced as coming', () => {
  const d = { ...data, items: { ...data.items, rice: [item('r2', 'Ρύζι', false, { lidl: px(1.9) }, { drop: { start: '2026-11-10', end: '2026-12-31', pct: 10 } })] } };
  const soon = alerts(d, S(), today).filter((x) => x.kind === 'soon');
  assert.equal(soon.length, 1);
  assert.equal(soon[0].item.id, 'r2');
});

test('free extra product is turned into the real quantity', () => {
  near(effectiveQty({ name: 'ΜΙΜΙΚΟΣ μπούτια κοτόπουλου 800g + 280g', unit_quantity: 0.8 }, 'kg').qty, 1.08);
  near(effectiveQty({ name: 'Κοπανάκι 650g +50% Δωρεάν Προϊόν', unit_quantity: 0.65 }, 'kg').qty, 0.975);
  near(effectiveQty({ name: 'Αυγά 10+2 δώρο', unit_quantity: 10 }, 'pcs').qty, 12);
  near(effectiveQty({ name: 'ΝΙΤΣΙΑΚΟΣ μπούτι κοτόπουλου 850g (-20%)', unit_quantity: 0.85 }, 'kg').qty, 0.85);
  near(effectiveQty({ name: 'Γιαούρτι 3x200g', unit_quantity: 0.6 }, 'kg').qty, 0.6);
});

test('matching rules keep the right products out', () => {
  const rules = compileRules(cfg.products);
  const is = (id, name, category, unit = cfg.products.find((p) => p.id === id).unit) =>
    matches(cfg.products.find((p) => p.id === id), rules.get(id), { name, category, subcategory: '', unit });
  assert.ok(is('chicken_thighs', 'ΝΙΤΣΙΑΚΟΣ Κοτόπουλο Μπούτι 850g', 'Πουλερικά'));
  assert.ok(!is('chicken_thighs', 'ΝΙΤΣΙΑΚΟΣ Κοτόπουλο Μπούτι Φιλέτο 700g', 'Πουλερικά'));
  assert.ok(is('chicken_whole', 'AB ΚΟΤΟΠΟΥΛΟ ΟΛΟΚΛΗΡΟ', 'Πουλερικά'));
  assert.ok(!is('chicken_whole', 'Κοτόπουλο Εντόσθια 450g', 'Πουλερικά'));
  assert.ok(is('yogurt', 'ΦΑΓΕ Total Γιαούρτι Στραγγιστό 0% Λιπαρά 1kg', 'Στραγγιστό Γιαούρτι'));
  assert.ok(!is('yogurt', 'ΓΑΛΠΟ Bio Organic Γιαούρτι Στραγγιστό 10% 500g', 'Στραγγιστό Γιαούρτι'));
  assert.ok(!is('yogurt', 'ΓΑΛΠΟ Επιδόρπιο Γιαουρτιού Δροσερό 2% 1kg', 'Στραγγιστό Γιαούρτι'));
  assert.ok(is('milk', 'ΛΥΚΑΣ Φρέσκο Γάλα Πλήρες 3,5% 1lt', 'Φρέσκο Γάλα'));
  assert.ok(!is('milk', 'ΔΕΛΤΑ Γάλα Υψηλής Παστερίωσης 1,5% 1lt', 'Γάλα Υψηλής Παστερίωσης'));
  assert.ok(!is('oats', 'MILLHOUSE Νιφάδες Βρώμης με Σκούρα Σοκολάτα 500g', 'Βρώμη'));
  assert.ok(is('eggs', 'ΧΡΥΣΑ ΑΥΓΑ Αυγά Μεσαία 10τεμ', 'Αυγά', 'pcs'));
  assert.ok(!is('eggs', 'ΧΡΥΣΑ ΑΥΓΑ Αυγά Μεσαία 10τεμ', 'Αυγά', 'kg'));
  assert.equal(norm('Ρεβύθια ΧΟΝΔΡΆ'), 'ρεβυθια χονδρα');
});

test('extract keeps only Greek chains and the cheapest line per chain', () => {
  const snap = { products: [{ id: 'x', name: 'ΣΚΛΑΒΕΝΙΤΗΣ Φακές Ψιλές 500g', category: 'Φακές', subcategory: 'Όσπρια', unit: 'kg', unit_quantity: 0.5, private_label: true,
    retailer_prices: [{ retailer: 'sklavenitis', price: 0.9 }, { retailer: 'sklavenitis', price: 0.85, is_discount: true, discount_percentage: 5.4 }, { retailer: 'carrefour_it', price: 0.5 }] }] };
  const by = extract(snap, cfg.products);
  assert.equal(by.legumes.length, 1);
  assert.deepEqual(Object.keys(by.legumes[0].prices), ['sklavenitis']);
  near(by.legumes[0].prices.sklavenitis.u, 1.7);
  assert.equal(by.legumes[0].prices.sklavenitis.d, 5);
});

console.log(`\n${n} tests passed`);
