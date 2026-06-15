"""In-deck config editing: validation, round-trip, backup, name allowlist."""

import json

from app import cfgedit


def test_unknown_name_rejected():
    assert cfgedit.read("nope")["ok"] is False
    assert cfgedit.write("nope", "{}")["ok"] is False


def test_write_read_roundtrip():
    payload = json.dumps({"hello": [1, 2, 3]})
    assert cfgedit.write("commands", payload)["ok"] is True
    r = cfgedit.read("commands")
    assert r["ok"] and json.loads(r["text"]) == {"hello": [1, 2, 3]}


def test_invalid_json_rejected_and_original_preserved():
    cfgedit.write("commands", json.dumps([{"id": "x"}]))  # seed valid
    res = cfgedit.write("commands", "{ not json")
    assert res["ok"] is False and "invalid JSON" in res["error"]
    assert json.loads(cfgedit.read("commands")["text"]) == [{"id": "x"}]


def test_backup_holds_previous_contents():
    cfgedit.write("modes", json.dumps({"v": 1}))
    cfgedit.write("modes", json.dumps({"v": 2}))
    bak = cfgedit.EDITABLE["modes"].with_name("modes.json.bak")
    assert bak.exists() and json.loads(bak.read_text()) == {"v": 1}
