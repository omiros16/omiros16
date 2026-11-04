// Shopping-list logic shared by the page and the scheduled update: which products the levers
// switch on and in what quantities, the cheapest option for each, baskets per shop, savings and
// alerts. Pure functions over the data file (latest.json); nothing here touches the network.

export const LEVERS = ['protein', 'whole', 'legumes', 'pl'];
export const WEEKS_PER_MONTH = 52 / 12;
export const UNIT_LABEL = { kg: 'κιλό', L: 'λίτρο', pcs: 'τεμ.' };

export const productById = (data, id) => data.cfg.products.find((p) => p.id === id);
export const chainName = (data, id) => data.cfg.chains.find((c) => c.id === id)?.name || id;

export function normSettings(s = {}) {
  return {
    levers: Object.fromEntries(LEVERS.map((k) => [k, !!s.levers?.[k]])),
    chains: { ...(s.chains || {}) },
    thresholds: { ...(s.thresholds || {}) },
    off: [...(s.off || [])],
    weekly: s.weekly !== false,
    auto: s.auto !== false,
  };
}

export const chainOn = (data, settings, id) => settings.chains[id] ?? !!data.cfg.chains.find((c) => c.id === id)?.heraklion;
export const enabledChains = (data, settings) => new Set(data.cfg.chains.filter((c) => chainOn(data, settings, c.id)).map((c) => c.id));
export const thresholdOf = (p, settings) => settings.thresholds[p.id] ?? p.threshold;
export const productOn = (p, settings) => !settings.off.includes(p.id);
export const tracked = (p, settings) => productOn(p, settings) && (!p.lever || settings.levers[p.lever]);

// Weekly quantities (handoff section 4). Breakfast yogurt is 7 × 100 g whatever the levers;
// the dinner yogurt shrinks to 100 g with the protein lever and is only eaten on the three
// evenings without legumes when the legumes lever is on, so the two cuts are not simply added.
export function quantities(data, settings) {
  const L = settings.levers, q = {};
  for (const p of data.cfg.products) q[p.id] = tracked(p, settings) ? p.qty : 0;
  if (q.yogurt) {
    const base = productById(data, 'yogurt').qty;
    let y = base;
    if (L.protein && L.legumes) y = 0.7 + 3 * 0.1;
    else if (L.protein) y = 1.5;
    else if (L.legumes) y = base - 1.2;
    q.yogurt = Math.max(0.7, y);
  }
  if (L.legumes) {
    if (q.rice) q.rice += 0.3;
    if (q.bananas) q.bananas *= 3 / 7;
  }
  return q;
}

// Manual prices (butchers, the market, whey) count until `days` after the day they were entered.
export function manualValid(data, productId, today) {
  return (data.manual || []).filter((m) => m.product === productId && addDays(m.date, m.days ?? 7) >= today);
}

// Every priced option for a product in the enabled shops, cheapest unit price first.
// With the private-label lever off, the dairy/grain/egg products count brands only.
export function options(data, product, settings, today) {
  const chains = enabledChains(data, settings);
  const brandsOnly = product.pl && !settings.levers.pl;
  const out = [];
  for (const it of data.items[product.id] || []) {
    if (brandsOnly && it.pl) continue;
    for (const [c, x] of Object.entries(it.prices)) {
      if (!chains.has(c)) continue;
      out.push({ store: c, storeName: chainName(data, c), manual: false, item: it.id, name: it.name, pl: it.pl, size: it.size, bonus: it.bonus, drop: it.drop, u: x.u, p: x.p, d: x.d, since: x.s || null });
    }
  }
  for (const m of manualValid(data, product.id, today)) {
    out.push({ store: `m:${m.place}`, storeName: m.place, manual: true, item: m.id, name: m.note || product.name, pl: false, u: m.price, p: null, d: 0, date: m.date, until: addDays(m.date, m.days ?? 7) });
  }
  return out.sort((a, b) => a.u - b.u || (a.manual - b.manual));
}

// Chicken: with the whole-chicken lever both are followed and the one that is cheaper in clean
// meat (price ÷ yield) is bought, in its own quantity; the other line stays as the alternative.
const CHICKEN = ['chicken_whole', 'chicken_thighs'];
const netCost = (data, o, id) => (o ? o.u / productById(data, id).yield : Infinity);

