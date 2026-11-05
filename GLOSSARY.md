# Glossary

- **Device** — one HDHomeRun unit on the LAN, known by its device ID (hex). Has one or more tuners.
- **Tuner** — one of a device's receivers, `/tunerN`. Idle (`ch=none` and no lock key), used by Sightline, or
  busy (used by another client).
- **Lock key** — a random uint32 Sightline sets on a tuner to claim it. Sets on a locked tuner must carry it.
- **Claim / release** — claim: set the lock key, then tune. Release: `channel none`, then `lockkey none`. Every
  claim is released, on every exit path. ATSC 3.0 is claimed with a **stream** instead.
- **Stream** — an HTTP stream (`/tunerN/v<guide>`) that holds a tuner for ATSC 3.0, which the control protocol
  can't tune (ADR-0004). Read and discarded; closing it releases the tuner.
- **Hold** — how Sightline holds a claimed tuner: a lock key or a stream.
- **Stale lock** — a lock key Sightline saved but never released (the process was killed). Released at start.
- **Channel** — a lineup entry by guide number (e.g. `9.1`), with its RF channel, frequency and band once tuned.
- **Band** — VHF-lo (RF 2–6), VHF-hi (7–13), UHF (14–36).
- **ss** — signal strength, raw RF power, 0–100.
- **snq** — signal-to-noise quality, 0–100. The number to aim by.
- **seq** — symbol quality: share of data received correctly in the last second, 0–100.
- **Reading** — one parsed `status` poll: ss, snq, seq, lock, bps, pps, at a UTC epoch-ms time.
- **Health** — Good, Marginal, Poor or No lock, from a reading and the thresholds. Always shown with a word or
  icon, never colour alone.
- **Session** — one run, of kind `LIVE`, `AIM`, `MONITOR` or `SWEEP`, with its readings and events.
- **Event** — something the dip detector recorded: `DIP`, `DROPOUT`, `TUNER_LOST`, `DEVICE_UNREACHABLE`, `GAP`.
- **Dip** — snq below the poor threshold or seq below 100 for 2 readings in a row, until 5 good readings.
- **Dropout** — `lock=none` on a tuned channel.
- **Sweep** — tuning each lineup channel in turn for a **dwell** time and summarising each.
- **Finding** — one diagnosis-engine result: Likely or Possible, with evidence numbers and a fix.
- **Demo tuner** — the fake HDHomeRun (`FakeHdhr`) built into debug builds, with scripted signal patterns.
