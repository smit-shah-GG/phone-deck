"""PIN hashing + check (scrypt, constant-time compare)."""

from app import config


def test_hash_pin_is_deterministic_with_salt():
    rec = config.hash_pin("1234")
    again = config.hash_pin("1234", rec["salt"])
    assert again["digest"] == rec["digest"]


def test_hash_pin_salts_differ():
    assert config.hash_pin("1234")["salt"] != config.hash_pin("1234")["salt"]


def test_set_and_check_pin():
    config.set_pin("4321")
    assert config.pin_is_set()
    assert config.check_pin("4321")
    assert not config.check_pin("0000")
