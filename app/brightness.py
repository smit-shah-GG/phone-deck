"""Per-monitor hardware brightness via ddcutil (user-level; you're in the i2c group).

ddcutil's DRM connector matches the Hyprland monitor name exactly (e.g.
`card1-HDMI-A-1` -> `HDMI-A-1`), so we key brightness by monitor name. The
connector->bus map is parsed once from `ddcutil detect` and cached — detect is slow.
"""

from __future__ import annotations

import asyncio
import re

_VCP = re.compile(r"VCP 10 \S+ (\d+) (\d+)")  # current, max
_bus_by_monitor: dict[str, int] | None = None


async def _out(*args: str, timeout: float = 10) -> str:
    try:
        p = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
        )
        out, _ = await asyncio.wait_for(p.communicate(), timeout=timeout)
        return out.decode() if p.returncode == 0 else ""
    except (OSError, asyncio.TimeoutError):
        return ""


async def mapping() -> dict[str, int]:
    global _bus_by_monitor
    if _bus_by_monitor is not None:
        return _bus_by_monitor
    # NOTE: on this host plain `detect` omits DRM connector lines; --brief includes them.
    text = await _out("ddcutil", "detect", "--brief", timeout=25)
    result: dict[str, int] = {}
    bus: int | None = None
    for line in text.splitlines():
        s = line.strip()
        if s.startswith("I2C bus:"):
            m = re.search(r"i2c-(\d+)", s)
            bus = int(m.group(1)) if m else None
        elif s.startswith("DRM connector:") and bus is not None:
            conn = s.split(":", 1)[1].strip()        # card1-HDMI-A-1
            name = re.sub(r"^card\d+-", "", conn)    # HDMI-A-1
            result[name] = bus
    _bus_by_monitor = result
    return result


async def levels() -> dict[str, int]:
    """{monitor_name: brightness%}. Sequential to avoid i2c bus contention."""
    out: dict[str, int] = {}
    for name, bus in (await mapping()).items():
        raw = await _out("ddcutil", "--bus", str(bus), "getvcp", "10", "--brief")
        m = _VCP.search(raw)
        if m:
            out[name] = int(m.group(1))
    return out


async def set_level(monitor: str, pct: int) -> bool:
    bus = (await mapping()).get(monitor)
    if bus is None:
        return False
    pct = max(0, min(100, int(pct)))
    try:
        p = await asyncio.create_subprocess_exec(
            "ddcutil", "--bus", str(bus), "setvcp", "10", str(pct),
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL,
        )
        await asyncio.wait_for(p.communicate(), timeout=10)
        return p.returncode == 0
    except (OSError, asyncio.TimeoutError):
        return False
