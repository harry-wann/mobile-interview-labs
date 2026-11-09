/* 面試手冊 Service Worker:安裝時整站預快取,之後離線可用;
   策略 = stale-while-revalidate(先給快取秒開,背景更新下次生效)。
   改版時把 VERSION 加一,舊快取會自動清掉。 */
const VERSION = 'v1';
const CACHE = `handbook-${VERSION}`;
const ASSETS = [
  './',
  './index.html',
  './java.html',
  './objc.html',
  './architecture.html',
  './ds.html',
  './concurrency.html',
  './print.html',
  './manifest.webmanifest',
  './icons/icon-192.png',
  './icons/icon-512.png',
];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(ASSETS)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  e.respondWith(
    caches.match(e.request, { ignoreSearch: true }).then((cached) => {
      const fetched = fetch(e.request)
        .then((res) => {
          if (res && res.ok && new URL(e.request.url).origin === self.location.origin) {
            const clone = res.clone();
            caches.open(CACHE).then((c) => c.put(e.request, clone));
          }
          return res;
        })
        .catch(() => cached);
      return cached || fetched;
    })
  );
});
