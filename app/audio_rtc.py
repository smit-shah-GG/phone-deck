"""WebRTC audio bridge (phase 2: rig -> phone).

A custom MediaStreamTrack reads the rig's default-sink monitor via `parec` (raw
48kHz s16 stereo) and hands frames to aiortc, which Opus-encodes and sends to the
phone over a WebRTC PeerConnection. Reading a fixed chunk per recv() paces the
track to real time (parec produces at 48kHz).

Single session: a new offer replaces the previous one. Phone -> rig is phase 3.
"""

from __future__ import annotations

import asyncio
import subprocess
from fractions import Fraction

import av
from aiortc import RTCPeerConnection, RTCSessionDescription, VideoStreamTrack
from aiortc.mediastreams import MediaStreamError, MediaStreamTrack

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


# quality preset -> (scale filter or None, framerate). Lower = more network-resilient.
QUALITY = {"low": ("960:540", 20), "medium": ("1280:720", 24), "high": (None, 30)}


def wf_cmd(monitor: str, scale: str | None, fps: int) -> list[str]:
    """argv for capturing a monitor as rawvideo into a pipe (pure, for testing)."""
    cmd = ["wf-recorder", "-o", monitor, "-r", str(fps), "-c", "rawvideo",
           "-m", "nut", "-f", "/dev/stdout"]
    if scale:
        cmd += ["-F", f"scale={scale}"]
    return cmd


class ScreenTrack(VideoStreamTrack):
    """Live capture of a Hyprland monitor via wf-recorder -> rawvideo pipe -> PyAV.

    Resolution/framerate are capped per the quality preset so WebRTC isn't asked to
    push full raw 1080p30, which collapses on the slightest network dip.
    """

    def __init__(self, monitor: str, scale: str | None = "1280:720", fps: int = 24):
        super().__init__()
        self._monitor = monitor
        self._scale = scale
        self._fps = fps
        self._proc: subprocess.Popen | None = None
        self._gen = None

    def _ensure(self):
        if self._proc is None:
            self._proc = subprocess.Popen(
                wf_cmd(self._monitor, self._scale, self._fps),
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            self._gen = av.open(self._proc.stdout, format="nut").decode(video=0)

    async def recv(self):
        self._ensure()
        pts, time_base = await self.next_timestamp()
        frame = await asyncio.get_running_loop().run_in_executor(None, next, self._gen)
        frame.pts = pts
        frame.time_base = time_base
        return frame

    def stop(self):
        super().stop()
        if self._proc is not None and self._proc.poll() is None:
            self._proc.terminate()


_pc: RTCPeerConnection | None = None
_track: MonitorTrack | None = None
_video: ScreenTrack | None = None


async def stop() -> None:
    global _pc, _track, _video
    if _track is not None:
        _track.stop()
        _track = None
    if _video is not None:
        _video.stop()
        _video = None
    if _pc is not None:
        await _pc.close()
        _pc = None
    await _mic_teardown()
    await _route_restore()


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
    global _pc, _track, _video
    await stop()                                  # replace any existing session
    pc = RTCPeerConnection()
    _pc = pc

    @pc.on("connectionstatechange")
    async def _on_state():
        if pc.connectionState in ("failed", "closed", "disconnected"):
            await stop()

    if mic:
        await _mic_setup()

        @pc.on("track")
        def _on_track(track):
            if track.kind == "audio":
                _mic["task"] = asyncio.ensure_future(_consume_mic(track))

    await pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type=type_))

    if listen:                                    # attach our send track to the recvonly m-line
        device = await (_route_to_virtual() if phone_only else _default_sink_monitor())
        _track = MonitorTrack(device)
        pc.addTrack(_track)

    if video:                                     # video is a separate kind -> no transceiver ambiguity
        scale, fps = QUALITY.get(video_quality, QUALITY["medium"])
        _video = ScreenTrack(video, scale=scale, fps=fps)
        pc.addTrack(_video)

    await pc.setLocalDescription(await pc.createAnswer())
    await _ice_complete(pc)                        # non-trickle: answer once gathering done
    return {"sdp": pc.localDescription.sdp, "type": pc.localDescription.type}
