// HTTP helpers with retries; every source is optional, so failures return null.
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function getText(url, headers = {}, tries = 3) {
  for (let i = 0; i < tries; i++) {
    try {
      const r = await fetch(url, { headers: { 'User-Agent': UA, 'Accept-Language': 'en-US,en;q=0.9', ...headers }, signal: AbortSignal.timeout(40000) });
      if (r.ok) return await r.text();
      if (r.status === 404 || r.status === 403) { console.warn(`  ${r.status} ${url}`); return null; }
      console.warn(`  ${r.status} ${url} (try ${i + 1})`);
    } catch (e) {
      console.warn(`  ${e.message} ${url} (try ${i + 1})`);
    }
    await sleep(1500 * (i + 1));
  }
  return null;
}

export async function getJson(url, headers = {}) {
  const t = await getText(url, headers);
  if (!t) return null;
  try { return JSON.parse(t); } catch { console.warn(`  bad JSON ${url}`); return null; }
}

// Runs async jobs with limited concurrency.
export async function pool(items, n, fn) {
  const out = new Array(items.length);
  let next = 0;
  await Promise.all(Array.from({ length: Math.min(n, items.length) }, async () => {
    while (next < items.length) { const i = next++; out[i] = await fn(items[i], i); }
  }));
  return out;
}
