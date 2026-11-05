# Sightline — HDHomeRun Signal App Design Spec

Nov 3, 2026 · Matthew Cosgriff

Living copy of this spec: https://claude.ai/artifact/6WJgPrEtWaLPGducb8ZyfT (this file is the snapshot Claude Code builds from).
Screen mockups: [`docs/mockups/`](mockups/README.md).

## Overview

Sightline is an Android app for reading HDHomeRun tuner signal in real time and logging it over hours or days. It does two jobs: aiming an antenna live, and catching intermittent dropouts that never show up while you watch. The working name is Sightline, after a clear line of sight to the tower.

The gap it fills: SiliconDust's Android signal meter was pulled from Google Play years ago. Its last build is from 2015 and targets Android 4.3, so modern Android blocks the install. No current app logs signal over time.

**Goals**

- Discover HDHomeRun tuners on the LAN with no setup, plus manual IP entry.
- Show live signal strength, signal quality and symbol quality for any tuner and channel, at 1 Hz, as large gauges readable from a ladder.
- Run unattended monitoring for hours, log every reading, and flag dips.
- Turn the logs into charts and plain-language diagnoses: time-of-day patterns, single-channel vs all-channel faults, overload, and network vs RF problems.
- Export raw data as CSV and a summary report.
- Never interrupt a DVR recording.

**Non-goals**

- Watching or recording TV.
- Channel guide data.
- Cable or CableCARD tuners (HDHomeRun Prime). They report the same fields, but the app is not tested on them.
- iOS.

## Stack and architecture

Sightline is a Kotlin app with Jetpack Compose and Material 3, split into Gradle modules (ADR-0003). There is no backend. All traffic stays on the LAN.

| Layer | Choice | Notes |
| --- | --- | --- |
| Language / UI | Kotlin 2.x, Jetpack Compose, Material 3 with the fixed color tokens (dynamic color off) | minSdk 29, targetSdk latest |
| Architecture | MVVM, unidirectional state (`StateFlow`), Hilt for DI | One `ViewModel` per screen |
| Networking | Raw `DatagramSocket` + `Socket` for the HDHomeRun protocol; OkHttp + kotlinx.serialization for the JSON endpoints | No Retrofit needed |
| Storage | Room for readings and sessions; DataStore for settings | Readings table is append-heavy, indexed by session and time |
| Charts | Vico (Compose) for line, bar and column charts | Gauges, heatmap and polar plot are custom `Canvas` |
| Background | Foreground service, type `connectedDevice` | Not WorkManager: it can't poll at 1 Hz |
| Weather (optional) | Open-Meteo hourly history, no API key | For correlating dips with wind, temperature and rain |
| Sensors | Rotation vector sensor for compass heading | Aim mode only |

**Modules** (Gradle modules; dependencies run `:app` → `:data` → `:analysis` → `:protocol`)

- `:protocol` — pure Kotlin/JVM, no Android: packet codec, CRC32, discovery, control client, status parser. Unit-tested on the JVM.
- `:analysis` — pure Kotlin/JVM: health from thresholds, dip detector, diagnosis rules and aggregations for charts.
- `:data` — Android library: Room DAOs, repositories, DataStore settings, CSV exporter.
- `:app` — `ui` (screens, gauge and chart components, theme) and `monitor` (foreground service, polling scheduler).

```
 HDHomeRun tuner ──UDP/TCP 65001, HTTP──▶ protocol ──▶ monitor ──batched writes──▶ data (Room)
                                             │                                      │      │
                                             └──── live polling ──▶ ui ◀────────────┘      ▼
                                                                    ▲                   analysis ◀── Open-Meteo (optional)
                                                                    └─── findings and charts ──┘
```

Only `protocol` talks to the tuner; Live polls through it directly, while Monitor logs through it into Room for analysis.

## HDHomeRun protocol reference

Use the libhdhomerun control protocol as the primary data source and the HTTP JSON endpoints as helpers. The control protocol works on every model and reports idle tuners. `status.json` only lists tuners already in use and returns percentages only. Channels DVR made the same switch for the same reason. Every field below was checked against the user's FLEX 4K units; the captures and what they showed are in `docs/protocol-captures/`, which wins where this spec and the device disagree.

### Packet format (UDP and TCP, port 65001)

