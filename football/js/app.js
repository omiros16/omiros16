// UI of the match analysis app. All maths lives in model.js / markets.js / analyze.js.
import { analyze, teamForm, averages, table } from './analyze.js';
import { fairOdds, evAt, kelly } from './markets.js';
import { searchTeams } from './teams.js';
import { F } from './model.js';

const $app = document.getElementById('app');
const state = { index: null, leagues: new Map(), entries: [], pick: [null, null], tab: 'picks', match: null };

const store = {
  get(k, d) { try { const v = localStorage.getItem('fa.' + k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('fa.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
};

// ---------------------------------------------------------------- helpers

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const pct = (p) => (p === null || p === undefined ? '–' : p < 0.1 ? `${(p * 100).toFixed(1)}%` : `${Math.round(p * 100)}%`);
const odd = (o) => (!Number.isFinite(o) || o > 500 ? '–' : o.toFixed(2));
const f1 = (x) => (x === null || x === undefined ? '–' : x.toFixed(1));
const f2 = (x) => (x === null || x === undefined ? '–' : x.toFixed(2));
const dayFmt = new Intl.DateTimeFormat('el-GR', { weekday: 'long', day: 'numeric', month: 'numeric' });
const timeFmt = new Intl.DateTimeFormat('el-GR', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });
const shortDate = (d) => new Date(d * 86400000).toLocaleDateString('el-GR', { day: 'numeric', month: 'numeric', year: '2-digit' });
const parseOdds = (s) => { const v = parseFloat(String(s).replace(',', '.')); return v > 1 ? v : null; };
const fixtureTime = (t) => { const d = new Date(t); return Number.isNaN(+d) ? null : d; };
const leagueMeta = (id) => state.index.leagues.find((l) => l.id === id);
const matchKey = () => (state.match ? `${state.match.lg.id}|${state.match.h}|${state.match.a}` : '');

async function loadIndex() {
  const r = await fetch('data/index.json', { cache: 'no-cache' });
  if (!r.ok) throw new Error('index');
  state.index = await r.json();
  state.entries = state.index.leagues.flatMap((l) => l.teams.map((name) => ({ name, league: l.id, flag: l.flag, country: l.country })));
}

async function loadLeague(id) {
  if (state.leagues.has(id)) return state.leagues.get(id);
  const r = await fetch(`data/leagues/${id}.json`, { cache: 'no-cache' });
  if (!r.ok) throw new Error('league');
  const lg = await r.json();
  state.leagues.set(id, lg);
  return lg;
}

// ---------------------------------------------------------------- routing

function route() {
  const parts = (location.hash.slice(1) || '/').split('/').filter(Boolean).map(decodeURIComponent);
  window.scrollTo(0, 0);
  if (parts[0] === 'm' && parts.length >= 4) return renderMatch(parts[1], parts[2], parts[3]);
  if (parts[0] === 'info') return renderInfo();
  return renderHome();
}
const goMatch = (lg, h, a) => { location.hash = `#/m/${encodeURIComponent(lg)}/${encodeURIComponent(h)}/${encodeURIComponent(a)}`; };

// ---------------------------------------------------------------- home

function renderHome() {
  state.match = null;
  const upd = new Date(state.index.updated);
  const filter = store.get('fxFilter', 'all');
  const leagues = state.index.leagues;
  $app.innerHTML = `
    <section class="card">
      <div class="pick">
        ${['Γηπεδούχος', 'Φιλοξενούμενη'].map((lab, i) => `
          <div class="field">
            <label for="t${i}">${lab}</label>
            <input id="t${i}" data-side="${i}" placeholder="${i ? 'π.χ. Παναθηναϊκός' : 'π.χ. Ολυμπιακός'}" autocomplete="off" autocapitalize="off" spellcheck="false" value="${esc(state.pick[i]?.name || '')}">
            <span class="chosen">${state.pick[i] ? esc(state.pick[i].flag + ' ' + state.pick[i].country) : ''}</span>
            <div class="suggest" data-for="${i}" hidden></div>
          </div>`).join('')}
        <div class="row-actions">
          <button class="btn ghost" data-act="swap" aria-label="Αντιστροφή">⇅</button>
          <button class="btn" data-act="go">Ανάλυση</button>
        </div>
        <p class="error" id="err" hidden></p>
      </div>
    </section>
    <section>
      <div class="chips" id="fxchips">
        <button class="chip ${filter === 'all' ? 'on' : ''}" data-fx="all">Όλα</button>
        ${leagues.map((l) => `<button class="chip ${filter === l.id ? 'on' : ''}" data-fx="${l.id}">${l.flag} ${esc(l.country)}</button>`).join('')}
      </div>
      <div id="fxlist">${fixturesHtml(filter)}</div>
    </section>
    <p class="disclaimer">Ενημέρωση δεδομένων: ${upd.toLocaleDateString('el-GR')} ${timeFmt.format(upd)} · ${leagues.length} πρωταθλήματα<br>
    Οι πιθανότητες είναι εκτιμήσεις, όχι εγγυήσεις. Παίζε υπεύθυνα, μόνο 18+.</p>`;
}

function fixturesHtml(filter) {
  const from = Date.now() - 2 * 3600e3; // hide games that are already over
  const fx = state.index.fixtures.filter((f) => (filter === 'all' || f[0] === filter) && !(fixtureTime(f[1]) && fixtureTime(f[1]) < from));
  if (!fx.length) return '<p class="empty">Δεν υπάρχουν προγραμματισμένοι αγώνες τις επόμενες μέρες.</p>';
  let html = '', lastDay = '';
  for (const [id, t, home, away, q] of fx.slice(0, 160)) {
    const d = fixtureTime(t);
    const day = d ? dayFmt.format(d) : t;
    if (day !== lastDay) { html += `<div class="day">${esc(day)}</div>`; lastDay = day; }
    const l = leagueMeta(id);
    html += `<button class="fx" data-m="${esc(id)}|${esc(home)}|${esc(away)}">
      <span class="t">${d ? timeFmt.format(d) : ''}</span>
      <span class="teams">${esc(home)}<br>${esc(away)}<br><span class="lg">${l.flag} ${esc(l.country)} · ${esc(l.name)}</span></span>
      <span class="mini">${q ? `1 ${pct(q[0])}<br>X ${pct(q[1])}<br>2 ${pct(q[2])}` : ''}</span>
    </button>`;
  }
  return html;
}

function showSuggest(side) {
  const input = document.getElementById('t' + side);
  const box = document.querySelector(`.suggest[data-for="${side}"]`);
  const other = state.pick[1 - side];
  let list = searchTeams(state.entries, input.value, 12);
  if (other) list = [...list.filter((e) => e.league === other.league), ...list.filter((e) => e.league !== other.league)];
  list = list.slice(0, 8);
  if (!list.length || !input.value.trim()) { box.hidden = true; return; }
  box.innerHTML = list.map((e, i) => `<button data-pick="${side}" data-i="${i}" class="${i === 0 ? 'hl' : ''}"><span>${esc(e.name)}</span><span class="muted small">${e.flag} ${esc(e.country)}</span></button>`).join('');
  box._list = list;
  box.hidden = false;
}

function choose(side, entry) {
  state.pick[side] = entry;
  const input = document.getElementById('t' + side);
  input.value = entry.name;
  input.parentElement.querySelector('.chosen').textContent = `${entry.flag} ${entry.country}`;
  document.querySelector(`.suggest[data-for="${side}"]`).hidden = true;
  if (side === 0 && !state.pick[1]) document.getElementById('t1').focus();
}

function startAnalysis() {
  const err = document.getElementById('err');
  for (const side of [0, 1]) {
    const v = document.getElementById('t' + side).value;
    if (!state.pick[side] || state.pick[side].name !== v) {
      const other = state.pick[1 - side];
      let list = searchTeams(state.entries, v, 12);
      if (other) list = [...list.filter((e) => e.league === other.league), ...list];
      state.pick[side] = list[0] || null;
    }
  }
  const [h, a] = state.pick;
  const fail = (msg) => { err.textContent = msg; err.hidden = false; };
  if (!h || !a) return fail('Δεν βρέθηκε ομάδα. Γράψε τα πρώτα γράμματα και διάλεξε από τη λίστα.');
  if (h.name === a.name && h.league === a.league) return fail('Διάλεξε δύο διαφορετικές ομάδες.');
  if (h.league !== a.league) return fail(`Η ${h.name} (${h.country}) και η ${a.name} (${a.country}) είναι σε διαφορετικά πρωταθλήματα. Η ανάλυση γίνεται για αγώνες εντός πρωταθλήματος.`);
  goMatch(h.league, h.name, a.name);
}

// ---------------------------------------------------------------- match page

const TABS = [
  ['picks', 'Προτάσεις'], ['result', 'Αποτέλεσμα'], ['goals', 'Γκολ'], ['half', 'Ημίχρονα'], ['combo', 'Συνδυασμοί'],
  ['corners', 'Κόρνερ'], ['cards', 'Κάρτες'], ['shots', 'Σουτ/Φάουλ'], ['players', 'Σκόρερ'],
  ['form', 'Φόρμα'], ['h2h', 'Μεταξύ τους'], ['abs', 'Απουσίες'], ['scores', 'Σκορ'],
];

async function renderMatch(id, hn, an) {
  $app.innerHTML = '<p class="loading">Ανάλυση…</p>';
  let lg;
  try { lg = await loadLeague(id); } catch { $app.innerHTML = '<p class="empty">Δεν φορτώθηκαν τα δεδομένα του πρωταθλήματος.</p>'; return; }
  const h = lg.teams.indexOf(hn), a = lg.teams.indexOf(an);
  if (h < 0 || a < 0) { $app.innerHTML = '<p class="empty">Δεν βρέθηκε η ομάδα σε αυτό το πρωτάθλημα.</p>'; return; }
  const fx = (lg.fixtures || []).find((f) => f[2] === h && f[3] === a);
  const overrides = store.get(`ovr|${id}|${h}|${a}`, [{}, {}]);
  const refIdx = fx?.[4] ? lg.refs.indexOf(fx[4]) : -1;
  state.match = { lg, h, a, fx, overrides, referee: refIdx >= 0 ? refIdx : null };
  state.pick = [{ name: hn, league: id, flag: lg.flag, country: lg.country }, { name: an, league: id, flag: lg.flag, country: lg.country }];
  computeMatch();
  drawMatch();
}

function computeMatch() {
  const m = state.match;
  m.r = analyze(m.lg, m.h, m.a, { params: state.index.params, overrides: m.overrides, referee: m.referee });
}

function availableTabs() {
  const { r } = state.match;
  const cats = new Set(r.markets.map((g) => g.cat));
  return TABS.filter(([k]) => !['corners', 'cards', 'shots', 'players'].includes(k) || cats.has(k));
}

function drawMatch() {
  const { lg, r, fx } = state.match;
  const tabs = availableTabs();
  if (!tabs.some(([k]) => k === state.tab)) state.tab = 'picks';
  const d = fx ? fixtureTime(fx[1]) : null;
  const [p1, px, p2] = r.p;
  const best = topScore(r.M);
  $app.innerHTML = `
    <section class="card">
      <div class="mh-meta">${lg.flag} ${esc(lg.country)} · ${esc(lg.name)}${d ? ` · ${dayFmt.format(d)} ${timeFmt.format(d)}` : ''}</div>
      <div class="mh-teams">
        <div><div class="tn">${esc(r.names[0])}</div></div>
        <div><div class="xg">${f2(r.lh)} – ${f2(r.la)}</div><div class="vs">αναμενόμενα γκολ</div></div>
        <div><div class="tn">${esc(r.names[1])}</div></div>
      </div>
      <div class="bar">
        <div class="b1" style="flex:${p1}">${pct(p1)}</div><div class="bx" style="flex:${px}">${pct(px)}</div><div class="b2" style="flex:${p2}">${pct(p2)}</div>
      </div>
      <div class="kpis">
        <div class="kpi"><b>${best[0]}-${best[1]}</b><span>πιθανότερο σκορ</span></div>
        <div class="kpi"><b>${pct(itemP('ou', 'Over 2.5'))}</b><span>Over 2.5</span></div>
        <div class="kpi"><b>${pct(itemP('gg', 'Goal (G/G)'))}</b><span>G/G</span></div>
        <div class="kpi"><b>${r.stats.corners ? f1(r.stats.corners.mh + r.stats.corners.ma) : '–'}</b><span>κόρνερ (μ.ό.)</span></div>
      </div>
      ${r.warnings.map((w) => `<div class="warnbox">${esc(w)}</div>`).join('')}
    </section>
    <nav class="tabs">${tabs.map(([k, l]) => `<button class="tab ${k === state.tab ? 'on' : ''}" data-tab="${k}">${l}</button>`).join('')}</nav>
    <div id="pane">${paneHtml()}</div>
    <p class="disclaimer">Οι πιθανότητες βγαίνουν από τα στατιστικά των ομάδων, όχι από αποδόσεις. «Δίκαιη» είναι η απόδοση χωρίς γκανιότα· αξία έχει ένα στοίχημα μόνο αν ο bookmaker δίνει περισσότερα.</p>`;
}

function itemP(gid, label) {
  const g = state.match.r.markets.find((x) => x.id === gid);
  return g?.items.find((i) => i.l === label)?.pw ?? null;
}

function topScore(M) {
  let b = [0, 0], bp = 0;
  for (let i = 0; i < M.length; i++) for (let j = 0; j < M.length; j++) if (M[i][j] > bp) { bp = M[i][j]; b = [i, j]; }
  return b;
}

function paneHtml() {
  const { r } = state.match;
  switch (state.tab) {
    case 'picks': return picksHtml();
    case 'form': return formHtml();
    case 'h2h': return h2hHtml();
    case 'abs': return absHtml();
    case 'scores': return scoresHtml();
    case 'cards': return refereeHtml() + groupsHtml(r.markets.filter((g) => g.cat === 'cards'));
    default: return groupsHtml(r.markets.filter((g) => g.cat === state.tab));
  }
}

// ---------------------------------------------------------------- market rows and odds

const oddsMap = () => store.get(`odds|${matchKey()}`, {});
const bankroll = () => store.get('bankroll', 100);

function evHtml(it, o) {
  if (!o) return '';
  const ev = evAt(it, o);
  const stake = Math.min(0.05, kelly(it, o) * 0.25) * bankroll();
  if (ev >= 0.03) return `Αξία +${(ev * 100).toFixed(1)}% · ποντάρισμα ≈ ${stake.toFixed(2)}€ (¼ Kelly)`;
  if (ev > -0.03) return `Οριακό (${ev >= 0 ? '+' : ''}${(ev * 100).toFixed(1)}%) – δεν αξίζει το ρίσκο`;
  return `Χωρίς αξία (${(ev * 100).toFixed(1)}%)`;
}
const rowClass = (it, o) => (o ? (evAt(it, o) >= 0.03 ? 'value' : 'novalue') : '');

function rowHtml(g, it, odds) {
  const key = `${g.id}|${it.l}`, o = odds[key] || null;
  const fair = fairOdds(it);
  return `<div class="mrow ${rowClass(it, o)} ${it.pw >= 0.6 ? 'hi' : ''}" data-key="${esc(key)}">
    <div class="l">${esc(it.l)}${it.sub ? `<span class="sub">${esc(it.sub)}</span>` : ''}</div>
    <div class="pct">${pct(it.pw)}</div>
    <div class="odd">${odd(fair)}</div>
    <input inputmode="decimal" placeholder="απόδ." aria-label="Απόδοση bookmaker για ${esc(it.l)}" value="${o ? o : ''}" data-odds="${esc(key)}">
    <div class="meter"><i style="width:${Math.min(100, it.pw * 100).toFixed(1)}%"></i></div>
    <div class="ev">${evHtml(it, o)}</div>
  </div>`;
}

function groupHtml(g, odds) {
  const mean = Array.isArray(g.mean) ? `μ.ό. ${f1(g.mean[0])} – ${f1(g.mean[1])}` : g.mean ? `μ.ό. ${f1(g.mean)}` : '';
  return `<div class="group"><h3>${esc(g.t)} <span class="muted">${mean}</span></h3>
    <div class="ghead"><span>Επιλογή</span><span>Πιθ.</span><span>Δίκαιη</span><span>Δική σου</span></div>
    ${g.items.map((it) => rowHtml(g, it, odds)).join('')}
    ${g.note ? `<div class="note">${esc(g.note)}</div>` : ''}</div>`;
}

function groupsHtml(groups) {
  if (!groups.length) return '<p class="empty">Δεν υπάρχουν δεδομένα για αυτή την κατηγορία σε αυτό το πρωτάθλημα.</p>';
  const odds = oddsMap();
  let extra = '';
  if (state.tab === 'players') extra = '<p class="muted small">Οι πιθανότητες ισχύουν εφόσον ο παίκτης αγωνιστεί (αλλιώς το στοίχημα συνήθως ακυρώνεται). Υπολογίζονται από xG/γκολ ανά 90\', τα λεπτά που παίζει και τα αναμενόμενα γκολ της ομάδας του.</p>';
  return extra + groups.map((g) => groupHtml(g, odds)).join('');
}

function onOddsInput(input) {
  const key = input.dataset.odds;
  const odds = oddsMap();
  const v = parseOdds(input.value);
  if (v) odds[key] = v; else delete odds[key];
  store.set(`odds|${matchKey()}`, odds);
  // refresh every row showing this selection (it may appear in more than one tab)
  for (const row of document.querySelectorAll('.mrow')) {
    if (row.dataset.key !== key) continue;
    const it = findItem(key);
    if (!it) continue;
    row.classList.remove('value', 'novalue');
    const c = rowClass(it, v);
    if (c) row.classList.add(c);
    row.querySelector('.ev').textContent = evHtml(it, v);
    const inp = row.querySelector('input');
    if (inp !== input) inp.value = v || '';
  }
  const vb = document.getElementById('valuebets');
  if (vb) vb.innerHTML = valueBetsHtml();
}

function findItem(key) {
  const i = key.indexOf('|');
  const g = state.match.r.markets.find((x) => x.id === key.slice(0, i));
  return g?.items.find((it) => it.l === key.slice(i + 1));
}

// ---------------------------------------------------------------- suggestions

// Markets considered for the "most likely" list (one pick per market, sensible odds range).
const PICK_GROUPS = ['1x2', 'dc', 'dnb', 'ou', 'gg', 'team', 'ht', 'htou', 'ah', 'corners', 'cornersT', 'cards', 'cardsT', 'sot', 'rgg', 'rou2.5', 'cleansheet', 'first', 'halfx'];
const QUICK = [['1x2', 0], ['1x2', 1], ['1x2', 2], ['dc', 0], ['dc', 1], ['ou', 2], ['ou', 3], ['ou', 4], ['ou', 5], ['gg', 0], ['gg', 1]];

function valueBetsHtml() {
  const odds = oddsMap();
  const list = [];
  for (const [key, o] of Object.entries(odds)) {
    const it = findItem(key);
    if (!it) continue;
    const ev = evAt(it, o);
    if (ev >= 0.03) list.push([ev, key, it, o]);
  }
  list.sort((x, y) => y[0] - x[0]);
  if (!list.length) return `<p class="muted small" style="padding:0 14px 12px">${Object.keys(odds).length ? 'Καμία από τις αποδόσεις που έβαλες δεν έχει αξία. Αυτό είναι το πιο συχνό – μην ποντάρεις μόνο και μόνο για να παίξεις.' : 'Γράψε τις αποδόσεις του bookmaker (εδώ ή σε οποιαδήποτε καρτέλα) και θα δεις ποια στοιχήματα έχουν θετική αναμενόμενη απόδοση.'}</p>`;
  return list.map(([ev, key, it, o]) => {
    const g = state.match.r.markets.find((x) => x.id === key.split('|')[0]);
    const stake = Math.min(0.05, kelly(it, o) * 0.25) * bankroll();
    return `<div class="pick-item"><div><div class="what">${esc(it.l)} <span class="tag good">+${(ev * 100).toFixed(1)}%</span></div><div class="grp">${esc(g.t)} · απόδοση ${o} (δίκαιη ${odd(fairOdds(it))})</div></div>
      <div><div class="p">${pct(it.pw)}</div><div class="o">${stake.toFixed(2)}€</div></div></div>`;
  }).join('');
}

function picksHtml() {
  const { r } = state.match;
  const odds = oddsMap();
  const cands = [];
  for (const g of r.markets) {
    if (!PICK_GROUPS.includes(g.id)) continue;
    const ok = g.items.filter((it) => it.pw + it.pl > 0.98 && it.pw >= 0.55 && it.pw <= 0.88);
    ok.sort((x, y) => y.pw - x.pw);
    if (ok[0]) cands.push([g, ok[0]]);
  }
  cands.sort((x, y) => y[1].pw - x[1].pw);
  const mid = [];
  for (const g of r.markets) {
    if (!PICK_GROUPS.includes(g.id)) continue;
    for (const it of g.items) { const o = fairOdds(it); if (o >= 1.8 && o <= 3.2) mid.push([g, it]); }
  }
  mid.sort((x, y) => y[1].pw - x[1].pw);
  const seen = new Set(), mids = mid.filter(([g]) => !seen.has(g.id) && seen.add(g.id)).slice(0, 6);
  const line = ([g, it]) => `<div class="pick-item"><div><div class="what">${esc(it.l)}</div><div class="grp">${esc(g.t)} · αξίζει από απόδοση ${odd(fairOdds(it) * 1.05)}</div></div>
      <div><div class="p">${pct(it.pw)}</div><div class="o">δίκαιη ${odd(fairOdds(it))}</div></div></div>`;
  const quick = QUICK.map(([gid, i]) => { const g = r.markets.find((x) => x.id === gid); return g && g.items[i] ? rowHtml(g, g.items[i], odds) : ''; }).join('');
  const fav = r.p[0] > r.p[2] ? [r.names[0], r.p[0]] : [r.names[1], r.p[2]];
  const tot = r.lh + r.la;
  return `
    <div class="group"><h3>Σύνοψη</h3><div style="padding:10px 14px" class="small">
      Φαβορί: <b>${esc(fav[0])}</b> (${pct(fav[1])}), ισοπαλία ${pct(r.p[1])}. Αναμένονται <b>${f1(tot)}</b> γκολ
      (${tot > 2.7 ? 'ανοιχτό παιχνίδι' : tot < 2.2 ? 'κλειστό παιχνίδι' : 'μέσα στο μέσο όρο'}).
      ${r.eff.some((e) => e.attLoss > 0.02) ? `Οι απουσίες μειώνουν την επίθεση: ${r.names.map((n, i) => `${esc(n)} ${Math.round((r.eff[i].att - 1) * 100)}%`).join(', ')}.` : ''}
      ${r.meetings.length ? `Μεταξύ τους: ${r.meetings.length} αγώνες στα δεδομένα.` : ''}
    </div></div>
    <div class="group"><h3>Value bets <span class="muted">με τις δικές σου αποδόσεις</span></h3><div id="valuebets">${valueBetsHtml()}</div>
      <div class="bankroll">Κεφάλαιο (€): <input data-bank inputmode="decimal" value="${bankroll()}"> <span class="muted tiny">για το προτεινόμενο ποντάρισμα (¼ Kelly, max 5%)</span></div></div>
    <div class="group"><h3>Γρήγορη εισαγωγή αποδόσεων</h3>
      <div class="ghead"><span>Επιλογή</span><span>Πιθ.</span><span>Δίκαιη</span><span>Δική σου</span></div>${quick}</div>
    <div class="group"><h3>Πιο πιθανές επιλογές</h3>${cands.slice(0, 8).map(line).join('') || '<p class="muted small" style="padding:0 14px">—</p>'}
      <div class="note">Πιθανό δεν σημαίνει κερδοφόρο: παίξε μόνο αν η απόδοση του bookmaker είναι πάνω από το «αξίζει από».</div></div>
    <div class="group"><h3>Ισορροπημένες (απόδοση ~1.8–3.2)</h3>${mids.map(line).join('')}</div>`;
}

// ---------------------------------------------------------------- correct score grid

function scoresHtml() {
  const { r } = state.match;
  const N = 6;
  let max = 0;
  for (let i = 0; i < N; i++) for (let j = 0; j < N; j++) max = Math.max(max, r.M[i][j]);
  let cells = '<div class="h"></div>' + Array.from({ length: N }, (_, j) => `<div class="h">${j}</div>`).join('');
  for (let i = 0; i < N; i++) {
    cells += `<div class="h">${i}</div>`;
    for (let j = 0; j < N; j++) {
      const p = r.M[i][j], a = (p / max) * 0.85;
      const col = i > j ? '78,161,255' : i === j ? '154,167,180' : '255,138,76';
      cells += `<div style="background:rgba(${col},${a.toFixed(3)})">${(p * 100).toFixed(1)}</div>`;
    }
  }
  return `<div class="group"><h3>Πίνακας σκορ (%) <span class="muted">γραμμές: ${esc(r.names[0])} · στήλες: ${esc(r.names[1])}</span></h3><div class="grid-scores">${cells}</div></div>`
    + groupsHtml(r.markets.filter((g) => g.id === 'cs'));
}

// ---------------------------------------------------------------- referee

function refereeHtml() {
  const { lg, referee, r } = state.match;
  const refs = lg.fit.refs;
  if (!refs || !Object.keys(refs).length) return '';
  const list = Object.entries(refs).filter(([, v]) => v.n >= 3).sort((x, y) => lg.refs[x[0]].localeCompare(lg.refs[y[0]]));
  return `<div class="group"><h3>Διαιτητής <span class="muted">${r.refFactor !== 1 ? `κάρτες ×${r.refFactor.toFixed(2)}` : ''}</span></h3><div style="padding:10px 14px">
    <select class="ref" data-ref><option value="">Άγνωστος / μέσος όρος</option>
    ${list.map(([k, v]) => `<option value="${k}" ${String(referee) === k ? 'selected' : ''}>${esc(lg.refs[k])} (×${v.f.toFixed(2)}, ${v.n} αγ.)</option>`).join('')}</select></div></div>`;
}

// ---------------------------------------------------------------- form

function formList(rows) {
  return `<table class="list"><tr><th>Ημ/νία</th><th>Αντίπαλος</th><th class="n">Σκορ</th><th class="n">xG</th><th class="n">Κόρν.</th></tr>
    ${rows.map((x) => `<tr><td class="muted">${shortDate(x.d)}</td><td>${x.home ? '' : '@ '}${esc(x.opp)}</td>
      <td class="n"><span class="res ${x.res}">${x.gf}-${x.ga}</span></td><td class="n">${x.xgf !== null ? `${f1(x.xgf)}-${f1(x.xga)}` : '–'}</td>
      <td class="n">${x.cf !== null ? `${x.cf}-${x.ca}` : '–'}</td></tr>`).join('')}</table>`;
}

function formHtml() {
  const { lg, h, a, r } = state.match;
  const tbl = table(lg);
  const pos = (t) => tbl.findIndex((x) => x.t === t) + 1;
  const all = [h, a].map((t) => teamForm(lg, t, 'all', 6));
  const venue = [teamForm(lg, h, 'home', 6), teamForm(lg, a, 'away', 6)];
  const av = [h, a].map((t) => averages(teamForm(lg, t, 'all', 10)));
  const cmp = (lab, k, d = 1) => `<div class="lft">${av[0][k] === null ? '–' : av[0][k].toFixed(d)}</div><div class="c">${lab}</div><div class="rgt">${av[1][k] === null ? '–' : av[1][k].toFixed(d)}</div>`;
  const pctRow = (lab, k) => `<div class="lft">${pct(av[0][k])}</div><div class="c">${lab}</div><div class="rgt">${pct(av[1][k])}</div>`;
  const teamCard = (i) => {
    const t = [h, a][i], row = tbl.find((x) => x.t === t);
    return `<div class="group"><h3>${esc(r.names[i])} <span class="muted">${row ? `${pos(t)}η θέση · ${row.pts} β. · ${row.gf}-${row.ga}` : ''}</span></h3>
      <div style="padding:10px 14px"><div class="form-chips">${all[i].map((x) => `<span class="fc ${x.res}">${x.res === 'W' ? 'Ν' : x.res === 'D' ? 'Ι' : 'Η'}</span>`).join('')}</div></div>
      <div style="padding:0 14px 6px">${formList(all[i])}</div>
      <details style="padding:0 14px 10px"><summary>${i ? 'Μόνο εκτός έδρας' : 'Μόνο εντός έδρας'}</summary>${formList(venue[i])}</details></div>`;
  };
  return `
    <div class="group"><h3>Μέσοι όροι τελευταίων 10 αγώνων</h3><div style="padding:12px 14px" class="stat-cmp">
      <div class="lft">${esc(r.names[0])}</div><div class="c"></div><div class="rgt">${esc(r.names[1])}</div>
      ${cmp('Γκολ υπέρ', 'gf', 2)}${cmp('Γκολ κατά', 'ga', 2)}${cmp('xG υπέρ', 'xgf', 2)}${cmp('xG κατά', 'xga', 2)}
      ${cmp('Κόρνερ υπέρ', 'cf')}${cmp('Κόρνερ κατά', 'ca')}${cmp('Κίτρινες', 'yf')}${cmp('Σουτ στην εστία', 'sf')}
      ${pctRow('G/G', 'btts')}${pctRow('Over 2.5', 'o25')}
      <div class="lft">${av[0].pts}</div><div class="c">Βαθμοί</div><div class="rgt">${av[1].pts}</div>
    </div><div class="note">Το μοντέλο δίνει μεγαλύτερο βάρος στους πιο πρόσφατους αγώνες (χρόνος ημιζωής ${state.index.params.halfLife} ημέρες, επιλεγμένος από το backtest) και διορθώνει για τη δυναμικότητα των αντιπάλων.</div></div>
    ${teamCard(0)}${teamCard(1)}
    <div class="group"><h3>Βαθμολογία</h3><div style="padding:4px 14px 8px"><table class="list"><tr><th>#</th><th>Ομάδα</th><th class="n">Αγ</th><th class="n">Γκολ</th><th class="n">Β</th></tr>
      ${tbl.map((x, i) => `<tr style="${x.t === h || x.t === a ? 'font-weight:700' : ''}"><td>${i + 1}</td><td>${esc(lg.teams[x.t])}</td><td class="n">${x.p}</td><td class="n">${x.gf}-${x.ga}</td><td class="n">${x.pts}</td></tr>`).join('')}</table></div></div>`;
}

// ---------------------------------------------------------------- head to head

function h2hHtml() {
  const { lg, h, r } = state.match;
  const ms = r.meetings.slice(-10).reverse();
  if (!ms.length) return '<p class="empty">Δεν υπάρχουν πρόσφατοι αγώνες μεταξύ τους στο πρωτάθλημα (τελευταία 6 χρόνια).</p>';
  let w = 0, d = 0, l = 0, goals = 0, gg = 0, o25 = 0;
  for (const m of ms) {
    const hg = m[F.HG], ag = m[F.AG], us = m[F.H] === h ? hg - ag : ag - hg;
    if (us > 0) w++; else if (us === 0) d++; else l++;
    goals += hg + ag; if (hg > 0 && ag > 0) gg++; if (hg + ag > 2.5) o25++;
  }
  const n = ms.length, wgt = state.index.params.h2h;
  const eff = (f) => `${f >= 1 ? '+' : ''}${Math.round((f - 1) * 100)}%`;
  return `<div class="group"><h3>Τελευταίοι ${n} αγώνες</h3><div style="padding:12px 14px" class="stat-cmp">
      <div class="lft">${w}</div><div class="c">Νίκες ${esc(r.names[0])} · Ισοπαλίες ${d} · Νίκες ${esc(r.names[1])}</div><div class="rgt">${l}</div>
      <div class="lft">${(goals / n).toFixed(2)}</div><div class="c">γκολ ανά αγώνα · G/G · Over 2.5</div><div class="rgt">${pct(gg / n)} · ${pct(o25 / n)}</div>
    </div><div class="note">${wgt ? `Επίδραση στο μοντέλο: γκολ ${esc(r.names[0])} ${eff(r.h2h.fh)}, ${esc(r.names[1])} ${eff(r.h2h.fa)} (μέγιστο ±10%, όσο περισσότεροι αγώνες τόσο μεγαλύτερο βάρος – το backtest έδειξε ότι βοηθά λίγο).` : 'Το backtest έδειξε ότι οι μεταξύ τους αγώνες δεν βελτιώνουν την πρόβλεψη, γι\' αυτό φαίνονται μόνο ενημερωτικά.'}</div></div>
    <div class="group"><div style="padding:4px 14px 8px"><table class="list"><tr><th>Ημ/νία</th><th>Αγώνας</th><th class="n">Τελ.</th><th class="n">Ημ.</th></tr>
      ${ms.map((m) => `<tr><td class="muted">${shortDate(m[F.D])}</td><td>${esc(lg.teams[m[F.H]])} – ${esc(lg.teams[m[F.A]])}</td><td class="n"><b>${m[F.HG]}-${m[F.AG]}</b></td><td class="n muted">${m[F.HHG] !== null && m[F.HHG] !== undefined ? `${m[F.HHG]}-${m[F.HAG]}` : '–'}</td></tr>`).join('')}
    </table></div></div>`;
}

// ---------------------------------------------------------------- absences

const TYPE = { i: 'Τραυματίας', s: 'Τιμωρία', d: 'Αμφίβολος' };

function absHtml() {
  const { lg, h, a, r, overrides } = state.match;
  const out = [];
  [h, a].forEach((t, side) => {
    const listed = (lg.inj || []).filter((x) => x[0] === t);
    const sq = r.squads[side];
    const names = new Set(listed.map((x) => x[1]));
    for (const n of Object.keys(overrides[side] || {})) names.add(n);
    const rows = [...names].map((name) => {
      const info = listed.find((x) => x[1] === name);
      const p = sq.find((x) => x.name === name);
      const v = p ? p.avail : overrides[side]?.[name] ?? (info ? (info[2] === 'd' ? (info[5] ?? 50) / 100 : 0) : 1);
      const why = info ? `${TYPE[info[2]] || ''}${info[3] && info[3] !== 'injury' && info[3] !== 'suspension' ? ` · ${info[3]}` : ''}${info[4] ? ` · επιστροφή: ${info[4]}` : ''}` : 'Δική σου αλλαγή';
      const st = p ? `${p.goals} γκολ · ${Math.round(p.minutes)}' σε ${p.games} αγ.` : 'χωρίς στατιστικά – δεν επηρεάζει το μοντέλο';
      return `<div class="abs-row"><div><div>${esc(name)} <span class="muted tiny">${p ? p.pos : ''}</span></div><div class="why">${esc(why)} · ${esc(st)}</div></div>
        <div class="seg" data-side="${side}" data-player="${esc(name)}">
          <button data-av="1" class="${v >= 0.99 ? 'on' : ''}">Παίζει</button><button data-av="0.5" class="${v > 0.01 && v < 0.99 ? 'on' : ''}">50/50</button><button data-av="0" class="${v <= 0.01 ? 'on' : ''}">Εκτός</button></div></div>`;
    });
    const e = r.eff[side];
    const candidates = sq.filter((p) => !names.has(p.name) && p.share > 0.15).sort((x, y) => y.share - x.share);
    out.push(`<div class="group"><h3>${esc(r.names[side])} <span class="muted">${listed.length} στη λίστα</span></h3><div style="padding:6px 14px 10px">
      <div class="effect"><span>Επίθεση ${Math.round((e.att - 1) * 100)}%</span><span>Δεχόμενα γκολ ${e.def >= 1 ? '+' : ''}${Math.round((e.def - 1) * 100)}%</span></div>
      ${rows.join('') || '<p class="muted small">Καμία γνωστή απουσία.</p>'}
      ${candidates.length ? `<select class="ref" data-add="${side}" style="margin-top:8px"><option value="">+ Πρόσθεσε απουσία…</option>${candidates.map((p) => `<option>${esc(p.name)}</option>`).join('')}</select>` : ''}
    </div></div>`);
  });
  return `<p class="muted small">Πηγή: ${esc({ fpl: 'Fantasy Premier League', fotmob: 'FotMob', sportsgambler: 'Sportsgambler' }[lg.injSrc] || '—')} (ενημέρωση ${new Date(lg.updated).toLocaleDateString('el-GR')}). Άλλαξε ό,τι ξέρεις καλύτερα (π.χ. ανακοινωμένη ενδεκάδα) και όλες οι πιθανότητες ξαναϋπολογίζονται.</p>`
    + out.join('')
    + '<p class="muted tiny">Πώς μετράει: η επίθεση μειώνεται κατά το 40% του μεριδίου του παίκτη στα xG/γκολ και ασίστ της ομάδας (οι αντικαταστάτες καλύπτουν το υπόλοιπο). Η άμυνα επιβαρύνεται ανάλογα με τη θέση και τα λεπτά του (τερματοφύλακας > αμυντικός > μέσος). Ανώτατο όριο: −25% επίθεση, +25% δεχόμενα.</p>';
}

function setAvail(side, name, v) {
  const m = state.match;
  m.overrides[side] = { ...(m.overrides[side] || {}), [name]: v };
  store.set(`ovr|${m.lg.id}|${m.h}|${m.a}`, m.overrides);
  computeMatch();
  drawMatch();
}

// ---------------------------------------------------------------- info

function renderInfo() {
  state.match = null;
  const { params, leagues } = state.index;
  const rows = leagues.map((l) => {
    const b = l.bt || {};
    const gap = b.ll !== null && b.llMkt !== null ? b.ll - b.llMkt : null;
    return `<tr><td>${l.flag} ${esc(l.country)}</td><td class="n">${b.n ?? '–'}</td><td class="n">${b.acc !== null && b.acc !== undefined ? Math.round(b.acc * 100) + '%' : '–'}</td>
      <td class="n">${b.ll !== null && b.ll !== undefined ? b.ll.toFixed(3) : '–'}</td><td class="n">${gap !== null ? (gap > 0 ? '+' : '') + gap.toFixed(3) : '–'}</td>
      <td class="n" style="color:${b.roi > 0 ? 'var(--accent)' : 'var(--bad)'}">${b.roi !== null && b.roi !== undefined ? (b.roi > 0 ? '+' : '') + Math.round(b.roi * 100) + '%' : '–'}</td></tr>`;
  }).join('');
  $app.innerHTML = `<section class="card info">
    <h2>Πώς δουλεύει</h2>
    <p>Για κάθε πρωτάθλημα το μοντέλο υπολογίζει δύναμη επίθεσης και άμυνας κάθε ομάδας από τους αγώνες των τελευταίων ~16 μηνών, με περισσότερο βάρος στους πρόσφατους. Μετράει γκολ μαζί με xG/σουτ στην εστία, που είναι πιο σταθερός δείκτης από τα σκέτα γκολ, και διορθώνει για το πόσο δυνατοί ήταν οι αντίπαλοι. Οι αποδόσεις των bookmakers δεν χρησιμοποιούνται στην πρόβλεψη.</p>
    <p>Από τα αναμενόμενα γκολ βγαίνει η κατανομή του σκορ (Poisson με διόρθωση Dixon–Coles για τα 0-0/1-1), και από εκεί όλες οι αγορές: 1Χ2, Over/Under, G/G, χάντικαπ, ημίχρονα, ώρα γκολ κ.λπ. Κόρνερ, κάρτες, σουτ και φάουλ έχουν δικές τους βαθμολογίες ανά ομάδα. Οι κάρτες λαμβάνουν υπόψη και τον διαιτητή όπου υπάρχει. Οι σκόρερ βγαίνουν από xG/γκολ ανά 90' και λεπτά συμμετοχής.</p>
    <p>Οι απουσίες (τραυματισμοί/τιμωρίες) μειώνουν την επίθεση ή επιβαρύνουν την άμυνα ανάλογα με τη συνεισφορά του παίκτη. Οι μεταξύ τους αγώνες δίνουν μικρή διόρθωση (έως ±10%).</p>
    <p>Δύο φορές την εβδομάδα (Τρίτη και Παρασκευή) τα δεδομένα ανανεώνονται αυτόματα. Τα παλιά αρχεία αντικαθίστανται και κρατιέται σταθερό εύρος σεζόν, οπότε η εφαρμογή δεν βαραίνει με τον καιρό.</p>
    <h3>Αυτορρύθμιση (backtest)</h3>
    <p>Σε κάθε ενημέρωση το μοντέλο «ξαναπαίζει» τον τελευταίο χρόνο: για κάθε εβδομάδα κάνει πρόβλεψη μόνο με όσα ήξερε ως τότε. Διαλέγει έτσι μόνο του τις ρυθμίσεις. Τώρα: χρόνος ημιζωής φόρμας ${params.halfLife} ημέρες, βάρος xG ${Math.round(params.wxg * 100)}%, βάρος μεταξύ τους αγώνων ${params.h2h}.</p>
    <div style="overflow-x:auto"><table class="list"><tr><th>Πρωτάθλημα</th><th class="n">Αγ.</th><th class="n">Εύστοχο φαβορί</th><th class="n">Log-loss</th><th class="n">vs αγορά</th><th class="n">ROI value</th></tr>${rows}</table></div>
    <p class="muted small">Log-loss: όσο μικρότερο τόσο καλύτερο (το να μαντεύεις 33/33/33 δίνει 1.099). «vs αγορά»: διαφορά από τις αποδόσεις κλεισίματος των bookmakers (θετικό = η αγορά ήταν πιο ακριβής). «ROI value»: τι θα έβγαζε κάποιος ποντάροντας 1 μονάδα όπου το μοντέλο έβλεπε αξία πάνω από 5% στις μέσες αποδόσεις κλεισίματος.</p>
    <h3>Τι σημαίνει για τα χρήματά σου</h3>
    <p>Οι bookmakers έχουν γκανιότα (στον ΟΠΑΠ συχνά 6–10%), και οι αποδόσεις κλεισίματος είναι πολύ ακριβείς. Στα περισσότερα πρωταθλήματα το μοντέλο είναι λίγο λιγότερο ακριβές από την αγορά, και το τυφλό ποντάρισμα σε κάθε «value» ήταν χασοφόρο στο backtest. Καμία εφαρμογή δεν εγγυάται κέρδος. Χρησιμοποίησέ την ως δεύτερη γνώμη: για να εντοπίζεις αποδόσεις που ξεφεύγουν πολύ από τη δίκαιη τιμή, για να ελέγχεις αγορές όπως κόρνερ/κάρτες/σκόρερ, και για να ορίζεις ποντάρισμα με πειθαρχία. Βάλε όριο χρημάτων που αντέχεις να χάσεις.</p>
    <p class="muted small">Πηγές: football-data.co.uk (αποτελέσματα, στατιστικά, xG), Understat (xG παικτών), ESPN (στατιστικά παικτών), FotMob και Fantasy Premier League (απουσίες, πρόγραμμα).</p>
  </section>`;
}

// ---------------------------------------------------------------- events

$app.addEventListener('input', (e) => {
  const t = e.target;
  if (t.dataset.side !== undefined && t.tagName === 'INPUT') {
    state.pick[+t.dataset.side] = null;
    t.parentElement.querySelector('.chosen').textContent = '';
    showSuggest(+t.dataset.side);
  } else if (t.dataset.odds) onOddsInput(t);
  else if (t.dataset.bank !== undefined) {
    const v = parseOdds(t.value) || parseFloat(t.value.replace(',', '.'));
    if (v > 0) { store.set('bankroll', v); const vb = document.getElementById('valuebets'); if (vb) vb.innerHTML = valueBetsHtml(); }
  }
});

$app.addEventListener('change', (e) => {
  const t = e.target;
  if (t.dataset.ref !== undefined) { state.match.referee = t.value === '' ? null : +t.value; computeMatch(); drawMatch(); }
  else if (t.dataset.add !== undefined && t.value) setAvail(+t.dataset.add, t.value, 0);
});

$app.addEventListener('keydown', (e) => {
  const t = e.target;
  if (t.dataset.side === undefined || t.tagName !== 'INPUT') return;
  const box = document.querySelector(`.suggest[data-for="${t.dataset.side}"]`);
  if (e.key === 'Enter') {
    e.preventDefault();
    if (!box.hidden && box._list?.length) choose(+t.dataset.side, box._list[0]);
    else if (+t.dataset.side === 1) startAnalysis();
  } else if (e.key === 'Escape') box.hidden = true;
});

$app.addEventListener('click', (e) => {
  const b = e.target.closest('button, [data-m]');
  if (!b) return;
  if (b.dataset.pick !== undefined) {
    const box = b.closest('.suggest');
    choose(+b.dataset.pick, box._list[+b.dataset.i]);
  } else if (b.dataset.act === 'go') startAnalysis();
  else if (b.dataset.act === 'swap') {
    state.pick.reverse();
    for (const i of [0, 1]) {
      document.getElementById('t' + i).value = state.pick[i]?.name || '';
      document.querySelector(`#t${i} ~ .chosen`).textContent = state.pick[i] ? `${state.pick[i].flag} ${state.pick[i].country}` : '';
    }
  } else if (b.dataset.fx) {
    store.set('fxFilter', b.dataset.fx);
    document.querySelectorAll('#fxchips .chip').forEach((c) => c.classList.toggle('on', c === b));
    document.getElementById('fxlist').innerHTML = fixturesHtml(b.dataset.fx);
  } else if (b.dataset.m) {
    const [id, h, a] = b.dataset.m.split('|');
    goMatch(id, h, a);
  } else if (b.dataset.tab) {
    state.tab = b.dataset.tab;
    document.querySelectorAll('.tab').forEach((x) => x.classList.toggle('on', x === b));
    document.getElementById('pane').innerHTML = paneHtml();
    b.scrollIntoView({ inline: 'center', block: 'nearest' });
    const top = document.querySelector('.tabs').getBoundingClientRect().top;
    if (top < 0 || window.scrollY > document.querySelector('.tabs').offsetTop) window.scrollTo(0, document.querySelector('.tabs').offsetTop - 50);
  } else if (b.dataset.av !== undefined) {
    const seg = b.closest('.seg');
    setAvail(+seg.dataset.side, seg.dataset.player, +b.dataset.av);
  }
});

document.addEventListener('click', (e) => {
  if (!e.target.closest('.field')) document.querySelectorAll('.suggest').forEach((s) => { s.hidden = true; });
});

// ---------------------------------------------------------------- start

window.addEventListener('hashchange', route);
loadIndex().then(route).catch(() => {
  $app.innerHTML = '<p class="empty">Δεν φορτώθηκαν τα δεδομένα. Έλεγξε τη σύνδεση και ξαναδοκίμασε.</p>';
});
if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
