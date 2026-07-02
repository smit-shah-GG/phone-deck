"""Audio + media — all user-level via PipeWire (pactl) and playerctl.

State is parsed from pactl; control is fire-and-forget. No deckd involvement.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import re
import urllib.parse
import urllib.request

_PCT = re.compile(r"(\d+)%")

# The deck's own capture tools — never counted as a "call" recorder.
_DECK_CAPTURE = {"parec", "pacat"}


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


async def _pw_audio_nodes() -> tuple[list[dict], list[dict]]:
    """(sinks, sources) from native PipeWire via pw-dump. The deck reads devices here
    rather than `pactl` because the pulse-compat layer intermittently lags Bluetooth
    devices (the working reference, quickshell, also reads native PipeWire). Each entry
    is {id, name (raw node.name, for default-matching), desc (friendly label)}.
    """
    out = await _out("pw-dump")
    sinks: list[dict] = []
    sources: list[dict] = []
    try:
        objs = json.loads(out) if out else []
    except ValueError:
        return [], []
    for o in objs:
        if o.get("type") != "PipeWire:Interface:Node":
            continue
        p = (o.get("info") or {}).get("props") or {}
        mc = p.get("media.class", "")
        name = p.get("node.name", "")
        entry = {"id": o.get("id"), "name": name, "desc": p.get("node.description") or name}
        if mc == "Audio/Sink":
            sinks.append(entry)
        elif mc == "Audio/Source":
            sources.append(entry)
    return sinks, sources


async def _recording_apps() -> list[str]:
    """Binaries/names currently recording (PipeWire source-outputs), minus the deck's
    own capture. The client matches these against the configurable call-app allowlist
    to decide whether a call is live — keeping audio.py free of context coupling.
    """
    out = await _out("pactl", "list", "source-outputs")
    apps: list[str] = []
    for block in out.split("Source Output #")[1:]:
        binary = name = ""
        for line in block.splitlines():
            s = line.strip()
            if s.startswith("application.process.binary"):
                binary = s.split("=", 1)[1].strip().strip('"')
            elif s.startswith("application.name"):
                name = s.split("=", 1)[1].strip().strip('"')
        ident = binary or name
        if ident and ident not in _DECK_CAPTURE:
            apps.append(ident)
    return sorted(set(apps))


# Raw browser MPRIS buses that the KDE plasma-browser-integration extension mirrors.
# When that integration is on the bus, these raw buses are duplicates that cause the
# now-playing title to flap — and the raw chromium bus reports CanGoNext=false, so
# next/previous silently no-op. We defer to the plasma player instead (it exposes the
# page's MediaSession, so transport works), exactly as the quickshell reference does.
_BROWSER_PREFIXES = ("chromium", "chrome", "brave", "firefox", "vivaldi", "opera", "edge", "microsoft-edge")


async def _players() -> list[tuple[str, str]]:
    """(player_name, status) for every MPRIS player, in playerctl's stable order."""
    names = (await _out("playerctl", "-l")).split()
    if not names:
        return []
    statuses = (await _out("playerctl", "-a", "status")).splitlines()
    statuses += [""] * (len(names) - len(statuses))   # guard a race that trims the list
    return list(zip(names, (s.strip() for s in statuses)))


def _pick_player(players: list[tuple[str, str]]) -> str | None:
    """Choose the player to read + control, mirroring quickshell's MprisController:
    drop playerctld, and — when plasma-browser-integration is present — the raw browser
    buses it duplicates; then prefer a Playing player, then Paused, then the first.
    """
    has_plasma = any(n.startswith("plasma-browser-integration") for n, _ in players)

    def keep(n: str) -> bool:
        if n.startswith("playerctld"):
            return False                              # playerctld just copies other buses
        if has_plasma and n.startswith(_BROWSER_PREFIXES):
            return False                              # mirrored by (and richer via) plasma
        return True

    cand = [(n, s) for n, s in players if keep(n)] or players
    if not cand:
        return None
    for want in ("Playing", "Paused"):
        for n, s in cand:
            if s == want:
                return n
    return cand[0][0]


async def snapshot() -> dict:
    vol_raw = await _out("pactl", "get-sink-volume", "@DEFAULT_SINK@")
    m = _PCT.search(vol_raw)
    default_sink = (await _out("pactl", "get-default-sink")).strip()
    default_source = (await _out("pactl", "get-default-source")).strip()
    pw_sinks, pw_sources = await _pw_audio_nodes()
    # name = friendly label for display; id drives set-default (wpctl); raw node.name
    # decides which is active.
    sinks = [{"id": s["id"], "name": s["desc"], "active": s["name"] == default_sink}
             for s in pw_sinks]
    sources = [{"id": s["id"], "name": s["desc"], "active": s["name"] == default_source}
               for s in pw_sources]
    player = _pick_player(await _players())
    psel = ("-p", player) if player else ()
    np = (await _out("playerctl", *psel, "metadata", "--format", "{{status}}|{{artist}} - {{title}}")).strip()
    status, _, title = np.partition("|")
    art_url = await _art_url(player)
    return {
        "volume": int(m.group(1)) if m else None,
        "sink_muted": "yes" in (await _out("pactl", "get-sink-mute", "@DEFAULT_SINK@")),
        "mic_muted": "yes" in (await _out("pactl", "get-source-mute", "@DEFAULT_SOURCE@")),
        "sinks": sinks,
        "sources": sources,
        "recording": await _recording_apps(),
        "now_playing": {
            "status": status,
            "title": title.strip(" -"),
            "player": player or "",
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


async def set_sink(node_id) -> bool:
    # Native PipeWire set-default by node id (same source of truth as enumeration).
    # @DEFAULT_SINK@ (used by volume/mute) follows this, so pactl control still works.
    return await _fire("wpctl", "set-default", str(int(node_id)))


async def set_source(node_id) -> bool:
    return await _fire("wpctl", "set-default", str(int(node_id)))


async def media(action: str) -> bool:
    if action not in ("play-pause", "next", "previous"):
        return False
    player = _pick_player(await _players())          # target the same player the strip shows
    psel = ("-p", player) if player else ()
    return await _fire("playerctl", *psel, action)


# ---- cover art -------------------------------------------------------------
async def _art_url(player: str | None = None) -> str:
    psel = ("-p", player) if player else ()
    return (await _out("playerctl", *psel, "metadata", "mpris:artUrl")).strip()


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
