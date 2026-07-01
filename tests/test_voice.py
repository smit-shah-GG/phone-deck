"""Voice router — the routing contract (pure functions, no capture/dispatch)."""

import asyncio

from app import voice


def test_verb_exact_alias_case_and_punctuation():
    aliases = {"jarvis": ["travis", "jervis"]}
    assert voice.match_verb("macro", {}) == "macro"
    assert voice.match_verb("Type", {}) == "type"           # case-insensitive
    assert voice.match_verb("input,", {}) == "input"          # trailing punctuation stripped
    assert voice.match_verb("travis", aliases) == "jarvis"    # alias
    assert voice.match_verb("hello", {}) is None              # not a verb -> no route


def test_ascii_fold_whisper_punctuation():
    assert voice.ascii_fold("it’s “great” — really…") == 'it\'s "great" - really...'


def test_input_vocab_matches_most_specific():
    vocab = voice.DEFAULT["input"]
    assert voice.match_input("copy", vocab) == "copy"
    assert voice.match_input("terminal copy", vocab) == "terminal copy"   # must beat "copy"
    assert voice.match_input("select all", vocab) == "select all"
    assert voice.match_input("xyzzy nonsense", vocab) is None             # below cutoff -> no match


def test_macro_match_and_confirm_flag():
    ns = [
        {"label": "Work", "kind": "mode", "id": "work", "confirm": False},
        {"label": "Free", "kind": "mode", "id": "free", "confirm": False},
        {"label": "Redeploy site", "kind": "command", "id": "redeploy", "confirm": True},
    ]
    assert voice.match_macro("work", ns)["id"] == "work"
    assert voice.match_macro("redeploy site", ns)["confirm"] is True
    assert voice.match_macro("absolutely unrelated phrase", ns) is None


def test_plan_produces_executable_descriptor_without_dispatch():
    # plan() computes what WOULD run but never dispatches (the confirm-before-execute split).
    p = asyncio.run(voice.plan("type hello world"))
    assert p["plan"] == {"lane": "type", "text": "hello world"}
    p = asyncio.run(voice.plan("input copy"))
    assert p["plan"]["lane"] == "input" and p["plan"]["keys"] == ["ctrl", "c"]
    p = asyncio.run(voice.plan("gibberish nothing here"))
    assert p["plan"] is None


def test_plan_jarvis_grammar():
    # jarvis rides the confirm flow: plan() parses the grammar but never calls the LLM.
    p = asyncio.run(voice.plan("jarvis what is the capital of france"))
    assert p["plan"] == {"lane": "jarvis", "query": "what is the capital of france", "web": False}
    p = asyncio.run(voice.plan("jarvis search latest python release"))
    assert p["plan"]["web"] is True and p["plan"]["query"] == "latest python release"
    p = asyncio.run(voice.plan("jarvis reset"))
    assert p["plan"] == {"lane": "jarvis", "reset": True}
