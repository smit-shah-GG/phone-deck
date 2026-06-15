"""In-deck editing of the JSON config files (commands.json, modes.json).

Only these two files are editable, by name (no arbitrary paths). Writes are
JSON-validated first, and the previous contents are kept as a .bak so a bad edit
is recoverable. Changes take effect on the next run (both files load fresh).
"""

from __future__ import annotations

import json

from . import config

EDITABLE = {
    "commands": config.CONFIG_DIR / "commands.json",
    "modes": config.CONFIG_DIR / "modes.json",
}


def read(name: str) -> dict:
    path = EDITABLE.get(name)
    if path is None:
        return {"ok": False, "error": "unknown config"}
    try:
        return {"ok": True, "text": path.read_text() if path.exists() else ""}
    except OSError as exc:
        return {"ok": False, "error": str(exc)}


def write(name: str, text: str) -> dict:
    path = EDITABLE.get(name)
    if path is None:
        return {"ok": False, "error": "unknown config"}
    try:
        json.loads(text)  # validate before touching the file
    except json.JSONDecodeError as exc:
        return {"ok": False, "error": f"invalid JSON: line {exc.lineno} col {exc.colno}: {exc.msg}"}
    try:
        if path.exists():
            path.with_name(path.name + ".bak").write_text(path.read_text())
        path.write_text(text)
        return {"ok": True}
    except OSError as exc:
        return {"ok": False, "error": str(exc)}
