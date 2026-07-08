# Changelog

All notable changes to phone-deck. This project loosely follows
[Keep a Changelog](https://keepachangelog.com/) and [SemVer](https://semver.org/).

## [3.0.0] — 2026-07-09

The V3 cycle: a multi-host **Fleet Console**, a ground-up **UI redesign** (the cockpit), and
sharper **friday** answers. The backend, endpoints, and WebSocket state model are unchanged —
this is an interface + fleet release.

### Added — Fleet Console
- **Host switcher** — the wordmark is now a fleet dropdown; hop between rigs (lightning, raptor,
  blackbird) by navigating to each host's own origin, with live online dots (green online / grey
  offline / amber unknown). Cross-host reads are intentionally *not* proxied — each host serves
  itself.
- **Cogitator fleet board** — the ambient screen's reserved sockets now show the fleet as
  "outposts" (reachability at a glance) plus a decorative waveform scope tied to playback.
- **Reduced-host behavior** — hosts without a discrete GPU, DDC brightness, or multiple monitors
  degrade cleanly: backlight-brightness fallback, single-monitor workspace layout, and honest
  absence of GPU telemetry. Deployed to the raptor laptop (blackbird still gated).

### Changed — UI redesign (the cockpit)
- Ground-up restructure of the information architecture. The three stacked persistent strips
  (telemetry + context + nine tabs) that made everything physically tiny on the phone are gone.
  New shell: a thin instrument **band** (host + telemetry readouts that self-color amber/red when
  hot) · a full-height **Workspaces home** of per-monitor columns with a common context-controls
  strip below · a right **rail** — volume + transport + a **dock** that summons panels into a
  single swappable center (Files/Config/System stow under an overhead "More"). Visuals are
  unchanged: every component and the full theme are reused verbatim; only the layout is
  re-engineered. Built in a parallel worktree and cut over via a served-path symlink.
- Type: Chakra Petch structural labels + IBM Plex Mono data; softened CRT vignette.

### Changed — friday
- Feeds the full open-window list (not just the focused app); runs at a configurable context
  window (default 16384), with `num_keep` set so lowering `num_ctx` can't gut the injected state.

### Fixed
- Fleet host-switcher dropdown positioning — a `theme.css` card rule (`.bg-zinc-900.rounded-lg
  {position:relative}`, specificity 0,2,0) was overriding Tailwind's `.fixed`; pinned with an
  inline `position:fixed`.

## [2.5.0] — 2026-07-07

First tagged release — the full arc from the original control deck through the V2 feature
wave, the V2.1 visual redesign, and the V2.5 additions.

### Added — V2.5
- **Send-to-Rig** — the PWA registers as an Android share target. Share an image, text, or
  link from any app and it lands on the rig: images saved to the drop dir *and* placed on the
  clipboard as paste-ready PNG; text to the clipboard; a bare link opened. Auth is preserved
  through a service-worker bridge so the `SameSite=Strict` cookie still applies.
- **Ambient Cogitator** — after a few idle minutes (configurable) the deck becomes a phosphor
  instrument panel: device-grouped needle-dial telemetry with peak-hold ("was it pegged while
  I was away?"), clock, uptime, duotone now-playing, and an optional liturgy line. Any touch
  returns to the exact tab you left.

### Added — V2 (context-awareness · voice · maintenance · device parity)
- **Voice router** — push-to-talk, faster-whisper (`small.en`, CPU). Deterministic lead-word
  lanes: `macro` (run a mode/command), `type` (dictation), `input` (phrase → key chord), with
  **confirm-before-execute** so a mis-hear never fires. Rig-mic or phone-mic capture.
- **"friday"** — a read-only local-LLM answerer: Ollama + Qwen2.5, self-hosted SearXNG search,
  and live system-telemetry injected into every prompt.
- **Context-awareness strip** — call detection (mute / jump-to), media now-playing + transport,
  and focus-free in-app keyboard controls for YouTube / Brave / Teams; a macro-guard protects
  terminals running a live session from scene-macro closes.
- **Maintenance** — Bluetooth audio via native PipeWire, a root `perfmode` action, and dynamic
  monitor→workspace mapping that self-heals across a cold boot.
- **Device parity** — Pointer-Events trackpad (mouse *and* touch), Stream input-lock (pointer
  lock + keyboard forwarding for a full remote desktop), phone battery + reload in the header.

### Changed — V2.1 (phosphor redesign)
- Full visual overhaul to a **phosphor-terminal** aesthetic — green / amber / ice / auto color
  profiles, live-switchable, with no layout changes (muscle memory preserved). Segmented
  terminal toggles replace native dropdowns; a swipeable telemetry top bar; boxed readouts.

### Fixed
- Voice event-loop freezes: serialized transcription, and a fork/OpenMP deadlock (numeric libs
  pinned single-threaded before NumPy import).
- Media title + next/previous now track the *active* MPRIS player (like the quickshell reference).
- A class of unbounded / unguarded awaits in long-lived loops — hardening against the network
  flaps (relay outages, DHCP shuffles, VPN doze) that a phone-served appliance actually meets.

### Sunset
- The **Performance** and **Commands** tabs were retired — performance mode is now a toggle on
  **System**, and custom shell actions run via the voice `macro` lane (still editable in **Config**).

### Not shipped
- An "extension monitor" (the phone as a real extra display via a headless Hyprland output) was
  built and **shelved**: native-resolution video wouldn't stream reliably over the wireless path,
  and an invisible workspace is a focus hazard. Its salvage (a WebRTC session registry, hyprctl
  timeout guards, a raised video bitrate ceiling) was kept.

[2.5.0]: https://github.com/smit-shah-GG/phone-deck/releases/tag/v2.5.0
