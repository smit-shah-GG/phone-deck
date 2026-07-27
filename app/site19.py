"""Site-19 trigger — enter/exit/toggle the SCP-themed nested environment.

Runs the local controller (~/personal/site19/bin/site19) as the web-app user, the
same way commands.py runs local actions. The JWT session cookie is the actual auth
gate; on top of it, the crossing requires presenting the Level-5 card, whose UID is
matched server-side against ~/.config/phone-deck/site19.json. The expected UID lives
only on the box — it is never shipped to the browser or the public repo. The scanned
UID that the client sends is harmless (it's whatever card was tapped).
"""

from __future__ import annotations

import asyncio
import json
from pathlib import Path

from . import config

CONTROLLER = Path.home() / "personal" / "site19" / "bin" / "site19"
SITE19_FILE = config.CONFIG_DIR / "site19.json"
_ACTIONS = {"enter", "exit", "toggle"}


def _clearance_uid() -> str | None:
    """The one card UID that opens the door, normalized lowercase, or None if
    unset. Fail-closed: a missing/malformed config means no card is accepted."""
    try:
        uid = json.loads(SITE19_FILE.read_text()).get("card_uid")
    except (OSError, ValueError):
        return None
    return uid.strip().lower() if isinstance(uid, str) and uid.strip() else None


async def run(action: str, uid: str | None = None) -> dict:
    if action not in _ACTIONS:
        return {"ok": False, "error": f"unknown action {action!r}"}
    expected = _clearance_uid()
    if not expected:
        return {"ok": False, "error": "no clearance configured — see ~/.config/phone-deck/site19.json"}
    if (uid or "").strip().lower() != expected:
        return {"ok": False, "denied": True, "error": "clearance denied — unrecognized card"}
    if not CONTROLLER.exists():
        return {"ok": False, "error": "site19 controller not found"}
    try:
        p = await asyncio.create_subprocess_exec(
            str(CONTROLLER), action,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
        )
        out, _ = await asyncio.wait_for(p.communicate(), timeout=30)
        return {"ok": p.returncode == 0, "code": p.returncode, "output": out.decode()[-2000:]}
    except asyncio.TimeoutError:
        return {"ok": False, "error": "timeout"}
    except OSError as exc:
        return {"ok": False, "error": str(exc)}
