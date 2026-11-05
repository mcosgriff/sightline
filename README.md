# Sightline

[![CI](https://github.com/mcosgriff/sightline/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/mcosgriff/sightline/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/mcosgriff/sightline?sort=semver)](https://github.com/mcosgriff/sightline/releases/latest)
[![Licence: GPL-3.0](https://img.shields.io/github/license/mcosgriff/sightline)](LICENSE)
[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](#install)

An Android signal meter and logger for HDHomeRun tuners. Aim an antenna with live gauges, sweep every channel,
and run overnight monitoring that turns dropouts into plain-language diagnoses.

Not affiliated with SiliconDust.

> **Status: in development, no release yet.** The project scaffold is in; the HDHomeRun protocol is next. The
> design is in [`docs/SPEC.md`](docs/SPEC.md), the build order in [`docs/PLAN.md`](docs/PLAN.md), the screens in
> [`docs/mockups/`](docs/mockups/README.md).

## What it does

What's planned:

- **Finds your HDHomeRun** on the Wi-Fi with no setup, or by address.
- **Live gauges** for signal quality, strength and symbol quality, once a second, big enough to read from a ladder.
- **Aim mode:** one giant number, a compass plot of quality against heading, and an optional tone that rises with
  quality, for aiming with the phone in your pocket.
- **Sweep** every channel and compare sweeps before and after an antenna change.
- **Monitor** for hours with the screen off, then see when and how the signal dropped, with findings like "Dips
  follow the wind" and what to try.
- **Never interrupts a recording:** Sightline only uses idle tuners.

## Privacy

- Everything stays on your network. Sightline talks to your HDHomeRun and, only if you turn weather on, to
  [Open-Meteo](https://open-meteo.com/) for past weather at your home coordinates (rounded to about 1 km).
- No account, no server, no Google Play services or SDKs, no analytics, crash reporting or ads.

## Install

Sightline isn't on the Play Store. Once the first release is out, add `https://github.com/mcosgriff/sightline`
in [Obtainium](https://github.com/ImranR98/Obtainium), which installs and updates it from GitHub Releases, or
download the APK from the [latest release](https://github.com/mcosgriff/sightline/releases/latest). Android 10
or later.

## Verify your download

Every release APK is signed with the same key. Its SHA-256 certificate fingerprint is:

```
84:9C:00:D3:6A:48:F3:03:2C:59:28:8B:15:DA:FE:67:1D:32:DE:C9:7A:7F:D3:E3:DC:D3:4F:39:78:99:5A:89
```

Check an APK before installing it with `apksigner verify --print-certs sightline-vX.Y.Z.apk` (the
"SHA-256 digest" line, without colons), or the installed app with
[AppVerifier](https://github.com/soupslurpr/AppVerifier). Android also refuses an update signed with a
different key.

To check that an APK was built by this repo's release workflow from its tag:
`gh attestation verify sightline-vX.Y.Z.apk --repo mcosgriff/sightline`. Each release also carries the signed
provenance as `sightline-vX.Y.Z.apk.intoto.jsonl`, which `gh attestation verify` takes with `--bundle`, and a
SHA-256 checksum of the APK.

## Building

| Module | What lives there |
|---|---|
| `:protocol` | Pure Kotlin: the HDHomeRun packet codec, discovery, control client, status parser. Unit-tested, no Android. |
| `:analysis` | Pure Kotlin: signal health, dip detection, aggregations, the diagnosis rules. Unit-tested, no Android. |
| `:data` | Room, DataStore settings, repositories, the Hilt module. |
| `:app` | Jetpack Compose UI, Navigation 3, theme, the monitor service. |

1. Open this folder in Android Studio and let Gradle sync. JDK 17.
2. Run on a device or emulator with the `app` run configuration (debug app id `io.cosgriff.sightline.debug`).
   Debug builds include a Demo tuner, so no HDHomeRun is needed.
3. `./gradlew :protocol:test :analysis:test` runs the pure-Kotlin tests in seconds; `./gradlew build` runs
   everything, lint included. Lint fails the build on a warning.
4. Upgrade the Android Gradle Plugin with Tools → AGP Upgrade Assistant.

Commit messages and PR titles follow [Conventional Commits](https://www.conventionalcommits.org/). The rules a
change is held to are in [`CLAUDE.md`](CLAUDE.md); the reasons behind the big decisions are in
[`docs/adr/`](docs/adr/); the words used for things are in [`GLOSSARY.md`](GLOSSARY.md).

## Releasing (maintainers)

1. Create a release keystore once and **back it up**. Every update must be signed with the same key
   ([ADR-0001](docs/adr/0001-application-id-and-signing-key-are-permanent.md)):
   ```
   keytool -genkeypair -v -keystore sightline-release.jks -storetype PKCS12 -alias sightline -keyalg RSA -keysize 4096 -validity 36500
   ```
2. Add the signing secrets to the `release` environment (Settings → Environments → release → Environment
   secrets), not as repository secrets. The environment needs a maintainer's approval and accepts only `v*`
   tags.
   - `KEYSTORE_B64`: `base64 -i sightline-release.jks | gh secret set KEYSTORE_B64 --env release`
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`sightline`), `KEY_PASSWORD` (the same as the keystore's, for PKCS12)
3. Release: merge the open **release PR** (`chore(main): release X.Y.Z`). The `release-please` workflow keeps
   it up to date from the Conventional Commits on `main`: `fix:` bumps the patch version, `feat:` the minor,
   a breaking change the major. It updates `CHANGELOG.md` and `version.txt`. Merging tags `vX.Y.Z`, creates
   the GitHub Release and starts the signed build once CI has passed on that commit; approve the waiting
   `release` run in the Actions tab.

`applicationId` (`io.cosgriff.sightline`) must never change.

## How Sightline is made

Sightline is written with an AI coding assistant, and this says how, so nobody has to guess.

- **I decide what it does and how it works.** What the app should do, what it must never do (take a tuner
  another client is using, talk to anything but your tuner and the optional weather service), and the
  architecture that keeps those promises are my calls. They're written down, with their reasons, in
  [`CLAUDE.md`](CLAUDE.md), [`docs/SPEC.md`](docs/SPEC.md) and [`docs/adr/`](docs/adr/).
- **Claude writes most of the code.** It works from those decisions. Every commit it had a hand in says so
  (`Co-Authored-By: Claude`), so the history shows which work is which.
- **Nothing reaches `main` without me.** Every change is a pull request that I review and merge myself.
- **It's tested.** The protocol and analysis live in pure Kotlin modules with unit tests, and CI runs them, lint
  and both builds on every pull request.

If you find something that looks careless, open an issue: it's mine to fix, whoever typed it.

## Licence

GPL-3.0 ([`LICENSE`](LICENSE)). Inter and Roboto Mono are under the SIL Open Font License
(`app/src/main/assets/licences/`).
