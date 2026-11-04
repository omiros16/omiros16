// PosoKanei prices. The official API (api.posokanei.gov.gr) refuses GitHub's servers, so the
// update reads the public daily archive of the full catalogue kept at github.com/spyrosavl/posokanei
// (one JSON snapshot per day, ~30 MB, taken in the evening Greek time).
import { readFile } from 'node:fs/promises';

const ARCHIVE = process.env.POSOKANEI_ARCHIVE || 'https://raw.githubusercontent.com/spyrosavl/posokanei/main';
const UA = 'omiros16-deals (+https://github.com/omiros16/omiros16)';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function getJson(url, tries = 3) {
  for (let i = 1; i <= tries; i++) {
    try {
      const r = await fetch(url, { headers: { 'user-agent': UA }, signal: AbortSignal.timeout(120000) });
      if (r.status === 404) return null;
      if (r.ok) return await r.json();
      console.warn(`  ${r.status} ${url} (try ${i})`);
    } catch (e) {
      console.warn(`  ${e.message} ${url} (try ${i})`);
    }
    await sleep(3000 * i);
  }
  throw new Error(`could not download ${url}`);
}

export const snapshotUrl = (date) => `${ARCHIVE}/data/${date.slice(0, 4)}/posokanei-${date}.json`;

// Newest snapshot; SNAPSHOT_FILE points at a local copy instead (tests, offline runs).
export async function latestSnapshot() {
  if (process.env.SNAPSHOT_FILE) return JSON.parse(await readFile(process.env.SNAPSHOT_FILE, 'utf8'));
  const idx = await getJson(`${ARCHIVE}/data/latest.json`);
  if (!idx?.path) throw new Error('archive index has no path');
  const snap = await getJson(`${ARCHIVE}/${idx.path}`);
  if (!snap?.products?.length) throw new Error(`empty snapshot ${idx.path}`);
  return snap;
}

// Older snapshot of a given day, or null if that day is missing from the archive.
export async function snapshotOf(date) {
  return getJson(snapshotUrl(date), 2).catch(() => null);
}
