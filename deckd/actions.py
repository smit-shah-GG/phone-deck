"""Privileged action registry for deckd.

Each action is an explicit entry: a fixed argv (a list, never a shell string) plus
an optional parameter schema. The daemon only runs actions present here, and only
with parameters that pass validation. There is no path from a web request to an
arbitrary command — the web app sends an action *name* and validated params, and
this table decides what actually executes.
"""

from __future__ import annotations

GPU_MIN_WATTS = 100
GPU_MAX_WATTS = 170  # adjust to your GPU's max (see `nvidia-smi -q -d POWER`)


def _gpu_power_limit(params):
    watts = int(params["watts"])
    if not (GPU_MIN_WATTS <= watts <= GPU_MAX_WATTS):
        raise ValueError(f"watts out of range [{GPU_MIN_WATTS}, {GPU_MAX_WATTS}]")
    return ["nvidia-smi", "-pl", str(watts)]


# name -> {build: argv-builder(params)->list[str], schema: {param: type}, desc}
ACTIONS = {
    "ping": {
        "build": lambda p: ["true"],
        "schema": {},
        "desc": "Health check — no-op, confirms the daemon is reachable.",
    },
    "governor_performance": {
        "build": lambda p: ["cpupower", "frequency-set", "-g", "performance"],
        "schema": {},
        "desc": "Set intel_pstate governor to performance.",
    },
    "governor_powersave": {
        "build": lambda p: ["cpupower", "frequency-set", "-g", "powersave"],
        "schema": {},
        "desc": "Set intel_pstate governor to powersave.",
    },
    "gpu_power_limit": {
        "build": _gpu_power_limit,
        "schema": {"watts": int},
        "desc": f"Set RTX 3060 power limit ({GPU_MIN_WATTS}-{GPU_MAX_WATTS}W).",
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
    # Constant sh -c strings (no params, no injection surface) — sysfs needs a redirect.
    "turbo_on": {
        "build": lambda p: ["sh", "-c", "echo 0 > /sys/devices/system/cpu/intel_pstate/no_turbo"],
        "schema": {},
        "desc": "Enable Intel turbo boost.",
    },
    "turbo_off": {
        "build": lambda p: ["sh", "-c", "echo 1 > /sys/devices/system/cpu/intel_pstate/no_turbo"],
        "schema": {},
        "desc": "Disable Intel turbo boost.",
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
