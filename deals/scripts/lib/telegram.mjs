// Telegram messages (HTML parse mode). Without TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID the text
// is only printed, so runs on other branches or forks never message anyone.
import { eur, num, UNIT_LABEL, LEVERS } from '../../app/js/core.js';

const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const dm = (iso) => (iso ? `${+iso.slice(8, 10)}/${+iso.slice(5, 7)}` : '');
const per = (p) => `€/${UNIT_LABEL[p.unit]}`;
const DAYS = ['Κυριακή', 'Δευτέρα', 'Τρίτη', 'Τετάρτη', 'Πέμπτη', 'Παρασκευή', 'Σάββατο'];

function offerLine(o, p) {
  const bits = [esc(o.name)];
  if (o.p != null) bits.push(eur(o.p));
  if (o.bonus) bits.push(esc(o.bonus));
  if (o.d) bits.push(o.d === true ? 'προσφορά' : `−${o.d}%`);
  if (o.drop?.end) bits.push(`μείωση έως ${dm(o.drop.end)}`);
  if (o.manual) bits.push(`καταχώριση ${dm(o.date)}`);
  return bits.join(' · ');
}

export function alertText(data, list, ctx) {
  const now = list.filter((a) => a.kind === 'now');
  const soon = list.filter((a) => a.kind === 'soon');
  const out = [];
  for (const a of now) {
    const why = a.why.includes('threshold') ? `όριο ${num(a.thr)}` : '';
    const low = a.why.includes('low') ? `στο φθηνότερο 20% των 90 ημερών (συνήθως ${num(a.st.median)})` : '';
    out.push(`• <b>${esc(a.p.name)}</b>: <b>${num(a.best.u)} ${per(a.p)}</b> · ${esc(a.best.storeName)}\n  ${offerLine(a.best, a.p)}\n  ${[why, low].filter(Boolean).join(' · ')}` +
      (a.others.length ? `\n  επίσης: ${a.others.map((o) => `${esc(o.storeName)} ${num(o.u)}`).join(', ')}` : ''));
  }
  if (soon.length) {
    out.push('\n📅 <b>Έρχεται</b>');
    for (const a of soon) {
      const d = a.item.drop;
      out.push(`• <b>${esc(a.p.name)}</b>: ${esc(a.item.name)}${d.pct ? ` −${num(d.pct, 0)}%` : ''} από ${dm(d.start)}${d.end ? ` έως ${dm(d.end)}` : ''} · σήμερα ${num(a.u)} ${per(a.p)} (${esc(a.storeName)})`);
    }
  }
  return out.join('\n');
}

export function summaryText(data, ctx) {
  const { basket, save, settings } = ctx;
  const on = LEVERS.filter((k) => settings.levers[k]).map((k) => data.cfg.levers.find((l) => l.id === k).name);
  const lines = [`🧺 <b>Καλάθι εβδομάδας</b> (${basket.lines} προϊόντα με τιμή)`];
  for (const s of basket.single.slice(0, 4)) lines.push(`• ${esc(s.name)}: ${eur(s.total)}${s.missing.length ? ` (λείπουν ${s.missing.length})` : ''}`);
  if (basket.pair) lines.push(`• Φθηνότερο ζευγάρι: <b>${basket.pair.names.map(esc).join(' + ')}</b> ${eur(basket.pair.total)}${basket.pair.missing.length ? ` (λείπουν ${basket.pair.missing.length})` : ''}`);
  if (basket.unpriced.length) lines.push(`• Χωρίς τιμή: ${basket.unpriced.map((p) => esc(p.name)).join(', ')}`);
  lines.push(`\n🎚 Μοχλοί: ${on.length ? on.map(esc).join(', ') : 'κανένας (βασική εκδοχή)'}`);
  if (on.length) lines.push(`Εκτιμώμενη εξοικονόμηση: <b>${eur(save.month, 0)}/μήνα</b> (${eur(save.base)} → ${eur(save.cur)} την εβδομάδα)`);
  return lines.join('\n');
}

export function header(data, today) {
  const d = new Date(`${today}T12:00:00Z`);
  let h = `🛒 <b>Προσφορές</b> · ${DAYS[d.getUTCDay()]} ${dm(today)}`;
  if (data.stale) h += `\n⚠️ Οι τιμές είναι της ${dm(data.snapshot.date)}: η πηγή δεν ενημερώθηκε.`;
  return h;
}

export async function send(text) {
  const token = process.env.TELEGRAM_BOT_TOKEN, chat = process.env.TELEGRAM_CHAT_ID;
  console.log(`\n----- telegram -----\n${text}\n--------------------`);
  if (!token || !chat) { console.log('(no TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID: not sent)'); return false; }
  // Telegram allows 4096 characters per message; split on line breaks
  const parts = [];
  let cur = '';
  for (const line of text.split('\n')) {
    if ((cur + line).length > 3900) { parts.push(cur); cur = ''; }
    cur += `${line}\n`;
  }
  if (cur.trim()) parts.push(cur);
  for (const part of parts) {
    const r = await fetch(`https://api.telegram.org/bot${token}/sendMessage`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ chat_id: chat, text: part, parse_mode: 'HTML', disable_web_page_preview: true }),
    });
    if (!r.ok) throw new Error(`Telegram ${r.status}: ${(await r.text()).slice(0, 300)}`);
  }
  return true;
}

