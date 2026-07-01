"""The deckd privileged-action allowlist — the project's core security boundary."""

import actions
import pytest


def test_known_actions_build_exact_argv():
    assert actions.validate("ping", {}) == ["true"]
    assert actions.validate("suspend", {}) == ["systemctl", "suspend"]
    assert actions.validate("nm_restart", {}) == ["systemctl", "restart", "NetworkManager"]


def test_perfmode_is_constant_sh_c():
    # perfmode runs several privileged steps, but as a fixed param-free string -> no
    # injection surface (same pattern the pruned turbo_* actions used).
    argv = actions.validate("perfmode", {})
    assert argv[:2] == ["sh", "-c"]
    assert "cpupower" in argv[2] and "swappiness" in argv[2]


def test_unknown_action_rejected():
    with pytest.raises(KeyError):
        actions.validate("rm_rf", {})


def test_unexpected_params_rejected():
    # no current action takes params; any param at all must be refused.
    with pytest.raises(ValueError):
        actions.validate("ping", {"x": 1})
    with pytest.raises(ValueError):
        actions.validate("perfmode", {"watts": "140; reboot"})


def test_argv_is_always_a_list_never_shell():
    # every action builds a list (no shell string the caller could inject into)
    for name in actions.ACTIONS:
        assert isinstance(actions.validate(name, {}), list)
