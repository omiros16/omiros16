// Temporary: checks which data sources answer from a GitHub runner and what they return.
const UA = 'omiros16-deals/0.1 (+https://github.com/omiros16/omiros16)';
const section = process.argv[2] || 'all';
async function get(url) {
  const ctl = new AbortController(); const t = setTimeout(() => ctl.abort(), 25000);
  try {
    const r = await fetch(url, { signal: ctl.signal, redirect: 'follow', headers: { 'user-agent': UA, accept: '*/*' } });
    return { status: r.status, url: r.url, type: r.headers.get('content-type') || '', text: await r.text() };
  } catch (e) { return { status: 0, url, type: '', text: String(e.cause?.code || e.message) }; } finally { clearTimeout(t); }
}
const strip = h => h.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/\s+/g, ' ');
const links = (html, base) => [...html.matchAll(/href=["']([^"'#]+)["']/g)].map(m => { try { return new URL(m[1].replace(/&amp;/g, '&'), base).href; } catch { return null; } }).filter(Boolean);
const uniq = a => [...new Set(a)];
const KW = /(scrap|robot|spider|crawl|αυτοματοποιημ|αυτόματ|\bbot|εξόρυξ|data mining|αναπαραγ|αντιγραφ)/gi;

async function terms(home, extra = []) {
  const r = await get(home);
  const cand = uniq([...extra, ...links(r.text, r.url).filter(u => /(terms|oroi|όροι|xrhs|chrisi|xrisi|legal|nomik|conditions)/i.test(decodeURIComponent(u)))]).slice(0, 5);
  console.log(`\n## terms ${home} -> ${r.status}; candidates: ${cand.join(' ')}`);
  for (const u of cand) {
    const p = await get(u); const txt = strip(p.text); const hits = [];
    for (const m of txt.matchAll(KW)) { hits.push(txt.slice(Math.max(0, m.index - 200), m.index + 200)); if (hits.length >= 3) break; }
    console.log(`-- ${u} -> ${p.status}, ${txt.length} chars, ${hits.length} hits`);
    hits.forEach(h => console.log('   …' + h + '…'));
  }
}
async function page(url, { re, around } = {}) {
  const r = await get(url); const html = r.text;
  console.log(`\n## page ${url} -> ${r.status} ${r.type} ${html.length}b final=${r.url}`);
  console.log('title:', (html.match(/<title[^>]*>([^<]*)/i) || [])[1]?.trim());
  const blobs = ['__NEXT_DATA__', '__NUXT__', 'application/ld+json', 'window.__INITIAL', '__APOLLO_STATE__', 'self.__next_f', 'data-grid-data', 'data-gridbox', 'leaflet', 'publitas', 'issuu', 'flipsnack', '.pdf'].filter(s => html.includes(s));
  console.log('markers:', blobs.join(','));
  const ls = uniq(links(html, r.url)).filter(u => !re || re.test(decodeURIComponent(u)));
  console.log(`links(${ls.length}):`, ls.slice(0, 60).join(' '));
  if (around) { const txt = strip(html); let n = 0; for (const m of txt.matchAll(around)) { console.log('  …', txt.slice(Math.max(0, m.index - 120), m.index + 160)); if (++n >= 12) break; } }
  const srcs = uniq([...html.matchAll(/(?:src|data-src)=["']([^"']+)["']/g)].map(m => m[1])).filter(s => /leaflet|flyer|fylladio|pdf|issuu|publitas/i.test(s)).slice(0, 15);
  if (srcs.length) console.log('srcs:', srcs.join(' '));
  return html;
}
if (section === 'terms') {
  await terms('https://www.lidl-hellas.gr', ['https://www.lidl-hellas.gr/c/oroi-xrisis/s10019052', 'https://www.lidl-hellas.gr/c/nomikes-episimanseis/s10019051']);
  await terms('https://xalkiadakis.gr');
  await terms('https://www.mymarket.gr', ['https://www.mymarket.gr/terms']);
  await terms('https://www.ab.gr', ['https://www.ab.gr/oroi-xrisis']);
  await terms('https://www.masoutis.gr');
  await terms('https://www.kritikos-easy.gr');
  await terms('https://www.market-in.gr');
}
if (section === 'flyers') {
  const dates = /(από|απο|ισχ[υύ]|έως|εως|Δευτέρα|Πέμπτη|\d{1,2}[./]\d{1,2})/gi;
  await page('https://www.lidl-hellas.gr/c/fylladio-lidl/s10020481', { around: dates });
  await page('https://xalkiadakis.gr', { re: /fyllad|φυλλ|prosfor|προσφ|offer|pdf|issuu|publitas|e-?shop|katastim/i, around: /φυλλάδ/gi });
  await page('https://www.ab.gr/search/promotions', { around: /έως|ισχύ/gi });
  await page('https://www.mymarket.gr/fylladio', { around: dates });
  await page('https://www.masoutis.gr/categories/index/prosfores?item=0', { around: /έως|ισχύ/gi });
}
