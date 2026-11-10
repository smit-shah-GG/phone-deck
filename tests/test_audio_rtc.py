"""Audio/video streaming pure logic — quality presets and the wf-recorder command.

(The live WebRTC paths are validated by loopback during development, not here — they
need a display, PipeWire, and a Hyprland output.)
"""

from app import audio_rtc


def test_quality_presets():
    assert set(audio_rtc.QUALITY) == {"low", "medium", "high"}
    assert audio_rtc.QUALITY["low"] == ("960:540", 20)
    assert audio_rtc.QUALITY["medium"] == ("1280:720", 24)
    assert audio_rtc.QUALITY["high"] == (None, 30)


def test_wf_cmd_native_has_no_scale_filter():
    cmd = audio_rtc.wf_cmd("DP-3", None, 30)
    assert cmd[cmd.index("-o") + 1] == "DP-3"
    assert cmd[cmd.index("-r") + 1] == "30"
    assert "-F" not in cmd


def test_wf_cmd_applies_scale_and_fps():
    cmd = audio_rtc.wf_cmd("HDMI-A-1", "960:540", 20)
    assert cmd[cmd.index("-o") + 1] == "HDMI-A-1"
    assert cmd[cmd.index("-r") + 1] == "20"
    assert cmd[cmd.index("-F") + 1] == "scale=960:540"


def test_quality_feeds_wf_cmd():
    scale, fps = audio_rtc.QUALITY["medium"]
    cmd = audio_rtc.wf_cmd("DP-3", scale, fps)
    assert "scale=1280:720" in cmd and cmd[cmd.index("-r") + 1] == "24"


# ---- capture survives its output disappearing (headless toggle, 2026-11-02) ----
import asyncio

import av
import numpy
import pytest


def _mons(*names, focused=None):
    return [{"name": n, "focused": n == focused} for n in names]


def test_pick_keeps_requested_output_when_present():
    assert audio_rtc.pick_capture_output(["DP-3", "DP-3"], _mons("DP-3", "HDMI-A-1")) == "DP-3"


def test_pick_returns_to_requested_after_headless():
    # user picked DP-3, capture moved to HEADLESS-KEEPALIVE; headless off removes it
    # and DP-3 is back -> go home, not to whatever is focused
    got = audio_rtc.pick_capture_output(["DP-3", "HEADLESS-KEEPALIVE"],
                                        _mons("DP-3", "HDMI-A-1", focused="HDMI-A-1"))
    assert got == "DP-3"


def test_pick_prefers_headless_when_physical_gone():
    got = audio_rtc.pick_capture_output(["DP-3", "DP-3"],
                                        _mons("HEADLESS-KEEPALIVE", "HDMI-A-1", focused="HDMI-A-1"))
    assert got == "HEADLESS-KEEPALIVE"


def test_pick_falls_back_to_focused_then_any():
    assert audio_rtc.pick_capture_output(["X"], _mons("DP-3", "HDMI-A-1", focused="HDMI-A-1")) == "HDMI-A-1"
    assert audio_rtc.pick_capture_output(["X"], _mons("DP-3", "HDMI-A-1")) == "DP-3"
    assert audio_rtc.pick_capture_output(["X"], []) is None


def test_next_frame_turns_end_of_capture_into_none():
    assert audio_rtc._next_frame(iter([])) is None          # StopIteration must not escape

    def broken():
        raise av.error.InvalidDataError(1, "truncated pipe")
        yield
    assert audio_rtc._next_frame(broken()) is None


def test_lost_card_matches_stream_size():
    card = audio_rtc.lost_card(1280, 720)
    assert (card.width, card.height, card.format.name) == (1280, 720, "yuv420p")


def test_lost_card_falls_back_to_black_without_asset(monkeypatch, tmp_path):
    monkeypatch.setattr(audio_rtc, "LOST_CARD", tmp_path / "missing.png")
    card = audio_rtc.lost_card(64, 36)
    assert (card.width, card.height) == (64, 36)


