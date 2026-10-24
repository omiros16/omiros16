// Offline support. The app files and the data files are each kept as one copy that is
// replaced on update, and old caches are deleted, so storage stays the same size.
const VERSION = 'v3';
const SHELL = `shell-${VERSION}`, DATA = 'data';
const FILES = ['./', 'index.html', 'css/app.css', 'js/app.js', 'js/analyze.js', 'js/model.js', 'js/markets.js', 'js/teams.js', 'manifest.webmanifest', 'icon.svg', 'icon-192.png', 'icon-512.png', 'apple-touch-icon.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys()
    .then((keys) => Promise.all(keys.filter((k) => k !== SHELL && k !== DATA).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  if (url.pathname.includes('/data/')) {
    // data: network first so the weekly update shows up, cached copy when offline
    e.respondWith(fetch(e.request).then((r) => {
      if (r.ok) { const copy = r.clone(); caches.open(DATA).then((c) => c.put(e.request, copy)); }
      return r;
    }).catch(() => caches.match(e.request)));
    return;
  }
  // app files: answer from cache at once and refresh the cached copy in the background
  e.respondWith(caches.open(SHELL).then((c) => c.match(e.request).then((hit) => {
    const net = fetch(e.request).then((r) => { if (r.ok) c.put(e.request, r.clone()); return r; }).catch(() => hit);
    return hit || net;
  })));
});
