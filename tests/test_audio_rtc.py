"""Audio/video streaming pure logic — quality presets and the wf-recorder command.

(The live WebRTC paths are validated by loopback during development, not here — they
need a display, PipeWire, and a Hyprland output.)
"""

from app import audio_rtc


def test_quality_presets():
    assert set(audio_rtc.QUALITY) == {"low", "medium", "high"}
    assert audio_rtc.QUALITY["low"] == ("960:540", 20)
    assert audio_rtc.QUALITY["medium"] == ("1280:720", 24)
    assert audio_rtc.QUALITY["high"] == (None, 30)


def test_wf_cmd_native_has_no_scale_filter():
    cmd = audio_rtc.wf_cmd("DP-3", None, 30)
    assert cmd[cmd.index("-o") + 1] == "DP-3"
    assert cmd[cmd.index("-r") + 1] == "30"
    assert "-F" not in cmd


def test_wf_cmd_applies_scale_and_fps():
    cmd = audio_rtc.wf_cmd("HDMI-A-1", "960:540", 20)
    assert cmd[cmd.index("-o") + 1] == "HDMI-A-1"
    assert cmd[cmd.index("-r") + 1] == "20"
    assert cmd[cmd.index("-F") + 1] == "scale=960:540"


def test_quality_feeds_wf_cmd():
    scale, fps = audio_rtc.QUALITY["medium"]
    cmd = audio_rtc.wf_cmd("DP-3", scale, fps)
    assert "scale=1280:720" in cmd and cmd[cmd.index("-r") + 1] == "24"
