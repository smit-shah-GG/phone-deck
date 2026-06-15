"""Thin async client for the deckd Unix socket.

The web app sends an action name + params; deckd validates and executes. We never
build command strings here — that authority lives only in the daemon.
"""

from __future__ import annotations

import asyncio
import json

from . import config


async def run_action(action: str, params: dict | None = None) -> dict:
    try:
        reader, writer = await asyncio.open_unix_connection(config.DECK_SOCKET)
    except (FileNotFoundError, ConnectionRefusedError, PermissionError) as exc:
        return {"ok": False, "error": f"deckd unreachable: {exc}"}
    try:
        writer.write((json.dumps({"action": action, "params": params or {}}) + "\n").encode())
        await writer.drain()
        line = await asyncio.wait_for(reader.readline(), timeout=20)
        return json.loads(line) if line else {"ok": False, "error": "empty response"}
    except (asyncio.TimeoutError, json.JSONDecodeError) as exc:
        return {"ok": False, "error": str(exc)}
    finally:
        writer.close()
