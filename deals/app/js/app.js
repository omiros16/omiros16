// Screens of the deals page. Prices come from latest.json on the data branch; every lever,
// shop or threshold change shows at once here, and "Αποθήκευση" sends it to GitHub as an issue
// so the scheduled alerts follow it too.
import {
  LEVERS, UNIT_LABEL, MIN_HISTORY, normSettings, plan, baskets, savings, alerts, options, series, stats, effectiveThreshold,
  thresholdOf, productOn, chainOn, productById, eur, num, qtyText, addDays,
} from './core.js';

const $app = document.getElementById('app');
const $asof = document.getElementById('asof');
const $save = document.getElementById('savebar');
const DRAFT_KEY = 'deals.draft.v1';
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const dm = (iso) => (iso ? `${+iso.slice(8, 10)}/${+iso.slice(5, 7)}` : '');
const per = (p) => `€/${UNIT_LABEL[p.unit]}`;
const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Europe/Athens' }).format(new Date());
const same = (a, b) => JSON.stringify(normSettings(a)) === JSON.stringify(normSettings(b));

let data = null;
let draft = null; // { s: settings shown here, from: settings on the server when editing began, sent }

function dataUrl() {
  const q = new URLSearchParams(location.search).get('data');
  if (q) return q;
  if (location.hostname.endsWith('.github.io')) {
    const owner = location.hostname.split('.')[0];
    const repo = location.pathname.split('/').filter(Boolean)[0];
    return `https://raw.githubusercontent.com/${owner}/${repo}/deals-data/latest.json`;
  }
  return 'data/latest.json';
}

const store = {
  get() { try { return JSON.parse(localStorage.getItem(DRAFT_KEY)); } catch { return null; } },
  set(v) { try { v ? localStorage.setItem(DRAFT_KEY, JSON.stringify(v)) : localStorage.removeItem(DRAFT_KEY); } catch { /* private mode */ } },
};

const S = () => normSettings(draft?.s || data.settings);
function change(fn) {
  const s = S();
  fn(s);
  // a shop switched back to its default is no change
  for (const c of data.cfg.chains) if (s.chains[c.id] === !!c.heraklion) delete s.chains[c.id];
  draft = same(s, data.settings) ? null : { s, from: data.settings, sent: false };
  store.set(draft);
  render();
}

async function load() {
  try {
    const r = await fetch(dataUrl(), { cache: 'no-cache' });
    if (!r.ok) throw new Error(r.status);
    data = await r.json();
  } catch (e) {
    $app.innerHTML = `<p class="empty">Δεν βρέθηκαν τιμές (${esc(e.message)}). Η πρώτη ενημέρωση γίνεται από το GitHub Actions: <b>Actions → deals → Run workflow</b>.</p>`;
    return;
  }
  const d = store.get();
  // a draft that the server has since taken in (or that was sent) is dropped
  if (d && !same(d.s, data.settings) && !(d.sent && !same(d.from, data.settings))) draft = d;
  else store.set(null);
  render();
}

// ---------- pieces ----------

function badgesFor(o, p, s) {
  const b = [];
  if (o.d) b.push(`<span class="badge deal">${o.d === true ? 'προσφορά' : `−${o.d}%`}</span>`);
  if (o.bonus) b.push(`<span class="badge deal">${esc(o.bonus)}</span>`);
  if (o.drop?.end) b.push(`<span class="badge soon">μείωση έως ${dm(o.drop.end)}</span>`);
  if (o.u <= thresholdOf(p, s)) b.push('<span class="badge ok">κάτω από το όριο</span>');
  if (o.pl) b.push('<span class="badge">ιδιωτική ετικέτα</span>');
  if (o.since) b.push(`<span class="badge">από ${dm(o.since)}</span>`);
  if (o.manual) b.push(`<span class="badge">καταχώριση ${dm(o.date)}, ισχύει ως ${dm(o.until)}</span>`);
  return b.length ? `<div class="badges">${b.join('')}</div>` : '';
}

