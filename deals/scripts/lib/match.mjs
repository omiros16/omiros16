// Finds the products of the shopping list in a PosoKanei snapshot and works out real unit prices.

export const GR_CHAINS = ['sklavenitis', 'halkiadakis', 'lidl', 'ab_vasilopoulos', 'mymarket', 'kritikos', 'synka', 'masoutis', 'galaxias', 'market_in', 'bazaar'];

// lower case, no accents, final sigma as plain sigma, single spaces
export const norm = (s) => String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/ς/g, 'σ').replace(/\s+/g, ' ').trim();

const UNIT = { kg: 'kg', Kg: 'kg', KG: 'kg', L: 'L', l: 'L', lt: 'L', pcs: 'pcs' };

export function compileRules(products) {
  const out = new Map();
  for (const p of products) {
    if (!p.match) continue;
    const m = p.match;
    out.set(p.id, {
      cats: m.cats ? new Set(m.cats.map(norm)) : null,
      all: (m.all || []).map((r) => new RegExp(r)),
      none: (m.none || []).map((r) => new RegExp(r)),
    });
  }
  return out;
}

export function matches(def, rule, item) {
  if ((UNIT[item.unit] || item.unit) !== def.unit) return false;
  if (rule.cats && !rule.cats.has(norm(item.category)) && !rule.cats.has(norm(item.subcategory))) return false;
  const name = norm(item.name);
  return rule.all.every((r) => r.test(name)) && !rule.none.some((r) => r.test(name));
}

// Free extra product printed in the name ("800g + 280g", "+20% δωρεάν", "10+2 αυγά") turned into
// the quantity really paid for. Returns { qty, bonus } with qty in the product unit.
export function effectiveQty(item, unit) {
  const base = +item.unit_quantity;
  if (!(base > 0)) return null;
  const name = norm(item.name);
  if (unit === 'pcs') {
    const m = name.match(/(\d+)\s*\+\s*(\d+)\s*(τεμ|αυγ|δωρ|free|$|\s)/);
    if (m && +m[1] === base && +m[2] > 0 && +m[2] <= base) return { qty: base + +m[2], bonus: `+${m[2]} τεμ.` };
    return { qty: base, bonus: null };
  }
  const g = name.match(/\d\s*(?:g|gr|γρ|kg|ml|l|lt)?\s*\+\s*(\d+(?:[.,]\d+)?)\s*(g|gr|γρ|ml)(?![a-zα-ω])/);
  if (g) {
    const extra = parseFloat(g[1].replace(',', '.')) / 1000;
    if (extra > 0 && extra <= base * 1.5) return { qty: base + extra, bonus: `+${Math.round(extra * 1000)} ${g[2] === 'ml' ? 'ml' : 'g'}` };
  }
  const pc = name.match(/\+\s*(\d{1,3})\s*%\s*(δωρ|δωρεαν|free|gratis|επιπλεον|προιον)/) || name.match(/(\d{1,3})\s*%\s*(δωρεαν|επιπλεον προιον|περισσοτερο προιον)/);
  if (pc && +pc[1] > 0 && +pc[1] <= 100) return { qty: base * (1 + +pc[1] / 100), bonus: `+${pc[1]}% δώρο` };
  return { qty: base, bonus: null };
}

const r2 = (x) => Math.round(x * 100) / 100;
const r3 = (x) => Math.round(x * 1000) / 1000;

// Every snapshot product matching a list product, with the Greek chain prices only.
export function extract(snapshot, products) {
  const rules = compileRules(products);
  const byProduct = Object.fromEntries(products.filter((p) => rules.has(p.id)).map((p) => [p.id, []]));
  for (const item of snapshot.products || []) {
    for (const def of products) {
      const rule = rules.get(def.id);
      if (!rule || !matches(def, rule, item)) continue;
      const eq = effectiveQty(item, def.unit);
      if (!eq) continue;
      const prices = {};
      for (const rp of item.retailer_prices || []) {
        if (!GR_CHAINS.includes(rp.retailer) || !(rp.price > 0)) continue;
        const u = (rp.price / eq.qty) * (def.factor || 1);
        // keep the cheapest line if a chain lists the product twice
        if (prices[rp.retailer] && prices[rp.retailer].u <= u) continue;
        prices[rp.retailer] = { p: r2(rp.price), u: r3(u), d: rp.is_discount ? (rp.discount_percentage ? Math.round(rp.discount_percentage) : true) : 0, at: (rp.last_updated || '').slice(0, 10) };
      }
      if (!Object.keys(prices).length) continue;
      byProduct[def.id].push({
        id: item.id,
        name: String(item.name || '').replace(/\s+/g, ' ').trim(),
        brand: String(item.brand || '').trim() || null,
        pl: !!item.private_label,
        size: r3(+item.unit_quantity),
        bonus: eq.bonus,
        drop: item.price_drop ? { start: item.price_drop_start, end: item.price_drop_end, pct: item.price_drop_percentage ?? null } : null,
        prices,
      });
    }
  }
  return byProduct;
}

// Keeps the useful part of each product's candidates: the cheapest overall plus the cheapest
// branded and the cheapest private-label item of every chain, so that any choice of chains and
// levers made later in the app still finds its best price.
export function trim(items, keep = 14) {
  const minU = (it) => Math.min(...Object.values(it.prices).map((x) => x.u));
  const sorted = [...items].sort((a, b) => minU(a) - minU(b));
  const chosen = new Set(sorted.slice(0, keep));
  for (const chain of GR_CHAINS) {
    for (const pl of [false, true]) {
      let best = null;
      for (const it of items) {
        const x = it.prices[chain];
        if (x && it.pl === pl && (!best || x.u < best.prices[chain].u)) best = it;
      }
      if (best) chosen.add(best);
    }
  }
  return sorted.filter((it) => chosen.has(it));
}
