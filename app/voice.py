"""Voice router (Tier 1: deterministic lanes).

Start/stop toggle -> the rig's default mic is captured server-side via `parec` ->
faster-whisper `small.en` (CPU) transcribes -> the first spoken word picks a lane:

  macro <label>   run a mode or command (difflib match; confirm-flagged commands skipped)
  type  <prose>   type it verbatim (ASCII-folded) via uinput
  input <phrase>  a named intent -> key/chord from the voice.json vocab
  jarvis <query>  read-only answerer — NOT in Tier 1 (arrives in Tier 2)

No verb match -> no-op + a trace, so mis-hears are visible and never fire an action.
STT is CPU-only on purpose: the deterministic lanes keep working while the GPU is busy.
"""

from __future__ import annotations

import asyncio
import contextlib
import difflib
import os
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor

import numpy as np

from . import commands, config, hid, modes

VOICE_FILE = config.CONFIG_DIR / "voice.json"
VERBS = ("macro", "type", "input", "jarvis")
_MATCH_CUTOFF = 0.6
_AUTO_STOP_S = 30            # safety: kill a forgotten capture

DEFAULT: dict = {
    # per-verb aliases, grown from observed mis-hears (STT sometimes hears "travis" etc.)
    "aliases": {"macro": [], "type": [], "input": [], "jarvis": ["travis", "jervis", "service"]},
    # named intent -> hid key/chord tokens (fed straight to hid's combo handler).
    "input": {
        "copy": ["ctrl", "c"], "terminal copy": ["ctrl", "shift", "c"],
        "paste": ["ctrl", "v"], "cut": ["ctrl", "x"],
        "save": ["ctrl", "s"], "select all": ["ctrl", "a"],
        "undo": ["ctrl", "z"], "redo": ["ctrl", "shift", "z"],
        "find": ["ctrl", "f"], "switch window": ["alt", "tab"],
        "enter": ["enter"], "escape": ["esc"], "tab": ["tab"],
        "up": ["up"], "down": ["down"], "left": ["left"], "right": ["right"],
        "backspace": ["backspace"], "delete": ["delete"],
        "page up": ["pageup"], "page down": ["pagedown"],
    },
}

# Whisper emits typographic punctuation the US-ASCII uinput keymap would silently drop.
_FOLD = {
    "‘": "'", "’": "'", "“": '"', "”": '"',
    "–": "-", "—": "-", "…": "...", " ": " ",
}


# ---- config ----------------------------------------------------------------
def load_config() -> dict:
    import json
    if not VOICE_FILE.exists():
        with contextlib.suppress(OSError):
            config.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
            VOICE_FILE.write_text(json.dumps(DEFAULT, indent=2, ensure_ascii=False))
        return DEFAULT
    try:
        data = json.loads(VOICE_FILE.read_text())
        return data if isinstance(data, dict) else DEFAULT
    except (json.JSONDecodeError, OSError):
        return DEFAULT


# ---- STT -------------------------------------------------------------------
# faster-whisper / ctranslate2 transcribe() is NOT safe to call concurrently on one model
# (concurrent calls deadlock while holding the GIL, which freezes the whole event loop). So
# all transcription runs on a single dedicated worker — never in the shared default pool —
# which serializes it. The model load is lock-guarded too, so two first calls can't race.
_STT_EXECUTOR = ThreadPoolExecutor(max_workers=1, thread_name_prefix="stt")
_model = None
_model_lock = threading.Lock()


def _get_model():
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                from faster_whisper import WhisperModel
                _model = WhisperModel("small.en", device="cpu", compute_type="int8", cpu_threads=4)
    return _model


def _transcribe(audio: "np.ndarray") -> str:
    model = _get_model()
    segments, _ = model.transcribe(audio, language="en", beam_size=1, vad_filter=True)
    return " ".join(s.text for s in segments).strip()


def _transcribe_file(path: str) -> str:
    # faster-whisper decodes the container (webm/opus, etc.) via PyAV — used for the
    # device-mic path where the phone uploads a recorded clip.
    model = _get_model()
    segments, _ = model.transcribe(path, language="en", beam_size=1, vad_filter=True)
    return " ".join(s.text for s in segments).strip()


