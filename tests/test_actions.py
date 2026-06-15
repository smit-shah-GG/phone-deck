"""The deckd privileged-action allowlist — the project's core security boundary."""

import actions
import pytest


def test_known_actions_build_exact_argv():
    assert actions.validate("ping", {}) == ["true"]
    assert actions.validate("governor_performance", {}) == [
        "cpupower", "frequency-set", "-g", "performance"]
    assert actions.validate("gpu_power_limit", {"watts": 150}) == ["nvidia-smi", "-pl", "150"]


def test_unknown_action_rejected():
    with pytest.raises(KeyError):
        actions.validate("rm_rf", {})


@pytest.mark.parametrize("params", [{"watts": 999}, {"watts": 50}, {}])
def test_gpu_power_limit_out_of_range_or_missing(params):
    with pytest.raises(ValueError):
        actions.validate("gpu_power_limit", params)


def test_injection_string_is_not_an_int():
    with pytest.raises(ValueError):
        actions.validate("gpu_power_limit", {"watts": "140; reboot"})


def test_unexpected_params_rejected():
    with pytest.raises(ValueError):
        actions.validate("ping", {"x": 1})


def test_argv_is_always_a_list_never_shell():
    # every action builds a list (no shell string the caller could inject into)
    for name in actions.ACTIONS:
        params = {"watts": 120} if name == "gpu_power_limit" else {}
        assert isinstance(actions.validate(name, params), list)
