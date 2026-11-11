"""WebRTC bridge: audio (rig <-> phone) + screen video + extension-monitor pads.

A custom MediaStreamTrack reads the rig's default-sink monitor via `parec` (raw
48kHz s16 stereo) and hands frames to aiortc, which Opus-encodes and sends to the
phone over a WebRTC PeerConnection. Reading a fixed chunk per recv() paces the
track to real time (parec produces at 48kHz).

Session model: a keyed registry (the `stream` slot is the only key in use —
single, replace-on-new-offer, sole audio carrier). The registry shape replaced
bare module globals because it fixes a latent race (a replaced session's late
disconnect callback could kill its successor) and gives clean per-key teardown.
It also supports N-peer video classes if one ever earns its way back
(see docs_internal/shelf/extension-monitor-SHELVED.md).
"""

from __future__ import annotations

import asyncio
import subprocess
import time
from fractions import Fraction
from pathlib import Path

import av
import numpy
from aiortc import RTCPeerConnection, RTCSessionDescription, VideoStreamTrack
from aiortc.mediastreams import MediaStreamError, MediaStreamTrack

from . import hypr

# Raise the VP8 congestion-control ceiling: the stock 1.5 Mbps cap smears text,
# and a pad is a MONITOR — text is its whole life. The estimator still adapts
# downward on a bad path; we only lift where it's allowed to ramp.
try:
    from aiortc.codecs import vpx as _vpx
    _vpx.DEFAULT_BITRATE = 2_000_000
    _vpx.MAX_BITRATE = 8_000_000
except (ImportError, AttributeError):   # aiortc internals moved — defaults still work
    pass

SAMPLE_RATE = 48000
CHANNELS = 2
CHUNK_SAMPLES = SAMPLE_RATE * 20 // 1000        # 20 ms = 960 samples
CHUNK_BYTES = CHUNK_SAMPLES * CHANNELS * 2      # s16 stereo


SINK_NAME = "phonedeck_out"   # virtual sink for phone-only output
MIC_SINK = "phonedeck_mic"    # null sink whose monitor is the virtual microphone