# ---- capture (rig default mic via parec) -----------------------------------
_capture: dict | None = None


async def start() -> dict:
    """Begin capturing the rig's default source. Idempotent — drops any prior capture."""
    global _capture
    await _abort()
    fd, path = tempfile.mkstemp(suffix=".raw", prefix="deckvoice-")
    os.close(fd)
    f = open(path, "wb")
    try:
        proc = await asyncio.create_subprocess_exec(
            # --latency-msec=50 is essential: without it parec buffers the whole capture
            # internally and writes nothing to stdout until it has a large block, so a
            # short recording ends up empty when we terminate it.
            "parec", "--latency-msec=50", "--format=s16le", "--rate=16000", "--channels=1",
            stdout=f, stderr=asyncio.subprocess.DEVNULL,
        )
    except OSError as exc:
        f.close()
        with contextlib.suppress(OSError):
            os.unlink(path)
        return {"ok": False, "error": str(exc)}
    _capture = {"proc": proc, "path": path, "file": f}
    _capture["auto"] = asyncio.create_task(_auto_stop(_capture))
    return {"ok": True, "recording": True}


async def _auto_stop(cap: dict) -> None:
    with contextlib.suppress(asyncio.CancelledError):
        await asyncio.sleep(_AUTO_STOP_S)
        if _capture is cap:
            await _abort()


async def _finish(cap: dict) -> bytes:
    """Stop the capture process and return the raw PCM it wrote."""
    if cap.get("auto"):
        cap["auto"].cancel()
    with contextlib.suppress(ProcessLookupError):
        cap["proc"].terminate()
    with contextlib.suppress(Exception):
        await cap["proc"].wait()
    cap["file"].close()
    try:
        with open(cap["path"], "rb") as fh:
            return fh.read()
    except OSError:
        return b""
    finally:
        with contextlib.suppress(OSError):
            os.unlink(cap["path"])


async def _abort() -> None:
    """Stop and discard the current capture (no transcription/dispatch)."""
    global _capture
    if not _capture:
        return
    cap, _capture = _capture, None
    await _finish(cap)


async def stop() -> dict:
    """Stop capture -> transcribe -> route -> dispatch. Returns the trace."""
    global _capture
    if not _capture:
        return {"heard": "", "verb": None, "note": "nothing recording"}
    cap, _capture = _capture, None
    raw = await _finish(cap)
    if len(raw) < 3200:                      # < ~0.1s of audio -> nothing to do
        return {"heard": "", "verb": None, "note": "(too short)"}
    audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
    loop = asyncio.get_running_loop()
    text = await loop.run_in_executor(_STT_EXECUTOR, _transcribe, audio)
    return await plan(text)


async def transcribe_upload(data: bytes) -> dict:
    """Device-mic path: transcribe an uploaded audio clip (phone's own mic) -> plan."""
    if len(data) < 1000:
        return {"heard": "", "verb": None, "note": "(too short)", "plan": None}
    fd, path = tempfile.mkstemp(suffix=".webm", prefix="deckvoice-up-")
    os.close(fd)
    try:
        with open(path, "wb") as f:
            f.write(data)
        loop = asyncio.get_running_loop()
        text = await loop.run_in_executor(_STT_EXECUTOR, _transcribe_file, path)
    except OSError:
        return {"heard": "", "verb": None, "note": "(upload failed)", "plan": None}
    finally:
        with contextlib.suppress(OSError):
            os.unlink(path)
    return await plan(text)


# ---- routing (pure/testable) ----------------------------------------------
def match_verb(token: str, aliases: dict) -> str | None:
    token = token.lower().strip(".,!?;:")
    for verb in VERBS:
        if token == verb or token in [a.lower() for a in aliases.get(verb, [])]:
            return verb
    return None


def ascii_fold(s: str) -> str:
    return "".join(_FOLD.get(ch, ch) for ch in s)


def match_input(payload: str, vocab: dict) -> str | None:
    m = difflib.get_close_matches(payload.strip().lower(), list(vocab), n=1, cutoff=_MATCH_CUTOFF)
    return m[0] if m else None


