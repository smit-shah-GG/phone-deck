"""Remote keyboard/mouse via a kernel virtual input device (python-evdev UInput).

Works under Wayland/Hyprland because uinput is below the compositor. Runs as the
web-app user (member of the `input` group → /dev/uinput is rw), so no root/deckd.

The WS handler calls handle() with small JSON events: move/scroll/click/text/key/combo.
Text typing maps ASCII via a US layout (good enough for a remote keyboard; non-ASCII
unicode isn't supported by uinput keycodes).
"""

from __future__ import annotations

import threading

from evdev import UInput, ecodes as e

# char -> (ecode name, needs shift)
CHAR_MAP: dict[str, tuple[str, bool]] = {}
for _c in "abcdefghijklmnopqrstuvwxyz":
    CHAR_MAP[_c] = (f"KEY_{_c.upper()}", False)
    CHAR_MAP[_c.upper()] = (f"KEY_{_c.upper()}", True)
for _d in "0123456789":
    CHAR_MAP[_d] = (f"KEY_{_d}", False)
CHAR_MAP.update({
    " ": ("KEY_SPACE", False), "\t": ("KEY_TAB", False), "\n": ("KEY_ENTER", False),
    "`": ("KEY_GRAVE", False), "-": ("KEY_MINUS", False), "=": ("KEY_EQUAL", False),
    "[": ("KEY_LEFTBRACE", False), "]": ("KEY_RIGHTBRACE", False), "\\": ("KEY_BACKSLASH", False),
    ";": ("KEY_SEMICOLON", False), "'": ("KEY_APOSTROPHE", False),
    ",": ("KEY_COMMA", False), ".": ("KEY_DOT", False), "/": ("KEY_SLASH", False),
    "~": ("KEY_GRAVE", True), "!": ("KEY_1", True), "@": ("KEY_2", True), "#": ("KEY_3", True),
    "$": ("KEY_4", True), "%": ("KEY_5", True), "^": ("KEY_6", True), "&": ("KEY_7", True),
    "*": ("KEY_8", True), "(": ("KEY_9", True), ")": ("KEY_0", True), "_": ("KEY_MINUS", True),
    "+": ("KEY_EQUAL", True), "{": ("KEY_LEFTBRACE", True), "}": ("KEY_RIGHTBRACE", True),
    "|": ("KEY_BACKSLASH", True), ":": ("KEY_SEMICOLON", True), '"': ("KEY_APOSTROPHE", True),
    "<": ("KEY_COMMA", True), ">": ("KEY_DOT", True), "?": ("KEY_SLASH", True),
})

SPECIAL = {
    "enter": "KEY_ENTER", "esc": "KEY_ESC", "tab": "KEY_TAB", "backspace": "KEY_BACKSPACE",
    "delete": "KEY_DELETE", "up": "KEY_UP", "down": "KEY_DOWN", "left": "KEY_LEFT",
    "right": "KEY_RIGHT", "home": "KEY_HOME", "end": "KEY_END", "pageup": "KEY_PAGEUP",
    "pagedown": "KEY_PAGEDOWN", "space": "KEY_SPACE",
}
MODS = {"ctrl": "KEY_LEFTCTRL", "alt": "KEY_LEFTALT", "shift": "KEY_LEFTSHIFT", "super": "KEY_LEFTMETA"}
BUTTONS = {"left": "BTN_LEFT", "right": "BTN_RIGHT", "middle": "BTN_MIDDLE"}


def _code(name: str) -> int:
    return getattr(e, name)


def _capabilities() -> dict:
    keys = set()
    for kn, _ in CHAR_MAP.values():
        keys.add(_code(kn))
    for kn in list(SPECIAL.values()) + list(MODS.values()) + list(BUTTONS.values()):
        keys.add(_code(kn))
    return {e.EV_KEY: sorted(keys), e.EV_REL: [e.REL_X, e.REL_Y, e.REL_WHEEL, e.REL_HWHEEL]}


_ui: UInput | None = None
_lock = threading.Lock()


def _device() -> UInput:
    global _ui
    if _ui is None:
        _ui = UInput(_capabilities(), name="phone-deck-virtual-input")
    return _ui


def available() -> bool:
    try:
        _device()
        return True
    except Exception:  # noqa: BLE001 — any uinput/permission failure means "not available"
        return False


def _tap(code: int, mods: tuple[str, ...] = ()) -> None:
    ui = _device()
    for m in mods:
        ui.write(e.EV_KEY, _code(MODS[m]), 1)
    ui.write(e.EV_KEY, code, 1)
    ui.syn()
    ui.write(e.EV_KEY, code, 0)
    for m in reversed(mods):
        ui.write(e.EV_KEY, _code(MODS[m]), 0)
    ui.syn()


def button(name: str, down: bool) -> None:
    """Press/release a mouse button without moving (for click + drag on streamed video)."""
    with _lock:
        ui = _device()
        ui.write(e.EV_KEY, _code(BUTTONS.get(name, "BTN_LEFT")), 1 if down else 0)
        ui.syn()


def handle(msg: dict) -> None:
    t = msg.get("t")
    with _lock:
        ui = _device()
        if t == "move":
            ui.write(e.EV_REL, e.REL_X, int(msg.get("dx", 0)))
            ui.write(e.EV_REL, e.REL_Y, int(msg.get("dy", 0)))
            ui.syn()
        elif t == "scroll":
            ui.write(e.EV_REL, e.REL_WHEEL, int(msg.get("dy", 0)))
            if msg.get("dx"):
                ui.write(e.EV_REL, e.REL_HWHEEL, int(msg["dx"]))
            ui.syn()
        elif t == "click":
            c = _code(BUTTONS.get(msg.get("b", "left"), "BTN_LEFT"))
            ui.write(e.EV_KEY, c, 1)
            ui.syn()
            ui.write(e.EV_KEY, c, 0)
            ui.syn()
        elif t == "text":
            for ch in str(msg.get("s", "")):
                hit = CHAR_MAP.get(ch)
                if hit:
                    kn, sh = hit
                    _tap(_code(kn), ("shift",) if sh else ())
        elif t == "key":
            name = msg.get("name", "")
            if name in SPECIAL:
                _tap(_code(SPECIAL[name]), tuple(m for m in msg.get("mods", []) if m in MODS))
        elif t == "combo":
            keys = [k for k in msg.get("keys", [])]
            if keys:
                *mods, last = keys
                mods = tuple(m for m in mods if m in MODS)
                if last in SPECIAL:
                    _tap(_code(SPECIAL[last]), mods)
                elif last in MODS:
                    _tap(_code(MODS[last]), mods)
                elif last in CHAR_MAP:
                    kn, sh = CHAR_MAP[last]
                    _tap(_code(kn), mods + (("shift",) if sh else ()))
