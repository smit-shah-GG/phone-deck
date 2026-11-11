"""Routes for the home-screen Remote widget (tasks #143): one input event over HTTP,
volume steps, and CH▲/▼ = next / previous existing workspace."""

import pytest
from fastapi.testclient import TestClient

from app import audio, auth, hid, hypr, main

client = TestClient(main.app)


def _authed():
    client.cookies.set(auth.COOKIE_NAME, auth.issue_token())


def test_routes_need_auth():
    client.cookies.clear()
    assert client.post("/input", json={"t": "click"}).status_code == 401
    assert client.post("/audio/volume", json={"step": 5}).status_code == 401
    assert client.post("/hypr/workspace", json={"rel": 1}).status_code == 401


def test_input_passes_discrete_events_to_hid(monkeypatch):
    got = []
    monkeypatch.setattr(hid, "handle", got.append)
    _authed()
    for ev in ({"t": "click", "b": "right"}, {"t": "combo", "keys": ["super"]}, {"t": "key", "name": "esc"}):
        assert client.post("/input", json=ev).json() == {"ok": True}
    assert got == [{"t": "click", "b": "right"}, {"t": "combo", "keys": ["super"]}, {"t": "key", "name": "esc"}]


def test_input_refuses_continuous_events_and_reports_no_uinput(monkeypatch):
    _authed()
    assert client.post("/input", json={"t": "move", "dx": 5}).status_code == 400
    def boom(_):
        raise OSError("no /dev/uinput")
    monkeypatch.setattr(hid, "handle", boom)
    assert client.post("/input", json={"t": "click"}).json()["ok"] is False


@pytest.mark.parametrize("cur,step,want", [
    (50, 5, 55), (98, 5, 100), (100, 5, 100), (120, 5, 120), (120, -5, 115), (3, -5, 0), (100, -5, 95)])
def test_volume_steps(cur, step, want):
    assert audio.stepped(cur, step) == want


def test_volume_step_route(monkeypatch):
    async def out(*a):
        return "Volume: front-left: 40000 /  61% / -12.9 dB"
    sent = []
    async def setv(p):
        sent.append(p)
        return True
    monkeypatch.setattr(audio, "_out", out)
    monkeypatch.setattr(audio, "set_volume", setv)
    _authed()
    assert client.post("/audio/volume", json={"step": -5}).json() == {"ok": True}
    assert sent == [56]


@pytest.mark.parametrize("ids,cur,rel,want", [
    ([1, 2, 4], 2, 1, 4), ([1, 2, 4], 4, 1, 1), ([1, 2, 4], 1, -1, 4),
    ([3], 3, 1, 3), ([1, 22, 2], 2, 1, 1), ([], 7, 1, 7), ([], None, 1, None)])
def test_neighbour_ws(ids, cur, rel, want):
    assert hypr.neighbour_ws(ids, cur, rel) == want


def test_workspace_rel_route_focuses_the_neighbour_on_the_focused_monitor(monkeypatch):
    async def hj(what):
        return {"monitors": [{"name": "DP-1", "focused": False, "activeWorkspace": {"id": 6}},
                             {"name": "DP-3", "focused": True, "activeWorkspace": {"id": 2}}],
                "workspaces": [{"id": 1, "monitor": "DP-3"}, {"id": 2, "monitor": "DP-3"},
                               {"id": 5, "monitor": "DP-3"}, {"id": 6, "monitor": "DP-1"}]}[what]
    went = []
    async def focus(n):
        went.append(n)
        return True
    monkeypatch.setattr(hypr, "_hyprctl_json", hj)
    monkeypatch.setattr(hypr, "focus_workspace", focus)
    _authed()
    assert client.post("/hypr/workspace", json={"rel": 1}).json() == {"ok": True}
    assert client.post("/hypr/workspace", json={"rel": -1}).json() == {"ok": True}
    assert went == [5, 1]