def _frame(w=64, h=36):
    return av.VideoFrame.from_ndarray(numpy.full((h, w, 3), 90, numpy.uint8), format="rgb24")


def test_track_shows_card_then_reacquires_on_headless(monkeypatch):
    """DP-3's capture ends after 2 frames (headless on disabled it); the track sends
    the lost card, then moves to HEADLESS-KEEPALIVE inside the same track."""
    async def monitors(*_):
        return _mons("HEADLESS-KEEPALIVE")
    monkeypatch.setattr(audio_rtc.hypr, "_hyprctl_json", monitors)
    monkeypatch.setattr(audio_rtc, "video_status", {"state": "live", "output": "DP-3", "requested": "DP-3"})
    monkeypatch.setattr(audio_rtc, "_listener", None)

    opened = []
    def fake_open(monitor):
        opened.append(monitor)
        return iter([_frame(), _frame()]) if monitor == "DP-3" else iter([_frame()] * 50)

    async def run():
        t = audio_rtc.ScreenTrack("DP-3", scale="64:36", fps=24)
        t._open = fake_open
        out = [await t.recv() for _ in range(5)]
        t.stop()
        return t, out

    t, out = asyncio.run(run())
    assert opened == ["DP-3", "HEADLESS-KEEPALIVE"]
    assert out[2].format.name == "yuv420p"                      # frame 3 = the lost card
    assert t._monitor == "HEADLESS-KEEPALIVE" and not t._lost
    assert audio_rtc.video_status == {"state": "live", "output": "HEADLESS-KEEPALIVE", "requested": "DP-3"}


def test_track_keeps_sending_card_while_no_output(monkeypatch):
    async def monitors(*_):
        return []                                    # mid-swap: nothing listed yet
    monkeypatch.setattr(audio_rtc.hypr, "_hyprctl_json", monitors)
    monkeypatch.setattr(audio_rtc, "video_status", {"state": "live", "output": "DP-3", "requested": "DP-3"})
    monkeypatch.setattr(audio_rtc, "_listener", None)

    async def run():
        t = audio_rtc.ScreenTrack("DP-3", scale="64:36", fps=24)
        t._open = lambda m: iter([])                 # DP-3 already gone at connect time
        out = [await t.recv() for _ in range(3)]
        t.stop()
        return out

    out = asyncio.run(run())
    assert all((f.width, f.height) == (64, 36) for f in out)
    assert audio_rtc.video_status["state"] == "lost"


# ---- a DISABLED output stalls wf-recorder instead of ending it (2026-11-10) ----
class _FakeProc:
    def __init__(self):
        self.killed = False
    def poll(self):
        return -9 if self.killed else None
    def kill(self):
        self.killed = True
    def wait(self, timeout=None):
        return -9


def test_check_output_kills_capture_whose_output_is_gone():
    t = audio_rtc.ScreenTrack("DP-3")
    proc = t._proc = _FakeProc()
    assert t.check_output(["HEADLESS-KEEPALIVE", "HDMI-A-1"]) is True
    assert proc.killed and t._proc is None


def test_check_output_leaves_present_or_lost_captures_alone():
    t = audio_rtc.ScreenTrack("DP-3")
    proc = t._proc = _FakeProc()
    assert t.check_output(["DP-3", "HDMI-A-1"]) is False and not proc.killed
    t._lost = True                                    # already recovering: not ours to kill
    assert t.check_output([]) is False and not proc.killed


def test_outputs_changed_reaches_only_the_stream_video_track(monkeypatch):
    v = audio_rtc.ScreenTrack("DP-3")
    vp = v._proc = _FakeProc()
    other = object()                                  # an audio track: must be ignored
    monkeypatch.setattr(audio_rtc, "_sessions", {"stream": {"pc": None, "tracks": [other, v]}})
    audio_rtc.outputs_changed(["HEADLESS-KEEPALIVE"])
    assert vp.killed
    monkeypatch.setattr(audio_rtc, "_sessions", {})
    audio_rtc.outputs_changed([])                     # no stream: a no-op, not a crash
