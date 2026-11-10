"""CRT effects toggle: theme.json `crt` drives an override appended to theme.css."""

from app import theme


def test_crt_defaults_on_and_css_has_no_override():
    theme._write_cfg(crt=True)
    assert theme.crt() is True
    assert "CRT effects OFF" not in theme.css()


def test_crt_off_appends_override_last_and_keeps_profile():
    theme.set_profile("amber")
    assert theme.set_crt(False) == {"ok": True, "crt": False}
    css = theme.css()
    assert css.rstrip().endswith("}") and "CRT effects OFF" in css
    assert css.index("CRT effects OFF") > css.index("body::after {")     # appended last: it wins
    assert "body::before, body::after { display:none !important; }" in css
    assert theme._profile() == "amber"                                 # merge-write, no clobber
    theme.set_crt(True)
    assert "CRT effects OFF" not in theme.css()


def test_crt_garbage_value_falls_back_to_on():
    theme._write_cfg(crt="yes please")
    assert theme.crt() is True