function optionRow(o, p, s, { link = false, title = null } = {}) {
  const tag = link ? `a class="row" href="#/p/${p.id}"` : 'div class="row"';
  const pack = o.p != null ? `${eur(o.p)}${o.size ? ` · ${num(o.size, o.size < 1 ? 3 : 2).replace(/,?0+$/, '')} ${p.unit === 'pcs' ? 'τεμ.' : p.unit === 'L' ? 'L' : 'kg'}` : ''}` : '';
  return `<${tag}><div class="main"><div class="name">${esc(title || o.storeName)}</div><div class="sub">${esc(title ? `${o.storeName} · ${o.name}` : o.name)}</div>${badgesFor(o, p, s)}</div>
    <div class="price"><b class="n">${num(o.u)}</b><span>${per(p)}</span>${pack ? `<span class="n">${pack}</span>` : ''}</div></${link ? 'a' : 'div'}>`;
}

function issueUrl(title, payload, intro) {
  const body = `${intro}\n\nΠάτα **Create** (ή «Submit new issue») και η αλλαγή εφαρμόζεται σε 1–2 λεπτά. Το αίτημα κλείνει μόνο του.\n\n\`\`\`deals\n${JSON.stringify(payload)}\n\`\`\`\n`;
  return `https://github.com/${data.repo}/issues/new?title=${encodeURIComponent(title)}&body=${encodeURIComponent(body)}`;
}

function saveBar() {
  if (!draft) { $save.hidden = true; return; }
  $save.hidden = false;
  if (draft.sent) {
    $save.innerHTML = '<span>Αν πάτησες Create στο GitHub, οι ειδοποιήσεις αλλάζουν σε 1–2 λεπτά.</span><button class="btn ghost" data-act="reload">Ανανέωση</button><button class="btn" data-act="save">Ξανά</button>';
  } else {
    $save.innerHTML = '<span>Οι αλλαγές ισχύουν μόνο εδώ μέχρι να τις αποθηκεύσεις.</span><button class="btn ghost" data-act="undo">Αναίρεση</button><button class="btn" data-act="save">Αποθήκευση</button>';
  }
}

$save.addEventListener('click', (e) => {
  const act = e.target.closest('[data-act]')?.dataset.act;
  if (act === 'undo') { draft = null; store.set(null); render(); }
  if (act === 'reload') location.reload();
  if (act === 'save') {
    window.open(issueUrl('deals: ρυθμίσεις', { settings: draft.s }, 'Αλλαγή ρυθμίσεων από την εφαρμογή Προσφορές.'), '_blank', 'noopener');
    draft = { ...draft, sent: true };
    store.set(draft);
    saveBar();
  }
});

// ---------- screens ----------

