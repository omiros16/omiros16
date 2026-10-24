// Matching team and player names that different sites spell differently.
const EXPAND = {
  man: 'manchester', utd: 'united', 'nottm': 'nottingham', spurs: 'tottenham', wolves: 'wolverhampton',
  mgladbach: 'monchengladbach', gladbach: 'monchengladbach', ein: 'eintracht', sp: 'sporting', st: 'saint',
  sg: 'saint germain', psg: 'paris saint germain', ath: 'athletic', atl: 'atletico', olympiakos: 'olympiacos',
  inter: 'internazionale', qpr: 'queens park rangers', wba: 'west bromwich', bilbao: 'athletic',
};
const DROP = new Set(['fc', 'afc', 'cf', 'sc', 'ac', 'as', 'ss', 'ssc', 'us', 'club', 'de', 'cd', 'ud', 'rc', 'rcd', 'sd', 'vfl', 'vfb', 'tsg', 'fsv', 'sv', 'bv', 'fk', 'sk', 'nk', 'if', 'bk', 'ff', 'kv', 'krc', 'kaa', 'rsc', 'osc', 'ogc', 'losc', 'stade', 'the', 'and', 'calcio', '1', '1899', '1846', '1848', '1909', '04', '05', '96', 'cfc', 'nfc', 'pae', 'gfc', 'jk']);

export function norm(s) {
  return String(s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase()
    .replace(/ø/g, 'o').replace(/æ/g, 'ae').replace(/ß/g, 'ss').replace(/ı/g, 'i')
    .replace(/[’'`.]/g, '').replace(/&/g, ' and ').replace(/[^a-z0-9]+/g, ' ').trim();
}
export function tokens(s) {
  const out = [];
  for (const t of norm(s).split(' ')) {
    if (!t) continue;
    const e = EXPAND[t] || t;
    for (const x of e.split(' ')) if (!DROP.has(x)) out.push(x);
  }
  return out;
}
function trigrams(s) {
  const g = new Set(), t = `  ${s} `;
  for (let i = 0; i < t.length - 2; i++) g.add(t.slice(i, i + 3));
  return g;
}
export function similarity(a, b) {
  const ta = tokens(a), tb = tokens(b);
  if (!ta.length || !tb.length) return 0;
  const ja = ta.join(' '), jb = tb.join(' ');
  if (ja === jb) return 1;
  const A = trigrams(ja), B = trigrams(jb);
  let inter = 0;
  for (const x of A) if (B.has(x)) inter++;
  const dice = (2 * inter) / (A.size + B.size);
  // token containment: every token of the shorter name starts a token of the longer one
  const [s, l] = ta.length <= tb.length ? [ta, tb] : [tb, ta];
  const contained = s.every((x) => l.some((y) => y.startsWith(x) || x.startsWith(y)));
  return Math.max(dice, contained ? 0.85 : 0);
}

// Greedy one-to-one assignment of `names` to `targets`; returns Map name -> target index.
export function matchNames(names, targets, aliases = {}, min = 0.45) {
  const out = new Map(), used = new Set();
  for (const n of names) {
    const a = aliases[n];
    if (a !== undefined) { const i = targets.indexOf(a); if (i >= 0) { out.set(n, i); used.add(i); } }
  }
  const cand = [];
  for (const n of names) {
    if (out.has(n)) continue;
    targets.forEach((t, i) => { const s = similarity(n, t); if (s >= min) cand.push([s, n, i]); });
  }
  cand.sort((x, y) => y[0] - x[0]);
  for (const [, n, i] of cand) if (!out.has(n) && !used.has(i)) { out.set(n, i); used.add(i); }
  return out;
}

// Player names: same surname and compatible first name/initial.
export function playerSimilarity(a, b) {
  const ta = norm(a).split(' ').filter(Boolean), tb = norm(b).split(' ').filter(Boolean);
  if (!ta.length || !tb.length) return 0;
  if (ta.join(' ') === tb.join(' ')) return 1;
  const la = ta[ta.length - 1], lb = tb[tb.length - 1];
  const sa = new Set(ta), shared = tb.filter((x) => sa.has(x) && x.length > 2).length;
  if (la === lb) {
    if (ta.length === 1 || tb.length === 1) return 0.8;
    return ta[0][0] === tb[0][0] ? 0.92 : 0.6;
  }
  if (shared) return 0.55 + 0.1 * shared;
  const A = trigrams(ta.join(' ')), B = trigrams(tb.join(' '));
  let inter = 0;
  for (const x of A) if (B.has(x)) inter++;
  return (2 * inter) / (A.size + B.size) * 0.8;
}
