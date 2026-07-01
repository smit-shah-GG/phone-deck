"""phone-deck web app.

- JWT login (PIN) -> session cookie.
- /ws pushes live state: Hyprland events (instant) + telemetry/audio (2s) + sysinfo (5s).
- Privileged ops go to deckd; audio/media/commands/session ops run as this user.
Serves plain HTTP on 127.0.0.1; put `tailscale serve` in front for HTTPS/WSS.
"""

from __future__ import annotations

import asyncio
import contextlib
import mimetypes
from pathlib import Path

# Chrome rejects a manifest served as octet-stream — register the right type.
mimetypes.add_type("application/manifest+json", ".webmanifest")

from fastapi import Depends, FastAPI, File, Form, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from . import audio, audio_rtc, auth, brightness, cfgedit, commands, config, context, deckclient, grab, hid, hypr, modes, sysinfo, telemetry, theme, voice

BASE = Path(__file__).parent
app = FastAPI(title="phone-deck")
app.mount("/static", StaticFiles(directory=BASE / "static"), name="static")
templates = Jinja2Templates(directory=BASE / "templates")

# ---- live state broadcast --------------------------------------------------
_clients: set[WebSocket] = set()
_state: dict = {"hypr": {}, "telemetry": {}, "audio": {}, "sysinfo": {}}
_mon_sig: tuple = ()   # last-seen monitor name set; remap when it changes


async def _broadcast():
    dead = set()
    for ws in _clients:
        try:
            await ws.send_json(_state)
        except (WebSocketDisconnect, RuntimeError):
            dead.add(ws)
    _clients.difference_update(dead)


async def _on_hypr_change():
    global _mon_sig
    _state["hypr"] = await hypr.snapshot()
    # Re-apply the dynamic monitor→workspace mapping whenever the set of monitors
    # changes (hotplug). Cheap signature check keeps it off the hot path of ws/focus events.
    sig = tuple(m["name"] for m in _state["hypr"].get("monitors", []))
    if sig and sig != _mon_sig:
        _mon_sig = sig
        await hypr.apply_monitor_mapping(_state["hypr"]["monitors"])
    await _broadcast()


async def _fast_loop():  # telemetry + audio
    while True:
        _state["telemetry"] = await telemetry.snapshot()
        _state["audio"] = await audio.snapshot()
        await _broadcast()
        await asyncio.sleep(2)


async def _slow_loop():  # tailscale + processes
    while True:
        _state["sysinfo"] = await sysinfo.snapshot()
        await _broadcast()
        await asyncio.sleep(5)


@app.on_event("startup")
async def _startup():
    global _mon_sig
    await audio_rtc.cleanup_stale()   # drop a virtual sink left by a hard crash
    _state["hypr"] = await hypr.snapshot()
    # Apply the monitor→workspace mapping the deck now owns (replaces the static
    # `workspace = N, monitor:…` binds that used to live in general.conf).
    _mon_sig = tuple(m["name"] for m in _state["hypr"].get("monitors", []))
    await hypr.apply_monitor_mapping(_state["hypr"].get("monitors", []))
    _state["telemetry"] = await telemetry.snapshot()
    _state["audio"] = await audio.snapshot()
    _state["sysinfo"] = await sysinfo.snapshot()
    app.state.tasks = [
        asyncio.create_task(hypr.watch_events(_on_hypr_change)),
        asyncio.create_task(_fast_loop()),
        asyncio.create_task(_slow_loop()),
    ]


@app.on_event("shutdown")
async def _shutdown():
    await audio_rtc.stop()
    for t in app.state.tasks:
        t.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await t


# ---- auth ------------------------------------------------------------------
@app.get("/theme.css")
async def theme_css():
    # public (pre-auth) so the login page is themed too; reveals only colors
    return Response(theme.css(), media_type="text/css", headers={"Cache-Control": "no-cache"})


@app.get("/login", response_class=HTMLResponse)
async def login_form(request: Request):
    return templates.TemplateResponse(request, "login.html")


