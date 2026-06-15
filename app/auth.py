"""JWT issue/verify and a FastAPI dependency that gates protected routes."""

from __future__ import annotations

import datetime as dt
import time

import jwt
from fastapi import Cookie, HTTPException, WebSocket

from . import config

COOKIE_NAME = "deck_session"
ALGO = "HS256"

# Global bad-PIN lockout (single-user deck; tailscale serve hides the real client IP
# behind localhost, so per-IP limiting wouldn't be reliable anyway). After
# LOCK_THRESHOLD consecutive failures, lock with exponential backoff.
LOCK_THRESHOLD = 5
_LOCK_BASE = 30          # seconds
_LOCK_MAX = 900          # 15 min cap
_fails = {"count": 0, "until": 0.0}


def login_locked() -> float:
    """Seconds remaining on the lockout, or 0.0 if not locked."""
    return max(0.0, _fails["until"] - time.monotonic())


def record_failure() -> None:
    _fails["count"] += 1
    if _fails["count"] >= LOCK_THRESHOLD:
        extra = _fails["count"] - LOCK_THRESHOLD
        _fails["until"] = time.monotonic() + min(_LOCK_BASE * (2 ** extra), _LOCK_MAX)


def record_success() -> None:
    _fails["count"] = 0
    _fails["until"] = 0.0


def issue_token() -> str:
    now = dt.datetime.now(dt.timezone.utc)
    payload = {"sub": "deck", "iat": now, "exp": now + dt.timedelta(days=config.JWT_TTL_DAYS)}
    return jwt.encode(payload, config.JWT_KEY, algorithm=ALGO)


def _valid(token: str | None) -> bool:
    if not token:
        return False
    try:
        jwt.decode(token, config.JWT_KEY, algorithms=[ALGO])
        return True
    except jwt.PyJWTError:
        return False


def require_auth(deck_session: str | None = Cookie(default=None)):
    """HTTP dependency: 401 unless a valid session cookie is present."""
    if not _valid(deck_session):
        raise HTTPException(status_code=401, detail="auth required")
    return True


async def ws_authed(ws: WebSocket) -> bool:
    """WebSocket gate — checks the session cookie sent on the upgrade request."""
    return _valid(ws.cookies.get(COOKIE_NAME))
