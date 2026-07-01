// phone-deck client: live WS state, tab switching, action dispatch, PWA niceties.
"use strict";

const WS_COUNT = 15;  // number of workspaces shown per monitor
let volDragging = false;
let lastState = {};   // most recent WS state, for re-render after async fetches
let brightness = {};  // {monitor: pct}, fetched on demand (ddcutil is slow)
let videoCaptured = false;  // Stream "Lock input" (Pointer Lock) active -> relative input
let mainSock = null;        // the live-state WebSocket (hoisted so visibility can revive it)

async function fetchBrightness() {
  try {
    const r = await fetch("/brightness");
    if (r.ok) { brightness = await r.json(); renderWorkspaces(lastState); }
  } catch (_) {}
}

// ---- PWA: service worker, wake lock, landscape lock (secure context only) ----
if ("serviceWorker" in navigator) navigator.serviceWorker.register("/static/sw.js").catch(() => {});

let wakeLock = null;
async function keepAwake() {
  try { wakeLock = await navigator.wakeLock?.request("screen"); } catch (_) {}
}
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState !== "visible") return;
  keepAwake();
  // Backgrounded PWAs get their timers throttled, so the 2s auto-reconnect may not fire
  // while suspended — the UI can come back frozen on a dead socket. Revive on resume.
  if (!mainSock || mainSock.readyState > 1) connect();        // 2=CLOSING, 3=CLOSED
  if (!inputSock || inputSock.readyState > 1) connectInput();
});
keepAwake();
if (screen.orientation?.lock) screen.orientation.lock("landscape").catch(() => {});

// in-app fullscreen toggle (fallback when not launched as an installed PWA)
const fsBtn = document.getElementById("fs-btn");
if (fsBtn) fsBtn.onclick = () => {
  if (document.fullscreenElement) document.exitFullscreen();
  else document.documentElement.requestFullscreen().catch(() => {});
};

const reloadBtn = document.getElementById("reload-btn");
if (reloadBtn) reloadBtn.onclick = () => location.reload();

// phone battery in the top bar (Battery Status API reports the device running the
// browser = the phone). Chromium-only + secure-context; where it's unavailable the chip
// stays hidden (graceful).
(function initBattery() {
  const el = document.getElementById("st-bat");
  if (!el || !navigator.getBattery) return;
  navigator.getBattery().then((bat) => {
    const render = () => {
      const pct = Math.round(bat.level * 100);
      el.textContent = `${bat.charging ? "⚡" : "🔋"}${pct}%`;
      el.classList.toggle("text-red-400", !bat.charging && pct <= 15);
      el.classList.remove("hidden");
    };
    render();
    bat.addEventListener("levelchange", render);
    bat.addEventListener("chargingchange", render);
  }).catch(() => {});
})();

// ---- helpers ----
async function post(url, body) {
  const r = await fetch(url, {
    method: "POST", headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : "{}",
  });
  if (r.status === 401) { location.href = "/login"; return null; }
  return r.json().catch(() => ({}));
}
function setResult(id, res) {
  const el = document.getElementById(id);
  if (el && res) el.textContent = res.ok ? "✓ ok" : `✗ ${res.error || res.stderr || res.code || ""}`;
}

// ---- tabs ----
document.querySelectorAll(".tab").forEach((btn) => {
  btn.onclick = () => {
    document.querySelectorAll(".tab").forEach((b) => {
      const on = b === btn;
      b.classList.toggle("bg-zinc-800", on);
      b.classList.toggle("text-zinc-500", !on);
    });
    document.querySelectorAll("[data-page]").forEach((p) =>
      p.classList.toggle("hidden", p.dataset.page !== btn.dataset.tab));
    if (btn.dataset.tab === "commands") loadCommands();
    if (btn.dataset.tab === "modes") loadModes();
    if (btn.dataset.tab === "workspaces") fetchBrightness();
    if (btn.dataset.tab === "config") loadConfig(cfgCurrent);
    if (btn.dataset.tab === "stream") populateMonitors();
    if (btn.dataset.tab === "files") populateShotMonitors();
  };
});

