"""jarvis — the read-only answerer (voice router Tier 2).

Local LLM (ollama + Qwen2.5-7B) with:
- the live system `_state` injected into every prompt, so "what's my GPU temp" just works,
- explicit web grounding via self-hosted SearXNG ("jarvis search …"), cited,
- a persisted rolling conversation buffer (survives restarts; "jarvis reset" clears it),
- graceful degradation when the GPU is busy (JAX): it pre-checks free VRAM and replies
  "GPU busy" instead of OOMing or crawling on CPU.

STRICTLY read-only — it answers, it never acts. All HTTP is stdlib urllib in an executor
(no new deps); nvidia-smi runs async.
"""

from __future__ import annotations

import asyncio
import contextlib
import json
import urllib.error
import urllib.parse
import urllib.request

from . import config

OLLAMA_URL = "http://127.0.0.1:11434/api/chat"
MODEL = "qwen2.5:7b"
KEEP_ALIVE = "2m"                 # free VRAM ~2 min after last use, for JAX
SEARX_URL = "http://127.0.0.1:8888/search"
HISTORY_FILE = config.CONFIG_DIR / "jarvis_history.json"
HISTORY_TURNS = 8                 # keep the last N user+assistant exchanges
MIN_FREE_VRAM_MB = 5000           # ~5 GB needed to load Qwen2.5-7B Q4
REQUEST_TIMEOUT = 90

_SYSTEM = (
    "You are JARVIS, a terse read-only assistant embedded in a Linux (Hyprland) control "
    "deck. Answer briefly and directly — a sentence or two unless more is clearly needed. "
    "You cannot take actions or control anything; you only answer. "
    "Current system state — {state}."
)


# ---- history (persisted rolling buffer) ------------------------------------
def _load_history() -> list[dict]:
    try:
        data = json.loads(HISTORY_FILE.read_text())
        return data if isinstance(data, list) else []
    except (OSError, json.JSONDecodeError):
        return []


def _save_history(msgs: list[dict]) -> None:
    with contextlib.suppress(OSError):
        config.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        HISTORY_FILE.write_text(json.dumps(msgs[-2 * HISTORY_TURNS:]))


def reset() -> dict:
    with contextlib.suppress(OSError):
        HISTORY_FILE.unlink()
    return {"ok": True, "result": "memory cleared"}


# ---- live state summary ----------------------------------------------------
def _state_line(state: dict) -> str:
    t = state.get("telemetry", {}) or {}
    g, c = t.get("gpu") or {}, t.get("cpu") or {}
    a = state.get("audio", {}) or {}
    h = state.get("hypr", {}) or {}
    aw = h.get("active_window") or {}
    mons = h.get("monitors") or []
    foc = next((m for m in mons if m.get("focused")), None)
    parts = []
    if g:
        parts.append(f"GPU {g.get('util', '?')}% {g.get('temp', '?')}C "
                     f"{g.get('vram_used', '?')}/{g.get('vram_total', '?')}MB {g.get('power', '?')}W")
    if c:
        parts.append(f"CPU {c.get('util', '?')}% {c.get('temp', '?')}C mem {c.get('mem_used', '?')}/{c.get('mem_total', '?')}G")
    np = (a.get("now_playing") or {}).get("title")
    if np:
        parts.append(f"playing '{np}'")
    parts.append(f"volume {a.get('volume', '?')}%" + (" muted" if a.get("sink_muted") else "")
                 + (", mic muted" if a.get("mic_muted") else ""))
    if aw.get("class"):
        parts.append(f"focused app {aw.get('class')}")
    if foc:
        parts.append(f"workspace {foc.get('active_ws')} on {foc.get('name')}")
    return "; ".join(parts) or "unavailable"


