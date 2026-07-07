"""Fleet roster parsing + tailscale online-map (pure parts; no subprocess).
Fixtures use a placeholder tailnet — the real one never appears in the repo."""

import json

from app import config, fleet

TS = {
    "Self": {"HostName": "lightning", "DNSName": "lightning.example.ts.net.", "Online": True},
    "Peer": {
        "nk1": {"HostName": "raptor", "DNSName": "raptor.example.ts.net.", "Online": True},
        "nk2": {"HostName": "blackbird", "DNSName": "blackbird.example.ts.net.", "Online": False},
    },
}


def test_online_from_includes_self_and_peers():
    assert fleet._online_from(TS) == {"lightning": True, "raptor": True, "blackbird": False}


def test_online_from_empty():
    assert fleet._online_from({}) == {}


def test_roster_reads_hosts_and_drops_incomplete():
    f = config.CONFIG_DIR / "fleet.json"
    f.write_text(json.dumps({"hosts": [
        {"name": "raptor", "label": "F-22 · raptor", "url": "https://raptor.example.ts.net"},
        {"name": "nourl"},                  # missing url -> dropped
        {"url": "https://x.example.ts.net"},  # missing name -> dropped
    ]}))
    try:
        assert [h["name"] for h in fleet._roster()] == ["raptor"]
    finally:
        f.unlink(missing_ok=True)


def test_roster_missing_file():
    (config.CONFIG_DIR / "fleet.json").unlink(missing_ok=True)
    assert fleet._roster() == []