// ---- files: screenshot + drop ----
function populateShotMonitors() {
  const sel = document.getElementById("shot-monitor");
  const mons = (lastState.hypr && lastState.hypr.monitors) || [];
  const cur = sel.value;
  sel.innerHTML = mons.map((m) => `<option value="${m.name}">${m.name} (${m.model || ""})</option>`).join("");
  if (cur) sel.value = cur;
}
(function initFiles() {
  const grab = document.getElementById("shot-grab");
  if (!grab) return;
  grab.onclick = async () => {
    const mon = document.getElementById("shot-monitor").value;
    grab.classList.add("opacity-50");
    const r = await fetch(`/screenshot?monitor=${encodeURIComponent(mon)}`);
    grab.classList.remove("opacity-50");
    if (r.status === 401) { location.href = "/login"; return; }
    if (!r.ok) { document.getElementById("up-status"); return; }
    const url = URL.createObjectURL(await r.blob());
    const img = document.getElementById("shot-img");
    img.src = url; img.classList.remove("hidden");
    const dl = document.getElementById("shot-dl");
    dl.href = url; dl.download = `${mon}.png`; dl.classList.remove("hidden");
  };
  document.getElementById("up-send").onclick = async () => {
    const f = document.getElementById("up-file").files[0];
    const status = document.getElementById("up-status");
    if (!f) { status.textContent = "pick a file first"; return; }
    status.textContent = `uploading ${f.name}…`;
    const fd = new FormData(); fd.append("file", f);
    const r = await fetch("/upload", { method: "POST", body: fd });
    if (r.status === 401) { location.href = "/login"; return; }
    const res = await r.json().catch(() => ({}));
    status.textContent = res.ok
      ? `✓ saved ${res.name} (${(res.size / 1048576).toFixed(2)} MB)`
      : `✗ ${res.error || "failed"}`;
  };
})();

// ---- in-deck config editor ----
let cfgCurrent = "commands";
async function loadConfig(name) {
  cfgCurrent = name;
  document.querySelectorAll(".cfg-pick").forEach((b) =>
    b.classList.toggle("bg-emerald-700", b.dataset.cfg === name));
  document.getElementById("cfg-status").textContent = "";
  const r = await (await fetch(`/config/${name}`)).json().catch(() => ({}));
  document.getElementById("cfg-text").value = r.text || "";
}
document.querySelectorAll(".cfg-pick").forEach((b) => (b.onclick = () => loadConfig(b.dataset.cfg)));
const cfgSave = document.getElementById("cfg-save");
if (cfgSave) cfgSave.onclick = async () => {
  const status = document.getElementById("cfg-status");
  status.textContent = "saving…"; status.className = "text-xs flex-1 text-zinc-400";
  const res = await post(`/config/${cfgCurrent}`, { text: document.getElementById("cfg-text").value });
  if (res && res.ok) {
    status.textContent = "✓ saved"; status.className = "text-xs flex-1 text-emerald-400";
    if (cfgCurrent === "context") loadContextConfig();   // strip reflects edits without a reload
  } else { status.textContent = `✗ ${res ? res.error : "failed"}`; status.className = "text-xs flex-1 text-red-400"; }
};

// ---- privileged actions (Performance + System) ----
document.querySelectorAll(".act").forEach((btn) => {
  btn.onclick = async () => {
    if (btn.dataset.confirm && !confirm(btn.dataset.confirm)) return;
    const params = {};
    if (btn.dataset.param) {
      const [key, srcId] = btn.dataset.param.split(":");
      params[key] = Number(document.getElementById(srcId).value);
    }
    btn.classList.add("opacity-50");
    const res = await post("/action", { action: btn.dataset.action, params });
    btn.classList.remove("opacity-50");
    setResult(btn.closest('[data-page="system"]') ? "sys-result" : "act-result", res);
  };
});

// ---- audio / media ----
document.querySelectorAll("[data-mute]").forEach((b) =>
  (b.onclick = () => post("/audio/mute", { target: b.dataset.mute })));
document.querySelectorAll(".media").forEach((b) =>
  (b.onclick = () => post(`/media/${b.dataset.media}`)));

const volSlider = document.getElementById("vol-slider");
if (volSlider) {
  const send = () => post("/audio/volume", { pct: Number(volSlider.value) });
  volSlider.addEventListener("pointerdown", () => (volDragging = true));
  volSlider.addEventListener("input", () => (document.getElementById("vol-val").textContent = volSlider.value));
  volSlider.addEventListener("change", () => { send(); volDragging = false; });
}

// ---- system: lock + kill ----
document.querySelectorAll("[data-sys]").forEach((b) => {
  if (b.dataset.sys === "lock") b.onclick = () => post("/sys/lock");
});

