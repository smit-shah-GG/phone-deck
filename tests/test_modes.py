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
