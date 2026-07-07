"""Fleet roster + per-host reachability for the header host-switcher.

`fleet.json` (in the config dir — NEVER committed; it holds tailnet URLs) lists the
birds: {"hosts": [{"name","label","url"}, ...]}, identical on each box. status()
annotates each with `online` from THIS host's own `tailscale status --json` (Self +
Peer HostName->Online). Every box shares the same tailnet peer view, so the dots
need no cross-origin call — which is the whole point of the navigation-switcher (F2).
"""

from __future__ import annotations

import asyncio
import json

from . import config

FLEET_FILE = config.CONFIG_DIR / "fleet.json"


def _roster() -> list[dict]:
    """[{name,label,url}] from fleet.json; [] if absent/malformed. Drops entries
    missing a name or url (the two the switcher can't render without)."""
    try:
        data = json.loads(FLEET_FILE.read_text())
    except (OSError, json.JSONDecodeError):
        return []
    hosts = data.get("hosts") if isinstance(data, dict) else None
    return [h for h in (hosts or []) if isinstance(h, dict) and h.get("name") and h.get("url")]


def _online_from(data: dict) -> dict[str, bool]:
    """{HostName: Online} from a parsed `tailscale status --json`. Self is always
    True (you're on it, serving this page), peers carry their reported Online."""
    m: dict[str, bool] = {}
    self_ = data.get("Self") or {}
    if self_.get("HostName"):
        m[self_["HostName"]] = True
    for peer in (data.get("Peer") or {}).values():
        if isinstance(peer, dict) and peer.get("HostName"):
            m[peer["HostName"]] = bool(peer.get("Online"))
    return m


async def _online_map() -> dict[str, bool]:
    try:
        p = await asyncio.create_subprocess_exec(
            "tailscale", "status", "--json",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        out, _ = await asyncio.wait_for(p.communicate(), timeout=5)
    except (OSError, asyncio.TimeoutError):
        return {}
    if p.returncode != 0 or not out:
        return {}
    try:
        return _online_from(json.loads(out))
    except json.JSONDecodeError:
        return {}


async def status() -> list[dict]:
    """The roster with live reachability. `online`: True (up), False (known-offline),
    or None (unknown — tailscale down / host not in this box's tailnet view)."""
    roster = _roster()
    if not roster:
        return []
    online = await _online_map()
    return [
        {"name": h["name"], "label": h.get("label", h["name"]), "url": h["url"],
         "online": online.get(h["name"])}
        for h in roster
    ]
