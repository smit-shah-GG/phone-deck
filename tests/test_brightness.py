"""ddcutil detect parsing — the capability-detection crux. A laptop eDP reports an
i2c bus but under an "Invalid display" header; recording it would land us in ddc-mode
where every setvcp silently fails. These lock that skip (pure parse, no subprocess)."""

from app import brightness

# Real `ddcutil detect --brief` from raptor (Iris Xe, eDP-1): a bus, but Invalid.
RAPTOR = """\
Invalid display
   I2C bus:          /dev/i2c-11
   DRM connector:    card1-eDP-1
   drm_connector_id: 582
   Monitor:          LGD::
"""

# Lightning-shape: three valid external DDC monitors.
LIGHTNING = """\
Display 1
   I2C bus:          /dev/i2c-4
   DRM connector:    card1-DP-3
   Monitor:          LEN:LEN P24h-20:
Display 2
   I2C bus:          /dev/i2c-5
   DRM connector:    card1-HDMI-A-3
   Monitor:          DEL:DELL:
Display 3
   I2C bus:          /dev/i2c-6
   DRM connector:    card1-HDMI-A-1
   Monitor:          SAM:Samsung:
"""


def test_invalid_display_is_skipped():
    # raptor's eDP has a bus (i2c-11) but is Invalid -> nothing recorded -> backlight fallback.
    assert brightness._parse_ddc(RAPTOR) == {}


def test_valid_displays_keyed_by_hypr_name():
    assert brightness._parse_ddc(LIGHTNING) == {"DP-3": 4, "HDMI-A-3": 5, "HDMI-A-1": 6}


def test_mixed_records_only_valid():
    mixed = LIGHTNING + RAPTOR
    parsed = brightness._parse_ddc(mixed)
    assert "eDP-1" not in parsed
    assert parsed == {"DP-3": 4, "HDMI-A-3": 5, "HDMI-A-1": 6}


def test_empty_input():
    assert brightness._parse_ddc("") == {}