function home() {
  const s = S();
  const pl = plan(data, s, today);
  const sv = savings(data, s, today);
  const al = alerts(data, s, today);
  const now = al.filter((a) => a.kind === 'now'), soon = al.filter((a) => a.kind === 'soon');
  const anyLever = LEVERS.some((k) => s.levers[k]);
  let h = '';
  if (data.stale) h += `<div class="warn">Οι τιμές είναι της ${dm(data.snapshot.date)}: η πηγή δεν έχει ενημερωθεί από τότε.</div>`;
  h += `<div class="hero">
    <div class="stat"><div class="k">Εβδομάδα, φθηνότερα</div><div class="v n">${eur(pl.total, 0)}</div><div class="s">${pl.lines.filter((l) => l.best && !l.alt).length} προϊόντα με τιμή</div></div>
    <div class="stat ${anyLever && sv.month > 0 ? 'good' : ''}"><div class="k">Μοχλοί</div><div class="v n">${anyLever ? `${sv.month >= 0 ? '−' : '+'}${eur(Math.abs(sv.month), 0)}` : '—'}</div><div class="s">${anyLever ? 'τον μήνα, σε σχέση με τη βασική' : 'βασική εκδοχή'}</div></div>
  </div>`;

  h += '<h2>Μοχλοί budget</h2><div class="card">';
  for (const l of data.cfg.levers) {
    h += `<div class="lever"><div class="t"><b>${esc(l.name)}</b><span>${esc(l.desc)}</span></div><button class="switch" role="switch" aria-checked="${s.levers[l.id]}" aria-label="${esc(l.name)}" data-lever="${l.id}"></button></div>`;
  }
  h += '</div>';

  h += `<h2>Τώρα σε προσφορά <span class="side">${now.length || ''}</span></h2>`;
  if (now.length) {
    h += '<div class="card">';
    for (const a of now) {
      const why = [a.why.includes('threshold') ? `όριο ${num(a.thr)}` : null, a.why.includes('low') ? `χαμηλό 90 ημερών (συνήθως ${num(a.st.median)})` : null].filter(Boolean).join(' · ');
      h += `<a class="row alert" href="#/p/${a.p.id}"><div class="main"><div class="name">${esc(a.p.name)}</div><div class="sub">${esc(a.best.storeName)} · ${esc(a.best.name)}</div><div class="badges"><span class="badge ok">${esc(why)}</span>${a.best.d ? `<span class="badge deal">${a.best.d === true ? 'προσφορά' : `−${a.best.d}%`}</span>` : ''}</div></div>
        <div class="price"><b class="n">${num(a.best.u)}</b><span>${per(a.p)}</span></div></a>`;
    }
    h += '</div>';
  } else {
    h += '<p class="note">Τίποτα κάτω από τα όρια αυτή τη στιγμή.</p>';
  }
  if (soon.length) {
    h += '<h2>Έρχεται</h2><div class="card">';
    for (const a of soon) {
      h += `<a class="row alert soonish" href="#/p/${a.p.id}"><div class="main"><div class="name">${esc(a.p.name)}</div><div class="sub">${esc(a.item.name)}</div><div class="badges"><span class="badge soon">από ${dm(a.item.drop.start)}${a.item.drop.end ? ` έως ${dm(a.item.drop.end)}` : ''}</span>${a.item.drop.pct ? `<span class="badge deal">−${num(a.item.drop.pct, 0)}%</span>` : ''}</div></div>
        <div class="price"><b class="n">${num(a.u)}</b><span>σήμερα, ${esc(a.storeName)}</span></div></a>`;
    }
    h += '</div>';
  }

  h += '<h2>Η λίστα της εβδομάδας</h2><div class="card">';
  for (const l of pl.lines) {
    const o = l.best;
    const cls = ['row', l.alt ? 'alt' : '', o && o.u <= l.threshold ? 'under' : ''].join(' ');
    h += `<a class="${cls}" href="#/p/${l.p.id}"><div class="main"><div class="name">${esc(l.p.name)} <small>${qtyText(l.p, l.qty)}${l.alt ? ' · εναλλακτική' : ''}</small></div>
      <div class="sub">${o ? `${esc(o.storeName)} · ${esc(o.name)}` : 'χωρίς τιμή · πρόσθεσέ τη με το χέρι'}</div>${o ? badgesFor(o, l.p, s) : ''}</div>
      <div class="price">${o ? `<b class="n">${num(o.u)}</b><span>${per(l.p)}</span><span class="n">${l.alt ? '' : eur(l.cost)}</span>` : '<span>—</span>'}</div></a>`;
  }
  h += '</div>';
  h += `<p class="note">Σύγκριση πάντα ανά ${['κιλό', 'λίτρο', 'τεμάχιο'].join(' / ')}· τα «+δώρο» έχουν γίνει πραγματική τιμή μονάδας. Τιμές από το «Πόσο Κάνει» (${dm(data.snapshot.date)}).</p>`;
  return h;
}

function basket() {
  const s = S();
  const b = baskets(data, s, today);
  let h = '';
  if (b.pair) {
    h += `<h2>Φθηνότερος συνδυασμός</h2><div class="card pair"><div class="k">${b.pair.names.map(esc).join(' + ')}</div><div class="v n">${eur(b.pair.total)}</div>
      ${b.pair.missing.length ? `<div class="note">Λείπουν: ${b.pair.missing.map((p) => esc(p.name)).join(', ')}</div>` : ''}<ul>`;
    for (const [pid, o] of Object.entries(b.pair.pick)) h += `<li><span>${esc(productById(data, pid).name)}</span><span>${esc(o.storeName)} · <span class="n">${num(o.u)}</span></span></li>`;
    h += '</ul></div>';
  }
  h += `<h2>Όλα από ένα μαγαζί <span class="side">${b.lines} προϊόντα</span></h2><div class="card">`;
  const max = Math.max(...b.single.map((x) => x.total), 1);
  b.single.forEach((x, i) => {
    h += `<div class="bar ${i === 0 && !x.missing.length ? 'best' : ''}"><div class="hd"><b>${esc(x.name)}</b><span class="n">${eur(x.total)}</span></div><div class="track"><div class="fill" style="width:${(100 * x.total) / max}%"></div></div>
      ${x.missing.length ? `<div class="miss">λείπουν: ${x.missing.map((p) => esc(p.name)).join(', ')}</div>` : ''}</div>`;
  });
  h += '</div>';
  if (b.unpriced.length) h += `<p class="note">Χωρίς τιμή και εκτός σύγκρισης: ${b.unpriced.map((p) => `<a href="#/add/${p.id}">${esc(p.name)}</a>`).join(', ')}.</p>`;
  h += '<p class="note">Εκτίμηση: ποσότητα της εβδομάδας × φθηνότερη τιμή μονάδας κάθε μαγαζιού, χωρίς τη στρογγυλοποίηση σε συσκευασίες. Τα κρεοπωλεία μπαίνουν μόνο σε συνδυασμό.</p>';
  return h;
}

