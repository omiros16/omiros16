// Scheduled update: reads the newest PosoKanei snapshot, finds the products of the list, adds the
// day to the price history, writes latest.json for the page and sends a Telegram message when a
// price is worth acting on (never the same offer twice).
//   node deals/scripts/update.mjs <data dir>
// env: NOTIFY=1 to send to Telegram, SUMMARY=1 to add the weekly basket, TODAY=YYYY-MM-DD,
//      SNAPSHOT_FILE=<local snapshot>, BACKFILL_DAYS (default 90)
import { readFile, mkdir } from 'node:fs/promises';
import { extract, trim, GR_CHAINS } from './lib/match.mjs';
import { latestSnapshot, snapshotOf } from './lib/source.mjs';
import { readJson, writeJson, readHistory, writeHistory } from './lib/store.mjs';
import { send, alertText, summaryText, header } from './lib/telegram.mjs';
import { normSettings, alerts, baskets, savings, addDays } from '../app/js/core.js';

const DIR = process.argv[2] || 'deals-data';
const CONFIG = new URL('../config/products.json', import.meta.url);
const athensToday = () => new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Athens' }).format(new Date());
const r3 = (x) => (x == null ? null : Math.round(x * 1000) / 1000);
const log = (...a) => console.log(...a);

// A unit price far below everything else for the same product is a data error (wrong pack
// size in the source), not an offer.
function dropOutliers(byProduct) {
  for (const items of Object.values(byProduct)) {
    const all = items.flatMap((it) => Object.values(it.prices).map((x) => x.u)).sort((a, b) => a - b);
    if (all.length < 6) continue;
    const floor = all[Math.floor(all.length / 2)] * 0.25;
    for (const it of items) for (const [c, x] of Object.entries(it.prices)) if (x.u < floor) delete it.prices[c];
  }
  for (const [id, items] of Object.entries(byProduct)) byProduct[id] = items.filter((it) => Object.keys(it.prices).length);
}

function historyRows(date, byProduct) {
  const rows = [];
  for (const [product, items] of Object.entries(byProduct)) {
    for (const chain of GR_CHAINS) {
      let b = null, a = null;
      for (const it of items) {
        const x = it.prices[chain];
        if (!x) continue;
        if (a == null || x.u < a) a = x.u;
        if (!it.pl && (b == null || x.u < b)) b = x.u;
      }
      if (a != null) rows.push({ date, product, chain, branded: r3(b), any: r3(a) });
    }
  }
  return rows;
}

async function backfill(cfg, have, upto, days) {
  const rows = [];
  for (let d = addDays(upto, -days); d < upto; d = addDays(d, 3)) {
    if (have.has(d)) continue;
    const snap = await snapshotOf(d);
    if (!snap?.products?.length) { log(`  backfill ${d}: missing`); continue; }
    const by = extract(snap, cfg.products);
    dropOutliers(by);
    rows.push(...historyRows(snap.date || d, by));
    log(`  backfill ${d}: ${snap.products.length} products`);
  }
  return rows;
}

function compactHistory(rows) {
  const dates = [...new Set(rows.map((r) => r.date))].sort();
  const di = new Map(dates.map((d, i) => [d, i]));
  const ci = new Map(GR_CHAINS.map((c, i) => [c, i]));
  const p = {};
  for (const r of rows) {
    if (!ci.has(r.chain)) continue;
    (p[r.product] ||= []).push([di.get(r.date), ci.get(r.chain), r.branded, r.any]);
  }
  return { dates, chains: GR_CHAINS, p };
}

