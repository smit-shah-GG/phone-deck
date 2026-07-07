"""Per-monitor hardware brightness. Two backends, capability-detected once on first use.

- **ddcutil** — external DDC/CI monitors (lightning's three screens). ddcutil's DRM
  connector matches the Hyprland monitor name exactly (`card1-HDMI-A-1` -> `HDMI-A-1`),
  so brightness is keyed by monitor name. Only *valid* DDC displays count: a laptop eDP
  panel shows up in `ddcutil detect` under an "Invalid display" header — it has an
  i2c/AUX bus but no VCP 10, so recording it would land us in ddc-mode where every
  setvcp silently fails. The Display/Invalid block header is what we key off.
- **backlight** — a laptop's internal panel (raptor/blackbird eDP). Read the level
  straight from `/sys/class/backlight` (world-readable); write via `brightnessctl` (the
  sysfs `brightness` node is root-owned, so a raw write needs root we don't have —
  logind lets the seated user set it through brightnessctl instead). Keyed by the
  connected eDP connector name, which is also the Hyprland monitor name.

The chosen backend + its maps are detected once and cached — `ddcutil detect` is slow.
"""

from __future__ import annotations

import asyncio
import re
import shutil
from pathlib import Path

_VCP = re.compile(r"VCP 10 \S+ (\d+) (\d+)")  # current, max

_SYS_BACKLIGHT = Path("/sys/class/backlight")
_SYS_DRM = Path("/sys/class/drm")
# Never drive a laptop's only panel fully dark from the deck — that's a lockout you
# can't see to recover from. ddcutil (external monitors, own buttons) keeps full 0-100.
_BACKLIGHT_FLOOR = 5

# Detected-once backend state.
_MODE: str | None = None                 # "ddc" | "backlight" | "none"
_bus_by_monitor: dict[str, int] = {}     # ddc:       monitor name -> i2c bus
_bl_monitor: str = ""                    # backlight: eDP monitor name (== hypr name)
_bl_device: str = ""                     # backlight: brightnessctl device (e.g. intel_backlight)
_bl_dir: Path | None = None              # backlight: sysfs dir for reads


async def _out(*args: str, timeout: float = 10) -> str:
    try:
        p = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL
        )
        out, _ = await asyncio.wait_for(p.communicate(), timeout=timeout)
        return out.decode() if p.returncode == 0 else ""
    except (OSError, asyncio.TimeoutError):
        return ""


async def _run(*args: str, timeout: float = 10) -> bool:
    try:
        p = await asyncio.create_subprocess_exec(
            *args, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL
        )
        await asyncio.wait_for(p.communicate(), timeout=timeout)
        return p.returncode == 0
    except (OSError, asyncio.TimeoutError):
        return False


def _read_int(path: Path) -> int | None:
    try:
        return int(path.read_text().strip())
    except (OSError, ValueError):
        return None


def _parse_ddc(text: str) -> dict[str, int]:
    """Parse `ddcutil detect --brief` into {monitor_name: i2c_bus}, honoring block
    headers: a block starts unindented with "Display N" (a usable DDC monitor) or
    "Invalid display" (an i2c bus with no VCP — a laptop eDP), and only the former
    is recorded. Pure so the Invalid-display skip can be locked by a test."""
    result: dict[str, int] = {}
    valid = False
    bus: int | None = None
    for line in text.splitlines():
        if line[:1] not in (" ", "\t") and line.strip():   # unindented, non-empty = block header
            valid = line.strip().startswith("Display")
            bus = None
            continue
        s = line.strip()
        if s.startswith("I2C bus:"):
            m = re.search(r"i2c-(\d+)", s)
            bus = int(m.group(1)) if m else None
        elif s.startswith("DRM connector:") and bus is not None and valid:
            conn = s.split(":", 1)[1].strip()          # card1-HDMI-A-1
            result[re.sub(r"^card\d+-", "", conn)] = bus  # HDMI-A-1
    return result


def _detect_backlight() -> tuple[str, str, Path] | None:
    """(eDP monitor name, brightnessctl device, sysfs dir) or None if no user-settable
    backlight. brightnessctl is required because it's the *write* path; without it the
    slider could only read, which is misleading, so we don't offer brightness at all."""
    if not shutil.which("brightnessctl"):
        return None
    try:
        devs = [d for d in _SYS_BACKLIGHT.iterdir() if (d / "max_brightness").exists()]
    except OSError:
        return None
    if not devs:
        return None
    dev = next((d for d in devs if d.name == "intel_backlight"), devs[0])
    return (_edp_name(), dev.name, dev)


def _edp_name() -> str:
    """Hyprland name of the internal panel = its DRM connector, `card\\d+-` stripped.
    In backlight mode there are no valid DDC displays by construction, so the connected
    connector is the eDP panel; prefer an eDP-* match, else first connected, else eDP-1."""
    connected: list[str] = []
    try:
        for c in _SYS_DRM.iterdir():
            if "-" not in c.name:                      # skip cardN (the device); keep cardN-CONN
                continue
            try:
                if (c / "status").read_text().strip() == "connected":
                    connected.append(re.sub(r"^card\d+-", "", c.name))
            except OSError:
                continue
    except OSError:
        pass
    for n in connected:
        if n.startswith("eDP"):
            return n
    return connected[0] if connected else "eDP-1"


async def _detect() -> None:
    """Pick the backend once. ddcutil (valid DDC displays) wins where present — that's
    lightning, untouched. Else a laptop backlight. Else nothing controllable."""
    global _MODE, _bus_by_monitor, _bl_monitor, _bl_device, _bl_dir
    if _MODE is not None:
        return
    # NOTE: plain `detect` omits DRM connector lines on our hosts; --brief includes them.
    ddc = _parse_ddc(await _out("ddcutil", "detect", "--brief", timeout=25))
    if ddc:
        _bus_by_monitor, _MODE = ddc, "ddc"
        return
    bl = _detect_backlight()
    if bl:
        _bl_monitor, _bl_device, _bl_dir = bl
        _MODE = "backlight"
        return
    _MODE = "none"


async def levels() -> dict[str, int]:
    """{monitor_name: brightness%}. Empty if the host has no controllable brightness."""
    await _detect()
    if _MODE == "ddc":
        out: dict[str, int] = {}
        for name, bus in _bus_by_monitor.items():   # sequential: avoid i2c bus contention
            raw = await _out("ddcutil", "--bus", str(bus), "getvcp", "10", "--brief")
            m = _VCP.search(raw)
            if m:
                out[name] = int(m.group(1))
        return out
    if _MODE == "backlight" and _bl_dir is not None:
        cur, mx = _read_int(_bl_dir / "brightness"), _read_int(_bl_dir / "max_brightness")
        if cur is not None and mx:
            return {_bl_monitor: round(100 * cur / mx)}
    return {}


async def set_level(monitor: str, pct: int) -> bool:
    await _detect()
    pct = max(0, min(100, int(pct)))
    if _MODE == "ddc":
        bus = _bus_by_monitor.get(monitor)
        if bus is None:
            return False
        return await _run("ddcutil", "--bus", str(bus), "setvcp", "10", str(pct))
    if _MODE == "backlight" and monitor == _bl_monitor:
        return await _run("brightnessctl", "-d", _bl_device, "set", f"{max(_BACKLIGHT_FLOOR, pct)}%")
    return False
