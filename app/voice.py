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
_model = None


def _get_model():
    global _model
    if _model is None:
        from faster_whisper import WhisperModel
        _model = WhisperModel("small.en", device="cpu", compute_type="int8")
    return _model


def _transcribe(audio: "np.ndarray") -> str:
    model = _get_model()
    segments, _ = model.transcribe(audio, language="en", beam_size=1, vad_filter=True)
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
    text = await loop.run_in_executor(None, _transcribe, audio)
    return await route(text)


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


async def route(text: str) -> dict:
    cfg = load_config()
    text = (text or "").strip()
    if not text:
        return {"heard": "", "verb": None, "note": "(nothing heard)"}
    first, _, rest = text.partition(" ")
    rest = rest.strip()
    verb = match_verb(first, cfg.get("aliases", {}))
    if verb is None:
        return {"heard": text, "verb": None, "note": f"no verb (first word: {first})"}

    if verb == "type":
        folded = ascii_fold(rest)
        if folded:
            hid.handle({"t": "text", "s": folded})
        return {"heard": text, "verb": "type", "result": f"typed: {folded}" if folded else "(nothing to type)"}

    if verb == "input":
        phrase = match_input(rest, cfg.get("input", {}))
        if not phrase:
            return {"heard": text, "verb": "input", "result": "no match"}
        keys = cfg["input"][phrase]
        hid.handle({"t": "combo", "keys": keys})
        return {"heard": text, "verb": "input", "matched": phrase, "result": "+".join(keys)}

    if verb == "macro":
        ns = _macro_namespace()
        hit = match_macro(rest, ns)
        if not hit:
            close = difflib.get_close_matches(rest.strip().lower(), [n["label"].lower() for n in ns], n=1, cutoff=0.0)
            return {"heard": text, "verb": "macro", "result": "no match",
                    "note": f"closest: {close[0]}" if close else "no macros defined"}
        if hit["kind"] == "command" and hit["confirm"]:
            return {"heard": text, "verb": "macro", "matched": hit["label"],
                    "result": "needs confirm — run from Commands"}
        res = await (modes.run(hit["id"]) if hit["kind"] == "mode" else commands.run(hit["id"]))
        ok = res.get("ok")
        return {"heard": text, "verb": "macro", "matched": hit["label"], "kind": hit["kind"],
                "result": "ok" if ok else (res.get("error") or "failed")}

    # verb == "jarvis"
    return {"heard": text, "verb": "jarvis", "note": "jarvis arrives in Tier 2"}
