// Temporary: checks which data sources answer from a GitHub runner and what they return.
const UA = 'omiros16-deals/0.1 (+https://github.com/omiros16/omiros16)';
const section = process.argv[2] || 'all';
const sleep = ms => new Promise(r => setTimeout(r, ms));

async function get(url, { method = 'GET', body, headers = {}, ua = UA } = {}) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), 25000);
  try {
    const r = await fetch(url, {
      method, signal: ctl.signal, redirect: 'follow',
      headers: { 'user-agent': ua, accept: headers.accept || '*/*', ...(body ? { 'content-type': 'application/json' } : {}), ...headers },
      body: body ? JSON.stringify(body) : undefined,
    });
    const text = await r.text();
    return { status: r.status, url: r.url, type: r.headers.get('content-type') || '', text };
  } catch (e) {
    return { status: 0, url, type: '', text: String(e.cause?.code || e.message) };
  } finally { clearTimeout(t); }
}
const strip = h => h.replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ');
const links = (html, base) => [...html.matchAll(/href="([^"#]+)"/g)].map(m => { try { return new URL(m[1].replace(/&amp;/g, '&'), base).href; } catch { return null; } }).filter(Boolean);
const uniq = a => [...new Set(a)];

async function robots(hosts) {
  for (const h of hosts) {
    const r = await get(`https://${h}/robots.txt`);
    const lines = r.text.split('\n').map(s => s.trim()).filter(s => /^(user-agent|disallow|allow|crawl-delay)/i.test(s));
    console.log(`\n## robots ${h} -> ${r.status} (${lines.length} rules)`);
    // print the '*' group and anything mentioning bots in general
    let inStar = false, out = [];
    for (const l of lines) {
      if (/^user-agent/i.test(l)) inStar = /:\s*\*\s*$/.test(l);
      if (inStar || /^user-agent/i.test(l) && /\*/.test(l)) out.push(l);
    }
    console.log(out.slice(0, 40).join('\n') || r.text.slice(0, 300));
  }
}

async function terms(home, extra = []) {
  const r = await get(home);
  console.log(`\n## terms ${home} -> ${r.status} ${r.url}`);
  const cand = uniq([...extra, ...links(r.text, r.url).filter(u => /(terms|oroi|όροι|%CF%8C%CF%81%CE%BF%CE%B9|conditions|xrhs|chrisi|legal|policy)/i.test(decodeURIComponent(u)))]).slice(0, 6);
  console.log('candidates:', cand.join(' '));
  for (const u of cand) {
    const p = await get(u);
    const txt = strip(p.text);
    const hits = [];
    for (const m of txt.matchAll(/(scrap|robot|spider|crawl|αυτοματοποιημ|αυτόματ|bot\b|εξαγωγ|αναπαραγωγ|data mining|εξόρυξ)/gi)) {
      hits.push(txt.slice(Math.max(0, m.index - 220), m.index + 220));
      if (hits.length >= 4) break;
    }
    console.log(`-- ${u} -> ${p.status}, ${txt.length} chars, ${hits.length} hits`);
    hits.forEach(h => console.log('   …' + h + '…'));
  }
}

const PK = 'https://api.posokanei.gov.gr';
async function posokanei() {
  for (const p of ['/meta/retailers?countries=GR', '/meta/stats']) {
    const r = await get(PK + p, { headers: { accept: 'application/json' } });
    console.log(`\n## ${p} -> ${r.status} ${r.type}\n${r.text.slice(0, 2500)}`);
    await sleep(400);
  }
  const queries = ['κοτόπουλο ολόκληρο', 'κοτόπουλο μπούτι', 'μπούτι κοτόπουλου', 'αυγά', 'γάλα πλήρες', 'γιαούρτι στραγγιστό', 'βρώμη', 'ρύζι', 'μπανάνες', 'αρακάς', 'μπρόκολο', 'λαχανικά', 'φυστικοβούτυρο', 'μέλι', 'ελαιόλαδο', 'whey', 'πρωτεΐνη', 'τόνος', 'μακαρόνια', 'φακές', 'ρεβίθια'];
  let firstId = null;
  for (const q of queries) {
    const r = await get(PK + '/products/search', { method: 'POST', body: { title: q, page: 1, page_size: 12, sort_by: 'unit_price', sort_order: 'asc' }, headers: { accept: 'application/json' } });
    console.log(`\n## search "${q}" -> ${r.status}`);
    if (r.status !== 200) { console.log(r.text.slice(0, 400)); await sleep(400); continue; }
    const j = JSON.parse(r.text);
    console.log(`total=${j.total} keys=${Object.keys(j).join(',')}`);
    for (const p of j.products || []) {
      firstId ??= p.id;
      const rp = (p.retailer_prices || []).map(x => `${x.retailer}:${x.price}/${x.price_normalized}${x.is_discount ? '*' + (x.discount_percentage ?? '') : ''}@${(x.last_updated || '').slice(0, 10)}`).join(' ');
      console.log(`- ${p.id} | ${p.name} | ${p.brand} | ${p.unit_quantity}${p.unit} | pl=${p.private_label}${p.private_label_retailer ? '(' + p.private_label_retailer + ')' : ''} | ${p.category}/${p.subcategory} | ${rp}`);
    }
    await sleep(400);
  }
  if (firstId) {
    const r = await get(`${PK}/products/${firstId}?countries=GR&include_tax=true&include_history=true`, { headers: { accept: 'application/json' } });
    console.log(`\n## product ${firstId} -> ${r.status}\n${r.text.slice(0, 3500)}`);
  }
  const t = await get(`${PK}/meta/categories/tree?include_counts=true&include_hidden=false`, { headers: { accept: 'application/json' } });
  console.log(`\n## categories -> ${t.status}`);
  if (t.status === 200) {
    const walk = (n, d) => { if (d > 2) return; console.log(`${'  '.repeat(d)}${n.category_id} ${n.name || n.category_name} (${n.total_product_count ?? n.product_count})`); (n.children || []).forEach(c => walk(c, d + 1)); };
    (JSON.parse(t.text).tree || []).forEach(n => walk(n, 0));
  } else console.log(t.text.slice(0, 400));
}

async function page(url, re) {
  const r = await get(url);
  const html = r.text;
  console.log(`\n## page ${url} -> ${r.status} ${r.type} ${html.length}b final=${r.url}`);
  console.log('title:', (html.match(/<title[^>]*>([^<]*)/i) || [])[1]);
  const blobs = ['__NEXT_DATA__', '__NUXT__', 'application/ld+json', 'window.__INITIAL', 'window.__PRELOADED', '__APOLLO_STATE__', 'self.__next_f', 'data-page='].filter(s => html.includes(s));
  console.log('blobs:', blobs.join(','));
  const apis = uniq([...html.matchAll(/["'](https?:\/\/[^"'\s]*api[^"'\s]*|\/api\/[^"'\s]*)["']/gi)].map(m => m[1])).slice(0, 20);
  console.log('api-ish:', apis.join(' '));
  const ls = uniq(links(html, r.url)).filter(u => !re || re.test(decodeURIComponent(u)));
  console.log(`links(${ls.length}):`, ls.slice(0, 40).join(' '));
  const txt = strip(html);
  const euros = [...txt.matchAll(/€/g)].slice(0, 12).map(m => txt.slice(Math.max(0, m.index - 90), m.index + 25));
  euros.forEach(e => console.log('  €…', e));
  return html;
}

if (section === 'all' || section === 'posokanei') await posokanei();
if (section === 'all' || section === 'robots') await robots(['posokanei.gov.gr', 'api.posokanei.gov.gr', 'stinporta.gr', 'www.efood.gr', 'wolt.com', 'fylladio.ai', 'www.lidl-hellas.gr', 'www.sklavenitis.gr', 'www.ab.gr', 'www.mymarket.gr', 'www.masoutis.gr', 'www.xalkiadakis.gr', 'www.market-in.gr', 'www.kritikos-easy.gr', 'www.synka.gr']);
if (section === 'all' || section === 'terms') {
  await terms('https://stinporta.gr/el');
  await terms('https://www.efood.gr', ['https://www.efood.gr/terms', 'https://www.efood.gr/oroi-xrisis']);
  await terms('https://explore.wolt.com/el/grc/terms', ['https://explore.wolt.com/el/grc/terms']);
  await terms('https://fylladio.ai');
  await terms('https://posokanei.gov.gr');
}
if (section === 'all' || section === 'pages') {
  await page('https://stinporta.gr/el/store/mathioudakiskreopolio');
  await page('https://stinporta.gr/el/store/aggelakis');
  await page('https://stinporta.gr/el', /store|categor|kreo/i);
  await page('https://fylladio.ai');
  await page('https://www.lidl-hellas.gr', /prosfor|fylladi|φυλλάδ|προσφορ|leaflet|flyer|\/c\//i);
  await page('https://www.xalkiadakis.gr', /prosfor|fylladi|φυλλάδ|προσφορ|leaflet|offer/i);
  await page('https://www.sklavenitis.gr', /prosfor|fylladi|φυλλάδ|προσφορ|leaflet|offer/i);
}
