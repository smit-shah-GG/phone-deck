"""Runtime config + persisted secrets for the web app.

Secrets live in ~/.config/phone-deck/. The JWT signing key is generated once on first
run. The login PIN is stored as a salted scrypt hash (stdlib), set via set_pin.py.
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import secrets
from pathlib import Path

CONFIG_DIR = Path(os.environ.get("DECK_CONFIG_DIR", Path.home() / ".config" / "phone-deck"))
SECRET_FILE = CONFIG_DIR / "secret.json"

DECK_SOCKET = os.environ.get("DECK_SOCKET", "/run/phone-deck/deckd.sock")
BIND_HOST = os.environ.get("DECK_BIND_HOST", "127.0.0.1")
BIND_PORT = int(os.environ.get("DECK_BIND_PORT", "8765"))
JWT_TTL_DAYS = int(os.environ.get("DECK_JWT_TTL_DAYS", "7"))
# Set true once served over HTTPS (tailscale serve) so the cookie is Secure-only.
SECURE_COOKIES = os.environ.get("DECK_SECURE_COOKIES", "0") == "1"


def _load() -> dict:
    if SECRET_FILE.exists():
        return json.loads(SECRET_FILE.read_text())
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    data = {"jwt_key": secrets.token_hex(32), "pin": None}
    SECRET_FILE.write_text(json.dumps(data))
    SECRET_FILE.chmod(0o600)
    return data


_state = _load()
JWT_KEY: str = _state["jwt_key"]


def hash_pin(pin: str, salt: str | None = None) -> dict:
    salt = salt or secrets.token_hex(16)
    digest = hashlib.scrypt(pin.encode(), salt=salt.encode(), n=2**14, r=8, p=1).hex()
    return {"salt": salt, "digest": digest}


def set_pin(pin: str) -> None:
    _state["pin"] = hash_pin(pin)
    SECRET_FILE.write_text(json.dumps(_state))
    SECRET_FILE.chmod(0o600)


def check_pin(pin: str) -> bool:
    rec = _state.get("pin")
    if not rec:
        return False
    candidate = hash_pin(pin, rec["salt"])["digest"]
    return hmac.compare_digest(candidate, rec["digest"])


def pin_is_set() -> bool:
    return bool(_state.get("pin"))