function chart(p, s) {
  const pts = series(data, p, s, today);
  if (pts.length < 2) return '<p class="note">Δεν υπάρχει ακόμη αρκετό ιστορικό.</p>';
  const st = stats(pts.map((x) => x[1]));
  const thr = thresholdOf(p, s);
  const W = 600, H = 190, L = 34, R = 8, T = 10, B = 22;
  const t0 = Date.parse(pts[0][0]), t1 = Date.parse(pts[pts.length - 1][0]) || t0 + 1;
  let lo = st.min, hi = st.max;
  const showThr = thr <= hi * 1.35 && thr >= lo * 0.7;
  if (showThr) { lo = Math.min(lo, thr); hi = Math.max(hi, thr); }
  const pad = (hi - lo || hi * 0.1) * 0.12; lo -= pad; hi += pad;
  const x = (d) => L + ((Date.parse(d) - t0) / Math.max(1, t1 - t0)) * (W - L - R);
  const y = (v) => T + (1 - (v - lo) / (hi - lo)) * (H - T - B);
  const path = pts.map(([d, v], i) => `${i ? 'L' : 'M'}${x(d).toFixed(1)},${y(v).toFixed(1)}`).join('');
  let svg = `<svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Φθηνότερη τιμή ανά ημέρα, ${pts.length} μετρήσεις">`;
  if (st.n >= MIN_HISTORY) svg += `<rect class="p20" x="${L}" y="${y(st.p20).toFixed(1)}" width="${W - L - R}" height="${(H - B - y(st.p20)).toFixed(1)}"/>`;
  for (const v of [lo + pad, (lo + hi) / 2, hi - pad]) svg += `<text class="ax" x="${L - 4}" y="${y(v) + 3}" text-anchor="end">${num(v)}</text>`;
  svg += `<text class="ax" x="${L}" y="${H - 6}">${dm(pts[0][0])}</text><text class="ax" x="${W - R}" y="${H - 6}" text-anchor="end">${dm(pts[pts.length - 1][0])}</text>`;
  if (showThr) svg += `<line class="thr" x1="${L}" x2="${W - R}" y1="${y(thr)}" y2="${y(thr)}"/>`;
  svg += `<path class="ln" d="${path}"/>`;
  const last = pts[pts.length - 1];
  svg += `<circle class="dot" cx="${x(last[0])}" cy="${y(last[1])}" r="4"/></svg>`;
  return `<div class="chart">${svg}</div><div class="legend"><span><i style="background:var(--text)"></i>φθηνότερη τιμή</span>${showThr ? `<span><i style="background:var(--deal)"></i>όριο ${num(thr)}</span>` : `<span>όριο ${num(thr)} (εκτός κλίμακας)</span>`}${st.n >= MIN_HISTORY ? `<span><i style="background:var(--ok-bg);height:8px"></i>φθηνότερο 20% (έως ${num(st.p20)})</span>` : ''}<span>διάμεσος ${num(st.median)} · ελάχ. ${num(st.min)}</span></div>`;
}