// ---- commands / deploy ----
async function loadCommands() {
  const grid = document.getElementById("cmd-grid");
  const cmds = await (await fetch("/commands")).json().catch(() => []);
  if (!cmds.length) {
    grid.innerHTML = '<p class="col-span-3 text-zinc-600 text-sm">No commands. Add them to ~/.config/phone-deck/commands.json</p>';
    return;
  }
  grid.innerHTML = cmds.map((c) =>
    `<button class="cmd bg-zinc-800 rounded-lg py-4 text-sm" data-id="${c.id}"
      ${c.confirm ? 'data-confirm="1"' : ""}>${c.label}</button>`).join("");
  grid.querySelectorAll(".cmd").forEach((b) => {
    b.onclick = async () => {
      if (b.dataset.confirm && !confirm(`Run "${b.textContent}"?`)) return;
      const out = document.getElementById("cmd-output");
      out.textContent = "running…";
      b.classList.add("opacity-50");
      const res = await post("/commands/run", { id: b.dataset.id });
      b.classList.remove("opacity-50");
      out.textContent = (res.ok ? "✓ " : `✗ (${res.code ?? res.error}) `) + "\n" + (res.output || res.error || "");
    };
  });
}

// ---- remote input (keyboard + trackpad over a dedicated WS) ----
let inputSock = null;
function connectInput() {
  if (inputSock && inputSock.readyState <= 1) return;   // already open/connecting — no dupes
  const proto = location.protocol === "https:" ? "wss" : "ws";
  inputSock = new WebSocket(`${proto}://${location.host}/ws/input`);
  inputSock.onclose = () => setTimeout(connectInput, 2000);
}
function sendInput(o) {
  if (inputSock && inputSock.readyState === 1) inputSock.send(JSON.stringify(o));
}

(function initRemote() {
  const tp = document.getElementById("trackpad");
  if (!tp) return;
  const SENS = 3.0, ACCEL = 0.06;  // base gain + acceleration (faster swipe = farther)
  // Pointer Events so the trackpad works from touch AND a laptop mouse. The gesture
  // logic mirrors the old touch handlers exactly: 1 pointer = move/tap-left, 2 pointers
  // = scroll/tap-right; plus a wheel handler for mouse scroll.
  const pts = new Map();   // pointerId -> {x, y}
  let moved = false, startT = 0, wasTwo = false, scrollY = null;

  tp.addEventListener("pointerdown", (ev) => {
    ev.preventDefault();
    tp.setPointerCapture?.(ev.pointerId);
    pts.set(ev.pointerId, { x: ev.clientX, y: ev.clientY });
    if (pts.size === 1) { moved = false; startT = Date.now(); wasTwo = false; }
    else if (pts.size === 2) {
      wasTwo = true;
      const a = [...pts.values()];
      scrollY = (a[0].y + a[1].y) / 2;
    }
  });

  tp.addEventListener("pointermove", (ev) => {
    const prev = pts.get(ev.pointerId);
    if (!prev) return;
    ev.preventDefault();
    pts.set(ev.pointerId, { x: ev.clientX, y: ev.clientY });
    if (pts.size >= 2) {
      const a = [...pts.values()];
      const y = (a[0].y + a[1].y) / 2;
      if (scrollY != null) {
        const d = y - scrollY;
        if (Math.abs(d) > 3) { sendInput({ t: "scroll", dy: d < 0 ? 1 : -1 }); scrollY = y; }
      }
      return;
    }
    const dx = ev.clientX - prev.x, dy = ev.clientY - prev.y;
    if (Math.abs(dx) + Math.abs(dy) > 2) moved = true;
    const f = SENS * (1 + Math.hypot(dx, dy) * ACCEL);
    sendInput({ t: "move", dx: Math.round(dx * f), dy: Math.round(dy * f) });
  });

  function tpUp(ev) {
    if (!pts.has(ev.pointerId)) return;
    pts.delete(ev.pointerId);
    if (pts.size > 0) return;                 // wait for the last pointer to lift
    const quick = Date.now() - startT < (wasTwo ? 300 : 250);
    if (!moved && quick) sendInput({ t: "click", b: wasTwo ? "right" : "left" });
    wasTwo = false; scrollY = null;
  }
  tp.addEventListener("pointerup", tpUp);
  tp.addEventListener("pointercancel", tpUp);
  tp.addEventListener("wheel", (ev) => {        // mouse wheel -> scroll
    ev.preventDefault();
    sendInput({ t: "scroll", dy: ev.deltaY < 0 ? 1 : -1 });
  }, { passive: false });

  document.querySelectorAll("[data-click]").forEach((b) =>
    (b.onclick = () => sendInput({ t: "click", b: b.dataset.click })));
  document.querySelectorAll(".rkey").forEach((b) =>
    (b.onclick = () => sendInput({ t: "key", name: b.dataset.key })));
  document.querySelectorAll(".rcombo").forEach((b) =>
    (b.onclick = () => sendInput({ t: "combo", keys: b.dataset.combo.split(",") })));

  // soft-keyboard: diff the field and stream added chars / backspaces
  const kbd = document.getElementById("kbd");
  let prev = "";
  kbd.addEventListener("input", () => {
    const v = kbd.value;
    if (v.length > prev.length) sendInput({ t: "text", s: v.slice(prev.length) });
    else for (let i = 0; i < prev.length - v.length; i++) sendInput({ t: "key", name: "backspace" });
    prev = v;
    if (v.length > 200) { kbd.value = ""; prev = ""; }
  });

  fetch("/input/available").then((r) => r.json()).then((r) => {
    if (!r.ok) tp.textContent = "Remote input unavailable (no /dev/uinput access)";
  }).catch(() => {});
})();

