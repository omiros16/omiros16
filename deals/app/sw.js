// Offline support for the deals page: app files from cache (refreshed in the background),
// prices from the network first with the last copy as fallback. One cache each, replaced on update.
const VERSION = 'v1';
const SHELL = `deals-shell-${VERSION}`, DATA = 'deals-data';
const FILES = ['./', 'index.html', 'css/app.css', 'js/app.js', 'js/core.js', 'manifest.webmanifest', 'icon.svg', 'icon-192.png', 'icon-512.png', 'apple-touch-icon.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k.startsWith('deals-') && k !== SHELL && k !== DATA).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if (url.pathname.endsWith('/latest.json')) {
    e.respondWith(fetch(e.request).then((r) => {
      if (r.ok) { const copy = r.clone(); caches.open(DATA).then((c) => c.put(e.request.url, copy)); }
      return r;
    }).catch(() => caches.match(e.request.url)));
    return;
  }
  if (url.origin !== location.origin) return;
  e.respondWith(caches.open(SHELL).then((c) => c.match(e.request).then((hit) => {
    const net = fetch(e.request).then((r) => { if (r.ok) c.put(e.request, r.clone()); return r; }).catch(() => hit);
    return hit || net;
  })));
});