| Field | Size | Notes |
| --- | --- | --- |
| Type | 2 bytes, big-endian | `0x0002` discover request, `0x0003` discover reply, `0x0004` get/set request, `0x0005` get/set reply |
| Payload length | 2 bytes, big-endian | Length of the TLV payload only |
| Payload | variable | Sequence of TLVs |
| CRC32 | 4 bytes, little-endian | Standard CRC-32 (`java.util.zip.CRC32`) over type + length + payload |

Each TLV is a 1-byte tag, a variable-length size, then the value. The size is 1 byte if under 128. Otherwise it is 2 bytes: `(len and 0x7F) or 0x80`, then `len shr 7`. Get/set names and values are null-terminated ASCII.

| Tag | Name | Used for |
| --- | --- | --- |
| `0x01` | Device type | Discovery filter; tuner = `0x00000001`, wildcard = `0xFFFFFFFF` |
| `0x02` | Device ID | Discovery filter (wildcard `0xFFFFFFFF`) and reply |
| `0x03` | Get/set name | e.g. `/tuner0/status` |
| `0x04` | Get/set value | Present on set; returned on reply |
| `0x05` | Error message | Set when a get/set fails |
| `0x10` | Tuner count | Discovery reply |
| `0x15` | Lock key | uint32, sent with sets on a locked tuner |
| `0x2B` | Device auth | Discovery reply. A cloud credential: never stored, logged or committed |
| `0x2D` | (unnamed) | Some discovery replies carry it; ignored |
| `0x27` | Lineup URL | Discovery reply |
| `0x2A` | Base URL | Discovery reply, e.g. `http://192.168.1.50:80` |

The libhdhomerun source (github.com/Silicondust/libhdhomerun, `hdhomerun_pkt.h` and `hdhomerun_pkt.c`) is the authority if anything here disagrees with it.

### Discovery

1. Acquire a `WifiManager.MulticastLock` (many phones drop inbound broadcast without it).
2. Send a discover request with device type and device ID wildcards to `255.255.255.255:65001`, and also to the Wi-Fi subnet's directed broadcast address.
3. Collect replies for 1.5 s. Each reply gives device ID, tuner count, base URL and lineup URL.
4. Enrich with `GET {baseUrl}/discover.json` for friendly name, model and firmware.
5. Fallback: the user types an IP or `hdhomerun.local`, and the app calls `discover.json` directly.

### Control commands used

| Command | Purpose |
| --- | --- |
| `get /sys/hwmodel`, `get /sys/version` | Model (`HDFX-4K`) and firmware, shown on the device card. `/sys/model` is the firmware name (`hdhomerun_dvr_atsc3`) |
| `get /tunerN/status` | Idle check and RF readings (parsed below) |
| `get /tunerN/lockkey` | `none` or the IP holding the lock (an HTTP stream client holds one too) |
| `get /tunerN/target` | `none`, or where a stream goes (`http://<ip>:<port>`) |
| `set /tunerN/lockkey <random uint32>` | Claim a tuner before tuning. The reply is the holder's IP, not the key |
| `set /tunerN/vchannel 7.1` | Tune by virtual channel (sent with lock key). ATSC 1.0 and QAM only: an ATSC 3.0 channel answers `invalid virtual channel` |
| `set /tunerN/channel auto:<rf>` | Tune by RF channel, for channels missing from the lineup. ATSC 1.0 and QAM only; status keeps reporting `ch=auto:<rf>` |
| `get /tunerN/channel` | The `ch=` value a tune produced, read right after tuning so each poll can check it is unchanged |
| `get /tunerN/streaminfo` | Programs in the transport stream |
| `set /tunerN/channel none`, then `set /tunerN/lockkey none` | Release the tuner (always, including on crash recovery) |

`/tunerN/vstatus` does not exist on this firmware (`unknown getset variable`). Errors come back as tag `0x05` alone, e.g. `ERROR: resource locked by 192.168.1.21`, `ERROR: invalid channel`, `ERROR: invalid virtual channel`, `ERROR: unknown getset variable`.

### ATSC 3.0 (ADR-0004)

The control protocol can't tune ATSC 3.0 on this firmware. Sightline opens the HTTP stream `http://<ip>:5004/tuner<N>/v<guide>` (the lineup entry's `URL` with `/auto/` replaced by `/tuner<N>/`) on a tuner it checked is idle, reads and discards it, and polls status over the control protocol as usual. Closing the stream releases the tuner. Only some tuners do ATSC 3.0 (on a FLEX 4K, tuners 0 and 1); the others answer `806 Tune Failed`, so idle tuners are tried in order. Stream errors are in the `X-HDHomeRun-Error` header: `801 Unknown Channel`, `804 Tuner In Use`, `806 Tune Failed`.

