# Sightline — notes for Claude

Android app that reads HDHomeRun tuner signal live (antenna aiming) and logs it for hours (diagnosing intermittent
dropouts). Kotlin, Jetpack Compose, Material 3. No backend; all traffic stays on the LAN. Distributed by
sideloading via Obtainium from GitHub Releases, not the Play Store. Public repo, GPL-3.0 (`LICENSE`). Built the
way Waitlight (`~/AndroidStudioProjects/Waitlight`) is: same module approach, build setup, CI/release and
conventions.

## Read first

1. `docs/SPEC.md` — full design spec: protocol, data model, screens, monitor service, diagnosis rules, edge cases.
   Source of truth for behavior.
2. `docs/PLAN.md` — milestone checklist. Work in order, tick boxes as you finish them, and commit at the end of
   each milestone.
3. `docs/mockups/` — one PNG and one static HTML file per screen. Source of truth for layout, colors and copy.
   Before building a screen, open its PNG and read its HTML for exact colors, sizes and text.
4. `docs/protocol-captures/` — real output from the user's tuner, if captured. When it disagrees with SPEC, the
   capture wins; update SPEC to match.

This file, `docs/adr/` and (as features land) `docs/rules/` hold the rules and the decisions behind them. How a
feature works is in its code and tests. Words for things are in `GLOSSARY.md`.

## Status