async def _pactl(*args: str) -> str:
    p = await asyncio.create_subprocess_exec(
        "pactl", *args, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    out, _ = await p.communicate()
    return out.decode().strip()


async def _default_sink_monitor() -> str:
    return (await _pactl("get-default-sink")) + ".monitor"


# ---- phone-only routing: send playback to a virtual sink, silence the speakers ----
_routing = {"active": False, "module": None, "prev_default": None}


async def _move_all_inputs(target: str) -> None:
    for line in (await _pactl("list", "short", "sink-inputs")).splitlines():
        sid = line.split("\t")[0]
        if sid:
            await _pactl("move-sink-input", sid, target)


async def _route_to_virtual() -> str:
    """Create the virtual sink, make it default, move current streams onto it.

    Returns its monitor (what we capture). Speakers get nothing because nothing
    plays to the hardware sink anymore.
    """
    prev = await _pactl("get-default-sink")
    module = await _pactl(
        "load-module", "module-null-sink", f"sink_name={SINK_NAME}",
        "sink_properties=device.description=PhoneDeck")
    await _pactl("set-default-sink", SINK_NAME)
    await _move_all_inputs(SINK_NAME)
    _routing.update(active=True, module=module, prev_default=prev)
    return f"{SINK_NAME}.monitor"


async def _route_restore() -> None:
    if not _routing["active"]:
        return
    if _routing["prev_default"]:
        await _pactl("set-default-sink", _routing["prev_default"])
        await _move_all_inputs(_routing["prev_default"])   # move everything back, incl. new streams
    if _routing["module"]:
        await _pactl("unload-module", _routing["module"])
    _routing.update(active=False, module=None, prev_default=None)


async def cleanup_stale() -> None:
    """On startup, drop virtual sinks left over from a hard crash (PipeWire then
    re-selects a real default automatically)."""
    for line in (await _pactl("list", "short", "modules")).splitlines():
        if "module-null-sink" in line and (SINK_NAME in line or MIC_SINK in line):
            await _pactl("unload-module", line.split("\t")[0])


# ---- phone mic -> rig: receive the phone's audio into a virtual microphone ----
_mic = {"active": False, "module": None, "proc": None, "task": None}


async def _mic_setup() -> None:
    module = await _pactl(
        "load-module", "module-null-sink", f"sink_name={MIC_SINK}",
        "sink_properties=device.description=PhoneDeckMic")
    _mic.update(active=True, module=module)


async def _mic_teardown() -> None:
    if not _mic["active"]:
        return
    if _mic["task"]:
        _mic["task"].cancel()
    if _mic["proc"] is not None and _mic["proc"].poll() is None:
        _mic["proc"].terminate()
    if _mic["module"]:
        await _pactl("unload-module", _mic["module"])
    _mic.update(active=False, module=None, proc=None, task=None)


async def _consume_mic(track: MediaStreamTrack) -> None:
    """Pull the phone's audio frames, resample to 48k s16 stereo, and push them into
    the virtual mic sink via pacat (its monitor is what apps select as input)."""
    resampler = av.AudioResampler(format="s16", layout="stereo", rate=SAMPLE_RATE)
    proc = subprocess.Popen(
        ["pacat", "--format=s16le", f"--rate={SAMPLE_RATE}", f"--channels={CHANNELS}",
         "--latency-msec=50", "--device=" + MIC_SINK],
        stdin=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
    _mic["proc"] = proc
    loop = asyncio.get_running_loop()
    try:
        while True:
            frame = await track.recv()
            out = resampler.resample(frame)
            for rf in (out if isinstance(out, list) else [out]):
                await loop.run_in_executor(None, proc.stdin.write, bytes(rf.planes[0]))
    except (MediaStreamError, asyncio.CancelledError):
        pass
    except Exception:  # noqa: BLE001 — never let the mic path crash the session
        pass


class MonitorTrack(MediaStreamTrack):
    """Live capture of a PipeWire monitor source as a WebRTC audio track."""

    kind = "audio"

    def __init__(self, device: str):
        super().__init__()
        self._device = device
        self._proc: subprocess.Popen | None = None
        self._pts = 0

    def _ensure(self):
        # Start capture lazily on the first pull so audio buffered during ICE/connection
        # setup doesn't become permanent latency. --latency-msec keeps parec's own buffer small.
        if self._proc is None:
            self._proc = subprocess.Popen(
                ["parec", "--latency-msec=50", "--format=s16le", f"--rate={SAMPLE_RATE}",
                 f"--channels={CHANNELS}", "--device=" + self._device],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)

    async def recv(self):
        self._ensure()
        loop = asyncio.get_running_loop()
        data = await loop.run_in_executor(None, self._proc.stdout.read, CHUNK_BYTES)
        if not data or len(data) < CHUNK_BYTES:                       # pad short read/EOF with silence
            data = (data or b"") + b"\x00" * (CHUNK_BYTES - len(data or b""))
        frame = av.AudioFrame(format="s16", layout="stereo", samples=CHUNK_SAMPLES)
        frame.planes[0].update(data)
        frame.sample_rate = SAMPLE_RATE
        frame.pts = self._pts
        frame.time_base = Fraction(1, SAMPLE_RATE)
        self._pts += CHUNK_SAMPLES
        return frame

    def stop(self):
        super().stop()
        if self._proc is not None and self._proc.poll() is None:
            self._proc.terminate()
        if self._proc is not None:
            try:
                self._proc.wait(timeout=1)   # reap it: an unwaited parec lingered as a zombie (2026-11-11)
            except Exception:
                pass


# quality preset -> (scale filter or None, framerate). Lower = more network-resilient.
QUALITY = {"low": ("960:540", 20), "medium": ("1280:720", 24), "high": (None, 30)}


def wf_cmd(monitor: str, scale: str | None, fps: int) -> list[str]:
    """argv for capturing a monitor as rawvideo into a pipe (pure, for testing)."""
    cmd = ["wf-recorder", "-o", monitor, "-r", str(fps), "-c", "rawvideo",
           "-m", "nut", "-f", "/dev/stdout"]
    if scale:
        cmd += ["-F", f"scale={scale}"]
    return cmd


HEADLESS_OUTPUT = "HEADLESS-KEEPALIVE"   # headless.sh's virtual output (its only screen)
REACQUIRE_EVERY_S = 1.0
LOST_CARD = Path(__file__).parent / "static" / "stream-lost.png"


def pick_capture_output(preferred: list[str | None], monitors: list[dict]) -> str | None:
    """Where to (re)start screen capture once the captured output has gone away.

    Order: the first `preferred` name still present (the output the user picked,
    then the one last captured) → HEADLESS-KEEPALIVE (headless mode's only screen)
    → the focused monitor → any monitor. None only if Hyprland lists no outputs.
    Pure, for testing. Disabled outputs don't appear in `hyprctl monitors`, so a
    screen headless mode switched off counts as gone.
    """
    names = [m["name"] for m in monitors if m.get("name")]
    for p in preferred:
        if p and p in names:
            return p
    if HEADLESS_OUTPUT in names:
        return HEADLESS_OUTPUT
    for m in monitors:
        if m.get("focused") and m.get("name"):
            return m["name"]
    return names[0] if names else None


def _next_frame(gen):
    """next() for the executor; None once the capture has ended.

    StopIteration must not escape: asyncio can't carry it into a Future and turns
    it into a RuntimeError, which aiortc's RTP sender treats as fatal — the video
    froze for the rest of the session (2026-11-02, headless toggle). A wf-recorder
    dying mid-frame surfaces as a decode error instead, which means the same thing.
    """
    try:
        return next(gen)
    except StopIteration:
        return None
    except (av.FFmpegError, OSError, ValueError):
        return None


def _preset_size(scale: str | None) -> tuple[int, int]:
    if scale:
        w, h = scale.split(":")
        return int(w), int(h)
    return 1920, 1080


def lost_card(width: int, height: int) -> av.VideoFrame:
    """The 'video signal lost' frame at the stream's size (yuv420p, what VP8 takes).
    Falls back to black if the asset is missing — a card beats a dead track."""
    try:
        with av.open(str(LOST_CARD)) as c:
            src = next(c.decode(video=0))
        return src.reformat(width=width, height=height, format="yuv420p")
    except (av.FFmpegError, OSError, StopIteration):
        black = numpy.zeros((height, width, 3), numpy.uint8)
        return av.VideoFrame.from_ndarray(black, format="rgb24").reformat(format="yuv420p")


# What the video capture is doing, mirrored into the deck's WS state as `stream`
# so the phone can say "video lost" and follow a capture that moved outputs.
# state: idle | live | lost · output: what's captured (or was, while lost) ·
# requested: what the user picked when connecting.
video_status: dict = {"state": "idle", "output": None, "requested": None}
_listener = None


def set_listener(fn) -> None:
    """Register an async no-arg callback run whenever video_status changes."""
    global _listener
    _listener = fn


def _set_video_status(**changes) -> None:
    video_status.update(changes)
    if _listener is not None:
        try:
            asyncio.get_running_loop().create_task(_listener())
        except RuntimeError:          # no loop (tests / shutdown) — nothing to tell
            pass


class ScreenTrack(VideoStreamTrack):
    """Live capture of a Hyprland monitor via wf-recorder -> rawvideo pipe -> PyAV.

    Resolution/framerate are capped per the quality preset so WebRTC isn't asked to
    push full raw 1080p30, which collapses on the slightest network dip.

    Survives its output disappearing (headless mode on/off, a hotplug): when the
    capture ends, the track sends a 'video signal lost' card and retries every
    second on pick_capture_output()'s choice, inside the same WebRTC track — no
    renegotiation, so audio and input never notice.
    """

    def __init__(self, monitor: str, scale: str | None = "1280:720", fps: int = 24):
        super().__init__()
        self._requested = monitor
        self._monitor = monitor
        self._scale = scale
        self._fps = fps
        self._proc: subprocess.Popen | None = None
        self._gen = None
        self._stopped = False
        self._lost = False
        self._next_try = 0.0
        self._size: tuple[int, int] | None = None   # last real frame's (w, h)
        self._card: av.VideoFrame | None = None

    def _open(self, monitor: str):
        """Start wf-recorder on `monitor` and return its frame generator, or None if
        it won't start (output gone). Runs in the executor: av.open blocks until the
        stream header arrives, which must never stall the event loop."""
        self._kill()
        proc = subprocess.Popen(wf_cmd(monitor, self._scale, self._fps),
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self._proc = proc
        if self._stopped:              # stop() raced us: don't leave a capture squatting
            self._kill()
            return None
        try:
            return av.open(proc.stdout, format="nut").decode(video=0)
        except (av.FFmpegError, OSError, ValueError):
            self._kill()
            return None

    def _capture_lost(self) -> None:
        self._kill()
        self._gen = None
        self._lost = True
        self._next_try = 0.0           # first retry on the very next frame tick
        print(f"stream: video capture of {self._monitor} ended — reacquiring", flush=True)
        _set_video_status(state="lost", output=self._monitor)

    async def _reacquire(self, loop):
        now = time.monotonic()
        if now < self._next_try:
            return None
        self._next_try = now + REACQUIRE_EVERY_S
        target = pick_capture_output([self._requested, self._monitor],
                                     await hypr._hyprctl_json("monitors") or [])
        if target is None:
            return None
        gen = await loop.run_in_executor(None, self._open, target)
        frame = await loop.run_in_executor(None, _next_frame, gen) if gen else None
        if frame is None:
            self._kill()
            return None
        self._gen, self._monitor, self._lost = gen, target, False
        print(f"stream: video reacquired on {target}", flush=True)
        _set_video_status(state="live", output=target)
        return frame

    def _lost_frame(self) -> av.VideoFrame:
        w, h = self._size or _preset_size(self._scale)
        if self._card is None or (self._card.width, self._card.height) != (w, h):
            self._card = lost_card(w, h)
        return self._card

    async def recv(self):
        pts, time_base = await self.next_timestamp()
        loop = asyncio.get_running_loop()
        frame = None
        if self._lost:
            frame = await self._reacquire(loop)
        else:
            if self._gen is None:
                self._gen = await loop.run_in_executor(None, self._open, self._monitor)
            if self._gen is not None:
                frame = await loop.run_in_executor(None, _next_frame, self._gen)
            if frame is None and not self._stopped:
                self._capture_lost()
        if frame is None:
            frame = self._lost_frame()
        else:
            self._size = (frame.width, frame.height)
        frame.pts = pts
        frame.time_base = time_base
        return frame

    def _kill(self) -> None:
        proc = self._proc
        if proc is None:
            return
        if proc.poll() is None:
            # SIGKILL, not SIGTERM: wf-recorder traps SIGTERM to flush its recording,
            # and with our pipe reader gone that flush blocks forever — an immortal
            # capture squatting on a screencopy session (seen in prod 2026-07-03,
            # left video black for every later session). Rawvideo has no trailer
            # worth flushing; kill is correct.
            proc.kill()
        try:
            proc.wait(timeout=1)       # reap immediately (SIGKILL can't block)
        except Exception:
            pass
        if self._proc is proc:
            self._proc = None

    def check_output(self, names: list[str]) -> bool:
        """Drop the capture if its output is no longer listed. A DISABLED output
        (headless.sh on) doesn't make wf-recorder exit — it stays alive and silent
        (measured 2026-11-10), so the track would hold its last frame forever.
        Killing it gives the reader EOF; recv's normal loss path does the rest."""
        if self._lost or self._stopped or self._proc is None or self._monitor in names:
            return False
        print(f"stream: {self._monitor} left the output list — dropping its capture", flush=True)
        self._kill()
        return True

    def stop(self):
        self._stopped = True
        super().stop()
        self._kill()


# ---- session registry: "stream" (single, audio-carrying) + "pad:<id>" (N) ----
_sessions: dict[str, dict] = {}          # key -> {"pc": RTCPeerConnection, "tracks": [...]}


async def _teardown(key: str) -> None:
    s = _sessions.pop(key, None)
    if s is None:
        return
    for t in s["tracks"]:
        t.stop()
    if s["pc"] is not None:
        await s["pc"].close()
    if key == "stream":                   # audio machinery belongs to the stream slot only
        await _mic_teardown()
        await _route_restore()
        if video_status["state"] != "idle":
            _set_video_status(state="idle", output=None, requested=None)


def outputs_changed(names: list[str]) -> None:
    """Hyprland's output set changed: let the stream's screen capture notice if
    its output went away (disabled or removed) — see ScreenTrack.check_output."""
    s = _sessions.get("stream")
    for t in (s or {}).get("tracks", []):
        if isinstance(t, ScreenTrack):
            t.check_output(names)


async def stop(key: str = "stream") -> None:
    await _teardown(key)


async def stop_all() -> None:
    for key in list(_sessions):
        await _teardown(key)


def _register(key: str) -> tuple[RTCPeerConnection, dict]:
    """Create a session's pc with auto-cleanup on death. The identity guard
    matters: a replaced session's late disconnect event must not kill its
    successor under the same key."""
    pc = RTCPeerConnection()
    sess = {"pc": pc, "tracks": []}
    _sessions[key] = sess

    @pc.on("connectionstatechange")
    async def _on_state():
        if pc.connectionState not in ("failed", "closed", "disconnected"):
            return
        if _sessions.get(key, {}).get("pc") is pc:
            await _teardown(key)
        else:                             # orphaned (superseded) session: its own parts only
            for t in sess["tracks"]:
                t.stop()

    return pc, sess


async def _abandon(pc: RTCPeerConnection, sess: dict) -> dict:
    """A newer offer took the slot while this one was still being set up. Drop only
    this session's own pc + tracks: the shared audio machinery (mic sink, routing,
    video status) now belongs to the session that replaced it. Without this the old
    offer 500'd on a closed pc, and once even kept a capture running for a minute
    with nothing able to stop it (2026-11-11, tasks #125)."""
    for t in sess["tracks"]:
        t.stop()
    await pc.close()
    return {"error": "superseded"}


async def _ice_complete(pc: RTCPeerConnection) -> None:
    if pc.iceGatheringState == "complete":
        return
    done = asyncio.Event()

    @pc.on("icegatheringstatechange")
    def _on_change():
        if pc.iceGatheringState == "complete":
            done.set()

    await done.wait()


async def handle_offer(sdp: str, type_: str, listen: bool = True, mic: bool = False,
                       phone_only: bool = False, video: str | None = None,
                       video_quality: str = "medium") -> dict:
    await _teardown("stream")                     # single slot: replace any existing
    pc, sess = _register("stream")

    def superseded() -> bool:                     # a newer offer took the slot meanwhile
        return _sessions.get("stream") is not sess

    if mic:
        await _mic_setup()
        if superseded():
            return await _abandon(pc, sess)

        @pc.on("track")
        def _on_track(track):
            if track.kind == "audio":
                _mic["task"] = asyncio.ensure_future(_consume_mic(track))

    await pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type=type_))
    if superseded():                              # checked BEFORE routing: it's shared state
        return await _abandon(pc, sess)

    if listen:                                    # attach our send track to the recvonly m-line
        device = await (_route_to_virtual() if phone_only else _default_sink_monitor())
        if superseded():
            return await _abandon(pc, sess)
        track = MonitorTrack(device)
        sess["tracks"].append(track)
        pc.addTrack(track)

    if video:                                     # video is a separate kind -> no transceiver ambiguity
        scale, fps = QUALITY.get(video_quality, QUALITY["medium"])
        v = ScreenTrack(video, scale=scale, fps=fps)
        sess["tracks"].append(v)
        pc.addTrack(v)
        _set_video_status(state="live", output=video, requested=video)

    await pc.setLocalDescription(await pc.createAnswer())
    await _ice_complete(pc)                        # non-trickle: answer once gathering done
    if superseded():                              # the widest window: gathering takes ~5 s (#106)
        return await _abandon(pc, sess)
    return {"sdp": pc.localDescription.sdp, "type": pc.localDescription.type}
