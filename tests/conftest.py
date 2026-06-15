"""Test setup: isolate the config dir (so tests never touch the real ~/.config/
phone-deck) and make the stdlib-only deckd package importable as the daemon runs it.

This runs at collection time, before any app module imports config.
"""

import os
import sys
import tempfile
from pathlib import Path

os.environ.setdefault("DECK_CONFIG_DIR", tempfile.mkdtemp(prefix="phone-deck-test-"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "deckd"))