function product(id) {
  const p = productById(data, id);
  if (!p) return '<p class="empty">Δεν υπάρχει αυτό το προϊόν.</p>';
  const s = S();
  const q = plan(data, s, today).lines.find((l) => l.p.id === id);
  const opts = options(data, p, s, today);
  const st = stats(series(data, p, s, today).map((x) => x[1]));
  const limit = effectiveThreshold(p, s, st);
  const lever = p.lever && data.cfg.levers.find((l) => l.id === p.lever);
  let h = `<a class="back" href="#/">‹ Λίστα</a><div class="ptitle">${esc(p.name)}</div><div class="psub">${q ? `${qtyText(p, q.qty)} την εβδομάδα` : lever && !s.levers[p.lever] ? `Μπαίνει στη λίστα με τον μοχλό «${esc(lever.name)}»` : productOn(p, s) ? '' : 'Εκτός λίστας'}${p.note ? ` · ${esc(p.note)}` : ''}</div>`;
  h += `<h2>90 ημέρες</h2><div class="card">${chart(p, s)}</div>`;
  h += `<p class="note">Ειδοποίηση όταν η φθηνότερη τιμή πέσει στα ${num(limit)} ${per(p)} ή κάτω${limit < thresholdOf(p, s) ? ` (όριό σου ${num(thresholdOf(p, s))}, αλλά με το ιστορικό ζητείται και τιμή στο φθηνότερο 20% και κάτω από τη συνηθισμένη)` : ''}.</p>`;

  if (p.pl) {
    const all = options(data, p, { ...s, levers: { ...s.levers, pl: true } }, today).filter((o) => !o.manual);
    const pl = all.find((o) => o.pl), br = all.find((o) => !o.pl);
    if (pl && br) {
      const diff = ((br.u - pl.u) / br.u) * 100;
      h += `<h2>Ιδιωτική ετικέτα ή επώνυμο</h2><div class="card cmp"><div><div class="k">Ιδιωτική ετικέτα · ${esc(pl.storeName)}</div><div class="v n">${num(pl.u)}</div><div class="note">${esc(pl.name)}</div></div>
        <div><div class="k">Επώνυμο · ${esc(br.storeName)}</div><div class="v n">${num(br.u)}</div><div class="note">${esc(br.name)}</div></div></div>
        <p class="note">${diff > 0 ? `Η ιδιωτική ετικέτα είναι ${num(diff, 0)}% φθηνότερη.` : 'Το επώνυμο είναι εξίσου ή πιο φθηνό.'} ${s.levers.pl ? '' : 'Ο μοχλός private label είναι κλειστός: η λίστα μετρά μόνο επώνυμα.'}</p>`;
    }
  }

  const byStore = [];
  for (const o of opts) if (!byStore.some((x) => x.store === o.store)) byStore.push(o);
  h += `<h2>Ανά μαγαζί</h2><div class="card">${byStore.length ? byStore.map((o) => optionRow(o, p, s)).join('') : '<p class="empty">Καμία τιμή στα ενεργά μαγαζιά.</p>'}</div>`;
  if (opts.length > byStore.length) {
    h += `<details><summary class="note">Όλες οι επιλογές (${opts.length})</summary><div class="card">${opts.map((o) => optionRow(o, p, s, { title: o.name })).join('')}</div></details>`;
  }

  h += `<h2>Ρυθμίσεις προϊόντος</h2><div class="card">
    <div class="field"><label for="thr">Όριο ειδοποίησης<small>${per(p)}</small></label><input id="thr" type="number" inputmode="decimal" step="0.01" min="0" value="${thresholdOf(p, s)}" data-thr="${p.id}"></div>
    <div class="field"><label>Στη λίστα<small>${p.optional ? 'προαιρετικό' : 'βασικό'}</small></label><button class="switch" role="switch" aria-checked="${productOn(p, s)}" aria-label="Στη λίστα" data-prod="${p.id}"></button></div>
  </div>
  <p><a class="btn ghost" href="#/add/${p.id}">+ Τιμή από κρεοπωλείο, λαϊκή ή άλλο μαγαζί</a></p>`;
  return h;
}