// ---- audio streaming (WebRTC, bidirectional) ----
let streamPc = null, micStream = null;
const streamOpts = { listen: true, mic: false, phoneOnly: false, video: false };
function streamState(text, on) {
  document.getElementById("stream-status").textContent = text;
  document.getElementById("stream-dot").classList.toggle("bg-emerald-500", !!on);
  document.getElementById("stream-dot").classList.toggle("bg-zinc-600", !on);
}
function populateMonitors() {
  const sel = document.getElementById("stream-monitor");
  const mons = (lastState.hypr && lastState.hypr.monitors) || [];
  const cur = sel.value;
  sel.innerHTML = mons.map((m) => `<option value="${m.name}">${m.name} (${m.model || ""})</option>`).join("");
  if (cur) sel.value = cur;
}
async function streamStop() {
  if (streamPc) { streamPc.close(); streamPc = null; }
  if (micStream) { micStream.getTracks().forEach((t) => t.stop()); micStream = null; }
  document.getElementById("stream-audio").srcObject = null;
  const v = document.getElementById("stream-video-el");
  v.srcObject = null; v.classList.add("hidden");
  await post("/audio/webrtc/stop");
  streamState("Idle", false);
}
async function streamConnect() {
  if (!streamOpts.listen && !streamOpts.mic && !streamOpts.video) {
    streamState("enable a direction first", false); return;
  }
  await streamStop();
  streamState("connecting…", false);
  const pc = new RTCPeerConnection();
  streamPc = pc;
  pc.ontrack = (e) => {
    if (e.track.kind === "video") {
      const v = document.getElementById("stream-video-el");
      v.srcObject = e.streams[0]; v.classList.remove("hidden");
    } else {
      document.getElementById("stream-audio").srcObject = e.streams[0];
    }
  };
  pc.onconnectionstatechange = () => {
    if (pc !== streamPc) return;
    if (pc.connectionState === "connected") streamState("● connected", true);
    else if (["failed", "disconnected", "closed"].includes(pc.connectionState)) streamState(pc.connectionState, false);
  };
  try {
    if (streamOpts.mic) {
      micStream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true } });
      // one bidirectional m-line when also listening, so the rig-audio track can't land on
      // the wrong transceiver (aiortc.addTrack reuses the first audio transceiver it finds)
      const dir = streamOpts.listen ? "sendrecv" : "sendonly";
      micStream.getAudioTracks().forEach((t) => pc.addTransceiver(t, { direction: dir }));
    } else if (streamOpts.listen) {
      pc.addTransceiver("audio", { direction: "recvonly" });
    }
    if (streamOpts.video) pc.addTransceiver("video", { direction: "recvonly" });
    await pc.setLocalDescription(await pc.createOffer());
    await new Promise((res) => {                    // non-trickle: wait for ICE gather
      if (pc.iceGatheringState === "complete") return res();
      pc.onicegatheringstatechange = () => pc.iceGatheringState === "complete" && res();
    });
    const ans = await post("/audio/webrtc/offer", {
      sdp: pc.localDescription.sdp, type: pc.localDescription.type,
      listen: streamOpts.listen, mic: streamOpts.mic, phoneOnly: streamOpts.phoneOnly,
      video: streamOpts.video ? document.getElementById("stream-monitor").value : null,
      videoQuality: document.getElementById("stream-quality").value });
    if (!ans || !ans.sdp) { streamState("offer rejected", false); return; }
    await pc.setRemoteDescription(ans);
  } catch (err) {
    streamState("error: " + (err.name || err), false);
    await streamStop();
  }
}
(function initStream() {
  const c = document.getElementById("stream-connect");
  if (!c) return;
  c.onclick = streamConnect;
  document.getElementById("stream-stop").onclick = streamStop;
  const toggle = (id, key, label) => {
    const b = document.getElementById(id);
    b.onclick = () => {
      streamOpts[key] = !streamOpts[key];
      b.textContent = `${label}: ${streamOpts[key] ? "ON" : "OFF"}`;
      b.classList.toggle("bg-emerald-700", streamOpts[key]);
      b.classList.toggle("bg-zinc-800", !streamOpts[key]);
      b.classList.toggle("text-zinc-400", !streamOpts[key]);
    };
  };
  toggle("stream-listen", "listen", "Listen to PC");
  toggle("stream-mic", "mic", "Phone mic → PC");
  toggle("stream-phoneonly", "phoneOnly", "Phone-only output");
  toggle("stream-video", "video", "Video (screen)");

  // touch-on-video -> remote cursor. Pointer events cover touch AND mouse (laptops).
  // tap = left click · drag = move-with-button · long-press = right click.
  const vid = document.getElementById("stream-video-el");
  const vmon = () => document.getElementById("stream-monitor").value;
  function vfrac(ev) {                       // letterbox-correct fraction within the video content
    if (!vid.videoWidth) return null;
    const r = vid.getBoundingClientRect();
    const s = Math.min(r.width / vid.videoWidth, r.height / vid.videoHeight);
    const dw = vid.videoWidth * s, dh = vid.videoHeight * s;
    const fx = (ev.clientX - r.left - (r.width - dw) / 2) / dw;
    const fy = (ev.clientY - r.top - (r.height - dh) / 2) / dh;
    return (fx < 0 || fx > 1 || fy < 0 || fy > 1) ? null : { fx, fy };
  }
  function vsend(t, ev) { const p = vfrac(ev); if (p) sendInput({ t, monitor: vmon(), fx: p.fx, fy: p.fy }); }
  let vDown = false, vMoved = false, vDrag = false, vStart = null, vTimer = null, vLast = 0;
  vid.addEventListener("pointerdown", (ev) => {
    if (!vid.srcObject || videoCaptured) return;   // capture mode uses relative input instead
    ev.preventDefault(); vid.setPointerCapture(ev.pointerId);
    vDown = true; vMoved = false; vDrag = false; vStart = { x: ev.clientX, y: ev.clientY };
    vsend("vmove", ev);
    vTimer = setTimeout(() => { if (vDown && !vMoved) { vsend("vrclick", ev); vDown = false; } }, 500);
  });
  vid.addEventListener("pointermove", (ev) => {
    if (!vDown || videoCaptured) return;
    if (!vMoved && Math.hypot(ev.clientX - vStart.x, ev.clientY - vStart.y) > 6) {
      vMoved = true; vDrag = true; clearTimeout(vTimer); vsend("vdown", ev);
    }
    if (vMoved) { const now = performance.now(); if (now - vLast > 40) { vLast = now; vsend("vmove", ev); } }
  });
  function vEnd(ev) {
    clearTimeout(vTimer);
    if (!vDown) return;
    if (vDrag) sendInput({ t: "vup" });        // release always, no coords needed
    else if (!vMoved) vsend("vclick", ev);
    vDown = false; vDrag = false; vMoved = false;
  }
  vid.addEventListener("pointerup", vEnd);
  vid.addEventListener("pointercancel", vEnd);
})();

