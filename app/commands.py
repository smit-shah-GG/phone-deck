"""User-defined Commands / Deploy actions.

These come from ~/.config/phone-deck/commands.json — a LOCAL, trusted file the owner
edits. Because the source is local config (never the network), a shell string is
allowed for convenience. Commands run as the web-app user, not root.

commands.json schema: a list of
  {"id": "redeploy", "label": "Redeploy site", "run": "cd /srv/app && ./deploy.sh",
   "confirm": true, "timeout": 120}
`run` may be a string (run via bash -lc) or a list (argv, no shell).
"""

from __future__ import annotations

import asyncio
import json

from . import config

CMD_FILE = config.CONFIG_DIR / "commands.json"


def load() -> list[dict]:
    if not CMD_FILE.exists():
        return []
    try:
        data = json.loads(CMD_FILE.read_text())
        return data if isinstance(data, list) else []
    except (json.JSONDecodeError, OSError):
        return []


def listing() -> list[dict]:
    """Safe metadata for the UI — never exposes the command text."""
    return [
        {"id": c["id"], "label": c.get("label", c["id"]), "confirm": bool(c.get("confirm"))}
        for c in load()
        if "id" in c
    ]


async def run(cmd_id: str) -> dict:
    cmd = next((c for c in load() if c.get("id") == cmd_id), None)
    if not cmd:
        return {"ok": False, "error": "unknown command"}
    spec = cmd.get("run")
    if isinstance(spec, str):
        argv = ["bash", "-lc", spec]
    elif isinstance(spec, list) and spec:
        argv = [str(x) for x in spec]
    else:
        return {"ok": False, "error": "command has no 'run'"}
    try:
        p = await asyncio.create_subprocess_exec(
            *argv, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
        )
        out, _ = await asyncio.wait_for(p.communicate(), timeout=cmd.get("timeout", 60))
        return {"ok": p.returncode == 0, "code": p.returncode, "output": out.decode()[-3000:]}
    except asyncio.TimeoutError:
        return {"ok": False, "error": "timeout"}
    except OSError as exc:
        return {"ok": False, "error": str(exc)}
