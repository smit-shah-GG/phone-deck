"""Send-to-Rig: the deck as an Android share target (main phone -> rig).

Governing model (v2.5-design §3): **share = paste-ready on the rig** (+ a durable
copy in ~/phone-deck-drop); a payload that is purely one bare http(s) link opens.
The rig's clipboard is the inbox — no launching, no junk drawer.

Auth mechanics: the OS share-sheet POST to /share arrives cookie-less (JWT cookie
is SameSite=Strict), so the service worker intercepts it and re-issues the form as
a same-origin fetch to /share/api, where the cookie attaches. Strict stays Strict.

`files` entries are duck-typed UploadFile-likes (.filename, .content_type, .file)
so this module stays framework-free.
"""

from __future__ import annotations

import asyncio
import re

from . import grab

# Only ever opened, never anything else: a shared "URL" can be file:// or an
# arbitrary intent:// scheme — those are treated as text (clipboard), not opened.
_BARE_URL = re.compile(r"^https?://\S+$")

_WLCOPY_TIMEOUT = 10   # image payloads from a phone camera can be several MB
_OPEN_TIMEOUT = 8
_PNG_TIMEOUT = 20      # 12MP camera jpeg -> png is ~1-2s; generous headroom
_PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def classify(url: str, text: str) -> tuple[str, str] | None:
    """("open", href) iff the payload is purely one bare http(s) link — the url
    param, or a text that IS just a link (Android apps habitually put the URL in
    text). Anything else -> ("copy", payload): words go to the clipboard verbatim,
    no clever extraction, no surprise browser windows. None for an empty share.
    """
    url = (url or "").strip()
    text = (text or "").strip()
    for cand in (url, text):
        if cand and _BARE_URL.match(cand):
            return ("open", cand)
    payload = text or url
    return ("copy", payload) if payload else None


async def _copy(data: bytes, mime: str | None = None) -> bool:
    """wl-copy the payload (text or image). wl-copy forks a background server for
    the clipboard and the parent exits, so this returns promptly; bounded anyway
    (unbounded awaits in this codebase have a body count — see 68803c2)."""
    argv = ["wl-copy"] + (["-t", mime] if mime else [])
    try:
        p = await asyncio.create_subprocess_exec(
            *argv, stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        await asyncio.wait_for(p.communicate(data), timeout=_WLCOPY_TIMEOUT)
        return p.returncode == 0
    except asyncio.TimeoutError:
        try:
            p.kill()
        except ProcessLookupError:
            pass
        return False
    except OSError:
        return False


async def _to_png(data: bytes) -> bytes | None:
    """Transcode an image to PNG via ffmpeg. Chromium-family paste targets
    (browsers, chat webapps — everywhere pastes actually happen) only accept an
    image/png clipboard offer; a jpeg-only offer is invisible to them (found the
    hard way: 'cannot paste anywhere', 2026-07-03)."""
    try:
        p = await asyncio.create_subprocess_exec(
            "ffmpeg", "-loglevel", "error", "-i", "-",
            "-frames:v", "1", "-f", "image2pipe", "-c:v", "png", "-",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        out, _ = await asyncio.wait_for(p.communicate(data), timeout=_PNG_TIMEOUT)
        return out if p.returncode == 0 and out[:8] == _PNG_MAGIC else None
    except asyncio.TimeoutError:
        try:
            p.kill()
        except ProcessLookupError:
            pass
        return None
    except OSError:
        return None


async def _open(href: str) -> bool:
    """xdg-open, bounded. On timeout the handler is assumed to have taken over
    (some xdg-open paths linger); we deliberately do NOT kill it."""
    try:
        p = await asyncio.create_subprocess_exec(
            "xdg-open", href,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        return (await asyncio.wait_for(p.wait(), timeout=_OPEN_TIMEOUT)) == 0
    except asyncio.TimeoutError:
        return True
    except OSError:
        return False


async def handle(url: str, text: str, files: list) -> dict:
    """Perform the share. Returns {ok, action, detail} for the confirmation page."""
    if files:
        names = []
        for f in files:
            res = grab.save_upload(f.filename, f.file)
            if res.get("ok"):
                names.append(res["name"])
        if not names:
            return {"ok": False, "action": "save", "detail": "save failed"}
        detail = f"saved {names[0]}" if len(names) == 1 else f"saved {len(names)} files"
        f0 = files[0]
        if len(files) == 1 and (f0.content_type or "").startswith("image/"):
            f0.file.seek(0)                      # save_upload consumed the stream
            data, mime = f0.file.read(), (f0.content_type or "image/png")
            if mime != "image/png":              # paste targets want PNG (see _to_png)
                png = await _to_png(data)
                if png:
                    data, mime = png, "image/png"
            if await _copy(data, mime):
                detail += " → clipboard"
            return {"ok": True, "action": "image", "detail": detail}
        return {"ok": True, "action": "save", "detail": detail}

    plan = classify(url, text)
    if plan is None:
        return {"ok": False, "action": "none", "detail": "empty share"}
    kind, payload = plan
    if kind == "open":
        ok = await _open(payload)
        return {"ok": ok, "action": "open",
                "detail": "opened on rig" if ok else "open failed"}
    ok = await _copy(payload.encode())
    return {"ok": ok, "action": "copy",
            "detail": "→ rig clipboard" if ok else "wl-copy failed"}
