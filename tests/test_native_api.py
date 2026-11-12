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


def test_apk_without_the_cookie_bounces_then_asks_for_the_pin(tmp_path, monkeypatch):
    # opened from a link in another app, the Strict cookie stays behind: bounce once same-site
    monkeypatch.setattr(config, "APK_DIR", tmp_path)
    _anon()
    r = client.get("/app.apk", follow_redirects=False)
    assert r.status_code == 200 and 'location.replace("/app.apk?b=1")' in r.text
    r = client.get("/app.apk?b=1", follow_redirects=False)            # still no cookie: signed out
    assert r.status_code == 303 and r.headers["location"] == "/login?next=/app.apk"
    _authed()
    assert client.get("/app.apk").status_code == 404                  # signed in, nothing published


def test_login_returns_only_to_the_apk(monkeypatch):
    monkeypatch.setattr(config, "check_pin", lambda p: p == "1234")
    monkeypatch.setattr(auth, "login_locked", lambda: 0)
    _anon()
    r = client.post("/login", data={"pin": "1234", "next": "/app.apk"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/app.apk"
    _anon()
    r = client.post("/login", data={"pin": "1234", "next": "https://evil.example/x"}, follow_redirects=False)
    assert r.headers["location"] == "/"                               # no open redirect
    _anon()
    r = client.post("/login", data={"pin": "1234"}, follow_redirects=False)
    assert r.headers["location"] == "/"                               # the native app's login is unchanged
    _anon()
    r = client.post("/login", data={"pin": "0000", "next": "/app.apk"}, follow_redirects=False)
    assert r.headers["location"] == "/login?bad=1&next=/app.apk"


def test_apk_meta_reports_the_published_build(tmp_path, monkeypatch):
    monkeypatch.setattr(config, "APK_DIR", tmp_path)
    _anon()
    assert client.get("/app.apk.json").status_code == 401
    _authed()
    assert client.get("/app.apk.json").status_code == 404
    (tmp_path / "archon.apk").write_bytes(b"PK")
    (tmp_path / "archon.apk.json").write_text('{"version": "0.1.84", "code": 84}')
    assert client.get("/app.apk.json").json() == {"version": "0.1.84", "code": 84}
    (tmp_path / "archon.apk.json").write_text('{"version": "0.1.7"}')      # an old release.sh: no code
    assert client.get("/app.apk.json").json() == {"version": "0.1.7", "code": 0}


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


# ---- headless mode (#131): status from headless.sh's state file; on/off delegated to it ----

def test_headless_status_reads_the_state_file(tmp_path, monkeypatch):
    from app import headless
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    assert headless.status()["on"] is False
    (tmp_path / "headless.state").write_text("1794390000\n")
    st = headless.status()
    assert st["on"] is True and st["since"] == 1794390000


def test_state_carries_headless(monkeypatch, tmp_path):
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    (tmp_path / "headless.state").write_text("5")
    _authed()
    assert client.get("/state").json()["headless"]["on"] is True


def test_headless_route_runs_the_script(tmp_path, monkeypatch):
    from app import headless
    calls = tmp_path / "calls"
    script = tmp_path / "headless.sh"
    script.write_text(f'#!/bin/sh\necho "$1" >> {calls}\necho "headless $1"\n')
    script.chmod(0o755)
    monkeypatch.setattr(headless, "SCRIPT", script)
    monkeypatch.setenv("XDG_RUNTIME_DIR", str(tmp_path))
    _anon()
    assert client.post("/headless", json={"on": True}).status_code == 401
    _authed()
    r = client.post("/headless", json={"on": True}).json()
    assert r["ok"] is True and r["output"] == "headless on"
    client.post("/headless", json={"on": False})
    assert calls.read_text().split() == ["on", "off"]


def test_headless_route_without_the_script(tmp_path, monkeypatch):
    from app import headless
    monkeypatch.setattr(headless, "SCRIPT", tmp_path / "missing.sh")
    _authed()
    r = client.post("/headless", json={"on": True}).json()
    assert r["ok"] is False and "no headless script" in r["error"]