async function main() {
  await mkdir(DIR, { recursive: true });
  const cfg = JSON.parse(await readFile(CONFIG, 'utf8'));
  const settings = normSettings(await readJson(`${DIR}/settings.json`, {}));
  const manual = await readJson(`${DIR}/manual.json`, []);
  const state = await readJson(`${DIR}/state.json`, { sent: {}, seen: {} });
  const today = process.env.TODAY || athensToday();

  const snap = await latestSnapshot();
  const sdate = snap.date || today;
  log(`snapshot ${sdate} (${snap.products.length} products, fetched ${snap.fetched_at || '?'})`);
  const byProduct = extract(snap, cfg.products);
  dropOutliers(byProduct);
  for (const [id, items] of Object.entries(byProduct)) log(`  ${id}: ${items.length} matching products`);

  // first day an item has been on offer in a chain, while the offer lasts
  const seen = {};
  for (const items of Object.values(byProduct)) {
    for (const it of items) {
      for (const [c, x] of Object.entries(it.prices)) {
        if (!x.d) continue;
        const k = `${it.id}|${c}`;
        seen[k] = state.seen?.[k] || sdate;
        x.s = seen[k];
      }
    }
  }
  state.seen = seen;

  let hist = await readHistory(DIR);
  const have = new Set(hist.map((r) => r.date));
  const fresh = historyRows(sdate, byProduct);
  if (have.size < 5 && !process.env.SNAPSHOT_FILE) {
    log('history is short: filling it from older snapshots');
    fresh.push(...await backfill(cfg, have, sdate, +(process.env.BACKFILL_DAYS || 90)));
  }
  await writeHistory(DIR, fresh);
  hist = await readHistory(DIR);

  const items = Object.fromEntries(Object.entries(byProduct).map(([id, list]) => [id, trim(list)]));
  const repo = process.env.GITHUB_REPOSITORY || 'omiros16/omiros16';
  const [owner, name] = repo.split('/');
  const data = {
    updated: new Date().toISOString(),
    repo,
    app: process.env.APP_URL || `https://${owner}.github.io/${name}/deals/`,
    snapshot: { date: sdate, fetched: snap.fetched_at || null, products: snap.products.length, source: 'https://posokanei.gov.gr', archive: 'https://github.com/spyrosavl/posokanei' },
    stale: sdate < addDays(today, -2),
    today,
    cfg,
    settings,
    manual,
    items,
    hist: compactHistory(hist.filter((r) => r.date >= addDays(today, -100))),
  };

  // Alerts: a product is announced once while it stays cheap; again only if it gets cheaper
  // than the announced price, or after 14 days without an alert. Remembered once delivered.
  const list = alerts(data, settings, today);
  const priceOf = (a) => a.best?.u ?? a.u;
  const isNew = (a) => {
    const s = state.sent[a.key];
    return !s || s.last < addDays(today, -14) || (a.kind === 'now' && priceOf(a) < s.u - 0.005);
  };
  const newOnes = list.filter(isNew);
  for (const a of list) if (!isNew(a)) state.sent[a.key].last = today;
  for (const [k, v] of Object.entries(state.sent)) if (v.last < addDays(today, -60)) delete state.sent[k];
  data.alerts = list.map((a) => ({
    kind: a.kind, product: a.p.id, why: a.why || null, key: a.key, new: newOnes.includes(a), first: state.sent[a.key]?.first || today,
    store: a.best?.store || a.store, storeName: a.best?.storeName || a.storeName, item: a.best?.item || a.item?.id, u: a.best?.u ?? a.u,
  }));

  await writeJson(`${DIR}/latest.json`, data, false);
  await writeJson(`${DIR}/state.json`, state);
  if (!(await readJson(`${DIR}/settings.json`, null))) await writeJson(`${DIR}/settings.json`, settings);
  if (!(await readJson(`${DIR}/manual.json`, null))) await writeJson(`${DIR}/manual.json`, manual);

  const basket = baskets(data, settings, today);
  const save = savings(data, settings, today);
  log(`\n${list.length} alerts (${newOnes.length} new); basket ${basket.single[0]?.name} ${basket.single[0]?.total?.toFixed(2)} €, pair ${basket.pair?.names?.join(' + ')} ${basket.pair?.total?.toFixed(2)} €`);

  const sunday = new Date(`${today}T12:00:00Z`).getUTCDay() === 0;
  const withSummary = process.env.SUMMARY === '1' || (sunday && settings.weekly);
  const staleNote = data.stale && state.staleNoted !== sdate;
  const parts = [];
  if (newOnes.length) parts.push(alertText(data, newOnes, {}));
  if (withSummary) parts.push(summaryText(data, { basket, save, settings }));
  if (parts.length || staleNote) {
    const text = `${header(data, today)}\n\n${parts.join('\n\n')}\n\n<a href="${data.app}">Άνοιξε την εφαρμογή</a>`;
    if (process.env.NOTIFY === '1') {
      if (await send(text)) {
        for (const a of newOnes) state.sent[a.key] = { first: state.sent[a.key]?.first || today, last: today, u: priceOf(a) };
        if (staleNote) state.staleNoted = sdate;
        await writeJson(`${DIR}/state.json`, state);
      }
    } else {
      log(`\n(NOTIFY is not 1: message not sent)\n${text}`);
    }
  } else {
    log('nothing new to send');
  }
}

main().catch((e) => { console.error(e); process.exit(1); });