// ---- capture mode: "Lock input" on Stream (laptop becomes kb+mouse+screen) ----
(function initCapture() {
  const btn = document.getElementById("stream-capture");
  const vid = document.getElementById("stream-video-el");
  if (!btn || !vid) return;
  const SPECIAL = {
    Enter: "enter", Escape: "esc", Tab: "tab", Backspace: "backspace", Delete: "delete",
    ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right",
    Home: "home", End: "end", PageUp: "pageup", PageDown: "pagedown", " ": "space",
  };

  btn.onclick = () => {
    if (!vid.srcObject) { document.getElementById("stream-status").textContent = "turn video on first"; return; }
    vid.requestPointerLock?.();
  };
  document.addEventListener("pointerlockchange", () => {
    videoCaptured = document.pointerLockElement === vid;
    btn.textContent = videoCaptured ? "🔓 Locked — Esc to release" : "🔒 Lock input → rig (mouse + keys)";
    btn.classList.toggle("bg-emerald-700", videoCaptured);
    btn.classList.toggle("text-zinc-400", !videoCaptured);
    btn.classList.toggle("bg-zinc-800", !videoCaptured);
  });

  // relative mouse + clicks + wheel while captured (reuses the existing hid vocab)
  vid.addEventListener("mousemove", (ev) => {
    if (videoCaptured && (ev.movementX || ev.movementY))
      sendInput({ t: "move", dx: ev.movementX, dy: ev.movementY });
  });
  vid.addEventListener("mousedown", (ev) => {
    if (!videoCaptured) return;
    ev.preventDefault();
    sendInput({ t: "click", b: ev.button === 2 ? "right" : ev.button === 1 ? "middle" : "left" });
  });
  vid.addEventListener("contextmenu", (ev) => { if (videoCaptured) ev.preventDefault(); });
  vid.addEventListener("wheel", (ev) => {
    if (!videoCaptured) return;
    ev.preventDefault();
    sendInput({ t: "scroll", dy: ev.deltaY < 0 ? 1 : -1 });
  }, { passive: false });

  // keyboard forward while captured. Esc can't be sent — it releases Pointer Lock.
  window.addEventListener("keydown", (ev) => {
    if (!videoCaptured) return;
    ev.preventDefault();
    const k = ev.key;
    const mods = [];
    if (ev.ctrlKey) mods.push("ctrl");
    if (ev.altKey) mods.push("alt");
    if (ev.metaKey) mods.push("super");
    if (mods.length) {                                  // chord -> combo
      let base = SPECIAL[k] || (k.length === 1 ? k.toLowerCase() : null);
      if (ev.shiftKey) mods.push("shift");
      if (base) sendInput({ t: "combo", keys: [...mods, base] });
    } else if (SPECIAL[k]) {                            // named key (+ optional shift)
      sendInput({ t: "key", name: SPECIAL[k], mods: ev.shiftKey ? ["shift"] : [] });
    } else if (k.length === 1) {                        // printable -> text (handles case)
      sendInput({ t: "text", s: k });
    }
  });
})();

