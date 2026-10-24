export function parseCsv(text) {
  text = text.replace(/^﻿/, '');
  const rows = [];
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue;
    const cells = [];
    let cur = '', q = false;
    for (let i = 0; i < line.length; i++) {
      const c = line[i];
      if (q) { if (c === '"' && line[i + 1] === '"') { cur += '"'; i++; } else if (c === '"') q = false; else cur += c; }
      else if (c === '"') q = true;
      else if (c === ',') { cells.push(cur); cur = ''; }
      else cur += c;
    }
    cells.push(cur);
    rows.push(cells);
  }
  if (!rows.length) return [];
  const head = rows[0].map((h) => h.trim());
  return rows.slice(1).map((r) => Object.fromEntries(head.map((h, i) => [h, (r[i] ?? '').trim()])));
}

// dd/mm/yy or dd/mm/yyyy -> days since 1970-01-01
export function dayOf(s) {
  const m = /^(\d{1,2})\/(\d{1,2})\/(\d{2,4})$/.exec(s || '');
  if (!m) return null;
  let y = +m[3];
  if (y < 100) y += 2000;
  return Math.floor(Date.UTC(y, +m[2] - 1, +m[1]) / 86400000);
}
export const isoDay = (d) => new Date(d * 86400000).toISOString().slice(0, 10);
export const today = () => Math.floor(Date.now() / 86400000);
