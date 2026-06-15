"""deckd — the privileged helper.

Runs as root. Listens on a Unix socket. Accepts newline-delimited JSON requests
{"action": "...", "params": {...}}, validates against actions.ACTIONS, executes the
fixed argv with no shell, and returns {"ok": bool, ...}. The web app (unprivileged)
is the only intended client; the socket is chowned to the deck group, mode 0660.

Stdlib only — no third-party code runs as root.
"""

from __future__ import annotations

import asyncio
import grp
import json
import logging
import os
import subprocess

from actions import validate

SOCKET_PATH = os.environ.get("DECK_SOCKET", "/run/phone-deck/deckd.sock")
SOCKET_GROUP = os.environ.get("DECK_SOCKET_GROUP", "")  # group allowed to connect
EXEC_TIMEOUT = 15

log = logging.getLogger("deckd")


def _run(argv: list[str]) -> dict:
    try:
        proc = subprocess.run(
            argv, capture_output=True, text=True, timeout=EXEC_TIMEOUT, check=False
        )
    except subprocess.TimeoutExpired:
        return {"ok": False, "error": "timeout", "argv": argv}
    return {
        "ok": proc.returncode == 0,
        "code": proc.returncode,
        "stdout": proc.stdout[-2000:],
        "stderr": proc.stderr[-2000:],
        "argv": argv,
    }


async def _handle(reader: asyncio.StreamReader, writer: asyncio.StreamWriter):
    try:
        line = await reader.readline()
        if not line:
            return
        req = json.loads(line)
        argv = validate(req.get("action", ""), req.get("params") or {})
        log.info("exec %s", argv)
        resp = await asyncio.get_running_loop().run_in_executor(None, _run, argv)
    except Exception as exc:  # noqa: BLE001 — return the error, never crash the daemon
        resp = {"ok": False, "error": str(exc)}
    try:
        writer.write((json.dumps(resp) + "\n").encode())
        await writer.drain()
    finally:
        writer.close()


async def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s deckd %(message)s")
    if os.path.exists(SOCKET_PATH):
        os.unlink(SOCKET_PATH)
    os.makedirs(os.path.dirname(SOCKET_PATH), exist_ok=True)

    server = await asyncio.start_unix_server(_handle, path=SOCKET_PATH)
    os.chmod(SOCKET_PATH, 0o660)
    if SOCKET_GROUP:
        os.chown(SOCKET_PATH, 0, grp.getgrnam(SOCKET_GROUP).gr_gid)
    log.info("listening on %s (group=%s)", SOCKET_PATH, SOCKET_GROUP or "root")
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    asyncio.run(main())
