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

- [x] Room: `Session`, `Reading`, `Event`, `WeatherHour` with the indexes from SPEC.
- [x] `MonitorService`: foreground service type `connectedDevice`, ongoing notification with live snq, dip count and Stop action; wake lock + Wi-Fi lock; battery-optimization exemption prompt on first use.
- [x] Polling loop per SPEC: hold or rotate mode, 10 s batched writes, lock wait after tune, reconnect with backoff, release on every exit path.
- [x] `DipDetector` state machine (enter/exit/merge rules, dropout, device unreachable, tuner lost, gap > 60 s) with unit tests driven by `FakeHdhr` patterns.
- [x] Monitor setup screen (spec section 6) and running screen per `mockups/Monitor.png` with a live-growing `SignalTimeline`.
- [x] Optional rate-limited dip notifications.
- **Done when:** a 1-hour Demo run with the flutter and dropout patterns records the expected events, survives screen off, and stops cleanly.
  - Events: checked by `DipDetectorTest` (an hour of each pattern) and `MonitorRunnerTest` (the loop against
    `FakeHdhr`, sped up). Screen off and battery drain need a phone: still to check on the first release build.

## Milestone 5 — Analysis

- [x] Open-Meteo hourly history fetch after a session (home coordinates from settings).
  - `OpenMeteo` in `:data` (forecast API for recent sessions, archive for old), stored as `WeatherHour` rows.
    Retried at app start and from Session detail, which can also set home from one location fix. Demo sessions
    get made-up weather from the Demo wind channel's gust schedule, labelled as Demo.
- [x] Aggregations: time in good health, dip counts, longest dropout, medians, per-hour below-threshold share, histogram bins, per-channel ranges.
  - `SessionStats` and `timelineOf` in `:analysis`; a `SessionSummary` row (Room v4) per finished session for History.
- [x] Diagnosis engine: one rule class per row of the SPEC table, each returning a finding with Likely/Possible, an evidence string with numbers, and a fix. Unit tests per rule with synthetic sessions that should and should not fire.
  - `Diagnosis.kt`; numbers and Likely criteria in `docs/rules/diagnosis.md`. `:analysis` returns typed evidence;
    `:app` words it (`session/Findings.kt`). Not RF's "seq at 100 during reported glitches" needs user reports, so
    only its device-unreachable half is built.
- [x] Session detail screen per `mockups/Session.png`: summary cards, diagnosis cards, `SignalTimeline` with weather panel, `HourHeatmap`, `ChannelRangeBars`, `Histogram`, event list with tap-to-zoom.
  - Tapping an event or a heatmap hour zooms the timeline (re-read at that detail); "Whole session" zooms out.
    Pinch-zoom and a draggable range selector are not built. Export CSV and Share report wait for milestone 6,
    so their buttons aren't shown.
- [x] History screen (spec section 9).
  - Newest first, with kind, start, length, channels, sparkline and health chip; tap opens, press and hold
    selects for delete. Comparing two sessions is milestone 6 ("History compare").
