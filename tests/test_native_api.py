"""Server support for the native app (tasks #119): token refresh, palette JSON, /state, /app.apk."""

import json

import pytest
from fastapi.testclient import TestClient

from app import auth, config, main, theme

# No `with`: startup hooks (Hyprland watchers, telemetry loops) never run in tests.
client = TestClient(main.app)


def _authed():
    client.cookies.set(auth.COOKIE_NAME, auth.issue_token())


def _anon():
    client.cookies.clear()


def test_refresh_needs_a_valid_session():
    _anon()
    assert client.post("/auth/refresh").status_code == 401
    client.cookies.set(auth.COOKIE_NAME, "garbage")
    assert client.post("/auth/refresh").status_code == 401


def test_refresh_returns_a_fresh_token_and_sets_the_cookie():
    _authed()
    r = client.post("/auth/refresh")
    assert r.status_code == 200
    body = r.json()
    assert auth._valid(body["token"])
    assert body["exp"] == auth.token_exp(body["token"]) > 0
    assert auth.COOKIE_NAME in r.cookies and r.cookies[auth.COOKIE_NAME] == body["token"]
    cookie = r.headers["set-cookie"].lower()
    assert "httponly" in cookie and "samesite=strict" in cookie


def test_token_exp_of_garbage_is_zero():
    assert auth.token_exp("garbage") == 0


def test_palette_is_public_and_matches_the_css():
    _anon()
    theme.set_profile("amber")
    theme.set_crt(False)
    r = client.get("/palette")
    assert r.status_code == 200
    p = r.json()
    assert p["profile"] == "amber" and p["crt"] is False
    css = theme.css()
    for k, v in p["colors"].items():
        assert f"--p-{k}:{v};" in css              # same values the PWA paints with
    assert {"bg", "primary", "secondary", "on", "outline"} <= set(p["colors"])
    theme.set_crt(True)
    theme.set_profile("green")


def test_state_needs_auth_and_returns_the_ws_snapshot(monkeypatch):
    _anon()
    assert client.get("/state").status_code == 401
    monkeypatch.setitem(main._state, "audio", {"mic_muted": True, "now_playing": {"status": "Playing"}})
    _authed()
    r = client.get("/state")
    assert r.status_code == 200
    s = r.json()
    assert set(main._state) <= set(s)
    assert s["audio"]["mic_muted"] is True


def test_apk_needs_auth_and_404s_until_published(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "APK_DIR", tmp_path)
    _anon()
    assert client.get("/app.apk").status_code == 401
    _authed()
    assert client.get("/app.apk").status_code == 404


@pytest.mark.parametrize("meta,name", [
    ('{"version": "0.1.7"}', "archon-0.1.7.apk"),
    ("not json", "archon-latest.apk"),
    ('{"version": "../../evil x"}', "archon-....evilx.apk"),   # path bits stripped from the filename
])
def test_apk_serves_the_published_build(tmp_path, monkeypatch, meta, name):
    monkeypatch.setattr(config, "APK_DIR", tmp_path)
    (tmp_path / "archon.apk").write_bytes(b"PK\x03\x04fake")
    (tmp_path / "archon.apk.json").write_text(meta)
    _authed()
    r = client.get("/app.apk")
    assert r.status_code == 200 and r.content == b"PK\x03\x04fake"
    assert r.headers["content-type"] == "application/vnd.android.package-archive"
    assert name in r.headers["content-disposition"]
    assert r.headers["cache-control"] == "no-store"