Milestones 0 (scaffold) and 1 (protocol core) are done: `:protocol` has the codec, discovery, control client,
HTTP API and `TunerSessions`, tested against golden captures and `FakeHdhr`; debug builds have the Demo tuner
and a protocol debug screen (from Devices). Next is milestone 2, Devices and Live. One M1 check is left: the
debug screen against a real unit from a phone (the emulator can't see the LAN).
The user has two **HDHomeRun FLEX 4K** units (`HDFX-4K`, 4 tuners, ATSC 3.0 on tuners 0 and 1 only), and a DVR
records from them. Captures and what they showed: `docs/protocol-captures/README.md`.

`./gradlew build` is green (Gradle 9.8.2, AGP 9.5.0, lint included). Keep it green; lint fails the build on a
warning as on an error (`warningsAsErrors`, in `:app` and `:data`), and there is no baseline: fix a warning, or
suppress that one case with a comment saying why.

## Commands

- `./gradlew :protocol:test :analysis:test` — fast pure-Kotlin tests. Run often. They open loopback sockets
  (`FakeHdhr`); in a sandbox that blocks them, run outside it.
- `SIGHTLINE_REAL_HDHR=<ip> ./gradlew :protocol:test --tests '*RealDeviceTest*'` — the client against a real
  unit (claims an idle tuner and releases it).
- `./gradlew testDebugUnitTest` — `:data` and `:app` tests (screens under Robolectric).
- `./gradlew build` — everything, lint included. Run before saying a milestone is done.
- `./gradlew :app:installDebug` — install on a phone or emulator (debug app id `io.cosgriff.sightline.debug`).
- Release: merge the PR `release-please` keeps open (version and changelog come from Conventional Commits);
  it tags `vX.Y.Z` and starts `release.yml`, which builds the signed APK after approval in the `release`
  environment (secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

## Modules

([ADR-0003](docs/adr/0003-pure-kotlin-protocol-and-analysis-modules.md)) Dependencies run one way:
`:app` → `:data` → `:analysis` → `:protocol`.

- `:protocol` — pure Kotlin/JVM, **no Android**. Packet codec, CRC, discovery, control client, status parser,
  HTTP helpers (OkHttp + kotlinx.serialization), `TunerSessions`. Android pieces (MulticastLock, broadcast
  address) are injected from outside (`:data`'s `AndroidDiscoveryEnvironment`). `FakeHdhr` is in its
  `testFixtures`, used by its tests and, as the Demo tuner, by debug builds of `:app` (`src/debug`; `src/release`
  has empty stand-ins).
- `:analysis` — pure Kotlin/JVM, **no Android**. Health from thresholds, the dip detector, aggregations for the
  charts, the diagnosis rules. Every rule gets a test.
- `:data` — Android library. Room (readings, sessions, events, weather), DataStore settings, repositories, CSV
  export, the Hilt module. The one `Clock` and the `@ApplicationScope` scope come from here.
- `:app` — Compose UI (`ui/theme`, `ui/components`, `ui/screens/<screen>`), Navigation 3, ViewModels, the
  monitor foreground service (`monitor/`), later the Glance widget.

## Decisions — don't change without asking

- **Never take a tuner that is in use by another client.** Always release a claimed tuner (`channel none`, then
  `lockkey none`) on every exit path, including crashes (stale lock-key recovery on start). Monitor never offers
  the override.
- **The monitor service is foreground type `connectedDevice`, never `dataSync`.**
- **ATSC 3.0 is tuned with an HTTP stream** on an idle tuner, read and discarded; ATSC 1.0 and QAM with a lock
  key ([ADR-0004](docs/adr/0004-atsc3-tuned-by-http-stream.md)). The firmware can't tune ATSC 3.0 otherwise.
- **Never store, log or commit DeviceAuth** (UDP tag `0x2B`, `discover.json`): it is a cloud credential.
- **No analytics or third-party network calls** besides the HDHomeRun and optional Open-Meteo. **No Google Play
  services or Google SDKs** ([ADR-0002](docs/adr/0002-no-google-play-services.md)): bundled fonts, platform
  sensors and `LocationManager`.
- **Every screen works against the debug-only Demo tuner (`FakeHdhr`)**, so nothing needs real hardware to build
  or test.
- **applicationId `io.cosgriff.sightline` must never change. The release signing key must never change**
  ([ADR-0001](docs/adr/0001-application-id-and-signing-key-are-permanent.md)).
- **Units:** imperial (°F, mph) by default with a metric setting; store metric internally, convert for display.
  Times are stored as UTC epoch ms and shown in local time.
- **IDE is Android Studio.** Keep AGP current (Studio's Upgrade Assistant).
- minSdk 29, targetSdk 37, compileSdk 37, JDK 17, Kotlin + Compose + Material 3, Hilt (KSP), Room (KSP),
  Navigation 3 with `@Serializable` routes that are `NavKey`s. Dynamic colour is off; use the theme tokens.

## Design tokens

In `ui/theme/Theme.kt`, from SPEC "Color tokens": dark (default, whatever the phone's setting), light, and true
black (backgrounds only). Material roles carry the base tokens; the health colours and the rest come from
`MaterialTheme.sightlineColors` by their SPEC names (`healthGood`, `accent`, …). **Never hardcode a hex in a
screen.**

- Fonts: Inter for text, Roboto Mono for every number (`ui/theme/Type.kt`, `Numbers.*`), bundled variable fonts
  in `res/font` (OFL, `assets/licences/`).
- Corners from `MaterialTheme.shapes`: 10dp `small` (tuner cells, chips), 18 `medium` (cards), 24 `large`.
- Touch targets ≥ 48dp. **Colour is never the only signal:** every health colour comes with a word or icon, and
  a content description.
- Gauges, heatmap, range bars and polar plot are custom Compose `Canvas` components in `ui/components`; Vico for
  timeline and histogram.

## Conventions

- Logic goes in `:protocol` or `:analysis` with a test; ViewModels stay thin.
- **`:protocol` and `:analysis` return no English:** typed results that `:app` words.
- **All wording lives in `res/values/strings.xml`.** Composables use `stringResource` / `pluralStringResource`.
  Names are `<screen>_<what>`. Never build a phrase by cutting up another; give it its own resource.
- **A composable used by more than one screen lives in `ui/components/`**; one used by a single screen stays in
  that screen's package.
- **A screen is a `…Content` composable** (plain state and callbacks) wrapped by a `…Screen` that reads the
  ViewModel. A `…Content` that needs the time takes it as a parameter.
- Time comes from the injected `Clock`, never `Instant.now()` or `System.currentTimeMillis()` in logic.
- Never show placeholder data as if real; mark sample values and unbuilt screens clearly.
- Commit messages and PR titles are Conventional Commits (`feat:`, `fix:`, …); `pr-title.yml` checks.
- Any Python helper scripts: use `uv run` with inline script dependencies.
- Public repo: never commit keystores or `local.properties`.

## Open questions (ask the user, don't guess)

See the end of `docs/SPEC.md`.

## Agent skills

### Issue tracker

GitHub Issues on `mcosgriff/sightline`, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default triage roles, each label named after its role. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