- **Done when:** a Demo session with the wind pattern plus a synthetic weather fixture produces the "Dips follow the wind" finding shown in the mockup.
  - New Demo channel 5.1 "DEMO Wind" (`SignalPattern.windy()`, gusty hours 2–4 of every 6). Checked by
    `DiagnosisTest` (six hours of it with weather from the same schedule: the wind finding, Likely, first) and
    `SessionContentTest` (the card's title, badge, evidence and fix). The Demo tuner is in debug builds only, so
    on a release build this is seen with a real tuner and real weather.

## Milestone 6 — Polish and release

- [x] CSV export (readings, events) via the share sheet; one-page report (PDF via `PdfDocument`, or Markdown).
  - `SessionExport` in `:data` (readings paged, so a 72 h session never loads at once); the PDF in
    `session/Report.kt`, worded by the screen with Session detail's own words. Shared through a FileProvider
    that serves `cache/exports/` only.
- [x] Multi-tuner grid (spec section 5).
  - Leaves one idle tuner free for a DVR (`MultiTunerPlan`); the user's choice, asked 2026-11-04.
- [x] Settings screen (spec section 10) including theme, units and retention cleanup.
- [x] Glance widget for an active monitor.
- [x] Roborazzi screenshot tests for the custom components in all three themes.
  - `ComponentScreenshotTest`: ArcGauge, TriGauge, Sparkline, ChannelRangeBars, PolarAim, HourHeatmap. Unit
    test runs verify against `app/src/test/screenshots`; `./gradlew recordRoborazziDebug` re-records.
- [x] History compare (SPEC milestone 6, Screens › 9): two selected sessions side by side.
- [x] Release: set up the keystore and the `release` environment secrets (README › Releasing); the workflows already exist from M0. Check the Obtainium notes in README.
  - Keystore and the four secrets in the `release` environment (v* tags only, reviewer required), done by the user.
- **Done when:** merging the release-please PR for `v0.1.0` produces a signed APK on the GitHub release page.

## Milestone 7 — Device tools

Asked for 2026-11-14: start a channel scan, see whether a tuner needs a firmware update, and install it. The user
chose the device's own scan, firmware downloaded from SiliconDust (ADR-0005), both refused while any tuner is in
use, on a new screen (SPEC Screens › 12).

- [x] `:protocol`: `LineupStatus`, `HttpApi.lineupStatus`/`startLineupScan`/`abortLineupScan`; upgrade packet types and
  raw-body frames; `ControlClient.upgrade`; `SiliconDustFirmware` (newest by redirect, download) and
  `FirmwareVersions`. `FakeHdhr` answers all of it and restarts on a new version. Golden test from the captured idle
  `lineup_status.json`.
- [x] `:data`: `DeviceTools` (busy guard, scan, update job in the app scope), `DeviceRepository.recordFirmware`, the
  Demo tuner's own firmware files and a Demo-only way to stop its made-up player.
- [x] `:app`: Device tools screen from a wrench on each Devices card, with confirm dialogs; Diagnostics points to it.
- [x] A real channel scan, run by the user from Device tools 2026-11-14: `lineup_status.json` while it runs, and
  every tuner getset answers `tuner busy` (now its own `Occupancy`, `TunerUse` and `TunerHolder`).
- [ ] An update against a real unit, once SiliconDust has a build newer than `20261107`.
- **Done when:** a scan and an update run end to end against the Demo tuner, and are refused while its made-up
  player holds tuner 3. `DeviceToolsTest` and `DeviceToolsContentTest` cover both against `FakeHdhr`.

## Milestone 8 — Whole-picture monitoring

Asked for 2026-11-14: catch dropouts during real DVR recordings, tell one unit's fault from a shared one, and tell
the phone's Wi-Fi from the tuner. Three additions to Monitor; SPEC sections come with each before it is built.

- [x] **A second Demo tuner** (#24). Debug builds list "Demo tuner 2" (`1D3E0002`) after the first: the same
  lineup 4 points weaker, the made-up player on its tuner 0, firmware up to date. `FakeHdhr.demo()` and
  `FakeHdhr.secondDemo()` build both as the app does, and `FakeHdhr.fault` (`UnitFault`) scripts a fault on one
  unit only, for the One unit rule (SPEC "Testing", Demo devices). `TwoDemoTunersTest`, `DemoTunerDevicesTest`.

- [x] **Watch a whole device** (#25). Monitor's fourth mode, "Watch every tuner", on one device or both: gets only
  (as watch mode on one tuner: `status` and `debug` each poll, the rest when a channel changes), so every recording
  the DVR makes is logged with its signal and packet errors. Each tuner keeps its own dip detector and packet error
  baseline; an idle tuner pauses, as in watch mode. One control connection per device, all tuners read each poll.
  - `:analysis`: `DeviceWatch` (a detector and baseline per tuner, device-wide unreachable and gap), `TunerKey`
    keying `PacketErrorCounter`, and `TunerSummary` per tuner. `DeviceWatchTest`.
  - `:data`: Room v7 (`session.wholeDevice`, `session.devices`, `reading.deviceId`, `event.deviceId` and
    `event.tuner`), `MonitorRunner.observeAll`, and device and tuner columns in the CSVs. `MigrationTest`,
    `WholeDeviceWatchTest` (both Demo tuners, players retuning, idle tuners record nothing, no set).
  - `:app`: the setup choice (this device only or both), a running view with one row per tuner (who holds it,
    channel, health), and Session detail's Tuners section with an All tuners / One tuner toggle on the timeline.
  - Decided 2026-11-14: the poll interval is the other modes' (Settings' "How often to read", changeable in
    setup); Session detail toggles between one tuner and all.
- [x] **Compare the two units** (#26). Monitor's fifth mode, "Compare two units": the same channel on an idle
  tuner of each device at once, so a dip on both points to the antenna, splitter or amplifier, and a dip on one to
  that unit or its coax branch. Lock keys (ATSC 3.0 by stream, tuners 0 and 1) on idle tuners only, each unit
  keeping one more idle for the DVR; released on every exit path, and both lock keys covered by the stale lock-key
  recovery. Refused at start when a unit can't spare a tuner; a unit whose last idle tuner a recording takes gives
  its own back and waits.
  - `:analysis`: Common path (two units) and One unit rules (`TwoUnitCommonPathRule`, `OneUnitRule`,
    `Diagnosable.comparedUnits`), One tuner keyed by device and tuner, Flutter per device; numbers in
    `docs/rules/diagnosis.md`. `DiagnosisTest`.
  - `:data`: `MonitorPlan.compareUnits`, `MonitorRunner.compare` (a loop per unit, `UnitState`, `ComparedUnit`,
    `MonitorEnd.NO_SPARE_TUNER`), `SessionInfo.compare`; no Room change. `TwoUnitCompareTest` (both Demo tuners: a
    fault on one gives One unit, on both Common path; stop, ATSC 3.0, refusal, giving a tuner back),
    `CompareRecoveryTest` (process death on two devices).
  - `:app`: setup (second unit, a channel both carry), a running view with a row per unit and both lines on one
    timeline, Session detail with a line per unit, notification and widget; Demo tuner 2's Demo fault
    (`UnitFault.recurring`, 20 s every 15 min) so a debug build shows One unit.
  - Decided 2026-11-15: one tuner stays idle per unit for the DVR, as Multi-tuner does; its own Monitor mode.
- [ ] **Record the phone's Wi-Fi with each session.** Signal strength (RSSI), link speed, frequency band and access
  point changes from the platform (`ConnectivityManager` network callback, `WifiInfo` from `NetworkCapabilities`;
  no Google APIs), stored with readings, so a "device unreachable" event can be laid against the phone roaming or
  its signal falling.
  - `:data`: Wi-Fi samples in Room, written with the 10 s batch; the BSSID only when the app already has location
    permission, and never sent anywhere.
  - `:analysis`: a Phone Wi-Fi rule (device-unreachable events that start within a few seconds of a roam, a
    disconnect or RSSI below a threshold), with tests.
  - `:app`: a Wi-Fi lane on `SignalTimeline` in Session detail, and the rule's finding.
  - Ask first: whether to show the BSSID at all (it identifies the user's access point in shared reports and CSV).
- **Done when:** against two Demo tuners (a second `FakeHdhr` in debug builds), a whole-device watch logs a made-up
  player's channel changes on every tuner; a two-unit session with a fault on one unit gives the One unit finding;
  and a scripted Wi-Fi drop gives the Phone Wi-Fi finding instead of Not RF.