# ---- GPU availability ------------------------------------------------------
async def _free_vram_mb() -> int | None:
    """Free VRAM in MB, or None if it can't be determined (then we don't pre-block)."""
    try:
        p = await asyncio.create_subprocess_exec(
            "nvidia-smi", "--query-gpu=memory.free", "--format=csv,noheader,nounits",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        out, _ = await p.communicate()
        return int(out.decode().strip().splitlines()[0])
    except (OSError, ValueError, IndexError):
        return None


def _model_loaded_blocking() -> bool:
    """Is the model already resident in VRAM? (ollama /api/ps) — if so, it can answer even
    when free VRAM is low, so we don't false-report 'GPU busy'."""
    try:
        with urllib.request.urlopen("http://127.0.0.1:11434/api/ps", timeout=3) as r:  # noqa: S310
            data = json.loads(r.read())
        return any(str(m.get("name", "")).startswith("qwen2.5") for m in (data.get("models") or []))
    except (urllib.error.URLError, OSError, ValueError):
        return False


# ---- web grounding (SearXNG) -----------------------------------------------
def _search_blocking(query: str, n: int = 4) -> list[dict]:
    url = SEARX_URL + "?" + urllib.parse.urlencode({"q": query, "format": "json"})
    req = urllib.request.Request(url, headers={"User-Agent": "phone-deck-jarvis"})
    with urllib.request.urlopen(req, timeout=15) as r:  # noqa: S310 — local self-hosted SearXNG
        data = json.loads(r.read())
    out = []
    for res in (data.get("results") or [])[:n]:
        out.append({"title": res.get("title", ""), "url": res.get("url", ""),
                    "content": (res.get("content") or "")[:500]})
    return out


# ---- ollama chat -----------------------------------------------------------
def _chat_blocking(messages: list[dict]) -> str:
    payload = {
        "model": MODEL, "messages": messages, "stream": False, "keep_alive": KEEP_ALIVE,
        "options": {"temperature": 0.4, "num_predict": 512},
    }
    req = urllib.request.Request(
        OLLAMA_URL, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT) as r:  # noqa: S310 — localhost ollama
        data = json.loads(r.read())
    return (data.get("message") or {}).get("content", "").strip()


# ---- entry point -----------------------------------------------------------
async def answer(query: str, state: dict, web: bool = False) -> dict:
    """Answer `query`. Returns {ok, result, sources}. Read-only."""
    query = (query or "").strip()
    if not query:
        return {"ok": False, "result": "(empty query)", "sources": []}

    loop = asyncio.get_running_loop()
    free = await _free_vram_mb()
    if free is not None and free < MIN_FREE_VRAM_MB:
        # low VRAM only blocks if the model isn't already loaded (a resident model can answer)
        if not await loop.run_in_executor(None, _model_loaded_blocking):
            return {"ok": False, "sources": [],
                    "result": f"GPU busy — {free} MB free, need ~{MIN_FREE_VRAM_MB} (JAX is likely on the card)."}

    sources: list[dict] = []
    system = _SYSTEM.format(state=_state_line(state))
    user_content = query
    if web:
        try:
            sources = await loop.run_in_executor(None, _search_blocking, query)
        except (urllib.error.URLError, OSError, ValueError):
            return {"ok": False, "result": "search unavailable (SearXNG down?)", "sources": []}
        if sources:
            ctx = "\n\n".join(f"[{i}] {s['title']}\n{s['content']}\n({s['url']})"
                              for i, s in enumerate(sources, 1))
            system += ("\nAnswer using ONLY these web results; cite them inline as [1], [2]. "
                       "If they don't cover it, say so.\n\n" + ctx)

    history = _load_history()
    messages = [{"role": "system", "content": system}, *history,
                {"role": "user", "content": user_content}]
    try:
        text = await loop.run_in_executor(None, _chat_blocking, messages)
    except urllib.error.URLError:
        return {"ok": False, "result": "model unavailable (ollama down or GPU busy).", "sources": sources}
    except (OSError, ValueError) as exc:
        return {"ok": False, "result": f"error: {exc}", "sources": sources}
    if not text:
        return {"ok": False, "result": "(empty answer)", "sources": sources}

    _save_history(history + [{"role": "user", "content": query},
                             {"role": "assistant", "content": text}])
    return {"ok": True, "result": text, "sources": sources}