### Status parsing

`get /tunerN/status` returns one line of space-separated `key=value` pairs:

```
ch=8vsb:189000000 lock=8vsb ss=100 snq=97 seq=100 bps=7831328 pps=0
ch=atsc3:593000000:1 lock=atsc3 ss=100 snq=100 seq=100 bps=0 pps=285
```

| Key | Meaning | Type |
| --- | --- | --- |
| `ch` | Requested channel: `none`, `auto:<rf>`, `<mod>:<freq Hz>`, or for ATSC 3.0 `atsc3:<freq Hz>:<n>` | String |
| `lock` | Detected modulation: `none`, `8vsb`, `qam256`, `atsc3`, … | Enum, unknown values kept as raw text |
| `ss` | Signal strength (raw RF power), 0–100 | Int |
| `snq` | Signal-to-noise quality, 0–100. The number to aim by | Int |
| `seq` | Symbol quality: share of data received correctly in the last second, 0–100 | Int |
| `bps` | Raw channel bit rate. Always 0 on ATSC 3.0 | Long |
| `pps` | Packets per second sent to the network (0 when not streaming) | Long |

Parse leniently: ignore unknown keys and keep the raw line in the log for debugging. `ch=none` means the tuner is idle. `lock=none` with a tuned channel means no lock, recorded as ss/snq/seq of 0 and flagged as a dropout (the tuner may still report `ss`). The first reading after a tune can show a lock with `seq=0 bps=0`; the lock wait after a tune discards it.

RF channel and frequency come from the `ch` field after tuning. Map frequency to US RF channel with a built-in table (VHF-lo 2–6, VHF-hi 7–13, UHF 14–36). Show the band, because VHF and UHF problems have different causes.

### HTTP helpers

- `GET /discover.json` — FriendlyName, ModelNumber, FirmwareName, FirmwareVersion, DeviceID, TunerCount, BaseURL, LineupURL. It also has `DeviceAuth`, a credential, which Sightline never reads.
- `GET /lineup.json` — GuideNumber, GuideName, VideoCodec, AudioCodec, URL per channel; used for the channel picker and, for ATSC 3.0, the stream URL. Its SignalStrength and SignalQuality are from the last channel scan, not live.
- `GET /status.json` — one entry per tuner; an idle one has only `Resource`, an in-use one adds VctNumber, VctName, Frequency, percentages and TargetIP.

## Data model

Seven Room tables. Readings are the only large table: a 12-hour, one-channel session at 1 Hz is about 43,000 rows, roughly 3 MB.

| Entity | Key fields | Notes |
| --- | --- | --- |
| `Device` | deviceId (PK, hex string), friendlyName, model, firmware, baseUrl, tunerCount, lastSeen | Refreshed on each discovery |
| `Channel` | deviceId + guideNumber (PK), guideName, rfChannel?, frequencyHz?, band?, favorite | rfChannel filled the first time the channel is tuned |
| `Session` | id (PK), deviceId, kind (`LIVE`, `AIM`, `MONITOR`, `SWEEP`), startedAt, endedAt?, channels (list), tuner?, pollIntervalMs, dwellMs?, note, weatherFetched, fetchWeather | tuner is null when a sweep moved between tuners; dwellMs is a sweep's | One per run; live views are saved as sessions only when the user taps Save |
| `Reading` | id (PK), sessionId (FK, indexed), ts (epoch ms, indexed with sessionId), guideNumber, tuner, ss, snq, seq, bps, pps, lock, headingDeg?, rawStatus? | `rawStatus` kept only when the parse failed |
| `Event` | id (PK), sessionId, startTs, endTs, guideNumber?, kind (`DIP`, `DROPOUT`, `TUNER_LOST`, `DEVICE_UNREACHABLE`, `GAP`), minSnq?, minSeq?, noLockMs | Written by the dip detector. guideNumber is null for device-wide events; minSnq/minSeq are the lowest locked values among the bad readings, null if it never locked; noLockMs is how long of it had no lock ("lock lost 4 s") |
| `WeatherHour` | sessionId + hourTs (PK), tempC, windKph, gustKph, precipMm | From Open-Meteo, fetched after a session ends |
| `SessionSummary` | sessionId (PK, FK), readings, goodShare?, dips, medianSnq?, health?, sparkline | What History shows; written when a session ends (or at the next app start), kept when old readings are deleted |

