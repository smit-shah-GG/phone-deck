// Minimal service worker: cache the app shell so the deck opens instantly and
// survives brief network blips. Live data still comes over the WebSocket.
// Also the Send-to-Rig share-target bridge: the OS share-sheet POST to /share
// arrives cookie-less (JWT cookie is SameSite=Strict), so we intercept it here
// and re-issue the form as a same-origin fetch to /share/api — where the cookie
// attaches — then answer with an instant confirmation page. Strict stays Strict.
const CACHE = "deck-v15";
// app.js is version-queried so a stale copy can't be served from cache: bump ?v= in
// deck.html (always fetched fresh) and here in lockstep to force a clean re-fetch.
const SHELL = ["/static/app.js?v=15", "/static/manifest.webmanifest"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (url.pathname === "/share" && e.request.method === "POST") {
    e.respondWith(handleShare(e.request));                     // send-to-rig bridge
    return;
  }
  if (url.pathname === "/ws" || e.request.method !== "GET") return; // never cache WS / writes
  // Page navigations: always go to network so UI updates show immediately.
  if (e.request.mode === "navigate") return;
  e.respondWith(
    fetch(e.request).then((r) => {
      const copy = r.clone();
      caches.open(CACHE).then((c) => c.put(e.request, copy)).catch(() => {});
      return r;
    }).catch(() => caches.match(e.request))
  );
});

async function handleShare(req) {
  let r;
  try {
    const fd = await req.formData();
    r = await fetch("/share/api", { method: "POST", body: fd, credentials: "same-origin" });
  } catch (err) {
    return sharePage(false, "RIG UNREACHABLE", "check tailscale / deck service");
  }
  if (r.status === 401 || r.status === 403 || r.redirected)
    return sharePage(false, "NOT LOGGED IN", "open the deck once, then share again");
  let j = null;
  try { j = await r.json(); } catch (e) {}
  if (!j || !j.ok) return sharePage(false, "SHARE FAILED", (j && j.detail) || `status ${r.status}`);
  return sharePage(true, "SENT → LIGHTNING", j.detail || "");
}

function sharePage(ok, head, detail) {
  const esc = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;");
  const c = ok ? "#33ffa0" : "#ff5252";
  return new Response(
`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>deck share</title>
<body style="margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;background:#000;color:${c};font-family:monospace;text-align:center">
<div style="font-size:22px;letter-spacing:.15em">${esc(head)} ${ok ? "✓" : "✗"}</div>
<div style="opacity:.65;font-size:14px;padding:0 24px">${esc(detail)}</div>
<div style="opacity:.35;font-size:12px;margin-top:18px">tap back to return</div>
</body>`,
    { headers: { "Content-Type": "text/html; charset=utf-8" } });
}