// ---- modes / scenes ----
async function loadModes() {
  const grid = document.getElementById("mode-grid");
  const list = await (await fetch("/modes")).json().catch(() => []);
  if (!list.length) {
    grid.innerHTML = '<p class="col-span-3 text-zinc-600 text-sm">No modes. Add them to ~/.config/phone-deck/modes.json</p>';
    return;
  }
  grid.innerHTML = list.map((m) =>
    `<button class="mode bg-zinc-800 rounded-lg py-6 text-lg font-medium" data-id="${m.id}">${m.label}</button>`).join("");
  grid.querySelectorAll(".mode").forEach((b) => {
    b.onclick = async () => {
      const out = document.getElementById("mode-output");
      out.textContent = `applying ${b.textContent}…`;
      b.classList.add("opacity-50");
      const res = await post("/modes/run", { id: b.dataset.id });
      b.classList.remove("opacity-50");
      out.textContent = res.ok ? (res.steps || []).join("\n") : `✗ ${res.error || ""}`;
    };
  });
}

// ---- live state rendering ----
function renderStatus(s) {
  const g = s.telemetry?.gpu, c = s.telemetry?.cpu, a = s.audio;
  if (g) document.getElementById("st-gpu").textContent =
    `GPU ${g.util|0}% · ${(g.vram_used/1024).toFixed(1)}/${(g.vram_total/1024).toFixed(0)}G · ${g.temp|0}° · ${g.power|0}W`;
  if (c) document.getElementById("st-cpu").textContent =
    `CPU ${c.util|0}%${c.temp ? " · " + (c.temp|0) + "°" : ""} · ${c.mem_used}/${c.mem_total}G`;
  if (a) document.getElementById("st-aud").textContent =
    `${a.mic_muted ? "🔇mic" : "🎙"} ${a.sink_muted ? "🔇" : (a.volume ?? "—") + "%"}`;
  const mons = s.hypr?.monitors || [];
  const foc = mons.find((m) => m.focused);
  document.getElementById("st-ws").textContent = foc ? `${foc.name} · ws ${foc.active_ws}` : "";
}

