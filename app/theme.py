"""Theme — the Phosphor terminal skin (V2.1), with selectable color profiles.

Emits /theme.css: a palette (:root vars, chosen by profile) + the Phosphor skin (mono
font, phosphor glow, scanlines, CRT vignette, subtle motion) mapped onto the deck's
existing Tailwind classes — so the whole look changes here, without touching the templates.

Profiles (from ~/.config/phone-deck/theme.json → {"profile": "green"}), switchable in the
System tab:
  green (default · classic P1) · amber (P3) · ice (blue-white) · auto (phosphor hue from
  the matugen wallpaper palette, forced luminous).
Amber is the fixed secondary/warning tone across every profile. True-danger red is left
alone.
"""

from __future__ import annotations

import colorsys
import json
from pathlib import Path

from . import config

COLORS_FILE = Path.home() / ".local/state/quickshell/user/generated/colors.json"
THEME_FILE = config.CONFIG_DIR / "theme.json"

# profile -> palette. bg is true-black (#000) on purpose: AMOLED draws it as *off* pixels,
# so the glow blooms and it sips power.
PROFILES: dict[str, dict[str, str]] = {
    "green": {
        "--p-bg": "#000000", "--p-low": "#030603", "--p-surface": "#081208", "--p-high": "#0e200e",
        "--p-on": "#b8ffda", "--p-on-var": "#6fb992", "--p-primary": "#33ffa0",
        "--p-on-primary": "#02160b", "--p-secondary": "#ffb000", "--p-outline": "#1e3d2b",
    },
    "amber": {
        "--p-bg": "#000000", "--p-low": "#060402", "--p-surface": "#140d03", "--p-high": "#241806",
        "--p-on": "#ffdca6", "--p-on-var": "#b9945a", "--p-primary": "#ffb000",
        "--p-on-primary": "#1a0f00", "--p-secondary": "#33ffa0", "--p-outline": "#3d2c14",
    },
    "ice": {
        "--p-bg": "#000000", "--p-low": "#020406", "--p-surface": "#071018", "--p-high": "#0d1e2a",
        "--p-on": "#c9ecff", "--p-on-var": "#7aa6bf", "--p-primary": "#7fdcff",
        "--p-on-primary": "#021018", "--p-secondary": "#ffb000", "--p-outline": "#173445",
    },
}


VALID_PROFILES = ("green", "amber", "ice", "auto")


_AMBIENT_DEFAULT = {"idle_min": 2, "liturgy": True}


def _cfg() -> dict:
    try:
        d = json.loads(THEME_FILE.read_text())
        return d if isinstance(d, dict) else {}
    except (OSError, json.JSONDecodeError):
        return {}


def _write_cfg(**updates) -> str | None:
    """Merge-write theme.json (profile + ambient share the file — never clobber)."""
    try:
        config.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        THEME_FILE.write_text(json.dumps({**_cfg(), **updates}))
        return None
    except OSError as exc:
        return str(exc)


def _profile() -> str:
    p = _cfg().get("profile", "green")
    return p if p in VALID_PROFILES else "green"


def set_profile(p: str) -> dict:
    if p not in VALID_PROFILES:
        return {"ok": False, "error": "unknown profile"}
    err = _write_cfg(profile=p)
    return {"ok": False, "error": err} if err else {"ok": True, "profile": p}


def ambient() -> dict:
    """Cogitator settings, clamped sane: {idle_min: 1-30, liturgy: bool}."""
    a = _cfg().get("ambient", {})
    out = dict(_AMBIENT_DEFAULT)
    if isinstance(a, dict):
        if isinstance(a.get("idle_min"), (int, float)):
            out["idle_min"] = max(1, min(30, int(a["idle_min"])))
        if isinstance(a.get("liturgy"), bool):
            out["liturgy"] = a["liturgy"]
    return out


def set_ambient(a: dict) -> dict:
    cur = ambient()
    try:
        if "idle_min" in a:
            cur["idle_min"] = max(1, min(30, int(a["idle_min"])))
        if "liturgy" in a:
            cur["liturgy"] = bool(a["liturgy"])
    except (TypeError, ValueError):
        return {"ok": False, "error": "bad ambient values"}
    err = _write_cfg(ambient=cur)
    return {"ok": False, "error": err} if err else {"ok": True, "ambient": cur}


def _hex_to_hls(h: str) -> tuple[float, float, float]:
    h = h.lstrip("#")
    r, g, b = (int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))
    return colorsys.rgb_to_hls(r, g, b)


