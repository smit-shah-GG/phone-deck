"""Privileged action registry for deckd.

Each action is an explicit entry: a fixed argv (a list, never a shell string) plus
an optional parameter schema. The daemon only runs actions present here, and only
with parameters that pass validation. There is no path from a web request to an
arbitrary command — the web app sends an action *name* and validated params, and
this table decides what actually executes.
"""

from __future__ import annotations

# Engage-only performance mode — mirrors the `perfmode on` fish ritual as one fixed,
# param-free command (constant string -> no injection surface). Steps are chained with
# `;` so a missing tool (e.g. nvidia-smi) doesn't block the rest.
_PERFMODE_ON = (
    "cpupower frequency-set -g performance; "
    "echo 0 > /sys/devices/system/cpu/intel_pstate/no_turbo; "
    "nvidia-smi -pm 1; "
    "nvidia-smi -lgc 0,2100; "
    "sysctl -q vm.swappiness=10"
)


# name -> {build: argv-builder(params)->list[str], schema: {param: type}, desc}
ACTIONS = {
    "ping": {
        "build": lambda p: ["true"],
        "schema": {},
        "desc": "Health check — no-op, confirms the daemon is reachable.",
    },
    "perfmode": {
        "build": lambda p: ["sh", "-c", _PERFMODE_ON],
        "schema": {},
        "desc": "Engage performance mode: governor=performance, turbo on, GPU persistence "
                "+ clocks locked, swappiness=10. Engage-only (no off).",
    },
    "suspend": {
        "build": lambda p: ["systemctl", "suspend"],
        "schema": {},
        "desc": "Suspend the host.",
    },
    "reboot": {
        "build": lambda p: ["systemctl", "reboot"],
        "schema": {},
        "desc": "Reboot the host.",
    },
    "poweroff": {
        "build": lambda p: ["systemctl", "poweroff"],
        "schema": {},
        "desc": "Power off the host.",
    },
    "nm_restart": {
        "build": lambda p: ["systemctl", "restart", "NetworkManager"],
        "schema": {},
        "desc": "Restart NetworkManager.",
    },
    # New privileged actions go here. Keep argv as a list; validate every param.
}


def validate(name: str, params: dict) -> list[str]:
    """Return the argv for `name`+`params`, or raise on anything not allowlisted."""
    if name not in ACTIONS:
        raise KeyError(f"unknown action: {name!r}")
    spec = ACTIONS[name]
    params = params or {}
    for key, typ in spec["schema"].items():
        if key not in params:
            raise ValueError(f"missing param: {key}")
        try:
            typ(params[key])
        except (TypeError, ValueError):
            raise ValueError(f"param {key} must be {typ.__name__}")
    extra = set(params) - set(spec["schema"])
    if extra:
        raise ValueError(f"unexpected params: {sorted(extra)}")
    return spec["build"](params)