Settings in DataStore: thresholds, poll interval, units (°F / mph default, metric option), theme, home coordinates for weather (entered manually or from one location fix), retention (default 90 days). Store metric internally; convert only for display.

## Visual design system

The app should read at arm's length on a roof: large numbers, color-coded health, and motion that shows direction of change. The app chrome and signal health both use the fixed tokens below (dynamic color is off), so a color always means the same thing.

### Signal thresholds (defaults, editable in Settings)

| Metric | Good | Marginal | Poor | Why |
| --- | --- | --- | --- | --- |
| Signal quality (snq) | ≥ 80 | 60–79 | < 60 | The metric to aim by |
| Symbol quality (seq) | 100 | 90–99 | < 90 | Any drop means visible errors |
| Signal strength (ss) | 40–94 | 25–39 or ≥ 95 | < 25 | Neutral by default; ≥ 95 hints at overload |

### Color tokens

The mockups use the dark theme, which is the default. True black (OLED) swaps only the backgrounds.

| Token | Dark (default) | Light | Use |
| --- | --- | --- | --- |
| `bg` | `#0E1114` (true black `#000000`) | `#F7F9FA` | Screen background |
| `surface` | `#171B20` | `#FFFFFF` | Cards, chips |
| `outline` | `#2A3138` | `#D5DBE0` | Card borders, pill buttons |
| `textPrimary` | `#E8ECEF` | `#111418` | Numbers, titles |
| `textSecondary` | `#9AA4AE` | `#5B6670` | Labels |
| `accent` | `#5AA9FF` | `#1E6FD9` | Primary buttons, selection, sparklines |
| `healthGood` | `#4CC38A` | `#2E7D32` | Good |
| `healthMarginal` | `#F2B33D` | `#B26A00` | Marginal |
| `healthPoor` | `#F2685C` | `#C62828` | Poor, dips, dropouts |
| `healthNoLock` | `#6B7580` | `#616161` | No lock, busy tuner |

Every health color is paired with an icon or word (check, warning, cross, dash) so it works for color-blind users.

Type: Inter (or the system sans) for text; numbers in a tabular monospace (Roboto Mono) so digits don't jitter as they change. Gauge values are 56 sp on Live, 120+ sp on Aim; labels 12–14 sp. Touch targets are at least 44 dp.

### Reusable visual components

| Component | What it shows | Implementation notes |
| --- | --- | --- |
| `ArcGauge` | One metric as a 270° arc with a needle, threshold ticks, a colored fill and the number in the center | Custom `Canvas`; spring-animated needle (stiffness medium, no overshoot); a hollow "ghost" marker for the session peak; band colors drawn from thresholds |
| `TriGauge` | Signal quality as a large center gauge, with strength and symbol quality as two smaller gauges beneath | Composes three `ArcGauge`s; collapses to one row in landscape |
| `Sparkline` | Last 60 s of one metric | Custom `Canvas`, 1 px line, threshold dashed behind |
| `SignalTimeline` | snq over a whole session, with dip events as red bands, a draggable range selector and pinch-zoom | Vico line chart; event bands as decorations; weather as a second panel of bars |
| `HourHeatmap` | Hour of day (0–23) × channel; each cell = percent of time below the snq threshold | Custom `Canvas` grid; cell tap opens that hour in the timeline |
| `ChannelRangeBars` | Every channel's min, median and max snq as a horizontal range bar, sorted worst first | Custom `Canvas`; RF channel and band label on each row; threshold tick |
| `PolarAim` | snq plotted against compass heading while the antenna rotates; best heading marked | Custom `Canvas`; optional tower bearing ticks entered by the user |
| `Histogram` | Distribution of snq readings for a session | Vico column chart, 5-point bins colored by health band |
| `HealthChip` | One-word status (Good, Marginal, Poor, No lock) | Color + icon |

### Feedback that doesn't need eyes

- **Tone mode:** a continuous tone whose pitch rises with snq (200–1200 Hz) and stutters when seq drops below 100. For aiming with the phone in a pocket and earbuds in.
- **Haptics:** a short tick on every new session peak, a double buzz on lock loss.
- **Keep screen on** during Live and Aim.

## Screens

Navigation is a bottom bar with four destinations: Devices, Live, Monitor, History. Settings sits in the top app bar. Aim and Sweep open from Live. Mockups for each screen are in `docs/mockups/` (PNG plus static HTML).

### 1. Devices — `mockups/Devices.png`

