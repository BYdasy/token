// 仅提供离线说明；认证、余额、业务页面和API响应不进入离线缓存。
const CACHE = 'token-user-offline-v1';
const offline = new URL('offline.html', self.registration.scope).href;
self.addEventListener('install', event => { event.waitUntil(caches.open(CACHE).then(cache => cache.add(offline))); });
self.addEventListener('activate', event => { event.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', event => {
  if (event.request.mode !== 'navigate' || event.request.method !== 'GET') return;
  const url = new URL(event.request.url);
  if (!url.href.startsWith(self.registration.scope)) return;
  event.respondWith(fetch(event.request).catch(() => caches.match(offline)));
});