function renderWorkspaces(s) {
  const grid = document.querySelector('[data-page="workspaces"]');
  const mons = s.hypr?.monitors || [];
  grid.innerHTML = mons.map((m) => `
    <div class="bg-zinc-900 rounded-xl p-3">
      <div class="text-xs text-zinc-500 mb-2 truncate">${m.name} · ${m.model || ""} · ${m.refresh}Hz
        ${m.focused ? '<span class="text-emerald-500">●</span>' : ""}</div>
      <div class="grid grid-cols-5 gap-1.5 content-start">
        ${Array.from({length: WS_COUNT}, (_, i) => i + 1).map((n) => `
          <button class="ws aspect-square rounded-lg text-sm font-medium
            ${m.active_ws === n ? "bg-emerald-600" : "bg-zinc-800"}"
            data-ws="${n}">${n}</button>`).join("")}
      </div>
      <div class="mt-2 flex items-center gap-2">
        <button class="dpms text-xs px-2 py-1 rounded ${m.dpms ? "bg-zinc-800" : "bg-red-800"}"
          data-mon="${m.name}" data-on="${m.dpms ? 1 : 0}" title="DPMS">${m.dpms ? "⏻ on" : "off"}</button>
        <span class="text-xs text-zinc-600">☀</span>
        <input type="range" min="0" max="100" value="${brightness[m.name] ?? 50}"
          class="bri flex-1" data-mon="${m.name}" ${m.name in brightness ? "" : "disabled"}>
      </div>
      <div class="mt-2 space-y-1">
        ${(s.hypr?.windows || []).filter((w) => w.monitor === m.name).map((w) => `
          <button class="win w-full text-left text-xs rounded px-2 py-1 truncate
            ${w.focused ? "bg-emerald-700" : "bg-zinc-800"}" data-addr="${w.address}"
            >${w.ws != null ? w.ws + " · " : ""}${(w.title || w.cls || "?").replace(/</g, "&lt;")}</button>`).join("")}
      </div>
    </div>`).join("");
  grid.querySelectorAll(".ws").forEach((b) => {
    b.onclick = () => post("/hypr/workspace", { id: Number(b.dataset.ws) });
  });
  grid.querySelectorAll(".dpms").forEach((b) => {
    b.onclick = () => post("/hypr/dpms", { monitor: b.dataset.mon, on: b.dataset.on !== "1" });
  });
  grid.querySelectorAll(".bri").forEach((s) => {
    s.addEventListener("change", () => {
      brightness[s.dataset.mon] = Number(s.value);
      post("/brightness", { monitor: s.dataset.mon, pct: Number(s.value) });
    });
  });
  grid.querySelectorAll(".win").forEach((b) => {
    b.onclick = () => post("/hypr/focus", { address: b.dataset.addr });
  });
}

function renderAudio(s) {
  const a = s.audio;
  if (!a) return;
  if (!volDragging && a.volume != null) {
    volSlider.value = a.volume;
    document.getElementById("vol-val").textContent = a.volume;
  }
  document.getElementById("mic-btn").classList.toggle("bg-red-800", a.mic_muted);
  document.getElementById("spk-btn").classList.toggle("bg-red-800", a.sink_muted);
  const np = a.now_playing;
  document.getElementById("now-playing").textContent =
    np && np.title ? `${np.status === "Playing" ? "▶" : "⏸"} ${np.title}` : "—";
  const art = document.getElementById("art");
  if (np && np.art_key) {
    if (art.dataset.k !== np.art_key) {       // only refetch when the track changes
      art.dataset.k = np.art_key;
      art.onerror = () => art.classList.add("hidden");
      art.onload = () => art.classList.remove("hidden");
      art.src = `/media/art?k=${np.art_key}`;
    }
  } else {
    art.classList.add("hidden");
    art.removeAttribute("src");
    art.dataset.k = "";
  }
  document.getElementById("sink-list").innerHTML = (a.sinks || []).map((d) =>
    `<button class="sink w-full text-left text-sm rounded-lg px-3 py-2 truncate
      ${d.active ? "bg-emerald-700" : "bg-zinc-800"}" data-id="${d.id}">${d.name}</button>`).join("");
  document.querySelectorAll(".sink").forEach((b) =>
    (b.onclick = () => post("/audio/sink", { id: Number(b.dataset.id) })));
  document.getElementById("source-list").innerHTML = (a.sources || []).map((d) =>
    `<button class="source w-full text-left text-sm rounded-lg px-3 py-2 truncate
      ${d.active ? "bg-emerald-700" : "bg-zinc-800"}" data-id="${d.id}">${d.name}</button>`).join("");
  document.querySelectorAll(".source").forEach((b) =>
    (b.onclick = () => post("/audio/source", { id: Number(b.dataset.id) })));
}

function renderSystem(s) {
  const ts = s.sysinfo?.tailscale;
  if (ts) document.getElementById("ts-status").textContent =
    `Tailscale: ${ts.up ? "up" : "down"} · ${ts.self} · ${ts.online}/${ts.total} peers online`;
  const procs = s.sysinfo?.procs || [];
  document.getElementById("proc-list").innerHTML = procs.map((p) =>
    `<div class="flex items-center gap-2 bg-zinc-900 rounded px-2 py-1">
      <span class="flex-1 truncate">${p.name}</span>
      <span class="text-zinc-500 tabular-nums">${p.mem}%</span>
      <button class="kill text-red-400 px-2" data-pid="${p.pid}" ${p.mine ? "" : "disabled"}>✕</button>
    </div>`).join("");
  document.querySelectorAll(".kill").forEach((b) =>
    (b.onclick = () => b.disabled || post("/sys/kill", { pid: Number(b.dataset.pid) })));
}