// The week's list: each product with its quantity and cheapest option.
export function plan(data, settings, today) {
  const q = quantities(data, settings);
  const lines = [];
  for (const p of data.cfg.products) {
    if (!q[p.id]) continue;
    const opts = options(data, p, settings, today);
    const best = opts[0] || null;
    lines.push({ p, qty: q[p.id], best, cost: best ? best.u * q[p.id] : null, threshold: thresholdOf(p, settings), opts, alt: false });
  }
  const ch = lines.filter((l) => CHICKEN.includes(l.p.id));
  if (ch.length === 2) {
    const [w, t] = CHICKEN.map((id) => ch.find((l) => l.p.id === id));
    (netCost(data, w.best, w.p.id) < netCost(data, t.best, t.p.id) ? t : w).alt = true;
  }
  const counted = lines.filter((l) => !l.alt);
  return {
    lines,
    total: counted.reduce((a, l) => a + (l.cost || 0), 0),
    unpriced: counted.filter((l) => !l.best).map((l) => l.p),
  };
}

// Weekly cost of the list in each shop on its own and in the cheapest pair of shops. Only
// products that have a price somewhere are compared, so every shop is judged on the same items.
// Chicken counts once: each shop is given the cheaper of whole or thighs that it sells.
export function baskets(data, settings, today) {
  const pl = plan(data, settings, today);
  const priced = pl.lines.filter((l) => l.best);
  const stores = new Map();
  for (const l of priced) {
    for (const o of l.opts) {
      if (!stores.has(o.store)) stores.set(o.store, { id: o.store, name: o.storeName, manual: o.manual, best: {} });
      const s = stores.get(o.store);
      if (!s.best[l.p.id] || o.u < s.best[l.p.id].u) s.best[l.p.id] = o;
    }
  }
  // needs: a list of alternatives, each a line; chicken is one need with two alternatives
  const needs = [];
  const chicken = priced.filter((l) => CHICKEN.includes(l.p.id));
  if (chicken.length) needs.push({ p: chicken.find((l) => !l.alt)?.p || chicken[0].p, alts: chicken });
  for (const l of priced) if (!CHICKEN.includes(l.p.id)) needs.push({ p: l.p, alts: [l] });
  // compare chicken alternatives in clean meat, everything else by unit price
  const value = (o, l) => (l.p.yield ? o.u / l.p.yield : o.u);
  const costIn = (ids) => {
    let total = 0; const missing = []; const pick = {};
    for (const n of needs) {
      let b = null, bl = null;
      for (const l of n.alts) {
        for (const id of ids) {
          const o = stores.get(id).best[l.p.id];
          if (o && (!b || value(o, l) < value(b, bl))) { b = o; bl = l; }
        }
      }
      if (b) { total += b.u * bl.qty; pick[bl.p.id] = b; } else missing.push(n.p);
    }
    return { ids, total, missing, pick };
  };
  const better = (a, b) => a.missing.length - b.missing.length || a.total - b.total;
  const single = [...stores.values()].filter((s) => !s.manual).map((s) => ({ ...costIn([s.id]), name: s.name })).sort(better);
  const ids = [...stores.keys()];
  let pair = null;
  for (let i = 0; i < ids.length; i++) {
    for (let j = i + 1; j < ids.length; j++) {
      const c = costIn([ids[i], ids[j]]);
      if (!pair || better(c, pair) < 0) pair = c;
    }
  }
  if (pair) pair.names = pair.ids.map((id) => stores.get(id).name);
  return { single, pair, lines: needs.length, unpriced: pl.unpriced };
}

// Estimated monthly saving of the chosen levers against the basic version (all levers off),
// both priced with this week's best prices in the enabled shops.
export function savings(data, settings, today) {
  const base = plan(data, { ...settings, levers: Object.fromEntries(LEVERS.map((k) => [k, false])) }, today);
  const cur = plan(data, settings, today);
  return { base: base.total, cur: cur.total, month: (base.total - cur.total) * WEEKS_PER_MONTH };
}

