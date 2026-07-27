"""Hyprland integration: snapshot queries via hyprctl, live events via .socket2.sock.

Snapshots (monitors/workspaces/clients) come from `hyprctl -j`. Live updates come from
the event socket so the deck reflects keyboard-driven changes on the rig instantly.
"""

from __future__ import annotations

import asyncio
import json
import os
import socket

RUNTIME = os.environ.get("XDG_RUNTIME_DIR", f"/run/user/{os.getuid()}")
_ENV_HIS = os.environ.get("HYPRLAND_INSTANCE_SIGNATURE", "")


def _his() -> str:
    """Resolve the *current* Hyprland instance signature. Prefer the signature the service
    started with if its event socket is still LIVE; otherwise probe the socket dirs
    newest-first and return the first whose `.socket2.sock` actually accepts a connection.

    A dead Hyprland leaves its socket *files* behind, so "newest dir that has a
    .socket2.sock" can point at a corpse — which is exactly how the deck ends up talking to
    a dead instance and returning empty state (blank Workspaces). Probing for a live
    listener is what makes the auto-heal actually heal, even when the alive instance isn't
    the newest by mtime.
    """
    base = f"{RUNTIME}/hypr"

    def _alive(sig: str) -> bool:
        try:
            s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            s.settimeout(0.2)
            s.connect(os.path.join(base, sig, ".socket2.sock"))
            s.close()
            return True
        except OSError:
            return False

    if _ENV_HIS and _alive(_ENV_HIS):
        return _ENV_HIS
    try:
        dirs = sorted(
            ((os.path.getmtime(os.path.join(base, name)), name) for name in os.listdir(base)),
            reverse=True,
        )
    except OSError:
        return _ENV_HIS
    for _, name in dirs:
        if _alive(name):
            return name
    return _ENV_HIS


def _env() -> dict:
    return {**os.environ, "HYPRLAND_INSTANCE_SIGNATURE": _his()}


async def _communicate(proc, timeout: float = 8):
    """Bounded communicate — a wedged hyprctl (compositor mid-death) must not
    freeze whichever loop called us. Same await-hygiene class as 68803c2."""
    try:
        return await asyncio.wait_for(proc.communicate(), timeout=timeout)
    except asyncio.TimeoutError:
        try:
            proc.kill()
        except ProcessLookupError:
            pass
        return b"", b""


async def _hyprctl_json(*args: str):
    proc = await asyncio.create_subprocess_exec(
        "hyprctl", "-j", *args, env=_env(),
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
    )
    out, _ = await _communicate(proc)
    if proc.returncode != 0 or not out:
        return None
    return json.loads(out)


async def _hyprctl_dispatch(*args: str):
    proc = await asyncio.create_subprocess_exec(
        "hyprctl", "dispatch", *args, env=_env(),
        stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
    )
    await _communicate(proc)
    return proc.returncode == 0


async def _hyprctl_keyword(*args: str) -> bool:
    proc = await asyncio.create_subprocess_exec(
        "hyprctl", "keyword", *args, env=_env(),
        stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
    )
    await _communicate(proc)
    return proc.returncode == 0




# Invisible-output armor (survivor of the shelved Extension Monitor, 2026-07-04):
# any headless/virtual output that ever appears — a stray experiment, another tool —
# must never join the physical 5-block workspace mapping, and workspaces at ws21+
# must never swallow focus (an invisible workspace is a focus black hole; it cost
# two windows before we learned). See docs_internal/shelf/extension-monitor-SHELVED.md.
PAD_PREFIX = "pad-"
PAD_WS_BASE = 21


def is_pad(name: str) -> bool:
    return name.startswith(PAD_PREFIX) or name.startswith("HEADLESS")


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
    monitors = [m for m in monitors if not is_pad(m["name"])]   # pads own ws21+
    # A single-monitor host (the laptops) has nothing to partition — every workspace
    # lands on the only output anyway. Binding ws1-5 to it would impose a phantom
    # 5-workspace ceiling and churn moveworkspacetomonitor relocations; leave Hyprland's
    # native single-monitor behavior alone. (Dock a 2nd monitor -> partition resumes.)
    if len(monitors) <= 1:
        return []
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
                "scale": m.get("scale", 1.0),
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
    if int(ws_id) >= PAD_WS_BASE:
        # Pad workspaces live on an invisible output — plain `workspace` would
        # warp focus into the void. Reel the workspace onto the monitor the user
        # is actually looking at instead (verified live 2026-07-03).
        return await _hyprctl_dispatch("focusworkspaceoncurrentmonitor", str(int(ws_id)))
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
                    try:
                        await on_change()
                    except Exception as e:  # noqa: BLE001 — the watcher must never die:
                        # a one-off downstream failure (broadcast hiccup, transient
                        # hyprctl error) would otherwise silently kill live updates
                        # for the rest of the process lifetime.
                        print(f"watch_events: on_change failed: {e!r}")
        except (ConnectionResetError, asyncio.IncompleteReadError):
            pass
        await asyncio.sleep(1)