def _hls_to_hex(hue: float, light: float, sat: float) -> str:
    r, g, b = colorsys.hls_to_rgb(hue, light, sat)
    return "#%02x%02x%02x" % (int(r * 255), int(g * 255), int(b * 255))


def _auto_vars() -> dict[str, str]:
    """Phosphor palette whose HUE comes from the matugen primary, but forced to a luminous,
    saturated phosphor level so it always glows (a muted wallpaper won't produce a sad,
    washed-out terminal). Surfaces are hue-tinted near-black; amber stays the secondary."""
    v = dict(PROFILES["green"])
    try:
        primary = json.loads(COLORS_FILE.read_text()).get("primary")
    except (OSError, json.JSONDecodeError):
        primary = None
    if not primary:
        return v
    hue, _, _ = _hex_to_hls(primary)
    v["--p-primary"] = _hls_to_hex(hue, 0.62, 0.95)
    v["--p-on"] = _hls_to_hex(hue, 0.85, 0.55)
    v["--p-on-var"] = _hls_to_hex(hue, 0.60, 0.30)
    v["--p-outline"] = _hls_to_hex(hue, 0.20, 0.45)
    v["--p-surface"] = _hls_to_hex(hue, 0.055, 0.40)
    v["--p-high"] = _hls_to_hex(hue, 0.095, 0.38)
    return v