// Best price per update day over the last `days` days, for the shops and levers given.
export function series(data, product, settings, today, days = 90) {
  const h = data.hist;
  const rows = h?.p?.[product.id];
  if (!rows) return [];
  const chains = enabledChains(data, settings);
  const brandsOnly = product.pl && !settings.levers.pl;
  const from = addDays(today, -days);
  const best = new Map();
  for (const [di, ci, b, a] of rows) {
    const date = h.dates[di];
    if (date < from || !chains.has(h.chains[ci])) continue;
    const v = brandsOnly ? b : a;
    if (v == null) continue;
    if (!best.has(date) || v < best.get(date)) best.set(date, v);
  }
  return [...best.entries()].sort((x, y) => x[0].localeCompare(y[0]));
}

export function stats(values) {
  const v = [...values].sort((a, b) => a - b);
  if (!v.length) return { n: 0 };
  const at = (q) => v[Math.min(v.length - 1, Math.max(0, Math.ceil(q * v.length) - 1))];
  return { n: v.length, min: v[0], p20: at(0.2), median: at(0.5), max: v[v.length - 1] };
}

// Alert limit. With enough history ("auto", on unless switched off in the settings) the limit
// also adapts to the product: a price must be within the set threshold, among the cheapest 20%
// of the last 90 days and below the usual (median) price, so a generous threshold does not ring
// for a price that is simply always that low.
export const MIN_HISTORY = 8;
export function effectiveThreshold(p, settings, st) {
  const thr = thresholdOf(p, settings);
  return settings.auto && st?.n >= MIN_HISTORY ? Math.min(thr, st.p20) : thr;
}

// What deserves a notification: a tracked product whose best price is within its limit, plus
// price cuts that start on a later day.
export function alerts(data, settings, today) {
  const out = [];
  const chains = enabledChains(data, settings);
  for (const p of data.cfg.products) {
    if (!tracked(p, settings)) continue;
    const opts = options(data, p, settings, today);
    const best = opts[0];
    const thr = thresholdOf(p, settings);
    if (best) {
      const st = stats(series(data, p, settings, today).map((x) => x[1]));
      const limit = effectiveThreshold(p, settings, st);
      const dip = !settings.auto || st.n < MIN_HISTORY || best.u < st.median;
      if (best.u <= limit && dip) {
        const why = [best.u <= thr ? 'threshold' : null, st.n >= MIN_HISTORY && best.u <= st.p20 ? 'low' : null].filter(Boolean);
        const others = [];
        for (const o of opts.slice(1)) {
          if (o.u > limit || o.store === best.store || others.some((x) => x.store === o.store)) continue;
          others.push(o);
          if (others.length === 2) break;
        }
        out.push({ kind: 'now', why, p, best, thr, limit, st, others, key: `now|${p.id}` });
      }
    }
    for (const it of data.items[p.id] || []) {
      if (!it.drop?.start || it.drop.start <= today) continue;
      if (p.pl && it.pl && !settings.levers.pl) continue;
      const at = Object.entries(it.prices).filter(([c]) => chains.has(c)).sort((a, b) => a[1].u - b[1].u);
      if (!at.length) continue;
      out.push({ kind: 'soon', p, item: it, store: at[0][0], storeName: chainName(data, at[0][0]), u: at[0][1].u, key: `soon|${p.id}|${it.id}|${it.drop.start}` });
    }
  }
  return out;
}

export function addDays(iso, n) {
  const d = new Date(`${iso}T12:00:00Z`);
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
}

export const eur = (x, digits = 2) => (x == null || !isFinite(x) ? '–' : `${x.toFixed(digits).replace('.', ',')} €`);
export const num = (x, digits = 2) => (x == null || !isFinite(x) ? '–' : x.toFixed(digits).replace('.', ','));

// Quantity in words for the list ("2,1 κιλά", "15 τεμ.", "300 g").
export function qtyText(p, q) {
  if (p.unit === 'pcs') return `${Math.round(q)} τεμ.`;
  if (q < 1) return `${Math.round(q * 1000)} ${p.unit === 'L' ? 'ml' : 'g'}`;
  return `${num(q, q % 1 ? 1 : 0)} ${p.unit === 'L' ? 'λίτρα' : 'κιλά'}`;
}
