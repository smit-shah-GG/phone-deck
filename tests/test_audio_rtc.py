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


# ---- concurrent offers: the newer one wins cleanly, the older one leaves nothing behind (#125) ----
import types


class _FakePC:
    instances = []
    gate_next = None                      # handed to the next pc: pauses setRemoteDescription

    def __init__(self, configuration=None):
        self.configuration = configuration
        self.closed, self.tracks, self.handlers = False, [], {}
        self.connectionState, self.iceGatheringState = "new", "complete"
        self.localDescription, self.gate = None, _FakePC.gate_next
        _FakePC.gate_next = None
        _FakePC.instances.append(self)

    def on(self, ev):
        def deco(f):
            self.handlers[ev] = f
            return f
        return deco

    async def setRemoteDescription(self, d):
        if self.gate:
            await self.gate.wait()

    def addTrack(self, t):
        if self.closed:
            raise RuntimeError("RTCPeerConnection is closed")   # what aiortc does
        self.tracks.append(t)

    async def createAnswer(self):
        return types.SimpleNamespace(sdp="answer-sdp", type="answer")

    async def setLocalDescription(self, d):
        self.localDescription = d

    async def close(self):
        self.closed = True


class _FakeTrack:
    def __init__(self, *a, **k):
        self.stopped = False

    def stop(self):
        self.stopped = True


@pytest.fixture
def fake_rtc(monkeypatch):
    _FakePC.instances, _FakePC.gate_next = [], None
    async def noop(*a, **k):
        return "sink.monitor"
    monkeypatch.setattr(audio_rtc, "RTCPeerConnection", _FakePC)
    monkeypatch.setattr(audio_rtc, "RTCSessionDescription", lambda sdp, type: None)
    monkeypatch.setattr(audio_rtc, "MonitorTrack", _FakeTrack)
    monkeypatch.setattr(audio_rtc, "ScreenTrack", _FakeTrack)
    for name in ("_default_sink_monitor", "_route_to_virtual", "_route_restore", "_mic_teardown", "_mic_setup"):
        monkeypatch.setattr(audio_rtc, name, noop)
    monkeypatch.setattr(audio_rtc, "_sessions", {})
    monkeypatch.setattr(audio_rtc, "_listener", None)
    monkeypatch.setattr(audio_rtc, "video_status", {"state": "idle", "output": None, "requested": None})


def test_offer_superseded_mid_setup_returns_superseded_not_500(fake_rtc):
    async def run():
        gate = asyncio.Event()
        _FakePC.gate_next = gate                         # offer 1 pauses inside setRemoteDescription
        t1 = asyncio.create_task(audio_rtc.handle_offer("o1", "offer", listen=True, video="DP-3"))
        await asyncio.sleep(0)
        r2 = await audio_rtc.handle_offer("o2", "offer", listen=True, video="DP-3")
        gate.set()
        return await t1, r2

    r1, r2 = asyncio.run(run())
    pc1, pc2 = _FakePC.instances
    assert r1 == {"error": "superseded"}                 # was: RuntimeError -> HTTP 500
    assert r2["sdp"] == "answer-sdp"
    assert pc1.closed and pc1.tracks == []               # never added a track to the dead pc
    assert audio_rtc._sessions["stream"]["pc"] is pc2 and len(pc2.tracks) == 2


def test_offer_superseded_during_ice_wait_stops_its_tracks(fake_rtc, monkeypatch):
    gates = {}
    async def ice(pc):                                   # offer 1 waits here, as aiortc does for ~5 s
        if pc is _FakePC.instances[0]:
            await gates["ice"].wait()
    monkeypatch.setattr(audio_rtc, "_ice_complete", ice)

    async def run():
        gates["ice"] = asyncio.Event()
        t1 = asyncio.create_task(audio_rtc.handle_offer("o1", "offer", listen=True, video="DP-3"))
        await asyncio.sleep(0)
        r2 = await audio_rtc.handle_offer("o2", "offer", listen=True, video="DP-3")
        gates["ice"].set()
        return await t1, r2

    r1, r2 = asyncio.run(run())
    pc1 = _FakePC.instances[0]
    assert r1 == {"error": "superseded"} and r2["sdp"] == "answer-sdp"
    assert pc1.closed and len(pc1.tracks) == 2
    assert all(t.stopped for t in pc1.tracks)            # nothing left capturing for a dead peer


def test_failed_orphan_session_stops_its_own_tracks_only(fake_rtc):
    async def run():
        pc_old, sess_old = audio_rtc._register("stream")
        old = _FakeTrack(); sess_old["tracks"].append(old)
        pc_new, sess_new = audio_rtc._register("stream")     # took the slot; old is now an orphan
        new = _FakeTrack(); sess_new["tracks"].append(new)
        pc_old.connectionState = "failed"
        await pc_old.handlers["connectionstatechange"]()
        return old, new, pc_new

    old, new, pc_new = asyncio.run(run())
    assert old.stopped and not new.stopped
    assert audio_rtc._sessions["stream"]["pc"] is pc_new


def test_monitor_track_stop_reaps_its_process():
    t = audio_rtc.MonitorTrack("sink.monitor")
    calls = []
    t._proc = types.SimpleNamespace(poll=lambda: None, terminate=lambda: calls.append("term"),
                                    wait=lambda timeout=None: calls.append("wait"))
    t.stop()
    assert calls == ["term", "wait"]                     # an unwaited parec stayed a zombie


# ---- #106: no STUN, so the answer isn't held ~5 s by unreachable STUN servers ----

def test_session_pc_gathers_host_candidates_only_and_fast():
    import time
    from aiortc import RTCPeerConnection

    async def run():
        client = RTCPeerConnection()
        client.addTransceiver("audio", direction="recvonly")
        await client.setLocalDescription(await client.createOffer())
        pc, _ = audio_rtc._register("probe106")
        try:
            await pc.setRemoteDescription(client.localDescription)
            t0 = time.monotonic()
            await pc.setLocalDescription(await pc.createAnswer())
            await audio_rtc._ice_complete(pc)
            took = time.monotonic() - t0
            sdp = pc.localDescription.sdp
        finally:
            audio_rtc._sessions.pop("probe106", None)
            await pc.close()
            await client.close()
        return took, sdp

    took, sdp = asyncio.run(run())
    assert took < 1.0                                  # was 5.0 s with aiortc's default STUN
    assert "typ host" in sdp and "typ srflx" not in sdp