// ---- context strip (surface-only: call · media · in-app controls) ----
let ctxConfig = { apps: {}, call_apps: [] };
async function loadContextConfig() {
  try { ctxConfig = await (await fetch("/context")).json(); } catch (_) {}
}
function ctxLoose(a, b) { return a === b || a.includes(b) || b.includes(a); }
function ctxBtn(label, handler, extra) {
  const b = document.createElement("button");
  b.className = "shrink-0 rounded px-2 py-0.5 text-xs " + (extra || "bg-zinc-800");
  b.textContent = label; b.onclick = handler;
  return b;
}
function ctxGroup(first) {
  const g = document.createElement("div");
  g.className = "flex items-center gap-1 shrink-0 " + (first
    ? "sticky left-0 z-10 bg-zinc-900/95 pr-1"
    : "ml-1 pl-1 border-l border-zinc-700/50");
  return g;
}
function ctxLabel(text, cls) {
  const s = document.createElement("span");
  s.className = "px-1 text-xs " + (cls || "text-zinc-500");
  s.textContent = text;
  return s;
}

function renderContext(s) {
  const strip = document.getElementById("ctx-strip");
  if (!strip) return;
  strip.innerHTML = "";
  const wins = s.hypr?.windows || [];
  const focusedCls = s.hypr?.active_window?.class || "";
  let first = true;

  // 1) Call — pinned hard-left, never scrolls off
  const calls = (ctxConfig.call_apps || []);
  const rec = (s.audio?.recording || []).filter((r) => calls.some((a) => ctxLoose(r, a)));
  if (rec.length) {
    const g = ctxGroup(first); first = false;
    g.appendChild(ctxLabel("📞", "text-emerald-400"));
    g.appendChild(ctxBtn(s.audio?.mic_muted ? "🔇 mic" : "🎙 mute",
      () => post("/audio/mute", { target: "mic" }),
      s.audio?.mic_muted ? "bg-red-800" : "bg-zinc-800"));
    const callWin = wins.find((w) => rec.some((r) => w.cls && ctxLoose(w.cls, r)));
    if (callWin) g.appendChild(ctxBtn("↪ call", () => post("/hypr/focus", { address: callWin.address })));
    strip.appendChild(g);
  }

  // 2) Media / now-playing — generic MPRIS, surfaced strip-wide
  const np = s.audio?.now_playing;
  if (np && np.title) {
    const g = ctxGroup(first); first = false;
    const t = ctxLabel((np.status === "Playing" ? "▶ " : "⏸ ") + np.title, "text-zinc-400");
    t.classList.add("max-w-[10rem]", "truncate");
    g.appendChild(t);
    g.appendChild(ctxBtn("⏮", () => post("/media/previous")));
    g.appendChild(ctxBtn("⏯", () => post("/media/play-pause")));
    g.appendChild(ctxBtn("⏭", () => post("/media/next")));
    strip.appendChild(g);
  }

  // 3) In-app controls — config-driven, keyed by window class, via sendshortcut
  for (const [cls, app] of Object.entries(ctxConfig.apps || {})) {
    const present = app.show === "persist"
      ? wins.some((w) => w.cls === cls)
      : focusedCls === cls;
    if (!present) continue;
    const g = ctxGroup(first); first = false;
    g.appendChild(ctxLabel(app.label || cls));
    (app.controls || []).forEach((c) =>
      g.appendChild(ctxBtn(c.label || c.key,
        () => post("/context/key", { cls, key: c.key, mods: c.mods || "" }))));
    strip.appendChild(g);
  }
}

function render(s) {
  lastState = s;
  renderStatus(s);
  renderContext(s);
  renderWorkspaces(s);
  renderAudio(s);
  renderSystem(s);
}

function connect() {
  if (mainSock && mainSock.readyState <= 1) return;   // already open/connecting — no dupes
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const sock = new WebSocket(`${proto}://${location.host}/ws`);
  mainSock = sock;
  const dot = document.getElementById("st-conn");
  sock.onopen = () => dot.classList.replace("bg-zinc-600", "bg-emerald-500");
  sock.onclose = () => { dot.classList.replace("bg-emerald-500", "bg-zinc-600"); setTimeout(connect, 2000); };
  sock.onmessage = (e) => render(JSON.parse(e.data));
}
// responsive density: tag <html> so the UI can adapt to a fine-pointer / wide screen
// (laptop) vs the phone. Deliberately light — deeper restyling rides with the deferred
// visuals refresh; this just enables it without aggressive layout changes.
function applyDensity() {
  document.documentElement.dataset.pointer =
    window.matchMedia("(pointer: fine)").matches ? "fine" : "coarse";
  document.documentElement.dataset.wide = window.innerWidth >= 1024 ? "1" : "0";
}
applyDensity();
window.addEventListener("resize", applyDensity);

connect();
connectInput();
fetchBrightness();
loadContextConfig();

setInterval(() => {
  document.getElementById("st-clock").textContent =
    new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}, 1000);
