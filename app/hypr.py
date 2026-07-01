"""Hyprland integration: snapshot queries via hyprctl, live events via .socket2.sock.

Snapshots (monitors/workspaces/clients) come from `hyprctl -j`. Live updates come from
the event socket so the deck reflects keyboard-driven changes on the rig instantly.
"""

from __future__ import annotations

import asyncio
import json
import os

RUNTIME = os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
_ENV_HIS = os.environ.get("HYPRLAND_INSTANCE_SIGNATURE", "")


def _his() -> str:
    """Resolve the *current* Hyprland instance signature — the newest socket dir under
    $XDG_RUNTIME_DIR/hypr. This auto-heals after a Hyprland restart (the env HIS the
    service started with goes stale), so the deck keeps working without a restart.
    """
    base = f"{RUNTIME}/hypr"
    try:
        cands = [
            (os.path.getmtime(os.path.join(base, name)), name)
            for name in os.listdir(base)
            if os.path.exists(os.path.join(base, name, ".socket2.sock"))
        ]
        if cands:
            return max(cands)[1]
    except OSError:
        pass
    return _ENV_HIS


def _env() -> dict:
    return {**os.environ, "HYPRLAND_INSTANCE_SIGNATURE": _his()}


async def _hyprctl_json(*args: str):
    proc = await asyncio.create_subprocess_exec(
        "hyprctl", "-j", *args, env=_env(),
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
    )
    out, _ = await proc.communicate()
    if proc.returncode != 0 or not out:
        return None
    return json.loads(out)


async def _hyprctl_dispatch(*args: str):
    proc = await asyncio.create_subprocess_exec(
        "hyprctl", "dispatch", *args, env=_env(),
        stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
    )
    await proc.communicate()
    return proc.returncode == 0


async def _hyprctl_keyword(*args: str) -> bool:
    proc = await asyncio.create_subprocess_exec(
        "hyprctl", "keyword", *args, env=_env(),
        stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
    )
    await proc.communicate()
    return proc.returncode == 0


async def apply_monitor_mapping(monitors: list[dict]) -> list[str]:
    """Bind 5-workspace blocks to monitors by live left→right (x) order: the Nth monitor
    (1-indexed) gets workspaces (5N−4 … 5N), the first of each block as the monitor
    default. Resilient to connector-name *and* position changes — this is what replaces
    the static `workspace = N, monitor:…` binds. `monitors` must be pre-sorted by x
    (snapshot() already does this).

    A `workspace` keyword only governs where a workspace opens in the *future* — it does
    not move one that already exists (e.g. created during the cold-boot window before this
    service came up). So we also relocate any existing workspace that's on the wrong
    monitor, which makes a cold boot self-heal."""
    existing = await _hyprctl_json("workspaces") or []
    ws_mon = {w["id"]: w.get("monitor", "") for w in existing}
    applied: list[str] = []
    for i, m in enumerate(monitors):
        for j in range(1, 6):
            ws = i * 5 + j
            val = f"{ws}, monitor:{m['name']}" + (", default:true" if j == 1 else "")
            await _hyprctl_keyword("workspace", val)
            if ws in ws_mon and ws_mon[ws] != m["name"]:   # relocate the already-misplaced
                await _hyprctl_dispatch("moveworkspacetomonitor", str(ws), m["name"])
        applied.append(f"{m['name']}: ws{i * 5 + 1}-{i * 5 + 5}")
    return applied


async def snapshot() -> dict:
    """Monitors (physical L->R), their active workspaces, and the focused window."""
    monitors = await _hyprctl_json("monitors") or []
    active = await _hyprctl_json("activewindow") or {}
    clients = await _hyprctl_json("clients") or []
    mons = sorted(
        (
            {
                "name": m["name"],
                "model": m.get("model", ""),
                "x": m.get("x", 0),
                "refresh": round(m.get("refreshRate", 0)),
                "active_ws": m.get("activeWorkspace", {}).get("id"),
                "focused": m.get("focused", False),
                "dpms": m.get("dpmsStatus", True),
                "y": m.get("y", 0),
                "w": m.get("width", 0),
                "h": m.get("height", 0),
            }
            for m in monitors
        ),
        key=lambda m: m["x"],
    )
    id_to_name = {m["id"]: m["name"] for m in monitors}
    focused_addr = active.get("address")
    windows = [
        {
            "address": c.get("address"),
            "title": (c.get("title") or "")[:60],
            "cls": c.get("class", ""),
            "ws": c.get("workspace", {}).get("id"),
            "monitor": id_to_name.get(c.get("monitor"), ""),
            "focused": c.get("address") == focused_addr,
        }
        for c in clients
        if c.get("mapped", True)
    ]
    windows.sort(key=lambda w: (w["ws"] if isinstance(w["ws"], int) else 0))
    return {
        "monitors": mons,
        "windows": windows,
        "active_window": {"title": active.get("title", ""), "class": active.get("class", "")},
    }


async def focus_workspace(ws_id: int) -> bool:
    return await _hyprctl_dispatch("workspace", str(int(ws_id)))


async def move_window_to_workspace(ws_id: int) -> bool:
    return await _hyprctl_dispatch("movetoworkspace", str(int(ws_id)))


async def set_dpms(monitor: str, on: bool) -> bool:
    return await _hyprctl_dispatch("dpms", "on" if on else "off", monitor)


async def focus_window(address: str) -> bool:
    if not address:
        return False
    return await _hyprctl_dispatch("focuswindow", f"address:{address}")


async def move_cursor(x: int, y: int) -> bool:
    return await _hyprctl_dispatch("movecursor", str(int(x)), str(int(y)))


async def send_shortcut(cls: str, key: str, mods: str = "") -> bool:
    """Deliver a key/chord to a window by class WITHOUT stealing focus.

    This is the mechanism behind the in-app controls — control YouTube on monitor 3
    while staying focused on monitor 1. `mods` is a Hyprland modifier string (e.g.
    "CTRL SHIFT"); empty for a bare key. The leading comma is required for no-mods.
    """
    if not cls or not key:
        return False
    return await _hyprctl_dispatch("sendshortcut", f"{mods},{key},class:{cls}")


async def watch_events(on_change):
    """Connect to .socket2.sock; call on_change() (async) on workspace/focus/monitor events.

    Reconnects on failure so a Hyprland restart doesn't kill the stream.
    """
    interesting = ("workspace", "focusedmon", "activewindow", "openwindow", "closewindow",
                   "movewindow", "monitoradded", "monitorremoved")
    while True:
        socket = f"{RUNTIME}/hypr/{_his()}/.socket2.sock"   # re-resolve each reconnect (auto-heal)
        try:
            reader, _ = await asyncio.open_unix_connection(socket)
        except (FileNotFoundError, ConnectionRefusedError):
            await asyncio.sleep(2)
            continue
        try:
            while True:
                line = await reader.readline()
                if not line:
                    break
                event = line.decode(errors="ignore").split(">>", 1)[0]
                if event in interesting:
                    await on_change()
        except (ConnectionResetError, asyncio.IncompleteReadError):
            pass
        await asyncio.sleep(1)