function settings() {
  const s = S();
  let h = '<h2>Μαγαζιά</h2><div class="card">';
  for (const c of data.cfg.chains) {
    h += `<div class="field"><label>${esc(c.name)}<small>${c.heraklion ? 'κατάστημα στο Ηράκλειο' : 'δεν επιβεβαιώθηκε κατάστημα στο Ηράκλειο'}${c.offers ? ` · <a href="${esc(c.offers)}" target="_blank" rel="noopener">φυλλάδιο</a>` : ''}</small></label>
      <button class="switch" role="switch" aria-checked="${chainOn(data, s, c.id)}" aria-label="${esc(c.name)}" data-chain="${c.id}"></button></div>`;
  }
  h += '</div>';

  h += `<h2>Ειδοποιήσεις στο Telegram</h2><div class="card">
    <div class="field"><label>Όρια με βάση το ιστορικό<small>ειδοποίηση μόνο για τιμή στο φθηνότερο 20% των 90 ημερών</small></label><button class="switch" role="switch" aria-checked="${s.auto}" aria-label="Όρια με βάση το ιστορικό" data-flag="auto"></button></div>
    <div class="field"><label>Σύνοψη κάθε Κυριακή<small>καλάθι ανά αλυσίδα και εξοικονόμηση</small></label><button class="switch" role="switch" aria-checked="${s.weekly}" aria-label="Σύνοψη κάθε Κυριακή" data-flag="weekly"></button></div>
  </div>`;

  h += '<h2>Προϊόντα</h2><div class="card">';
  for (const p of data.cfg.products) {
    const lever = p.lever && data.cfg.levers.find((l) => l.id === p.lever);
    h += `<div class="field"><label><a href="#/p/${p.id}">${esc(p.name)}</a><small>όριο ${num(thresholdOf(p, s))} ${per(p)}${lever ? ` · με μοχλό «${esc(lever.name)}»` : ''}${p.optional ? ' · προαιρετικό' : ''}</small></label>
      <button class="switch" role="switch" aria-checked="${productOn(p, s)}" aria-label="${esc(p.name)}" data-prod="${p.id}"></button></div>`;
  }
  h += '</div>';

  const manual = (data.manual || []).filter((m) => addDays(m.date, m.days ?? 7) >= today);
  h += `<h2>Τιμές με το χέρι <span class="side"><a href="#/add">+ Νέα</a></span></h2>`;
  if (manual.length) {
    h += '<div class="card">';
    for (const m of manual) {
      const p = productById(data, m.product);
      h += `<div class="field"><label>${esc(p?.name || m.product)} · <b class="n">${num(m.price)}</b> ${p ? per(p) : ''}<small>${esc(m.place)}${m.note ? ` · ${esc(m.note)}` : ''} · ${dm(m.date)} → ${dm(addDays(m.date, m.days ?? 7))}</small></label>
        <a class="btn ghost small" target="_blank" rel="noopener" href="${esc(issueUrl('deals: διαγραφή τιμής', { remove: m.id }, 'Διαγραφή χειροκίνητης τιμής από την εφαρμογή Προσφορές.'))}">Διαγραφή</a></div>`;
    }
    h += '</div>';
  } else {
    h += '<p class="note">Για κρεοπωλεία, λαϊκή, μπανάνες ή whey: πρόσθεσε την τιμή που είδες και μπαίνει στη σύγκριση για όσες μέρες ορίσεις.</p>';
  }

  h += `<h2>Πηγές</h2><div class="card prose">
    <p><b>Σούπερ μάρκετ:</b> το κρατικό <a href="https://posokanei.gov.gr" target="_blank" rel="noopener">«Πόσο Κάνει»</a> (ημερήσιες τιμές που δηλώνουν οι αλυσίδες), μέσω του δημόσιου ημερήσιου αρχείου <a href="${esc(data.snapshot.archive)}" target="_blank" rel="noopener">spyrosavl/posokanei</a>, γιατί το API δεν απαντά στους servers του GitHub.</p>
    <p><b>Έρχεται:</b> οι μειώσεις τιμών με μελλοντική ημερομηνία έναρξης από την ίδια πηγή. Τα φυλλάδια των αλυσίδων δεν διαβάζονται αυτόματα, γιατί οι όροι χρήσης των sites τους το απαγορεύουν. Τα ανοίγεις από τους συνδέσμους «φυλλάδιο».</p>
    <p><b>Κρεοπωλεία:</b> Wolt, e-food και stinporta απαγορεύουν τα bots στους όρους τους, οπότε οι τιμές τους μπαίνουν με το χέρι.</p>
    <p>Ενημέρωση: Τρίτη, Παρασκευή και Κυριακή πρωί. Τελευταία: ${new Date(data.updated).toLocaleString('el-GR', { timeZone: 'Europe/Athens', dateStyle: 'short', timeStyle: 'short' })}.</p>
  </div>`;
  return h;
}

