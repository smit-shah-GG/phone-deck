"""Gamepad cursor (stream focus mode): confinement + motion maths."""

from app import joystick

DP3 = {"name": "DP-3", "x": 0, "y": 0, "w": 1920, "h": 1080, "scale": 1.0}
HDMI = {"name": "HDMI-A-3", "x": 1920, "y": 0, "w": 1920, "h": 1080, "scale": 1.0}


def test_rect_is_logical_coords():
    assert joystick.rect(HDMI) == (1920.0, 0.0, 1920.0, 1080.0)
    scaled = {"x": 0, "y": 0, "w": 2560, "h": 1600, "scale": 1.6}
    assert joystick.rect(scaled) == (0.0, 0.0, 1600.0, 1000.0)


def test_step_moves_by_screen_fractions():
    r = joystick.rect(DP3)
    assert joystick.step((960.0, 540.0), 0.1, -0.1, r) == (1152.0, 432.0)


def test_step_clamps_to_the_streamed_screen():
    r = joystick.rect(HDMI)                       # streaming the middle screen
    x, y = joystick.step((3800.0, 10.0), 0.5, -0.5, r)
    assert (x, y) == (3839.0, 0.0)                # stopped at its right/top edge, not onto the next screen
    x, y = joystick.step((1925.0, 500.0), -0.5, 0.0, r)
    assert x == 1920.0                            # can't slide left onto DP-3


def test_entering_from_another_screen_lands_in_the_centre():
    r = joystick.rect(HDMI)
    assert joystick.place((300.0, 300.0), r, entering=True) == (2880.0, 540.0)
    assert joystick.place((2000.0, 100.0), r, entering=True) == (2000.0, 100.0)   # already here: stays


def test_subpixel_nudges_accumulate():
    r = joystick.rect(DP3)
    pos = (100.0, 100.0)
    for _ in range(10):
        pos = joystick.step(pos, 0.0001, 0.0, r)   # 0.19px each: would round away as ints
    assert round(pos[0] - 100.0, 3) == 1.92


def test_frac_is_relative_to_the_screen():
    r = joystick.rect(HDMI)
    assert joystick.frac((2880.0, 270.0), r) == (0.5, 0.25)


# ---- the live handler, with Hyprland faked: every warp must stay on the streamed screen ----
import asyncio

import pytest


class _WS:
    def __init__(self):
        self.sent = []

    async def send_json(self, m):
        self.sent.append(m)


@pytest.fixture
def rig(monkeypatch):
    from app import audio_rtc, hypr, main
    real = {"pos": (4579.0, 651.0)}                       # cursor starts on HDMI-A-1 (not streamed)
    warps = []

    async def fake_json(*args):
        assert args == ("cursorpos",)
        return {"x": real["pos"][0], "y": real["pos"][1]}

    async def fake_move(x, y):
        warps.append((x, y)); real["pos"] = (float(x), float(y))
        return True

    monkeypatch.setattr(hypr, "_hyprctl_json", fake_json)
    monkeypatch.setattr(hypr, "move_cursor", fake_move)
    monkeypatch.setattr(audio_rtc, "video_status", {"state": "live", "output": "DP-3", "requested": "DP-3"})
    monkeypatch.setitem(main._state, "hypr", {"monitors": [
        {"name": "DP-3", "x": 0, "y": 0, "w": 1920, "h": 1080, "scale": 1.0},
        {"name": "HDMI-A-3", "x": 1920, "y": 0, "w": 1920, "h": 1080, "scale": 1.0},
        {"name": "HDMI-A-1", "x": 3840, "y": 0, "w": 1920, "h": 1080, "scale": 1.0}]})
    monkeypatch.setattr(main, "_joy", {"pos": None, "at": 0.0})
    return main, real, warps


def test_handler_never_lets_the_cursor_leave_the_streamed_screen(rig):
    main, real, warps = rig
    ws = _WS()

    async def run():
        await main._joy_input({"t": "jwhere", "monitor": "DP-3"}, ws)
        for _ in range(120):                              # ~4 s of a full up-right push
            await main._joy_input({"t": "jmove", "monitor": "DP-3", "dfx": 0.024, "dfy": -0.042}, ws)
        real["pos"] = (2500.0, 600.0)                     # a real mouse drags it onto HDMI-A-3...
        main._joy["at"] -= 1.0                            # ...during a pause longer than RESYNC_S
        await main._joy_input({"t": "jmove", "monitor": "DP-3", "dfx": 0.0, "dfy": 0.01}, ws)

    asyncio.run(run())
    assert warps[0] == (960, 540)                         # entering: brought to the centre
    assert all(0 <= x <= 1919 and 0 <= y <= 1079 for x, y in warps), "a warp left DP-3"
    assert warps[120] == (1919, 0)                        # pinned in the top-right corner
    assert warps[-1][0] == 1919                           # resync pulled it back to DP-3's edge
    assert all(0 <= m["fx"] <= 1 and 0 <= m["fy"] <= 1 for m in ws.sent)


def test_buttons_press_and_release(rig, monkeypatch):
    main, _, _ = rig
    from app import hid
    calls = []
    monkeypatch.setattr(hid, "button", lambda b, down: calls.append((b, down)))
    ws = _WS()
    for m in ({"t": "jdown", "b": "left"}, {"t": "jup", "b": "left"}, {"t": "jdown", "b": "right"},
              {"t": "jup", "b": "right"}, {"t": "jdown", "b": "rm -rf"}):
        asyncio.run(main._joy_input(m, ws))
    assert calls == [("left", True), ("left", False), ("right", True), ("right", False), ("left", True)]
    assert ws.sent == []                                  # buttons don't report position
