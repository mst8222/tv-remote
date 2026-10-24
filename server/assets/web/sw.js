/* SPEC-020 PWA：缓存应用外壳，API 一律不缓存 */
var CACHE = 'tvrc-shell-v6';
var SHELL = [
  '/',
  '/index.html',
  '/style.css',
  '/app.js',
  '/manifest.webmanifest',
  '/icon-192.png',
  '/icon-512.png'
];

self.addEventListener('install', function (e) {
  e.waitUntil(
    // cache: reload 跳过 HTTP 缓存，避免升级后仍装上旧的外壳文件
    caches.open(CACHE).then(function (c) {
      return Promise.all(SHELL.map(function (url) {
        return fetch(url, { cache: 'reload' }).then(function (res) {
          if (res && res.ok) return c.put(url, res);
          return null;
        });
      }));
    }).then(function () { return self.skipWaiting(); })
  );
});

self.addEventListener('activate', function (e) {
  e.waitUntil(
    caches.keys().then(function (keys) {
      return Promise.all(keys.map(function (k) {
        if (k !== CACHE) return caches.delete(k);
        return null;
      }));
    }).then(function () { return self.clients.claim(); })
  );
});

self.addEventListener('fetch', function (e) {
  var url = new URL(e.request.url);
  if (e.request.method !== 'GET') return;
  if (url.pathname.indexOf('/api/') === 0) return; // 不缓存任何命令

  if (e.request.mode === 'navigate') {
    e.respondWith(
      fetch(e.request).catch(function () {
        return caches.match('/index.html');
      })
    );
    return;
  }

  e.respondWith(
    caches.match(e.request).then(function (hit) {
      if (hit) return hit;
      return fetch(e.request).then(function (res) {
        var copy = res.clone();
        caches.open(CACHE).then(function (c) { c.put(e.request, copy); });
        return res;
      });
    })
  );
});
