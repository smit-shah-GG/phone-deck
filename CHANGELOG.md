# Changelog

All notable changes to phone-deck. This project loosely follows
[Keep a Changelog](https://keepachangelog.com/) and [SemVer](https://semver.org/).

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
