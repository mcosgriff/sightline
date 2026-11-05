# Sightline — Implementation Plan

Work milestone by milestone, in order. Each milestone ends with the app building, all tests green, and a commit. Tick boxes as you go so progress survives between sessions. `SPEC.md` is the source of truth for behavior; the mockups in `mockups/` are the source of truth for look.

## Milestone 0 — Project scaffold

- [x] Create the Android project in this folder (not a subfolder): Gradle modules `:app`, `:data`, `:analysis`, `:protocol` (ADR-0003), package `io.cosgriff.sightline`, Kotlin DSL, version catalog (`gradle/libs.versions.toml`), Compose + Material 3, minSdk 29, latest stable targetSdk/compileSdk, Gradle wrapper committed.
- [x] Dependencies: Hilt, Room (KSP), DataStore, OkHttp, kotlinx.serialization, kotlinx.coroutines, Vico (compose-m3), Navigation 3, JUnit + Truth, Turbine, Robolectric. Glance and Roborazzi come with the milestones that use them.
- [x] Layout: `:protocol`, `:analysis`, `:data` modules; `:app` with `ui/theme`, `ui/components`, `ui/screens/<screen>` (and `monitor/` in M4).
- [x] Theme: color tokens from SPEC "Color tokens" (dark default, light, true black), Inter + Roboto Mono bundled (ADR-0002), type scale.
- [x] Bottom navigation shell with the four destinations as placeholders.
- [x] `.github/workflows/ci.yml` (tests, lint, debug + release builds on push and PR), `pr-title.yml`, `release-please.yml`, `release.yml`, Dependabot, as in Waitlight.
- [x] `LICENSE` (GPL-3.0 full text), `README.md` updated.
- **Done when:** `./gradlew assembleDebug testDebugUnitTest` passes and the app launches to the empty shell.

## Milestone 1 — Protocol core

- [x] `protocol/Packet.kt`: TLV encode/decode, varlen size, CRC32 little-endian, packet types.
- [x] `protocol/StatusParser.kt`: lenient `key=value` parser → `TunerStatus` (ch, lock, ss, snq, seq, bps, pps, raw). Unknown keys ignored, raw line kept.
- [x] `protocol/RfChannels.kt`: US frequency ↔ RF channel ↔ band table.
- [x] `protocol/Discovery.kt`: UDP broadcast discover (global + directed broadcast), 1.5 s collect, dedupe by device ID. Android-specific bits (MulticastLock, broadcast address) injected from outside so the package stays pure Kotlin.
- [x] `protocol/ControlClient.kt`: persistent TCP client, `get`/`set` with optional lock key, suspend API, timeouts, reconnect with backoff.
- [x] `protocol/HttpApi.kt`: `discover.json`, `lineup.json`, `status.json` models.
- [x] `protocol/TunerSession.kt`: claim (random lock key) → tune (vchannel or `auto:<rf>`) → poll status → release; always releases in `finally`.
- [x] Test fixture `FakeHdhr`: in-process UDP + TCP server that answers discovery and get/set, with scripted signal patterns (steady, slow fade, flutter, dropouts, vanish).
- [x] Unit tests: codec round trips, CRC vectors, varlen at 127/128, malformed packets, status lines (8vsb, atsc3, no lock, idle, unknown keys), claim/release against `FakeHdhr`.
- [x] If `docs/protocol-captures/` has real captures, add them as golden tests. If it doesn't, leave a TODO and use the libhdhomerun source as reference. (Captured from the user's two FLEX 4K units; ATSC 3.0 turned out to need the HTTP stream, ADR-0004.)
- [x] Debug-only screen that lists discovered devices and prints raw status lines once a second.
- [x] Debug builds include a "Demo tuner" device backed by `FakeHdhr` so all later UI works without hardware.
- **Done when:** tests pass and the debug screen shows live raw status from the Demo tuner (and from a real unit, if one is on the network). Demo tuner checked on an API 37 emulator: claim, poll and release by lock key and by HTTP stream. The emulator can't see the LAN, and Java on the dev Mac is blocked by macOS Local Network privacy, so a real unit is still to be checked from a phone on the Wi-Fi.

## Milestone 2 — Devices and Live

