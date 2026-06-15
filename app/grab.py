"""Screenshot (rig -> phone) via grim, and file drop (phone -> rig).

Screenshots capture a whole monitor (region-select needs an interactive picker on
the rig, which doesn't make sense from the phone). Uploads land in ~/phone-deck-drop;
filenames are reduced to their basename so an upload can't escape that directory.
"""

from __future__ import annotations

import asyncio
import shutil
from pathlib import Path

DROP_DIR = Path.home() / "phone-deck-drop"
_PNG = b"\x89PNG\r\n\x1a\n"


async def screenshot(monitor: str) -> bytes | None:
    if not monitor:
        return None
    try:
        p = await asyncio.create_subprocess_exec(
            "grim", "-o", monitor, "-",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        out, _ = await p.communicate()
    except OSError:
        return None
    return out if (p.returncode == 0 and out[:8] == _PNG) else None


def _unique(dest: Path) -> Path:
    if not dest.exists():
        return dest
    i = 1
    while True:
        cand = dest.with_name(f"{dest.stem}-{i}{dest.suffix}")
        if not cand.exists():
            return cand
        i += 1


def save_upload(filename: str, fileobj) -> dict:
    name = Path(filename or "").name          # strip any path components
    if not name:
        return {"ok": False, "error": "bad filename"}
    DROP_DIR.mkdir(parents=True, exist_ok=True)
    dest = _unique(DROP_DIR / name)
    try:
        with dest.open("wb") as f:
            shutil.copyfileobj(fileobj, f)    # stream, don't load whole file into memory
    except OSError as exc:
        return {"ok": False, "error": str(exc)}
    return {"ok": True, "name": dest.name, "size": dest.stat().st_size}
