"""Audio + media — all user-level via PipeWire (pactl) and playerctl.

State is parsed from pactl; control is fire-and-forget. No deckd involvement.
"""

from __future__ import annotations

import asyncio
import hashlib
import re
import urllib.parse
import urllib.request

_PCT = re.compile(r"(\d+)%")


async def _out(*args: str) -> str:
    try:
        p = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
        )
        out, _ = await p.communicate()
        return out.decode() if p.returncode == 0 else ""
    except OSError:
        return ""


async def _fire(*args: str) -> bool:
    try:
        p = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL
        )
        await p.communicate()
        return p.returncode == 0
    except OSError:
        return False


async def snapshot() -> dict:
    vol_raw = await _out("pactl", "get-sink-volume", "@DEFAULT_SINK@")
    m = _PCT.search(vol_raw)
    default_sink = (await _out("pactl", "get-default-sink")).strip()
    sinks = []
    for line in (await _out("pactl", "list", "short", "sinks")).splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            sinks.append({"name": parts[1], "active": parts[1] == default_sink})
    default_source = (await _out("pactl", "get-default-source")).strip()
    sources = []
    for line in (await _out("pactl", "list", "short", "sources")).splitlines():
        parts = line.split("\t")
        if len(parts) >= 2:
            sources.append({"name": parts[1], "active": parts[1] == default_source,
                            "monitor": parts[1].endswith(".monitor")})
    np = (await _out("playerctl", "metadata", "--format", "{{status}}|{{artist}} - {{title}}")).strip()
    status, _, title = np.partition("|")
    art_url = await _art_url()
    return {
        "volume": int(m.group(1)) if m else None,
        "sink_muted": "yes" in (await _out("pactl", "get-sink-mute", "@DEFAULT_SINK@")),
        "mic_muted": "yes" in (await _out("pactl", "get-source-mute", "@DEFAULT_SOURCE@")),
        "sinks": sinks,
        "sources": sources,
        "now_playing": {
            "status": status,
            "title": title.strip(" -"),
            # cache-bust key; the art itself is served by the /media/art proxy
            "art_key": hashlib.md5(art_url.encode()).hexdigest()[:12] if art_url else "",
        },
    }


async def set_volume(pct: int) -> bool:
    pct = max(0, min(150, int(pct)))
    return await _fire("pactl", "set-sink-volume", "@DEFAULT_SINK@", f"{pct}%")


async def toggle_mute(target: str) -> bool:
    if target == "mic":
        return await _fire("pactl", "set-source-mute", "@DEFAULT_SOURCE@", "toggle")
    return await _fire("pactl", "set-sink-mute", "@DEFAULT_SINK@", "toggle")


async def set_sink(name: str) -> bool:
    return await _fire("pactl", "set-default-sink", name)


async def set_source(name: str) -> bool:
    return await _fire("pactl", "set-default-source", name)


async def media(action: str) -> bool:
    if action not in ("play-pause", "next", "previous"):
        return False
    return await _fire("playerctl", action)


# ---- cover art -------------------------------------------------------------
async def _art_url() -> str:
    return (await _out("playerctl", "metadata", "mpris:artUrl")).strip()


def _image_type(data: bytes) -> str | None:
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if data[:4] == b"GIF8":
        return "image/gif"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None  # not an image -> refuse to serve


def _read_local(url: str) -> bytes:
    path = urllib.parse.unquote(urllib.parse.urlparse(url).path)
    with open(path, "rb") as f:
        return f.read(5_000_000)


def _fetch_http(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "phone-deck"})
    with urllib.request.urlopen(req, timeout=5) as r:  # noqa: S310 — url from local MPRIS, not client
        return r.read(5_000_000)


async def art() -> tuple[str, bytes] | None:
    """Bytes + content-type for the current track's cover, or None.

    The URL is taken from playerctl (the live player), never from the client, so
    there's no path-traversal surface. Non-image payloads are refused.
    """
    url = await _art_url()
    if not url:
        return None
    loop = asyncio.get_running_loop()
    try:
        if url.startswith("file://"):
            data = await loop.run_in_executor(None, _read_local, url)
        elif url.startswith(("http://", "https://")):
            data = await loop.run_in_executor(None, _fetch_http, url)
        else:
            return None
    except (OSError, ValueError):
        return None
    ct = _image_type(data)
    return (ct, data) if ct else None