- [x] `data`: Room database with `Device`, `Channel` (others added in M4); repositories; DataStore settings with defaults from SPEC.
- [x] `ui/components`: `ArcGauge` (270° arc, spring-animated value, threshold tick, ghost peak marker), `TriGauge`, `Sparkline`, `HealthChip`, health color mapping from thresholds.
- [x] Ask for Nearby devices (`ACCESS_LOCAL_NETWORK`, Android 17) before the first scan, with one sentence why; handle denial and revocation. The debug screen has a bare-bones version.
- [x] Devices screen per `mockups/Devices.png`: cards, tuner status chips (idle / Sightline / other client + IP), rescan, manual add, empty state.
- [x] Live screen per `mockups/Live.png`: channel pill with prev/next and swipe, TriGauge, 60 s sparkline, info row, health banner with one-sentence explanation, Aim/Sweep/Monitor buttons.
- [x] Live claims the first idle tuner on enter and releases it on leave (lifecycle-aware); never touches a busy tuner.
- [x] Stale lock-key recovery on app start.
- [x] Keep screen on while Live is visible.
- **Done when:** Live shows animated gauges from the Demo tuner and a real unit, and the tuner is released when navigating away or killing the app. Demo tuner checked on an API 37 emulator (ATSC 1.0 and 3.0, channel changes keep the tuner, leaving Live releases it); a killed process's lock is released at the next start (unit-tested). A real unit waits for the first release build: the user tests releases, not debug builds.
- Deferred: Live's overflow "Save as session" needs sessions (milestone 4).

## Milestone 3 — Aim and Sweep

- [x] Heading provider from the rotation vector sensor (true north via `GeomagneticField` when a location is known).
- [x] `PolarAim` component; Aim screen per `mockups/Aim.png` (true black, giant number, seq bar, best heading, polar plot, tower bearing ticks, optional second tuner reading).
- [x] Tone mode (`AudioTrack` sine, 200–1200 Hz mapped to snq, stutter when seq < 100) and haptics (tick on new peak, double buzz on lock loss).
- [x] Sweep: iterate lineup with dwell time, progress ring, results per `mockups/Sweep.png` with `ChannelRangeBars`, saved as a `SWEEP` session.
- [x] Sweep compare view (per-channel delta vs a previous sweep).
- **Done when:** a sweep over the Demo tuner's lineup produces the results screen and comparison works across two sweeps. Checked on an API 37 emulator: two sweeps of the Demo tuner, results, the "vs sweep on" line and the compare view; Aim with the peak, plot, toggles and a second tuner. The emulator's compass is fixed, so turning, tone and haptics wait for a real phone (and a release build).

## Milestone 4 — Monitor

- [ ] Room: `Session`, `Reading`, `Event`, `WeatherHour` with the indexes from SPEC.
- [ ] `MonitorService`: foreground service type `connectedDevice`, ongoing notification with live snq, dip count and Stop action; wake lock + Wi-Fi lock; battery-optimization exemption prompt on first use.
- [ ] Polling loop per SPEC: hold or rotate mode, 10 s batched writes, lock wait after tune, reconnect with backoff, release on every exit path.
- [ ] `DipDetector` state machine (enter/exit/merge rules, dropout, device unreachable, tuner lost, gap > 60 s) with unit tests driven by `FakeHdhr` patterns.
- [ ] Monitor setup screen (spec section 6) and running screen per `mockups/Monitor.png` with a live-growing `SignalTimeline`.
- [ ] Optional rate-limited dip notifications.
- **Done when:** a 1-hour Demo run with the flutter and dropout patterns records the expected events, survives screen off, and stops cleanly.

## Milestone 5 — Analysis

- [ ] Open-Meteo hourly history fetch after a session (home coordinates from settings).
- [ ] Aggregations: time in good health, dip counts, longest dropout, medians, per-hour below-threshold share, histogram bins, per-channel ranges.
- [ ] Diagnosis engine: one rule class per row of the SPEC table, each returning a finding with Likely/Possible, an evidence string with numbers, and a fix. Unit tests per rule with synthetic sessions that should and should not fire.
- [ ] Session detail screen per `mockups/Session.png`: summary cards, diagnosis cards, `SignalTimeline` with weather panel, `HourHeatmap`, `ChannelRangeBars`, `Histogram`, event list with tap-to-zoom.
- [ ] History screen (spec section 9).
- **Done when:** a Demo session with the wind pattern plus a synthetic weather fixture produces the "Dips follow the wind" finding shown in the mockup.

## Milestone 6 — Polish and release

- [ ] CSV export (readings, events) via the share sheet; one-page report (PDF via `PdfDocument`, or Markdown).
- [ ] Multi-tuner grid (spec section 5).
- [ ] Settings screen (spec section 10) including theme, units and retention cleanup.
- [ ] Glance widget for an active monitor.
- [ ] Roborazzi screenshot tests for the custom components in all three themes.
- [ ] Release: set up the keystore and the `release` environment secrets (README › Releasing); the workflows already exist from M0. Check the Obtainium notes in README.
- **Done when:** merging the release-please PR for `v0.1.0` produces a signed APK on the GitHub release page.
