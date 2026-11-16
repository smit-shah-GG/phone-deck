"""hyprctl syntax detection (2026-11-16): on Hyprland 0.57 `keyword` answers "unknown request"
with rc 0; the old phrase guards read that as success, cached the classic syntax, and every
workspace/window switch from Archon failed silently."""

import asyncio

import pytest

from app import hypr


def fake_hyprctl(speaks_lua: bool):
    calls = []
    async def run(*argv):
        calls.append(argv)
        lua_form = argv[0] == "eval" or (argv[0] == "dispatch" and argv[1].startswith("hl."))
        if lua_form == speaks_lua:
            return 0, b"ok\n"
        if not speaks_lua:
            return 0, b"error: invalid dispatcher\n"
        return (0, b"unknown request\n") if argv[0] == "keyword" else (0, b"error: ')' expected\n")
    return run, calls


@pytest.mark.parametrize("out,ok", [(b"ok\n", True), (b"unknown request\n", False), (b"error: x\n", False),
                                    (b"can't work with non-legacy parsers. Use eval.\n", False), (b"", False)])
def test_only_a_literal_ok_is_success(out, ok):
    assert hypr._ok(0, out) is ok
    assert hypr._ok(1, b"ok") is False


def test_057_unknown_request_no_longer_pins_the_classic_syntax(monkeypatch):
    run, calls = fake_hyprctl(speaks_lua=True)
    monkeypatch.setattr(hypr, "_run", run)
    monkeypatch.setattr(hypr, "_USE_LUA", None)
    assert asyncio.run(hypr._set_workspace_rule(1, "DP-3", True)) is True     # the startup monitor map
    assert hypr._USE_LUA is True
    assert asyncio.run(hypr.focus_window("0xabc")) is True
    assert calls[-1][0] == "dispatch" and calls[-1][1].startswith("hl.dsp.focus")


def test_a_stale_cache_heals_itself(monkeypatch):
    run, calls = fake_hyprctl(speaks_lua=True)
    monkeypatch.setattr(hypr, "_run", run)
    monkeypatch.setattr(hypr, "_USE_LUA", False)                               # stuck on classic (the bug)
    assert asyncio.run(hypr.focus_window("0xabc")) is True
    assert hypr._USE_LUA is True


def test_old_hyprland_still_works(monkeypatch):
    run, calls = fake_hyprctl(speaks_lua=False)
    monkeypatch.setattr(hypr, "_run", run)
    monkeypatch.setattr(hypr, "_USE_LUA", None)
    assert asyncio.run(hypr.focus_window("0xabc")) is True
    assert hypr._USE_LUA is False
    assert asyncio.run(hypr._set_workspace_rule(2, "DP-3", False)) is True


def test_a_real_failure_is_reported(monkeypatch):
    async def run(*argv):
        return 0, b"error: no such window\n"
    monkeypatch.setattr(hypr, "_run", run)
    monkeypatch.setattr(hypr, "_USE_LUA", True)
    assert asyncio.run(hypr.focus_window("0xdead")) is False