# The Phosphor skin. References the :root vars only (so it's profile-independent). color-mix
# is used for glows (Chrome supports it). Tailwind class names are kept — we just repaint them.
_SKIN = r"""
* { font-family:'IBM Plex Mono',ui-monospace,'Share Tech Mono',monospace !important; }

body { background:var(--p-bg)!important; color:var(--p-on)!important;
       text-shadow:0 0 1px color-mix(in srgb, var(--p-primary) 22%, transparent); }

/* CRT overlays: vignette + drifting scanlines, non-interactive, above everything */
body::before { content:""; position:fixed; inset:0; pointer-events:none; z-index:9998;
  box-shadow:inset 0 0 180px 30px rgba(0,0,0,.5); }
body::after { content:""; position:fixed; inset:0; pointer-events:none; z-index:9999;
  background:repeating-linear-gradient(to bottom, transparent 0 2px, rgba(0,0,0,.20) 2px 3px);
  background-size:100% 3px; animation:pscan 9s linear infinite; opacity:.5; }
@keyframes pscan { to { background-position:0 300px; } }
@media (prefers-reduced-motion:reduce){ body::after{ animation:none; } }

/* surface tiers */
.bg-black { background:var(--p-bg)!important; }
.bg-zinc-950, .bg-zinc-950\/60 { background:var(--p-low)!important; }
.bg-zinc-900 { background:var(--p-surface)!important; }
.bg-zinc-800 { background:var(--p-high)!important; }

/* text */
.text-zinc-100, .text-zinc-200 { color:var(--p-on)!important; }
.text-zinc-300, .text-zinc-400 { color:var(--p-on-var)!important; }
.text-zinc-500, .text-zinc-600 { color:var(--p-on-var)!important; opacity:.7; }
.border-zinc-700, .border-zinc-800 { border-color:var(--p-outline)!important; }

/* the brand + accent text glow phosphor */
.text-emerald-500 { color:var(--p-primary)!important;
  text-shadow:0 0 8px color-mix(in srgb, var(--p-primary) 55%, transparent),
              0 0 2px color-mix(in srgb, var(--p-primary) 80%, transparent); }
.text-emerald-400 { color:var(--p-secondary)!important;
  text-shadow:0 0 6px color-mix(in srgb, var(--p-secondary) 45%, transparent); }

/* filled primary (active workspace, connect, save, mode buttons…) = glowing phosphor */
.bg-emerald-500, .bg-emerald-600, .bg-emerald-700 {
  background:color-mix(in srgb, var(--p-primary) 88%, #000)!important;
  color:var(--p-on-primary)!important;
  box-shadow:0 0 12px color-mix(in srgb, var(--p-primary) 45%, transparent),
             inset 0 0 14px rgba(0,0,0,.30)!important; }

/* terminal buttons: hairline frame, phosphor lift on press */
button, .sel, textarea, input[type="text"], input[type="password"] {
  border:1px solid var(--p-outline)!important; border-radius:6px!important; }
button:active { box-shadow:0 0 10px color-mix(in srgb, var(--p-primary) 55%, transparent)!important;
  border-color:var(--p-primary)!important; }

/* active tab: bracketed, glowing */
.tab.bg-zinc-800 { color:var(--p-primary)!important; border-color:var(--p-primary)!important;
  box-shadow:0 0 8px color-mix(in srgb, var(--p-primary) 35%, transparent); }
.tab.bg-zinc-800::before { content:"["; margin-right:2px; opacity:.7; }
.tab.bg-zinc-800::after  { content:"]"; margin-left:2px; opacity:.7; }

/* active workspace / active list-item breathes */
.ws.bg-emerald-600, .sink.bg-emerald-700, .source.bg-emerald-700, .win.bg-emerald-700 {
  animation:pglow 2.6s ease-in-out infinite; }
@keyframes pglow { 50% { box-shadow:0 0 16px color-mix(in srgb, var(--p-primary) 70%, transparent),
                                    inset 0 0 14px rgba(0,0,0,.30); } }

/* the live link dot + recording states glow */
#st-conn.bg-emerald-500 { box-shadow:0 0 8px var(--p-primary); }

input[type="range"] { accent-color:var(--p-primary); }

/* Friday's typewriter cursor (Voice tab) */
.jv-cursor { color:var(--p-primary); text-shadow:0 0 6px var(--p-primary);
  animation:pblink 1.1s steps(1) infinite; }
@keyframes pblink { 50% { opacity:0; } }

/* amber = warning tone; keep the real danger reds as-is */
.bg-amber-900\/70 { background:color-mix(in srgb, var(--p-secondary) 26%, #000)!important;
  border-color:color-mix(in srgb, var(--p-secondary) 55%, transparent)!important;
  color:var(--p-secondary)!important; }

/* very subtle overall CRT flicker (V3 shell elements) */
.band, .center, .rail { animation:pflick 7s steps(60) infinite; }
@keyframes pflick { 0%,97%,100%{ opacity:1 } 98%{ opacity:.96 } 99%{ opacity:.99 } }
@media (prefers-reduced-motion:reduce){ .band, .center, .rail{ animation:none; } }

/* ════════ V2.1 flourishes: box-drawing frames · readout labels · boot-in ════════ */

/* terminal frames — sharpen padded panels + draw box-drawing corner ticks */
.bg-zinc-900.rounded-lg, .bg-zinc-900.rounded-xl { position:relative; border-radius:6px!important; }
.bg-zinc-900.rounded-lg::before, .bg-zinc-900.rounded-xl::before,
.bg-zinc-900.rounded-lg::after,  .bg-zinc-900.rounded-xl::after {
  content:""; position:absolute; width:11px; height:11px; pointer-events:none;
  border:0 solid var(--p-primary); opacity:.55; }
.bg-zinc-900.rounded-lg::before, .bg-zinc-900.rounded-xl::before {
  top:3px; left:3px; border-top-width:1px; border-left-width:1px; }
.bg-zinc-900.rounded-lg::after, .bg-zinc-900.rounded-xl::after {
  bottom:3px; right:3px; border-bottom-width:1px; border-right-width:1px; }

/* card-titles: amber uppercase trailing off into a box-drawing rule (sketch-native) */
p.uppercase { color:var(--p-secondary)!important; opacity:1;
  display:flex; align-items:center; gap:8px; letter-spacing:1.5px;
  text-shadow:0 0 6px color-mix(in srgb, var(--p-secondary) 45%, transparent); }
p.uppercase::after { content:"────────────────────────────────";
  color:var(--p-outline)!important; flex:1; overflow:hidden; white-space:nowrap;
  letter-spacing:0; text-shadow:none; opacity:.7; }

/* box-drawing separator under the tab row */
nav { border-bottom:1px solid var(--p-outline); }

/* workspaces as a framed matrix */
.ws { border:1px solid var(--p-outline)!important; border-radius:4px!important; }

/* page boot-in on tab switch (display:none→block restarts it each time) */
[data-page]:not(.hidden) { animation:pboot .42s ease-out; }
@keyframes pboot {
  0%   { opacity:0; transform:translateY(5px); filter:brightness(2.2) blur(.6px); }
  35%  { opacity:1; filter:brightness(1.7); }
  100% { opacity:1; transform:none; filter:none; }
}
@media (prefers-reduced-motion:reduce){ [data-page]:not(.hidden){ animation:none; } }

/* ════════ V2.1 component ports (match the winning sketch) ════════ */

/* Audio: device rows — dot + name, active glows (renderAudio emits .dev/.on/.dot) */
.dev { display:flex!important; align-items:center; gap:9px; padding:8px 10px!important;
  border:1px solid var(--p-outline)!important; border-radius:6px!important;
  background:var(--p-low)!important; color:var(--p-on-var)!important; }
.dev .dot { width:7px; height:7px; border-radius:50%; background:var(--p-outline); flex:none; }
.dev.on { color:var(--p-primary)!important; border-color:var(--p-primary)!important;
  background:color-mix(in srgb, var(--p-primary) 8%, #000)!important;
  text-shadow:0 0 6px color-mix(in srgb, var(--p-primary) 40%, transparent); }
.dev.on .dot { background:var(--p-primary); box-shadow:0 0 8px var(--p-primary); }

/* System: process bars (renderSystem emits .proc/.pn/.pbar/.pfill/.pv) */
.proc { display:flex; align-items:center; gap:10px; font-size:12px; }
.proc .pn { color:var(--p-on-var); flex:1; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.proc .pbar { width:90px; height:9px; background:var(--p-low); border:1px solid var(--p-outline);
  border-radius:4px; overflow:hidden; flex:none; }
.proc .pfill { display:block; height:100%; background:var(--p-primary);
  box-shadow:0 0 8px color-mix(in srgb, var(--p-primary) 50%, transparent); }
.proc .pv { color:var(--p-primary); width:38px; text-align:right;
  text-shadow:0 0 4px color-mix(in srgb, var(--p-primary) 35%, transparent); }
.proc .kill { color:#ff7a7a; border:none!important; background:none!important; }
#ts-status { color:var(--p-on-var)!important; }

/* Remote: trackpad as an input grid; amber combos; phosphor field */
#trackpad { border:1px solid var(--p-primary)!important; color:var(--p-on-var)!important;
  background:
    repeating-linear-gradient(0deg,transparent 0 21px,color-mix(in srgb,var(--p-primary) 6%,transparent) 21px 22px),
    repeating-linear-gradient(90deg,transparent 0 21px,color-mix(in srgb,var(--p-primary) 6%,transparent) 21px 22px),
    var(--p-low)!important;
  box-shadow:inset 0 0 34px color-mix(in srgb,var(--p-primary) 8%,transparent)!important; }
.rcombo { color:var(--p-secondary)!important;
  border-color:color-mix(in srgb,var(--p-secondary) 35%,transparent)!important; }
#kbd { color:var(--p-primary)!important; background:var(--p-low)!important;
  text-shadow:0 0 4px color-mix(in srgb,var(--p-primary) 30%,transparent); }

/* Modes: big terminal buttons + $ logline */
.mode { letter-spacing:2px!important; }
#mode-output { color:var(--p-primary)!important; background:var(--p-low)!important;
  border:1px solid var(--p-outline)!important; border-radius:6px!important;
  text-shadow:0 0 4px color-mix(in srgb,var(--p-primary) 35%,transparent)!important; }

/* Voice: terminal mic button; trace/answer cards; pending=amber; FRIDAY + source chips */
#voice-btn { border:1px solid var(--p-secondary)!important; color:var(--p-secondary)!important;
  background:radial-gradient(120% 120% at 50% 30%, color-mix(in srgb,var(--p-secondary) 12%,transparent), transparent)!important;
  text-shadow:0 0 8px color-mix(in srgb,var(--p-secondary) 45%,transparent)!important; letter-spacing:2px; }
#voice-log > div { border:1px solid var(--p-outline)!important; background:var(--p-low)!important; border-radius:8px!important; }
#voice-log .cursor-pointer { border-color:color-mix(in srgb,var(--p-secondary) 50%,transparent)!important;
  background:color-mix(in srgb,var(--p-secondary) 6%,#000)!important; }
#voice-log a { border:1px solid color-mix(in srgb,var(--p-primary) 30%,transparent)!important;
  border-radius:4px; padding:1px 7px; margin-right:6px; color:var(--p-primary)!important;
  text-shadow:0 0 4px color-mix(in srgb,var(--p-primary) 35%,transparent); }
.jv-friday { color:var(--p-primary); font-size:10px; letter-spacing:2px;
  text-shadow:0 0 6px color-mix(in srgb,var(--p-primary) 45%,transparent); }

/* Stream: amber lock button; framed video pane */
#stream-capture { color:var(--p-secondary)!important;
  border-color:color-mix(in srgb,var(--p-secondary) 40%,transparent)!important; }
#stream-video-el { border:1px solid var(--p-outline)!important; }

/* Files: framed capture preview */
#shot-img { border:1px solid var(--p-outline)!important; }

/* ════ V2.1 structural: sketch .card depth + big mode buttons ════ */
/* every framed panel gets the sketch's top-lit gradient */
.bg-zinc-900.rounded-lg, .bg-zinc-900.rounded-xl {
  background:linear-gradient(180deg, var(--p-surface), var(--p-low))!important; }
/* Modes: big terminal panels + $ prompt on the logline */
.mode { padding:30px 20px!important; font-size:20px!important; letter-spacing:3px!important;
  background:linear-gradient(180deg, var(--p-surface), var(--p-low))!important; }
#mode-output::before { content:"$ "; color:var(--p-secondary);
  text-shadow:0 0 6px color-mix(in srgb, var(--p-secondary) 45%, transparent); }

/* Stream: real sliding toggle switches */
.tgl { width:46px!important; height:22px!important; border-radius:12px!important; padding:0!important;
  border:1px solid var(--p-outline)!important; background:var(--p-low)!important;
  position:relative; cursor:pointer; flex:none; transition:.2s; }
.tgl::after { content:""; position:absolute; top:2px; left:2px; width:16px; height:16px;
  border-radius:50%; background:var(--p-on-var); transition:.2s; }
.tgl.on { border-color:var(--p-primary)!important;
  background:color-mix(in srgb, var(--p-primary) 18%, #000)!important;
  box-shadow:0 0 8px color-mix(in srgb, var(--p-primary) 40%, transparent)!important; }
.tgl.on::after { left:26px; background:var(--p-primary); box-shadow:0 0 8px var(--p-primary); }

/* Files: dashed drop-zones */
.shot-area { border:1px dashed var(--p-outline)!important; border-radius:8px;
  background:var(--p-low)!important; min-height:180px; display:flex; align-items:center; justify-content:center; }
#up-file { border:1px dashed var(--p-outline)!important; border-radius:8px;
  background:var(--p-low)!important; color:var(--p-on-var)!important; padding:14px!important; }

/* ════ V2.1: top-row boxed readouts (groups delimited by hairline frames) ════ */
#st-gpu, #st-cpu, #st-aud, #st-bat, #st-clock {
  border:1px solid color-mix(in srgb, var(--p-primary) 22%, transparent)!important;
  background:color-mix(in srgb, var(--p-primary) 4%, transparent);
  border-radius:4px; padding:1px 7px; white-space:nowrap; }

/* top-bar telemetry = swipeable strip; hide the scrollbar, fade the right edge as a "more →" hint */
#st-telemetry { scrollbar-width:none; -ms-overflow-style:none;
  -webkit-mask-image:linear-gradient(to right, #000 90%, transparent);
  mask-image:linear-gradient(to right, #000 90%, transparent); }
#st-telemetry::-webkit-scrollbar { display:none; }

/* ════ segmented terminal toggles — replace native <select> (no android popup) ════ */
.seg { display:flex; flex-wrap:wrap; gap:6px; }
.seg .segbtn {
  flex:1 1 auto; min-width:0; padding:7px 12px; cursor:pointer;
  font:inherit; letter-spacing:.03em; white-space:nowrap; text-align:center;
  color:var(--p-on-var); background:var(--p-low);
  border:1px solid var(--p-outline); border-radius:6px;
  transition:background .12s ease, color .12s ease, border-color .12s ease, box-shadow .12s ease; }
.seg .segbtn:hover { border-color:var(--p-primary); color:var(--p-on); }
.seg .segbtn.on {
  color:var(--p-on-primary)!important; background:var(--p-primary); border-color:var(--p-primary);
  text-shadow:none!important;
  box-shadow:0 0 10px var(--p-primary), inset 0 0 12px color-mix(in srgb, #000 22%, transparent); }

/* native <select> fallback skin — safety net for any select not converted to .seg */
.sel { -webkit-appearance:none; appearance:none;
  background-color:var(--p-low)!important; color:var(--p-on)!important;
  border:1px solid var(--p-outline)!important; border-radius:6px;
  padding:8px 30px 8px 12px!important; font:inherit;
  background-image:linear-gradient(45deg, transparent 50%, var(--p-primary) 50%),
                   linear-gradient(135deg, var(--p-primary) 50%, transparent 50%);
  background-position:calc(100% - 15px) 55%, calc(100% - 10px) 55%;
  background-size:5px 5px, 5px 5px; background-repeat:no-repeat; }
.sel option { background:var(--p-surface); color:var(--p-on); }
"""


def css() -> str:
    prof = _profile()
    vars_ = _auto_vars() if prof == "auto" else PROFILES.get(prof, PROFILES["green"])
    root = ":root{" + "".join(f"{k}:{v};" for k, v in vars_.items()) + "}"
    return root + _SKIN
