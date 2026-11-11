"""Headless mode from the deck (tasks #131): the user's own headless.sh, unchanged.

headless.sh disables every physical output and keeps the HEADLESS-KEEPALIVE virtual one, so
the panels stay black whatever the input does, while the box stays live (SSH, Tailscale, the
stream). It is the one source of truth: on/off are delegated to it, and its state file
($XDG_RUNTIME_DIR/headless.state, holding the epoch it went on) is the status.
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path

SCRIPT = Path(os.environ.get("DECK_HEADLESS_SCRIPT",
                             Path.home() / ".config/hypr/custom/scripts/headless.sh"))


def _state_file() -> Path:
    run = os.environ.get("XDG_RUNTIME_DIR") or f"/run/user/{os.getuid()}"
    return Path(run) / "headless.state"


def status() -> dict:
    """{"on": bool, "since": epoch | None, "available": bool}: read straight from the file."""
    f = _state_file()
    since = None
    if f.is_file():
        try:
            since = int(f.read_text().strip())
        except (OSError, ValueError):
            since = None
    return {"on": f.is_file(), "since": since, "available": SCRIPT.is_file()}


async def set_on(on: bool) -> dict:
    """Run `headless.sh on|off`. 'off' reloads Hyprland, so allow it a few seconds."""
    if not SCRIPT.is_file():
        return {"ok": False, "error": f"no headless script at {SCRIPT}"}
    try:
        proc = await asyncio.create_subprocess_exec(
            str(SCRIPT), "on" if on else "off",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
        out, _ = await asyncio.wait_for(proc.communicate(), timeout=20)
    except asyncio.TimeoutError:
        return {"ok": False, "error": "headless.sh timed out"}
    except OSError as exc:
        return {"ok": False, "error": str(exc)}
    return {"ok": proc.returncode == 0, "output": out.decode(errors="replace").strip(),
            "headless": status()}
