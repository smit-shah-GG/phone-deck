# phone-deck

Turn a spare Android phone (or any device with a browser) into a self-hosted
control deck and remote desktop for your Linux workstation — over your private
Tailscale network, with no third-party apps.

It started as "what do I do with an old phone?" and became a control plane for a
Hyprland rig: window/workspace control, scene macros, a push-to-talk voice router with a
local-LLM assistant, context-aware in-app controls, two-way audio + screen streaming, a
touch remote-desktop, a virtual keyboard/mouse, file transfer + phone→PC sharing, and live
telemetry — all served as an installed PWA with a phosphor-terminal look.

> **Note on portability.** This was built for one specific setup: **Arch-based Linux
> (Garuda) + Hyprland + PipeWire + NVIDIA + Tailscale**, with the
> [end-4 / illogical-impulse](https://github.com/end-4/dots-hyprland) dotfiles. The
> architecture is generic, but several integrations are environment-specific (monitor
> names, workspace bindings, the matugen theme path, `cpupower`/`nvidia-smi`). Treat
> it as a working reference to adapt, not a turnkey package.

---

## Features

The UI is a tabbed, dark, landscape web app (theme-synced to your desktop):

| Tab | What it does |
|---|---|
| **Workspaces** | Live per-monitor workspace grid (tap to switch), live window list (tap to focus), per-monitor DPMS toggle + `ddcutil` brightness |
| **Remote** | On-screen trackpad (drag/tap/two-finger) + virtual keyboard with modifiers & combos, via a kernel `uinput` device |
| **Modes** | One-tap scene macros — launch/close/arrange whole app layouts across monitors (e.g. "Work" / "Free") |
| **Voice** | Push-to-talk voice router (faster-whisper, CPU): lead-word lanes — `macro` (run a mode/command), `type` (dictate), `input` (phrase → key chord) — with **confirm-before-execute**, plus **"friday"**, a read-only local-LLM answerer (Ollama + Qwen) with self-hosted web search and live system-state awareness |
| **Audio** | Mic/speaker mute, volume, output **and** input device pickers, `playerctl` transport + cover art (tracks the active MPRIS player) |
| **Stream** | Bidirectional **WebRTC audio** (PC↔phone, with phone-as-mic and phone-only output) **+ screen video**, and **tap-to-control the streamed screen** = a real remote desktop |
| **System** | Performance-mode toggle, live **theme** switching, Tailscale status, top processes (tap to kill), lock, NetworkManager restart, suspend/reboot/poweroff |
| **Files** | Screenshot a monitor → view/download on the phone; drop a file phone→PC |
| **Config** | Edit the `commands.json` / `modes.json` (and context/voice) config from the phone (JSON-validated) |

Always on, above the tabs — a **context strip** that surfaces what's happening right now:
an active call (mute / jump-to it), media now-playing + transport, and in-app keyboard
controls for the focused app (YouTube / Brave / Teams), delivered *without stealing focus*.

Two more, beyond the tabs:

- **Send-to-Rig** — the PWA registers as an Android **share target**: share an image, some
  text, or a link from any app on your phone and it lands on the rig — images saved to a drop
  dir *and* placed on the clipboard as paste-ready PNG, text to the clipboard, a bare link opened.
- **Ambient Cogitator** — after a few idle minutes the deck becomes a phosphor instrument panel:
  needle-dial telemetry with peak-hold ("was it pegged while I was away?"), clock, now-playing.

Plus: on-screen numpad PIN login, screen wake-lock, fullscreen landscape PWA, and
brute-force lockout on the login.

---

## Architecture

```
   phone / laptop (browser PWA)
            │  HTTPS + WSS  (Tailscale-only)
            ▼
   tailscale serve  ──►  FastAPI web app   ── Unix socket ──►  deckd
   (real TLS cert)       (runs as your user)   (action names)   (runs as root)
                              │                                  │
              hyprctl · pactl · ddcutil · grim ·          fixed allowlist of
              wf-recorder · uinput · WebRTC               privileged commands
                                                          (cpupower, nvidia-smi,
                                                           systemctl, …)
```

Two processes:

- **`app/` — the web app** runs as your normal user. It does everything that doesn't
  need root: Hyprland control, audio, brightness, screen capture, virtual input,
  WebRTC streaming, file transfer.
- **`deckd/` — a tiny root helper** (stdlib only, no dependencies) for the few
  privileged actions. The web app never sends it shell strings — only **action names**
  from a fixed allowlist (`governor_performance`, `gpu_power_limit`, `suspend`, …),
  validated in [`deckd/actions.py`](deckd/actions.py) before anything executes. Even if
  the web app were fully compromised, the blast radius is exactly the allowlisted
  actions, with no argument injection. The socket is `root:<group>` mode `0660`.

### Security model

- Reached **only inside your tailnet** — `tailscale serve` exposes it on your
  MagicDNS name with a real Let's Encrypt cert; it never binds to `0.0.0.0`.
- **JWT** login (PIN → signed cookie), with exponential-backoff lockout after repeated
  failures.
- Privileged actions are isolated behind the allowlisted helper daemon.
- Uploads are basename-sanitized (can't escape the drop directory).

> The web app *can* run your configured shell commands and inject input as your user —
> it is, by design, a remote control for your machine. Keep it on your tailnet, behind
> the PIN, and don't expose it publicly.

---

## Requirements

- Linux with **Hyprland** (wlroots), **PipeWire** (with `pactl`/PulseAudio compat)
- **Python ≥ 3.11** and **[uv](https://docs.astral.sh/uv/)**
- **Tailscale** (with HTTPS certificates enabled for your tailnet)
- CLI tools used by various features (install what you want to use):
  `hyprctl`, `pactl` / `pw-record` / `pw-play`, `playerctl`, `ddcutil`,
  `wf-recorder`, `grim`, `cpupower`, `nvidia-smi`, `kitty` (or your terminal)
- Your user in the **`input`** group (for `/dev/uinput`) and **`i2c`** group (for `ddcutil`)
- `aiortc` + PyAV (installed via `uv`) for audio/video streaming

---

## Installation

```bash
git clone <your-repo-url> phone-deck
cd phone-deck
uv sync                          # creates .venv and installs dependencies
uv run python -m app.set_pin     # set your unlock PIN
```

### 1. The root helper (`deckd`)

Edit `systemd/deckd.service` first — set the paths and `DECK_SOCKET_GROUP` to a group
your user belongs to (usually your primary group):

```bash
sudo cp systemd/deckd.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now deckd
```

`deckd` runs the system Python (stdlib only — no venv needed).

### 2. The web app

Run it directly for development:

```bash
uv run uvicorn app.main:app --host 127.0.0.1 --port 8765
```

…or as a user service. Edit `systemd/phone-deck.service` paths first, then install it.
**Important:** on a setup where Hyprland is *not* launched via uwsm, the systemd
`graphical-session.target` never activates, so the user unit won't auto-start on login.
The reliable fix is to start it from Hyprland with the session environment imported —
add to your Hyprland autostart (e.g. end-4's `~/.config/hypr/custom/execs.conf`):

```bash
exec-once = systemctl --user import-environment WAYLAND_DISPLAY HYPRLAND_INSTANCE_SIGNATURE XDG_RUNTIME_DIR DBUS_SESSION_BUS_ADDRESS XDG_CURRENT_DESKTOP && systemctl --user start phone-deck
```

### 3. HTTPS over Tailscale

1. Enable certificates once: Tailscale admin console → **DNS → Enable HTTPS Certificates**.
2. Front the app with a real cert:

   ```bash
   sudo tailscale serve --bg 127.0.0.1:8765
   ```

3. Set `DECK_SECURE_COOKIES=1` in the web-app environment once HTTPS is live.

### 4. Install on the phone

Open `https://<your-host>.<your-tailnet>.ts.net/` in Chrome on the phone (it must be on
your tailnet), log in, then **⋮ → Install app**. It launches fullscreen, landscape-locked,
with the screen kept awake. HTTPS is required for the PWA service worker + wake-lock.

> Works from any tailnet device — laptops included (the touch surfaces use Pointer
> Events, so a mouse works too). The WebRTC stream is single-peer (one viewer at a time).

---

## Configuration

All runtime config lives in `~/.config/phone-deck/` and is editable from the **Config**
tab (JSON-validated, keeps a `.bak`). Changes take effect on the next action — no restart.

### `commands.json` — custom shell actions

```json
[
  { "id": "reload_wm", "label": "Reload WM", "run": "hyprctl reload" },
  { "id": "deploy", "label": "Deploy site", "run": "cd /srv/app && ./deploy.sh",
    "confirm": true, "timeout": 120 }
]
```

`run` may be a string (executed via `bash -lc`) or a list (argv, no shell). These run as
your user — it's a local, trusted file.

### `modes.json` — scene macros

Each mode is an ordered list of steps run via `hyprctl`:

- `launch  {cmd, workspace, match?, once?}` — open an app on a workspace. With `match`
  (window class) + `once`, it won't duplicate, and it relocates the window if it lands on
  the wrong workspace (needed for apps like browser PWAs whose window comes from an
  existing process).
- `close  {match, workspaces?}` — close windows of a class, optionally only on listed workspaces.
- `move   {match, workspace}` — move matching windows to a workspace.
- `focus  {workspace}` — switch a workspace into view (e.g. reset each monitor to its home ws).

### Hyprland workspace → monitor binding

Mode placement is deterministic only if your workspaces are pinned to monitors. Add
bindings to your Hyprland config (adjust workspace numbers and monitor names to yours):

```
workspace = 1, monitor:DP-3, default:true
workspace = 2, monitor:DP-3
# … etc
```

### Theme

The deck wears a **phosphor-terminal** skin with four live-switchable color profiles —
**green** (default), **amber**, **ice**, and **auto** (derives the phosphor hue from your
matugen wallpaper palette, `~/.local/state/quickshell/user/generated/colors.json`). Switch
it in the **System** tab; the choice persists in `~/.config/phone-deck/theme.json`.
`app/theme.py` serves it as `/theme.css`. The Ambient Cogitator's idle timeout and liturgy
line are configurable here too.

---

## How streaming works

Bidirectional WebRTC via **aiortc + PyAV**, bridged to PipeWire/Hyprland with subprocess
pipes (avoids fragile device-I/O bindings):

- **PC → phone audio:** capture the default sink's `.monitor` with `parec` → Opus.
- **Phone → PC mic:** receive the phone mic → a PipeWire null sink whose monitor apps
  select as an input ("Monitor of PhoneDeckMic").
- **Phone-only output:** route PC playback into a virtual sink so the speakers go silent
  while you listen on the phone; restored on stop.
- **PC → phone video:** `wf-recorder` captures a monitor → aiortc, with quality presets
  (540p / 720p / 1080p) to keep the bitrate sane over flaky links.
- **Touch remote desktop:** tap/drag on the streamed video → absolute cursor positioning
  (`hyprctl movecursor`) + `uinput` clicks. Tap = click, drag = move, long-press = right-click.

Constraints: ~150–300 ms latency; reliable only while the PWA is foreground (Android
suspends background tabs); use headphones to avoid echo; one streaming peer at a time;
a browser can't capture the phone's *own* app audio (mic only).

---

## Tech stack

- **Backend:** FastAPI + Uvicorn, WebSockets, aiortc/PyAV, python-evdev, psutil, PyJWT
- **Frontend:** HTML + Tailwind (CDN) + vanilla JS, PWA (manifest + service worker + wake-lock)
- **Helper daemon:** Python standard library only
- **Transport:** Tailscale (`tailscale serve` for HTTPS/WSS)

## Tests

```bash
uv run pytest
```

Covers the `deckd` allowlist, JWT + login lockout, PIN hashing, config-editor validation,
modes parsing, the streaming quality/command logic, and the input keymap. The live
WebRTC/PipeWire/Hyprland paths are validated by loopback during development.

## Project layout

```
app/          FastAPI web app
  main.py       routes + WebSockets
  hypr.py       Hyprland (snapshot, dispatch, event socket, cursor)
  audio.py      PipeWire device control + playerctl
  audio_rtc.py  WebRTC audio + screen video bridge
  hid.py        virtual keyboard/mouse via uinput
  modes.py      scene-macro engine
  brightness.py ddcutil
  grab.py       screenshot + file upload
  theme.py      matugen → CSS
  ...
deckd/        root helper (stdlib only) + action allowlist
systemd/      service units
tests/        pytest suite
```

## License

MIT — see `LICENSE` (add one before publishing if you want a different license).

## Acknowledgements

Built for [Hyprland](https://hyprland.org/), [PipeWire](https://pipewire.org/),
[Tailscale](https://tailscale.com/), [aiortc](https://github.com/aiortc/aiortc), and the
[end-4 dotfiles](https://github.com/end-4/dots-hyprland).
