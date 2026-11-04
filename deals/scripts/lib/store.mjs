// Files of the data branch: settings, manual prices, alert memory and the price history
// (one small CSV per month: the best unit price of each product in each chain on each update day).
import { readFile, writeFile, mkdir, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';

export async function readJson(path, fallback) {
  if (!existsSync(path)) return fallback;
  return JSON.parse(await readFile(path, 'utf8'));
}

export async function writeJson(path, value, pretty = true) {
  await writeFile(path, `${JSON.stringify(value, null, pretty ? 2 : 0)}\n`);
}

const HEADER = 'date,product,chain,branded,any';

// rows: [{ date, product, chain, branded, any }] for the months that touch the last `days` days
export async function readHistory(dir, days = 120) {
  const hdir = `${dir}/history`;
  if (!existsSync(hdir)) return [];
  const from = new Date(Date.now() - days * 864e5).toISOString().slice(0, 7);
  const rows = [];
  for (const f of (await readdir(hdir)).filter((f) => /^\d{4}-\d{2}\.csv$/.test(f) && f.slice(0, 7) >= from).sort()) {
    const lines = (await readFile(`${hdir}/${f}`, 'utf8')).trim().split('\n').slice(1);
    for (const line of lines) {
      const [date, product, chain, b, a] = line.split(',');
      rows.push({ date, product, chain, branded: b === '' ? null : +b, any: a === '' ? null : +a });
    }
  }
  return rows;
}

// Replaces the rows of the given dates and rewrites the affected month files.
export async function writeHistory(dir, newRows) {
  const hdir = `${dir}/history`;
  await mkdir(hdir, { recursive: true });
  const byMonth = new Map();
  for (const r of newRows) {
    const m = r.date.slice(0, 7);
    if (!byMonth.has(m)) byMonth.set(m, []);
    byMonth.get(m).push(r);
  }
  for (const [m, rows] of byMonth) {
    const path = `${hdir}/${m}.csv`;
    const dates = new Set(rows.map((r) => r.date));
    const kept = existsSync(path)
      ? (await readFile(path, 'utf8')).trim().split('\n').slice(1).filter((l) => l && !dates.has(l.slice(0, 10)))
      : [];
    const fmt = (x) => (x == null ? '' : String(x));
    const all = [...kept, ...rows.map((r) => [r.date, r.product, r.chain, fmt(r.branded), fmt(r.any)].join(','))].sort();
    await writeFile(path, `${HEADER}\n${all.join('\n')}\n`);
  }
}