function addForm(pid) {
  const places = [...new Set((data.manual || []).map((m) => m.place))];
  let h = '<a class="back" href="#/settings">‹ Ρυθμίσεις</a><div class="ptitle">Νέα τιμή</div><div class="psub">Από κρεοπωλείο, λαϊκή ή όποιο μαγαζί δεν είναι στο «Πόσο Κάνει».</div>';
  h += `<form id="add" class="card stack" autocomplete="off">
    <label>Προϊόν<select name="product">${data.cfg.products.map((p) => `<option value="${p.id}" ${p.id === pid ? 'selected' : ''}>${esc(p.name)} (${per(p)})</option>`).join('')}</select></label>
    <label>Μαγαζί<input name="place" required maxlength="60" list="places" placeholder="π.χ. Κρεοπωλείο Αγγελάκης"><datalist id="places">${places.map((x) => `<option value="${esc(x)}">`).join('')}</datalist></label>
    <label>Τιμή ανά μονάδα (€)<input name="price" required type="number" inputmode="decimal" step="0.01" min="0.01" placeholder="π.χ. 3,90"></label>
    <label>Ισχύει για (ημέρες)<input name="days" type="number" inputmode="numeric" min="1" max="60" value="7"></label>
    <label>Σημείωση<input name="note" maxlength="80" placeholder="π.χ. μπουτάκια νωπά, προσφορά Σαββάτου"></label>
    <button class="btn" type="submit">Αποστολή στο GitHub</button>
  </form><p class="note">Ανοίγει έτοιμο αίτημα στο GitHub· πάτα Create και η τιμή μπαίνει σε 1–2 λεπτά.</p>`;
  return h;
}

// ---------- routing and events ----------

function render() {
  if (!data) return;
  const [, route, arg] = (location.hash || '#/').split('/');
  const tab = route === 'basket' ? 'basket' : route === 'settings' || route === 'add' ? 'settings' : 'home';
  document.querySelectorAll('.tabs a').forEach((a) => a.classList.toggle('on', a.dataset.tab === tab));
  $asof.innerHTML = `τιμές ${dm(data.snapshot.date)}<br>${draft ? 'μη αποθηκευμένες αλλαγές' : 'αποθηκευμένες ρυθμίσεις'}`;
  $app.innerHTML = route === 'basket' ? basket() : route === 'settings' ? settings() : route === 'p' ? product(decodeURIComponent(arg || '')) : route === 'add' ? addForm(arg) : home();
  saveBar();
}

$app.addEventListener('click', (e) => {
  const sw = e.target.closest('.switch');
  if (!sw) return;
  const on = sw.getAttribute('aria-checked') !== 'true';
  if (sw.dataset.lever) change((s) => { s.levers[sw.dataset.lever] = on; });
  if (sw.dataset.chain) change((s) => { s.chains[sw.dataset.chain] = on; });
  if (sw.dataset.flag) change((s) => { s[sw.dataset.flag] = on; });
  if (sw.dataset.prod) change((s) => { s.off = on ? s.off.filter((x) => x !== sw.dataset.prod) : [...new Set([...s.off, sw.dataset.prod])]; });
});

$app.addEventListener('change', (e) => {
  const el = e.target;
  if (el.dataset.thr) {
    const v = parseFloat(String(el.value).replace(',', '.'));
    const p = productById(data, el.dataset.thr);
    change((s) => { if (v > 0 && v !== p.threshold) s.thresholds[p.id] = v; else delete s.thresholds[p.id]; });
  }
});

$app.addEventListener('submit', (e) => {
  if (e.target.id !== 'add') return;
  e.preventDefault();
  const f = new FormData(e.target);
  const price = { product: f.get('product'), place: String(f.get('place')).trim(), price: parseFloat(String(f.get('price')).replace(',', '.')), days: +f.get('days') || 7, note: String(f.get('note') || '').trim() || undefined };
  if (!(price.price > 0) || !price.place) return;
  window.open(issueUrl('deals: νέα τιμή', { price }, 'Νέα χειροκίνητη τιμή από την εφαρμογή Προσφορές.'), '_blank', 'noopener');
  e.target.insertAdjacentHTML('afterend', '<p class="warn">Άνοιξε το GitHub. Μετά το Create, η τιμή εμφανίζεται εδώ σε 1–2 λεπτά (κάνε ανανέωση).</p>');
});

window.addEventListener('hashchange', () => { render(); window.scrollTo(0, 0); });
if ('serviceWorker' in navigator) navigator.serviceWorker.register('sw.js').catch(() => {});
load();
