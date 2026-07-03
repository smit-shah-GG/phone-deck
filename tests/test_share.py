"""Send-to-Rig classify(): 'share = paste-ready; a bare link opens' — the routing
rules that decide clipboard vs xdg-open. Scheme allowlist is load-bearing (a shared
"URL" can be file:// or intent:// — must never reach xdg-open)."""

from app.share import classify


def test_bare_url_in_url_param_opens():
    assert classify("https://youtu.be/x", "") == ("open", "https://youtu.be/x")


def test_bare_url_in_text_opens():
    # Android apps habitually put the URL in the text field
    assert classify("", "https://example.com/a?b=c") == ("open", "https://example.com/a?b=c")


def test_bare_url_survives_whitespace():
    assert classify("", "  https://a.b/c  ") == ("open", "https://a.b/c")


def test_http_also_allowed():
    assert classify("http://a.b", "")[0] == "open"


def test_text_with_embedded_url_copies_verbatim():
    # no clever extraction, no surprise browser windows
    t = "check this https://a.b/c out"
    assert classify("", t) == ("copy", t)


def test_plain_text_copies():
    assert classify("", "some words") == ("copy", "some words")


def test_file_scheme_never_opens():
    assert classify("file:///etc/passwd", "") == ("copy", "file:///etc/passwd")


def test_intent_scheme_never_opens():
    kind, _ = classify("intent://scan/#Intent;end", "fallback words")
    assert kind == "copy"


def test_text_preferred_over_nonhttp_url_for_copy():
    assert classify("intent://x", "words") == ("copy", "words")


def test_empty_share_is_none():
    assert classify("", "") is None
    assert classify(None, None) is None
