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

import psutil

from . import config

MODES_FILE = config.CONFIG_DIR / "modes.json"

# Shells whose mere presence under a terminal doesn't count as "live work".
_SHELLS = {"fish", "bash", "zsh", "sh", "dash", "-fish", "-bash", "-zsh", "-sh"}


def _has_live_child(pid: int) -> bool:
    """Macro-guard: True (keep the window) if a terminal has any non-shell descendant
    — ssh, vim, a build, python/JAX, zellij (so cockpit's session is protected). This
    is a fail-safe brake: it only *stops* a close, never starts one, and returns True
    on any uncertainty (process gone / not inspectable) so we never nuke live work.
    """
    if not pid:
        return True
    try:
        for child in psutil.Process(pid).children(recursive=True):
            try:
                if child.name() not in _SHELLS:
                    return True
            except (psutil.NoSuchProcess, psutil.AccessDenied):
                return True  # can't tell -> fail safe
        return False
    except (psutil.Error, OSError):
        return True


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


# Hyprland 0.56 moved `hyprctl dispatch` to a Lua API: the arguments are
# evaluated as `hl.dispatch(<expr>)`, so the classic `dispatch workspace 8`
# string form errors out there — you pass an `hl.dsp.*` expression instead.
# Older Hyprland only speaks the classic form. We detect which the running
# compositor accepts on the first dispatch and cache it, so the deck works
# across versions and self-heals across an upgrade. Queries (`hyprctl -j …`)
# were unaffected by the change — only dispatch went Lua.
_USE_LUA: bool | None = None


def _lua_str(s: str) -> str:
    """Render a Python string as a double-quoted Lua string literal."""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


async def _run_dispatch(args: list[str]) -> tuple[int, bytes]:
    try:
        p = await asyncio.create_subprocess_exec(
            "hyprctl", "dispatch", *args,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT,
        )
        out, _ = await p.communicate()
        return p.returncode, out or b""
    except OSError:
        return 1, b"oserror"


def _ok(rc: int, out: bytes) -> bool:
    return rc == 0 and b"error" not in out.lower()


async def _dispatch(classic: list[str], lua: str) -> bool:
    """Issue one dispatch using whichever protocol the compositor speaks.

    `classic` is the old ["dispatcher", "args"] form; `lua` is the 0.56+
    `hl.dsp.*` expression. On a Lua compositor the classic attempt fails on a
    parse error *before* it acts (so no double-exec), then we retry with Lua
    and remember the choice for the rest of the run.
    """
    global _USE_LUA
    if _USE_LUA is None:
        if _ok(*await _run_dispatch(classic)):
            _USE_LUA = False
            return True
        _USE_LUA = True  # classic rejected -> this is a Lua-dispatch compositor
    if _USE_LUA:
        return _ok(*await _run_dispatch([lua]))
    return _ok(*await _run_dispatch(classic))


async def _exec(rule_cmd: str) -> bool:
    # exec_cmd parses [workspace N silent] window rules; exec_raw does not.
    return await _dispatch(["exec", rule_cmd], f"hl.dsp.exec_cmd({_lua_str(rule_cmd)})")


async def _focus_ws(ws: int) -> bool:
    return await _dispatch(["workspace", str(ws)], f"hl.dsp.focus({{workspace={ws}}})")


async def _move_to_ws(addr: str, ws: int) -> bool:
    # follow=false keeps it silent (0.56 window.move follows by default) — matching
    # the classic movetoworkspacesilent, so a mode run never yanks the active view.
    return await _dispatch(
        ["movetoworkspacesilent", f"{ws},address:{addr}"],
        f'hl.dsp.window.move({{window="address:{addr}", workspace={ws}, follow=false}})',
    )


async def _close(addr: str) -> bool:
    return await _dispatch(
        ["closewindow", f"address:{addr}"],
        f'hl.dsp.window.close({{window="address:{addr}"}})',
    )


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
                    await _move_to_ws(c["address"], ws)
                    done.append(f"placed {name} -> ws{ws}")
                else:
                    done.append(f"skip {name} (open)")
                continue
            before = {c["address"] for c in existing}
            await _exec(f"[workspace {ws} silent] {step['cmd']}")
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
                            await _move_to_ws(new[0]["address"], ws)
                            note = " (moved)"
                        break
            done.append(f"launch ws{ws}: {name}{note}")
        elif t == "close":
            cls = step.get("match")
            only = set(step.get("workspaces", []))
            n = kept = 0
            for c in await _clients():
                if c.get("class") == cls and (not only or c.get("workspace", {}).get("id") in only):
                    if _has_live_child(c.get("pid", 0)):   # macro-guard: don't nuke live work
                        kept += 1
                        continue
                    await _close(c["address"])
                    n += 1
            msg = f"close {cls} x{n}" + (f" on {sorted(only)}" if only else "")
            if kept:
                msg += f" · kept {kept} (live)"
            done.append(msg)
        elif t == "move":
            cls = step.get("match")
            ws = int(step["workspace"])
            for c in await _clients():
                if c.get("class") == cls:
                    await _move_to_ws(c["address"], ws)
            done.append(f"move {cls} -> ws{ws}")
        elif t == "focus":
            await _focus_ws(int(step["workspace"]))
            done.append(f"focus ws{step['workspace']}")
        await asyncio.sleep(0.15)  # let Hyprland settle between steps
    return {"ok": True, "steps": done}
