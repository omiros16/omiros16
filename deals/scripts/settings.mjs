// Applies a change sent from the page as a GitHub issue (only the repository owner's issues reach
// this script; see .github/workflows/deals.yml). The issue body carries one ```deals JSON block:
//   {"settings": {levers, chains, thresholds, off, auto, weekly}}   replaces those settings
//   {"price": {product, place, price, days, note}}                  adds a manual price
//   {"remove": "<manual price id>"}                                 deletes one
//   node deals/scripts/settings.mjs <data dir>     env: ISSUE_BODY, REPLY_FILE
import { readFile, writeFile } from 'node:fs/promises';
import { readJson, writeJson } from './lib/store.mjs';
import { LEVERS, normSettings, eur, UNIT_LABEL } from '../app/js/core.js';

const DIR = process.argv[2] || 'deals-data';
const cfg = JSON.parse(await readFile(new URL('../config/products.json', import.meta.url), 'utf8'));
const products = new Map(cfg.products.map((p) => [p.id, p]));
const chains = new Set(cfg.chains.map((c) => c.id));
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Athens' }).format(new Date());
const clean = (s, max) => String(s ?? '').replace(/[\u0000-\u001f\u007f<>`]/g, ' ').replace(/\s+/g, ' ').trim().slice(0, max);
const numIn = (x, lo, hi) => (typeof x === 'number' && isFinite(x) && x > lo && x < hi ? Math.round(x * 1000) / 1000 : null);

function parse(body) {
  if (!body || body.length > 20000) throw new Error('κενό ή πολύ μεγάλο αίτημα');
  const m = body.match(/```deals\s*\n([\s\S]*?)\n```/);
  if (!m) throw new Error('δεν βρέθηκε το μπλοκ ```deals');
  return JSON.parse(m[1]);
}

function settingsFrom(input, old) {
  const s = normSettings(old);
  if (input.levers) for (const k of LEVERS) if (typeof input.levers[k] === 'boolean') s.levers[k] = input.levers[k];
  if (input.chains) {
    s.chains = {};
    for (const [k, v] of Object.entries(input.chains)) if (chains.has(k) && typeof v === 'boolean') s.chains[k] = v;
  }
  if (input.thresholds) {
    s.thresholds = {};
    for (const [k, v] of Object.entries(input.thresholds)) { const n = numIn(v, 0, 1000); if (products.has(k) && n != null) s.thresholds[k] = n; }
  }
  if (Array.isArray(input.off)) s.off = [...new Set(input.off.filter((k) => products.has(k)))];
  if (typeof input.auto === 'boolean') s.auto = input.auto;
  if (typeof input.weekly === 'boolean') s.weekly = input.weekly;
  return s;
}

async function main() {
  const req = parse(process.env.ISSUE_BODY || '');
  const reply = [];
  if (req.settings) {
    const s = settingsFrom(req.settings, await readJson(`${DIR}/settings.json`, {}));
    await writeJson(`${DIR}/settings.json`, s);
    const on = cfg.levers.filter((l) => s.levers[l.id]).map((l) => l.name);
    reply.push(`Αποθηκεύτηκαν οι ρυθμίσεις. Μοχλοί: ${on.length ? on.join(', ') : 'κανένας'}.`);
  }
  if (req.price) {
    const x = req.price, p = products.get(x.product);
    const price = numIn(x.price, 0, 1000), place = clean(x.place, 60);
    if (!p || price == null || !place) throw new Error('η τιμή θέλει προϊόν, μαγαζί και τιμή ανά μονάδα');
    const days = Math.min(60, Math.max(1, Math.round(+x.days || 7)));
    const manual = await readJson(`${DIR}/manual.json`, []);
    const entry = { id: `m${Date.now().toString(36)}`, product: p.id, place, price, date: today, days, note: clean(x.note, 80) || null };
    manual.push(entry);
    // entries older than 60 days are of no use any more
    const keep = manual.filter((m) => m.date >= new Date(Date.now() - 60 * 864e5).toISOString().slice(0, 10));
    await writeJson(`${DIR}/manual.json`, keep);
    reply.push(`Προστέθηκε: ${p.name}, ${eur(price)}/${UNIT_LABEL[p.unit]} στο «${place}», ισχύει ${days} ημέρες.`);
  }
  if (req.remove) {
    const manual = await readJson(`${DIR}/manual.json`, []);
    const left = manual.filter((m) => m.id !== String(req.remove));
    await writeJson(`${DIR}/manual.json`, left);
    reply.push(left.length < manual.length ? 'Η τιμή διαγράφηκε.' : 'Δεν βρέθηκε αυτή η τιμή.');
  }
  if (!reply.length) throw new Error('το αίτημα δεν είχε καμία αλλαγή');
  console.log(reply.join('\n'));
  if (process.env.REPLY_FILE) await writeFile(process.env.REPLY_FILE, reply.join('\n'));
}

main().catch(async (e) => {
  const msg = `Η αλλαγή δεν εφαρμόστηκε: ${e.message}`;
  console.error(msg);
  if (process.env.REPLY_FILE) await writeFile(process.env.REPLY_FILE, msg);
  process.exit(1);
});