@app.post("/login")
async def login(pin: str = Form(...)):
    locked = auth.login_locked()
    if locked > 0:
        return RedirectResponse(f"/login?locked={int(locked) + 1}", status_code=303)
    if not config.check_pin(pin):
        auth.record_failure()
        return RedirectResponse("/login?bad=1", status_code=303)
    auth.record_success()
    resp = RedirectResponse("/", status_code=303)
    resp.set_cookie(
        auth.COOKIE_NAME, auth.issue_token(),
        httponly=True, samesite="strict", secure=config.SECURE_COOKIES,
        max_age=config.JWT_TTL_DAYS * 86400,
    )
    return resp


# ---- deck shell ------------------------------------------------------------
@app.get("/", response_class=HTMLResponse)
async def deck(request: Request):
    if not auth._valid(request.cookies.get(auth.COOKIE_NAME)):
        return RedirectResponse("/login", status_code=303)
    return templates.TemplateResponse(request, "deck.html")


# ---- privileged actions (deckd) --------------------------------------------
@app.post("/action")
async def action(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse(await deckclient.run_action(payload.get("action", ""), payload.get("params")))


# ---- hyprland --------------------------------------------------------------
@app.post("/hypr/workspace")
async def hypr_workspace(payload: dict, _=Depends(auth.require_auth)):
    ws_id = int(payload.get("id"))
    ok = await (hypr.move_window_to_workspace(ws_id) if payload.get("move")
                else hypr.focus_workspace(ws_id))
    return JSONResponse({"ok": ok})


@app.post("/hypr/dpms")
async def hypr_dpms(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse({"ok": await hypr.set_dpms(payload.get("monitor", ""), bool(payload.get("on")))})


@app.post("/hypr/focus")
async def hypr_focus(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse({"ok": await hypr.focus_window(payload.get("address", ""))})


# ---- brightness (ddcutil, user-level) --------------------------------------
@app.get("/brightness")
async def brightness_levels(_=Depends(auth.require_auth)):
    return JSONResponse(await brightness.levels())


@app.post("/brightness")
async def brightness_set(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse({"ok": await brightness.set_level(payload.get("monitor", ""), int(payload.get("pct", 0)))})


# ---- audio / media ---------------------------------------------------------
@app.post("/audio/volume")
async def audio_volume(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse({"ok": await audio.set_volume(int(payload.get("pct", 0)))})


@app.post("/audio/mute")
async def audio_mute(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse({"ok": await audio.toggle_mute(payload.get("target", "sink"))})


@app.post("/audio/sink")
async def audio_sink(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse({"ok": await audio.set_sink(payload.get("id"))})


@app.post("/audio/source")
async def audio_source(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse({"ok": await audio.set_source(payload.get("id"))})


@app.post("/media/{act}")
async def media(act: str, _=Depends(auth.require_auth)):
    return JSONResponse({"ok": await audio.media(act)})


# ---- audio streaming (WebRTC, rig -> phone) --------------------------------
@app.post("/audio/webrtc/offer")
async def webrtc_offer(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse(await audio_rtc.handle_offer(
        payload["sdp"], payload["type"],
        listen=bool(payload.get("listen", True)),
        mic=bool(payload.get("mic", False)),
        phone_only=bool(payload.get("phoneOnly", False)),
        video=payload.get("video") or None,
        video_quality=payload.get("videoQuality", "medium")))


@app.post("/audio/webrtc/stop")
async def webrtc_stop(_=Depends(auth.require_auth)):
    await audio_rtc.stop()
    return JSONResponse({"ok": True})


@app.get("/media/art")
async def media_art(_=Depends(auth.require_auth)):
    res = await audio.art()
    if not res:
        return Response(status_code=404)
    ct, data = res
    return Response(content=data, media_type=ct, headers={"Cache-Control": "max-age=3600"})


# ---- commands / deploy -----------------------------------------------------
@app.get("/commands")
async def commands_list(_=Depends(auth.require_auth)):
    return JSONResponse(commands.listing())


@app.post("/commands/run")
async def commands_run(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse(await commands.run(payload.get("id", "")))


# ---- modes / scenes --------------------------------------------------------
@app.get("/modes")
async def modes_list(_=Depends(auth.require_auth)):
    return JSONResponse(modes.listing())


@app.post("/modes/run")
async def modes_run(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse(await modes.run(payload.get("id", "")))


# ---- context strip ---------------------------------------------------------
@app.get("/context")
async def context_config(_=Depends(auth.require_auth)):
    return JSONResponse(context.listing())


@app.post("/context/key")
async def context_key(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse({"ok": await hypr.send_shortcut(
        payload.get("cls", ""), payload.get("key", ""), payload.get("mods", ""))})


# ---- voice router (Tier 1) -------------------------------------------------
@app.post("/voice/start")
async def voice_start(_=Depends(auth.require_auth)):
    return JSONResponse(await voice.start())


@app.post("/voice/stop")
async def voice_stop(_=Depends(auth.require_auth)):
    return JSONResponse(await voice.stop())


@app.post("/voice/transcribe")               # device-mic path: phone uploads its own clip
async def voice_transcribe(file: UploadFile = File(...), _=Depends(auth.require_auth)):
    return JSONResponse(await voice.transcribe_upload(await file.read()))


@app.post("/voice/execute")                  # dispatch a confirmed plan
async def voice_execute(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse(await voice.execute(payload.get("plan")))


# ---- in-deck config editing ------------------------------------------------
@app.get("/config/{name}")
async def config_read(name: str, _=Depends(auth.require_auth)):
    return JSONResponse(cfgedit.read(name))


@app.post("/config/{name}")
async def config_write(name: str, payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse(cfgedit.write(name, payload.get("text", "")))


# ---- system / session ------------------------------------------------------
@app.post("/sys/kill")
async def sys_kill(payload: dict, _=Depends(auth.require_auth)):
    return JSONResponse(sysinfo.kill(int(payload.get("pid"))))


@app.post("/sys/lock")
async def sys_lock(_=Depends(auth.require_auth)):
    return JSONResponse({"ok": await sysinfo.lock()})


# ---- websocket -------------------------------------------------------------
@app.get("/input/available")
async def input_available(_=Depends(auth.require_auth)):
    return JSONResponse({"ok": hid.available()})


# ---- screenshot + file drop ------------------------------------------------
@app.get("/screenshot")
async def screenshot(monitor: str, _=Depends(auth.require_auth)):
    png = await grab.screenshot(monitor)
    if not png:
        return Response(status_code=404)
    return Response(png, media_type="image/png")


@app.post("/upload")
async def upload(file: UploadFile = File(...), _=Depends(auth.require_auth)):
    return JSONResponse(grab.save_upload(file.filename, file.file))


async def _video_input(msg: dict) -> None:
    """Touch-on-video: map a fraction within a streamed monitor to an absolute
    cursor position (Hyprland movecursor over the full layout) + uinput clicks."""
    t = msg["t"]
    if t == "vup":                       # release must always fire (even off-content)
        hid.button("left", False)
        return
    mon = next((m for m in _state.get("hypr", {}).get("monitors", [])
                if m["name"] == msg.get("monitor")), None)
    if not mon:
        return
    gx = int(mon["x"] + min(1.0, max(0.0, float(msg.get("fx", 0)))) * mon["w"])
    gy = int(mon["y"] + min(1.0, max(0.0, float(msg.get("fy", 0)))) * mon["h"])
    await hypr.move_cursor(gx, gy)
    if t == "vclick":
        hid.button("left", True); hid.button("left", False)
    elif t == "vrclick":
        hid.button("right", True); hid.button("right", False)
    elif t == "vdown":
        hid.button("left", True)


@app.websocket("/ws/input")
async def ws_input(websocket: WebSocket):
    if not await auth.ws_authed(websocket):
        await websocket.close(code=4401)
        return
    await websocket.accept()
    try:
        while True:
            msg = await websocket.receive_json()
            try:
                if str(msg.get("t", "")).startswith("v"):
                    await _video_input(msg)
                else:
                    hid.handle(msg)
            except Exception:  # noqa: BLE001 — a bad event must not drop the connection
                pass
    except WebSocketDisconnect:
        pass


@app.websocket("/ws")
async def ws(websocket: WebSocket):
    if not await auth.ws_authed(websocket):
        await websocket.close(code=4401)
        return
    await websocket.accept()
    _clients.add(websocket)
    try:
        await websocket.send_json(_state)
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        pass
    finally:
        _clients.discard(websocket)
