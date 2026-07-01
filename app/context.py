"""Context-awareness config: per-app keymaps + call-app allowlist.

Surface-only — this config drives the context strip (in-app controls, call
detection). It never initiates an action; it only decides which controls to show.
Editable in the Config tab (context.json). Falls back to DEFAULT if absent, and
writes DEFAULT on first run so there's something to edit.

Schema:
  apps: { "<window-class>": {
            label: str,
            show:  "persist" | "focus",   # persist = whenever the window exists
                                          # focus   = only when it's focused
            controls: [ { label, key, mods? } ]   # mods e.g. "CTRL SHIFT"
        } }
  call_apps: [ "<binary-or-class>", ... ]   # matched against recording apps
"""

from __future__ import annotations

import json

from . import config

CONTEXT_FILE = config.CONFIG_DIR / "context.json"

DEFAULT: dict = {
    "apps": {
        # YouTube PWA — identified by its stable app class; controlled entirely via
        # sendshortcut (YouTube takes keys for everything, incl. k = play/pause), so
        # it works regardless of focus. show=persist -> reachable from any monitor.
        "chrome-agimnkijcaahngcdmfeangaknmldooml-Default": {
            "label": "YouTube",
            "show": "persist",
            "controls": [
                {"label": "−10", "key": "j"},
                {"label": "⏯", "key": "k"},
                {"label": "+10", "key": "l"},
                {"label": "CC", "key": "c"},
                {"label": "spd−", "key": "comma", "mods": "SHIFT"},
                {"label": "spd+", "key": "period", "mods": "SHIFT"},
                {"label": "⏭", "key": "n", "mods": "SHIFT"},
                {"label": "▤", "key": "t"},
                {"label": "⤢", "key": "f"},
            ],
        },
        "brave-browser": {
            "label": "Brave",
            "show": "focus",
            "controls": [
                {"label": "new", "key": "t", "mods": "CTRL"},
                {"label": "close", "key": "w", "mods": "CTRL"},
                {"label": "reopen", "key": "t", "mods": "CTRL SHIFT"},
                {"label": "‹tab", "key": "tab", "mods": "CTRL SHIFT"},
                {"label": "tab›", "key": "tab", "mods": "CTRL"},
                {"label": "back", "key": "left", "mods": "ALT"},
                {"label": "fwd", "key": "right", "mods": "ALT"},
                {"label": "↻", "key": "r", "mods": "CTRL"},
                {"label": "find", "key": "f", "mods": "CTRL"},
            ],
        },
        # Teams shortcuts (teams-for-linux). Mic mute is intentionally omitted — it's
        # covered by the Call context + the hardware mute. Verify these against your
        # Teams build and tweak in Config if they differ.
        "teams-for-linux": {
            "label": "Teams",
            "show": "focus",
            "controls": [
                {"label": "cam", "key": "o", "mods": "CTRL SHIFT"},
                {"label": "raise", "key": "k", "mods": "CTRL SHIFT"},
                {"label": "hangup", "key": "h", "mods": "CTRL SHIFT"},
            ],
        },
    },
    # Recording apps that count as a "call". Matched loosely (substring either way)
    # against the binary/name of whatever is reading the mic. The deck's own capture
    # (parec/pacat) is excluded upstream in audio.py, so it never false-fires.
    "call_apps": ["teams-for-linux", "zoom", "discord", "vesktop", "Slack", "brave"],
}


def _load() -> dict:
    if not CONTEXT_FILE.exists():
        try:
            config.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
            CONTEXT_FILE.write_text(json.dumps(DEFAULT, indent=2, ensure_ascii=False))
        except OSError:
            pass
        return DEFAULT
    try:
        data = json.loads(CONTEXT_FILE.read_text())
        return data if isinstance(data, dict) else DEFAULT
    except (json.JSONDecodeError, OSError):
        return DEFAULT


def listing() -> dict:
    """What the client needs to render the strip: app keymaps + call-app allowlist."""
    data = _load()
    return {"apps": data.get("apps", {}), "call_apps": data.get("call_apps", [])}