def _macro_namespace() -> list[dict]:
    ns = [{"label": m["label"], "kind": "mode", "id": m["id"], "confirm": False}
          for m in modes.listing()]
    ns += [{"label": c["label"], "kind": "command", "id": c["id"], "confirm": c.get("confirm", False)}
           for c in commands.listing()]
    return ns


def match_macro(payload: str, namespace: list[dict]) -> dict | None:
    labels = {n["label"].lower(): n for n in namespace}
    m = difflib.get_close_matches(payload.strip().lower(), list(labels), n=1, cutoff=_MATCH_CUTOFF)
    return labels[m[0]] if m else None


async def plan(text: str) -> dict:
    """Transcribed text -> a trace with an executable *plan* (or None). Never dispatches —
    the caller surfaces the plan and only executes on explicit confirmation. This is the
    carefulness that stops a mis-heard chord/macro from firing on its own."""
    cfg = load_config()
    text = (text or "").strip()
    if not text:
        return {"heard": "", "verb": None, "note": "(nothing heard)", "plan": None}
    first, _, rest = text.partition(" ")
    rest = rest.strip()
    verb = match_verb(first, cfg.get("aliases", {}))
    if verb is None:
        return {"heard": text, "verb": None, "note": f"no verb (first word: {first})", "plan": None}

    if verb == "type":
        folded = ascii_fold(rest)
        if not folded:
            return {"heard": text, "verb": "type", "note": "(nothing to type)", "plan": None}
        return {"heard": text, "verb": "type", "preview": f"type: {folded}",
                "plan": {"lane": "type", "text": folded}}

    if verb == "input":
        phrase = match_input(rest, cfg.get("input", {}))
        if not phrase:
            return {"heard": text, "verb": "input", "note": "no match", "plan": None}
        keys = cfg["input"][phrase]
        return {"heard": text, "verb": "input", "matched": phrase,
                "preview": f"input: {phrase} → {'+'.join(keys)}",
                "plan": {"lane": "input", "phrase": phrase, "keys": keys}}

    if verb == "macro":
        ns = _macro_namespace()
        hit = match_macro(rest, ns)
        if not hit:
            close = difflib.get_close_matches(rest.strip().lower(), [n["label"].lower() for n in ns], n=1, cutoff=0.0)
            return {"heard": text, "verb": "macro", "plan": None,
                    "note": f"no match (closest: {close[0]})" if close else "no macros defined"}
        return {"heard": text, "verb": "macro", "matched": hit["label"],
                "preview": f"macro: {hit['label']}" + (" (needs confirm)" if hit["confirm"] else ""),
                "plan": {"lane": "macro", "kind": hit["kind"], "id": hit["id"],
                         "label": hit["label"], "confirm": hit["confirm"]}}

    # verb == "jarvis"
    return {"heard": text, "verb": "jarvis", "note": "jarvis arrives in Tier 2", "plan": None}


async def execute(p: dict | None) -> dict:
    """Dispatch a plan from plan(). Re-validates through the safe primitives — never
    trusts the client blindly (macro ids are re-checked; keys/text go through hid)."""
    if not isinstance(p, dict):
        return {"ok": False, "result": "no plan"}
    lane = p.get("lane")
    if lane == "type":
        text = str(p.get("text", ""))
        if text:
            hid.handle({"t": "text", "s": text})
        return {"ok": True, "result": f"typed: {text}"}
    if lane == "input":
        keys = p.get("keys")
        if not isinstance(keys, list) or not keys:
            return {"ok": False, "result": "bad keys"}
        hid.handle({"t": "combo", "keys": [str(k) for k in keys]})
        return {"ok": True, "result": "+".join(str(k) for k in keys)}
    if lane == "macro":
        kind, cid = p.get("kind"), str(p.get("id", ""))
        valid = ({m["id"] for m in modes.listing()} if kind == "mode"
                 else {c["id"] for c in commands.listing()})
        if cid not in valid:
            return {"ok": False, "result": "unknown macro"}
        res = await (modes.run(cid) if kind == "mode" else commands.run(cid))
        return {"ok": bool(res.get("ok")), "result": "ok" if res.get("ok") else (res.get("error") or "failed")}
    return {"ok": False, "result": "unknown lane"}
