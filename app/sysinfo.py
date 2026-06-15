"""System / Network panel data: Tailscale status, top processes, and user-level
session actions (lock, process kill). Privileged actions (reboot/poweroff/NM) go
through deckd, not here.
"""

from __future__ import annotations

import asyncio
import json
import os
import signal

import psutil


async def _out(*args: str) -> str:
    try:
        p = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
        )
        out, _ = await p.communicate()
        return out.decode() if p.returncode == 0 else ""
    except OSError:
        return ""


async def tailscale() -> dict:
    raw = await _out("tailscale", "status", "--json")
    if not raw:
        return {"up": False, "peers": []}
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return {"up": False, "peers": []}
    peers = data.get("Peer") or {}
    online = sum(1 for p in peers.values() if p.get("Online"))
    return {
        "up": data.get("BackendState") == "Running",
        "self": (data.get("Self") or {}).get("HostName", "?"),
        "online": online,
        "total": len(peers),
    }


def top_procs(n: int = 8) -> list[dict]:
    """Top processes by RSS memory (stable without cpu_percent priming)."""
    procs = []
    for p in psutil.process_iter(["pid", "name", "memory_percent", "username"]):
        try:
            procs.append(
                {
                    "pid": p.info["pid"],
                    "name": (p.info["name"] or "?")[:22],
                    "mem": round(p.info["memory_percent"] or 0, 1),
                    "mine": p.info["username"] == psutil.Process().username(),
                }
            )
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            continue
    procs.sort(key=lambda x: x["mem"], reverse=True)
    return procs[:n]


def kill(pid: int) -> dict:
    try:
        os.kill(int(pid), signal.SIGTERM)
        return {"ok": True}
    except (ProcessLookupError, PermissionError, ValueError) as exc:
        return {"ok": False, "error": str(exc)}


async def lock() -> bool:
    try:
        await asyncio.create_subprocess_exec(
            "hyprlock", stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL
        )
        return True
    except OSError:
        return False


async def snapshot() -> dict:
    return {"tailscale": await tailscale(), "procs": top_procs()}
