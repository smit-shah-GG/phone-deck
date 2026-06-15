"""Modes / scenes — composite layouts applied via hyprctl (all user-level).

Defined in ~/.config/phone-deck/modes.json. Each mode is a list of steps run in
order. Workspaces are bound to monitors in the Hyprland config, so launching with
`[workspace N silent]` lands deterministically on the right monitor.

Step types:
  launch  {cmd, workspace, match?, once?}  exec on a workspace; once+match skips if that
                                           window class already exists (no duplicates).
  close   {match, workspaces?}             close windows of a class, optionally only on
                                           the listed workspaces (so we don't kill kitties
                                           on other workspaces).
  move    {match, workspace}               move matching windows to a workspace.
"""

from __future__ import annotations

import asyncio
import json

from . import config

MODES_FILE = config.CONFIG_DIR / "modes.json"


def _load() -> dict:
    if not MODES_FILE.exists():
        return {}
    try:
        data = json.loads(MODES_FILE.read_text())
        return data if isinstance(data, dict) else {}
    except (json.JSONDecodeError, OSError):
        return {}


def listing() -> list[dict]:
    return [{"id": k, "label": v.get("label", k)} for k, v in _load().items()]


async def _dispatch(*args: str) -> bool:
    try:
        p = await asyncio.create_subprocess_exec(
            "hyprctl", "dispatch", *args,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
        )
        await p.communicate()
        return p.returncode == 0
    except OSError:
        return False


async def _clients() -> list[dict]:
    try:
        p = await asyncio.create_subprocess_exec(
            "hyprctl", "-j", "clients",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
        )
        out, _ = await p.communicate()
        return json.loads(out) if out else []
    except (OSError, json.JSONDecodeError):
        return []


async def run(mode_id: str) -> dict:
    mode = _load().get(mode_id)
    if not mode:
        return {"ok": False, "error": "unknown mode"}
    done: list[str] = []
    for step in mode.get("steps", []):
        t = step.get("type")
        if t == "launch":
            match = step.get("match")
            ws = int(step["workspace"])
            name = step["cmd"].split()[0].rsplit("/", 1)[-1]
            existing = [c for c in await _clients() if match and c.get("class") == match]
            if step.get("once") and existing:
                # already open — just make sure it's on the right workspace and move on
                c = existing[0]
                if c.get("workspace", {}).get("id") != ws:
                    await _dispatch("movetoworkspacesilent", f"{ws},address:{c['address']}")
                    done.append(f"placed {name} -> ws{ws}")
                else:
                    done.append(f"skip {name} (open)")
                continue
            before = {c["address"] for c in existing}
            await _dispatch("exec", f"[workspace {ws} silent] {step['cmd']}")
            note = ""
            if match:
                # Brave PWAs / brave-origin-nightly spawn their window from an existing process,
                # so the [workspace N silent] exec rule misses it. Wait for the new window and
                # force it onto the target workspace.
                for _ in range(40):                       # up to ~8s (Brave restore is slow)
                    await asyncio.sleep(0.2)
                    new = [c for c in await _clients()
                           if c.get("class") == match and c["address"] not in before]
                    if new:
                        if new[0].get("workspace", {}).get("id") != ws:
                            await _dispatch("movetoworkspacesilent", f"{ws},address:{new[0]['address']}")
                            note = " (moved)"
                        break
            done.append(f"launch ws{ws}: {name}{note}")
        elif t == "close":
            cls = step.get("match")
            only = set(step.get("workspaces", []))
            n = 0
            for c in await _clients():
                if c.get("class") == cls and (not only or c.get("workspace", {}).get("id") in only):
                    await _dispatch("closewindow", f"address:{c['address']}")
                    n += 1
            done.append(f"close {cls} x{n}" + (f" on {sorted(only)}" if only else ""))
        elif t == "move":
            cls = step.get("match")
            ws = int(step["workspace"])
            for c in await _clients():
                if c.get("class") == cls:
                    await _dispatch("movetoworkspacesilent", f"{ws},address:{c['address']}")
            done.append(f"move {cls} -> ws{ws}")
        elif t == "focus":
            await _dispatch("workspace", str(int(step["workspace"])))
            done.append(f"focus ws{step['workspace']}")
        await asyncio.sleep(0.15)  # let Hyprland settle between steps
    return {"ok": True, "steps": done}
