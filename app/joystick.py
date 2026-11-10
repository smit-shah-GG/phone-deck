"""Gamepad cursor for stream focus mode: relative motion confined to the streamed screen.

The server owns the position: the real cursor's current spot + the stick's delta,
clamped to the captured monitor, then an absolute warp. That keeps the clamp and the
phone's crosshair exact. A uinput relative move would be put through Hyprland's
pointer acceleration, so the phone couldn't know where it landed (or keep it from
drifting onto a screen that isn't being streamed). Pure helpers; main.py does the I/O.
"""

from __future__ import annotations

# Steering resumed after this long a pause -> re-read the real cursor first: a mouse
# (or a tap on the video) may have moved it in between.
RESYNC_S = 0.3

Rect = tuple[float, float, float, float]
Pos = tuple[float, float]


def rect(mon: dict) -> Rect:
    """(x, y, w, h) of a monitor in Hyprland's LOGICAL layout coords — the space
    `cursorpos` and `movecursor` use (physical size / scale)."""
    sc = float(mon.get("scale") or 1.0)
    return float(mon["x"]), float(mon["y"]), mon["w"] / sc, mon["h"] / sc


def inside(pos: Pos, r: Rect) -> bool:
    x0, y0, w, h = r
    return x0 <= pos[0] < x0 + w and y0 <= pos[1] < y0 + h


def place(pos: Pos, r: Rect, entering: bool = False) -> Pos:
    """Clamp a position into the streamed screen. When focus mode starts with the
    cursor on ANOTHER screen, bring it to this screen's centre rather than its nearest
    edge — you can't see it where it is, and an edge spot is easy to miss."""
    x0, y0, w, h = r
    if entering and not inside(pos, r):
        return x0 + w / 2, y0 + h / 2
    return min(x0 + w - 1, max(x0, pos[0])), min(y0 + h - 1, max(y0, pos[1]))


def step(pos: Pos, dfx: float, dfy: float, r: Rect) -> Pos:
    """Move by a fraction of the screen's width/height, then clamp. Positions stay
    float so sub-pixel nudges accumulate instead of rounding away."""
    return place((pos[0] + dfx * r[2], pos[1] + dfy * r[3]), r)


def frac(pos: Pos, r: Rect) -> Pos:
    """Position as fractions of the screen — what the phone draws the crosshair at."""
    x0, y0, w, h = r
    return (pos[0] - x0) / w, (pos[1] - y0) / h