- One card per HDHomeRun: friendly name, model, firmware, IP, and a dot per tuner (green = idle, blue = in use by Sightline, gray = in use by another client, with that client's IP shown).
- Pull to rescan. A field adds a device by IP or hostname.
- Empty state: a short explanation that the phone must be on the same Wi-Fi, a Retry button, and the manual entry field.
- Tapping a card opens Live on that device's first idle tuner.

### 2. Live — `mockups/Live.png`

- **Top bar:** device, tuner (auto-picks the first idle tuner) and a channel selector. Swipe left or right on the gauges to step through channels.
- **Center:** `TriGauge` — signal quality large, strength and symbol quality beneath, each with its ghost peak marker and number.
- **Below the gauges:** a `Sparkline` for the last 60 s, then an info row: RF channel, band (VHF-lo, VHF-hi, UHF), frequency in MHz, modulation.
- **Health banner:** a `HealthChip` plus one sentence, e.g. "Strong signal but low quality: likely overload or multipath."
- **Actions:** Aim, Sweep, Monitor (this channel). Overflow: Save as session.
- Polls once a second. Leaving the screen releases the tuner.

### 3. Aim — `mockups/Aim.png`

- Full-screen, true black, high contrast. One giant snq number (120+ sp), a thin seq bar under it, and the session peak with its heading ("Best 91 at 247°").
- `PolarAim` fills the lower half: each reading is plotted at the phone's compass heading, with the best heading marked. Hold the phone against the antenna mast while rotating.
- Toggles: tone, haptics, reset peak, and up to 3 tower bearings the user typed in (for example from a rabbitears.info signal report).
- A second tuner can watch a second channel, shown as a small second reading, so the user can find a heading that works for both towers.
- **Heading:** from the rotation vector sensor, of whichever phone axis is more level: the top edge when the phone lies flat, the back (camera) when it stands against the mast. Magnetic until home is known; "use true north" takes one coarse location fix, saves home to 2 decimal places and applies the declination there (`GeomagneticField`). Tower bearings are typed as true bearings. No compass: the number still works, the plot says why it is empty.
- **Plot and peak:** only locked readings count. The plot keeps the best snq per 5° of heading; points more than 15° apart aren't joined. The first lock sets the peak without a tick; each higher reading ticks. Reset peak clears the peak and the plot.
- Tone is off until turned on (a phone that beeps unasked is a surprise); haptics are on. Both choices and the tower bearings are kept in settings.

### 4. Sweep — `mockups/Sweep.png`

- Tunes each lineup channel in turn for a dwell time (default 6 s), collecting readings for each.
- Progress ring while running, then summary counts (Good / Marginal / Poor) and a `ChannelRangeBars` list sorted worst first: channel, RF, band, range, median snq, health chip.
- Results save as a `SWEEP` session, so sweeps can be compared before and after an antenna change ("vs sweep on Oct 28: 5 better · 1 worse").
- Compare view: two sweeps side by side with the change per channel (up green, down red).
- **Rules:** each channel is claimed and released on its own (last tuner first), so a DVR waits at most one dwell. The dwell counts from the tune; readings start after a 1 s settle (the first second reads seq 0). A channel whose RF channel was already measured in this sweep is skipped: subchannels share the signal. A channel no idle tuner can take (ATSC 3.0 with only the ATSC 1.0 tuners free) or that the device refuses is listed as not tuned. All tuners busy: wait, as Live does. The device not answering three claims in a row stops the sweep, and nothing partial is saved; leaving the screen stops it the same way.
- **Health per channel:** never locked is No lock; losing lock during the dwell is Poor; otherwise the health of the median reading (snq and seq). The Poor card counts No lock too. Worst first: No lock, Poor, Marginal, Good, then by median snq.
- **Comparison:** by guide number, on median snq; a change of 3 points or more counts as better or worse, and gaining or losing lock always counts. "vs sweep on" compares with the device's sweep before this one; the compare view can pick any other.

### 5. Multi-tuner (no mockup yet)

- A 2×2 grid of compact gauges, one per tuner, each on its own channel. Shows whether a problem hits one tuner or all of them, and lets the user compare towers at once.

### 6. Monitor setup (no mockup yet)

- Pick device, tuner, and one or more channels.
- Mode: hold one channel, or rotate through several with a dwell time (default 30 s each).
- Duration (1 h to 72 h, or until stopped), poll interval (1 s default, 5 s battery saver), and dip thresholds (prefilled from Settings).
- Option: fetch weather afterward.
- An estimate line: "About 43,000 readings, 3 MB" plus a battery estimate once measured.
- Start button → the foreground service starts and the Monitor tab switches to the running view.

### 7. Monitor running — `mockups/Monitor.png`

- Elapsed and remaining time with a progress bar, live counters (dips, longest, time good).
- A `SignalTimeline` that grows in real time, with dip bands appearing as they happen.
- Recent dips list, current reading, Stop button with a confirm dialog.
- The ongoing notification shows the current snq, dip count and a Stop action. An optional home-screen widget (Jetpack Glance) shows the same.

### 8. Session detail — `mockups/Session.png` (full scroll)

The main analysis screen, from top to bottom:

1. **Summary cards:** time in good health (%), number of dips, longest dropout, median snq.
2. **Diagnosis cards** from the analysis engine, each with a one-line finding, a Likely/Possible badge, its evidence and a suggested fix.
3. `SignalTimeline` per channel with a weather panel (gusts as bars, toggles for temperature and precipitation).
4. `HourHeatmap` for sessions longer than 12 h.
5. `ChannelRangeBars` when more than one channel was logged.
6. `Histogram` of snq.
7. **Event list:** each dip with time, channel, duration, minimum values and weather at the time; tapping one zooms the timeline to it.
8. Export: CSV of readings, CSV of events, and a one-page summary report (PDF or Markdown) with the charts.

### 9. History (no mockup yet)

- Sessions newest first, each row with kind, date, duration, channels, a tiny sparkline and a health chip.
- Multi-select to compare two sessions or delete.

### 10. Settings (no mockup yet)

- Thresholds, poll interval, units, theme (system / light / dark / true black), home location for weather, data retention, keep screen on, tone range, and an About page with licenses.

## Monitor mode

Monitor mode is the feature no other app has. It needs to survive many hours with the screen off, so the service design matters more than anywhere else.

### Service

- Foreground service with `foregroundServiceType="connectedDevice"`. The HDHomeRun is a LAN device, and this type has no time cap. Do not use `dataSync`, which Android 15 limits to 6 hours a day.
- Manifest permissions: `ACCESS_LOCAL_NETWORK` (Android 17 blocks LAN traffic for apps targeting API 37 without it; a runtime permission in Nearby devices, asked for before the first scan), `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` (the prerequisite for `connectedDevice`), `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Verify the `connectedDevice` prerequisites against current Android docs at build time.
- Hold a partial wake lock and a high-performance `WifiLock` for the session, released in `onDestroy` and on Stop.
- On first Monitor start, ask for a battery optimization exemption, with one sentence explaining why. That's acceptable for an app distributed outside Play.
- Recommend in the setup screen that the phone stays on a charger.

### Polling loop

1. Open one TCP control connection to the device and keep it open. Reconnect with backoff (1, 2, 4, 8 s, capped at 30 s).
2. Claim the tuner: random lock key, `set lockkey`, then tune.
3. After each tune, discard readings until `lock` is not `none`, or 3 s pass (then record a dropout).
4. Poll `status` at the interval and buffer readings in memory.
5. Flush to Room every 10 s in one transaction.
6. In rotate mode, move to the next channel after the dwell time.
7. On stop, normal or not: tune `none`, release the lock key, close the socket.

Store the active lock key and tuner in DataStore. On app start, if a stale lock key is found, release that tuner first. This covers the process being killed mid-session.

### Dip detection

A small state machine per channel:

- **Enter a dip** when snq < the poor threshold or seq < 100 for 2 consecutive readings.
- **Enter a dropout** when `lock=none` on a tuned channel.
- **Exit** after 5 consecutive good readings; the event ends at the first of them.
- **Merge** events less than 10 s apart (same channel only; a dip is final once that window has passed).
- A dip's lowest values count only its bad readings, not the recovery.
- Record start, end, minimum snq and minimum seq as an `Event`.
- **Device unreachable** (3 failed polls) is its own event, so network problems are never mistaken for RF problems.
- **Tuner lost** (a set fails with a lock error, or another client takes the tuner): record the event, try another idle tuner once, otherwise pause and notify. While paused, try again every 30 s; the event lasts until the next reading. A tuner the user picked in setup is waited for, not swapped.
- **Gap** is not recorded while the monitor stops polling on purpose: changing channel in rotate mode, or waiting for a tuner.
- A monitor session the process didn't end (killed) is ended at its last reading on the next app start; the service is not sticky.

Optional dip notifications, at most one per 10 minutes, with a summary ("3 dips on 9.1 in the last hour").

### After the session

- If weather is enabled, fetch hourly temperature, wind, gusts and precipitation from Open-Meteo for the session window and home coordinates (the forecast API for the last ~80 days, the archive before that). A fetch that fails is tried again at the next app start or from Session detail; with no home set, Session detail offers one location fix.
- Write the session's summary for History.
- The diagnosis engine runs when Session detail opens, on the stored readings, events and weather, so findings follow the current thresholds and rules. Findings aren't stored; a session whose readings are deleted keeps its summary only.

## Diagnosis engine

The engine turns a session into plain-language findings, each with evidence and a fix. It is rule-based and deterministic, so every finding can show exactly why it fired. Each finding is labeled Likely or Possible, and the evidence line quotes the numbers.

| Pattern | Rule (starting values, tune with real data) | Finding shown | Suggested fix |
| --- | --- | --- | --- |
| Overload | Median ss ≥ 95 and median snq < 70 | Signal is too strong and noisy | Remove the amplifier or preamp; try a 6–10 dB attenuator |
| Weak signal | Median ss < 35 and median snq < 60 | Signal is weak | Re-aim, raise the antenna, shorten the coax, or add a preamp |
| One channel bad | One channel's median snq is 20+ points below the others' | Problem is specific to that station's tower or RF channel | Check that station's direction and band in a signal report |
| VHF weak | VHF channels' median snq is 20+ points below UHF | Antenna may not receive VHF well | Use an antenna with VHF elements |
| Upper UHF interference | Dips cluster on RF 30–36 while lower channels are fine | Possible cellular (600 MHz) interference | Add an LTE/5G filter |
| Time of day | Over 50% of dip time falls in one 4-hour window, across 2+ days | Dips follow the clock | Look for atmospheric effects or a device on a schedule nearby |
| Wind | Dip rate with gusts over 30 km/h is 3× the rate under 15 km/h | Dips follow the wind | Secure the mast and antenna; check connectors |
| Rain | Dip rate during precipitation is 3× the dry rate | Dips follow rain | Check outdoor connectors and coax for water |
| Heat | Dip rate in the hottest 25% of hours is 3× the coolest 25% | Dips follow temperature | Check amplifier power supply and HDHomeRun ventilation |
| Common path | In multi-tuner sessions, 80%+ of dips start within 2 s on all channels | Everything drops together | Check the shared cable, splitter, amplifier power, or tuner |
| One tuner | Same channel is fine on other tuners but dips on one | Possible tuner fault | Avoid that tuner; contact SiliconDust support |
| Impulse noise | seq drops while snq stays ≥ 80 | Short bursts of interference | Look for LED drivers, motors or power supplies near the antenna or cable |
| Flutter | snq swings 20+ points within 10 s, repeatedly | Multipath or aircraft flutter | Re-aim for a cleaner path; try a more directional antenna |
| Not RF | Device-unreachable events, or seq at 100 during reported glitches | The tuner is fine; the network or player is not | Wire the tuner and player; check the switch and Wi-Fi |

Each rule's exact numbers, and what makes it Likely rather than Possible, are in [`docs/rules/diagnosis.md`](rules/diagnosis.md). Findings are sorted by strength of evidence. A session with no rule firing shows "No pattern found" with the summary numbers, never an empty card.

## Edge cases and constraints

- **Never interrupt a recording.** A tuner whose status shows a channel, or whose lock key is held by another IP, is busy. Sightline only uses idle tuners. Taking a busy one needs an explicit override dialog naming the client IP, and Monitor mode never offers it.
- **All tuners busy:** show who holds each one and offer to wait; retry every 30 s.
- **Plex or Channels DVR grabs a tuner mid-session:** handled as a Tuner lost event. A DVR on the same unit may not use lock keys, so an idle-looking tuner can still be claimed by the DVR. Check that the status `ch` still matches what Sightline set on every poll.
- **Phone leaves Wi-Fi or the network changes:** pause polling, log a Device unreachable event, resume when the device answers again.
- **IP changes (DHCP):** look the device up again by device ID through discovery before giving up.
- **Several HDHomeRuns:** every screen is scoped to one device; History filters by device.
- **ATSC 3.0 (`lock=atsc3`):** tuned by HTTP stream on a tuner that supports it (ADR-0004); log it like any other channel and show an ATSC 3.0 badge. Tag readings so analysis can separate them, and don't use `bps` for them.
- **Old or unusual firmware:** if `status` has a key Sightline doesn't know, keep the raw line; if parsing fails, show the raw line on Live rather than crashing.
- **Clock changes and time zones:** store UTC epoch milliseconds; display in local time.
- **Doze:** the foreground service plus battery optimization exemption keeps polling alive; if a gap over 60 s appears anyway, record a `GAP` event and draw it as a gap in the timeline, not a dropout.
- **Storage:** readings older than the retention setting are deleted on app start; Event rows and session summaries are kept.
- **Privacy:** no analytics, no network calls except to the HDHomeRun and, if enabled, Open-Meteo. Location is used once to save home coordinates and never stored more precisely than 2 decimal places (about 1 km).

## Build, release and testing

Same release path as Glovebox: a public GitHub repo under GPL-3.0, release APKs built by GitHub Actions, installed and updated through Obtainium.

**Build**

- Gradle version catalog, Kotlin DSL, Android Studio project at `~/AndroidStudioProjects/Sightline`.
- GitHub Actions: lint, unit tests and debug build on every push; a signed release APK attached to a GitHub Release on each `v*` tag. Signing keys live in repository secrets.
- Reproducible version names from tags (`v0.3.0` → versionName 0.3.0).

**Testing**

- **Protocol unit tests (JVM):** encode and decode against golden packets captured from a real unit with `tcpdump` while running `hdhomerun_config`. CRC, varlen sizes at 127/128 bytes, and malformed replies.
- **Status parser tests:** real status lines from 8VSB, ATSC 3.0, no-lock and idle tuners, plus unknown keys.
- **Fake HDHomeRun:** a small Kotlin server in test sources that answers discovery and get/set, with scripted signal patterns (steady, slow fade, wind flutter, hard dropouts, device vanishing). Used by the dip detector and diagnosis tests.
- **Demo device:** the same fake built into debug builds as a selectable "Demo tuner", so the UI can be built and screenshotted without hardware.
- **Screenshot tests:** Roborazzi for `ArcGauge`, `TriGauge`, the heatmap and the polar plot in light, dark and true-black themes.
- **Soak test:** a 12-hour Monitor run on a real phone, screen off, on a charger and on battery, to measure gaps and battery drain.

## Milestones

See [`PLAN.md`](PLAN.md) for the task-level breakdown.

1. **Protocol core:** packet codec, discovery, control client, status parser, the fake HDHomeRun, all unit-tested. A debug screen that prints raw status lines.
2. **Live:** Devices and Live screens, `ArcGauge` / `TriGauge` / `Sparkline`, tuner claim and release. This alone replaces the old app.
3. **Aim and Sweep:** full-screen Aim with `PolarAim`, tone and haptics; Sweep with `ChannelRangeBars` and sweep comparison.
4. **Monitor:** foreground service, Room logging, dip detection, running view and notification.
5. **Analysis:** Session detail with `SignalTimeline`, `HourHeatmap`, `Histogram`, event list, diagnosis engine, weather overlay.
6. **Polish and release:** CSV and report export, History compare, Multi-tuner grid, Glance widget, Settings, first tagged release for Obtainium.

## Open questions

- [x] Capture real output from the unit before milestone 1. Done 2026-11-04 from two FLEX 4K units; see `docs/protocol-captures/README.md`.
- [x] Which HDHomeRun model is it, and does it support ATSC 3.0? HDHomeRun FLEX 4K, so yes (ATSC 1.0 and 3.0 tuners).
- [x] Does a DVR record from these tuners? Yes. An HTTP-stream client holds the lock key with its IP, so it shows as busy; every poll still checks that `ch` is unchanged.
- [ ] Keep the name Sightline?
- [ ] Tower bearings in Aim: typed in by hand (current plan), or bundle a station list?
- [ ] Later, maybe: a headless logger on the home server using the same `protocol` module, for always-on logging without a phone.

## Sources

- [SiliconDust: understanding signal strength and quality](https://info.hdhomerun.com/info/troubleshooting:signal_strength_quality)
- [libhdhomerun usage notes (status fields)](https://gist.github.com/dougcooper/cb2ccf40c1a1fda8ccf4dce4a26b75ff)
- [Channels DVR: switch from status.json to the libhdhomerun status API](https://community.getchannels.com/t/beta-hdhr-signal-statistics-for-dvr-recordings/20959)
- [SiliconDust forum: status.json returns percentages only](https://forum.silicondust.com/forum/viewtopic.php?p=405467)
- [SiliconDust forum: Android signal app status](https://forum.silicondust.com/forum/viewtopic.php?p=402746)
