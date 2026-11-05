# Protocol captures

Real output from two **HDHomeRun FLEX 4K** units (`HDFX-4K`, firmware `20260326`), captured 2026-11-04. They are the golden test data for `:protocol`
(`protocol/build.gradle.kts` puts this folder on the test classpath) and where they disagree with `SPEC.md`, the
capture wins.

The **DeviceAuth** token (UDP tag `0x2B`, the `DeviceAuth` JSON field) is a credential for SiliconDust's cloud.
It is replaced with `REDACTEDREDACTEDREDACTED` everywhere, with the CRCs recomputed. Never commit a real one.

The captures are also anonymized, so they describe the firmware and not anyone's home: the device IDs are
replaced (`1A2B3C4D`, `1A2B3C5E`), the lineup is cut to the four channels the tests use with neutral names and
no favourites, and program names in `streaminfo` are `PROG-<n>`. Guide numbers, frequencies, signal values,
error texts and private LAN addresses are as captured. Do the same with any new capture.

## Files

| File | What |
| --- | --- |
| `discover-udp.txt` | The wildcard discover request and both devices' replies, in both variants (with and without tag `0x2D`). |
| `control-get-idle.txt` | `get` requests and replies with every tuner idle, including errors for unknown names. |
| `control-tune-8vsb.txt` | Claim with a lock key, tune 9.1 (8VSB, RF 9) and 39.1 (weaker, RF 16), what a second client sees, release. |
| `control-tune-rf.txt` | Tuning by RF channel with `auto:<rf>`. |
| `control-tune-atsc3-syntax.txt` | Every control-protocol form tried for ATSC 3.0; all refused. |
| `control-status-atsc3-http.txt` | Status of a tuner held by an HTTP stream of ATSC 3.0 channel 102.1. |
| `discover-18.json`, `discover-32.json` | `GET /discover.json` from each unit. |
| `lineup.json` | `GET /lineup.json` from 1A2B3C4D, cut to four channels (two ATSC 1.0, two ATSC 3.0). |

Packet files: `> hex` is sent by the client, `< hex` by the device, and the `#` line above says what it was.

## What the captures showed

Differences from the first SPEC, now folded into it:

- **ATSC 3.0 can't be tuned through the control protocol** on this firmware: every form is `invalid channel` or
  `invalid virtual channel`. Only the HTTP stream tunes it (ADR-0004).
- **Only tuners 0 and 1 do ATSC 3.0.** Tuners 2 and 3 answer an ATSC 3.0 stream with HTTP 503,
  `X-HDHomeRun-Error: 806 Tune Failed`. No getset reports per-tuner capability.
- **`/tunerN/vstatus` doesn't exist** (`unknown getset variable`). `streaminfo` lists the programs, `vchannel` the
  guide number tuned.
- **`/sys/model` is the firmware name** (`hdhomerun_dvr_atsc3`); the model number is `/sys/hwmodel` (`HDFX-4K`)
  or `ModelNumber` in `discover.json`.
- **`set lockkey <key>` answers with the holder's IP**, not the key. `get lockkey` gives the holder's IP or `none`.
  A set without the key on a locked tuner: `ERROR: resource locked by 192.168.1.21`.
- **An HTTP stream client holds the lock** (its IP) and a `target` (`http://<ip>:<port>`), so a DVR streaming a
  tuner shows as busy.
- **Error replies carry only tag `0x05`**, without the name echoed.
- **`ch=auto:<rf>` stays `auto:<rf>`** after tuning by RF; tuning by virtual channel gives `<mod>:<freq>`, and
  ATSC 3.0 adds a third field: `atsc3:593000000:1`.
- **`bps` is always 0 on ATSC 3.0.**
- **The first status after a tune can show a lock with `seq=0 bps=0`** (39.1 and `auto:9`, one second after).
- **With `lock=none` the tuner still reports `ss`** (`auto:34`: `ss=100 snq=0`).
- **Discovery replies come several times per device**, once per address the request reached.
- **`/sys/features`** (139 bytes) is the one reply here with a two-byte TLV size.
- HTTP stream errors: `801 Unknown Channel` (404), `804 Tuner In Use` (503), `806 Tune Failed` (503).

## Capturing more

`hdhomerun_config` couldn't reach the units from the Mac these came from (its discover goes out another
interface), and Java there is blocked by macOS Local Network privacy, so they were taken with small Python
scripts speaking the protocol directly. With a working `hdhomerun_config`:

```
hdhomerun_config discover
hdhomerun_config <DEVICE_ID> get /tuner0/status
sudo tcpdump -i any -w control.pcap port 65001
```

Use an idle tuner (not one a DVR is recording on), and redact `DeviceAuth` before committing.

To run the Kotlin client against a real unit from a machine where Java can reach the LAN:
`SIGHTLINE_REAL_HDHR=<ip> ./gradlew :protocol:test --tests '*RealDeviceTest*'`.
