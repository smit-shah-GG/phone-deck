"""Hardware telemetry: GPU via nvidia-smi, CPU/memory via psutil.

These have no event stream, so the app polls and pushes deltas to the deck.
"""

from __future__ import annotations

import asyncio

import psutil

_GPU_QUERY = (
    "utilization.gpu,memory.used,memory.total,temperature.gpu,power.draw,power.limit"
)


async def gpu() -> dict | None:
    try:
        proc = await asyncio.create_subprocess_exec(
            "nvidia-smi", f"--query-gpu={_GPU_QUERY}",
            "--format=csv,noheader,nounits",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL,
        )
        try:
            # Bounded: a wedged nvidia-smi (driver hiccup mid-JAX) would otherwise
            # freeze the 2s poll loop forever — same class as the _broadcast freeze.
            out, _ = await asyncio.wait_for(proc.communicate(), timeout=5)
        except asyncio.TimeoutError:
            try:
                proc.kill()
            except ProcessLookupError:
                pass
            return None
        if proc.returncode != 0 or not out:
            return None
        util, vram_used, vram_total, temp, draw, limit = (
            x.strip() for x in out.decode().splitlines()[0].split(",")
        )
        return {
            "util": float(util),
            "vram_used": float(vram_used),
            "vram_total": float(vram_total),
            "temp": float(temp),
            "power": float(draw),
            "power_limit": float(limit),
        }
    except (OSError, ValueError):
        return None


def _cpu_temp() -> float | None:
    try:
        temps = psutil.sensors_temperatures()
    except (AttributeError, OSError):
        return None
    for key in ("coretemp", "k10temp", "acpitz"):
        if key in temps and temps[key]:
            return round(temps[key][0].current, 1)
    return None


async def cpu() -> dict:
    # non-blocking sample; first call after import primes the counter
    load = psutil.cpu_percent(interval=None)
    mem = psutil.virtual_memory()
    return {
        "util": load,
        "temp": _cpu_temp(),
        "mem_used": round(mem.used / 2**30, 1),
        "mem_total": round(mem.total / 2**30, 1),
    }


async def snapshot() -> dict:
    g, c = await asyncio.gather(gpu(), cpu())
    return {"gpu": g, "cpu": c}
