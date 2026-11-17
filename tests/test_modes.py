"""Modes config parsing (the hyprctl execution path needs a live session, not tested)."""

import json

from app import modes


def test_listing_reads_modes(tmp_path, monkeypatch):
    p = tmp_path / "modes.json"
    p.write_text(json.dumps({
        "work": {"label": "Work", "steps": []},
        "free": {"label": "Free", "steps": []},
    }))
    monkeypatch.setattr(modes, "MODES_FILE", p)
    listed = modes.listing()
    assert {m["id"] for m in listed} == {"work", "free"}
    assert {m["label"] for m in listed} == {"Work", "Free"}


def test_label_falls_back_to_id(tmp_path, monkeypatch):
    p = tmp_path / "modes.json"
    p.write_text(json.dumps({"solo": {"steps": []}}))
    monkeypatch.setattr(modes, "MODES_FILE", p)
    assert modes.listing() == [{"id": "solo", "label": "solo"}]


def test_missing_file_is_empty(tmp_path, monkeypatch):
    monkeypatch.setattr(modes, "MODES_FILE", tmp_path / "nope.json")
    assert modes.listing() == []


def test_bad_json_is_empty(tmp_path, monkeypatch):
    p = tmp_path / "modes.json"
    p.write_text("{ not json")
    monkeypatch.setattr(modes, "MODES_FILE", p)
    assert modes.listing() == []


def _run_with(monkeypatch, clients, steps):
    import asyncio
    moved, execs = [], []
    async def fake_clients():
        return clients
    async def fake_move(addr, ws):
        moved.append((addr, ws)); return True
    async def fake_exec(cmd):
        execs.append(cmd); return True
    monkeypatch.setattr(modes, "_clients", fake_clients)
    monkeypatch.setattr(modes, "_move_to_ws", fake_move)
    monkeypatch.setattr(modes, "_exec", fake_exec)
    monkeypatch.setattr(modes, "_load", lambda: {"m": {"steps": steps}})
    asyncio.run(modes.run("m"))
    return moved, execs


def test_once_leaves_windows_alone_when_one_is_already_there(monkeypatch):
    # two Opera GX windows (ws 8 and ws 3): the mode wants one on ws 3 → nothing moves
    clients = [{"class": "Opera GX", "address": "0xa", "workspace": {"id": 8}},
               {"class": "Opera GX", "address": "0xb", "workspace": {"id": 3}}]
    moved, execs = _run_with(monkeypatch, clients, [{"type": "launch", "workspace": 3, "match": "Opera GX", "once": True, "cmd": "opera-gx"}])
    assert moved == [] and execs == []


def test_once_brings_one_over_when_none_is_there(monkeypatch):
    clients = [{"class": "Opera GX", "address": "0xa", "workspace": {"id": 8}}]
    moved, execs = _run_with(monkeypatch, clients, [{"type": "launch", "workspace": 3, "match": "Opera GX", "once": True, "cmd": "opera-gx"}])
    assert moved == [("0xa", 3)] and execs == []
