"""Remote-input keymap + capabilities (pure logic; no uinput device created)."""

from evdev import ecodes as e

from app import hid


def test_capabilities_have_rel_and_keys():
    caps = hid._capabilities()
    assert e.REL_X in caps[e.EV_REL] and e.REL_WHEEL in caps[e.EV_REL]
    assert e.BTN_LEFT in caps[e.EV_KEY] and e.KEY_A in caps[e.EV_KEY]


def test_char_map_covers_basics():
    assert hid.CHAR_MAP["a"] == ("KEY_A", False)
    assert hid.CHAR_MAP["A"] == ("KEY_A", True)
    assert hid.CHAR_MAP["5"] == ("KEY_5", False)
    assert hid.CHAR_MAP["!"] == ("KEY_1", True)
    assert hid.CHAR_MAP[" "] == ("KEY_SPACE", False)


def test_every_mapped_keyname_resolves_to_an_ecode():
    for kn, _ in hid.CHAR_MAP.values():
        assert isinstance(hid._code(kn), int)
    for kn in list(hid.SPECIAL.values()) + list(hid.MODS.values()) + list(hid.BUTTONS.values()):
        assert isinstance(hid._code(kn), int)
