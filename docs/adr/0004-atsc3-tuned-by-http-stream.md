# ATSC 3.0 channels are tuned with an HTTP stream, not the control protocol

On the user's FLEX 4K (firmware 20260326) the control protocol can't tune ATSC 3.0 at all: `set vchannel 102.1`
answers `invalid virtual channel`, and `set channel` with `auto:34`, `atsc3:34`, `atsc3:593000000` and
`atsc3:593000000:1` all answer `invalid channel` (`docs/protocol-captures/control-tune-atsc3-syntax.txt`). The only
thing that tunes it is the HTTP stream other apps use, `http://<ip>:5004/tuner<N>/v<guide>`. While that stream is
open the tuner reports ordinary status over the control protocol (`ch=atsc3:593000000:1 lock=atsc3 ss snq seq`),
and its lock key shows the client's IP.

So for ATSC 3.0 Sightline opens the stream on a tuner it has checked is idle, reads it and throws the bytes away,
and polls status over the control protocol as for any other channel. ATSC 1.0 and QAM keep the lock-key path.

The user chose this over ATSC 1.0 only, and over watching ATSC 3.0 only when another app has it tuned.

## Consequences

- An ATSC 3.0 reading costs about 3 Mbit/s of Wi-Fi while the tuner is held. Fine on a LAN, worth a line in the
  Monitor setup screen.
- The tuner is held by the open connection, not a lock key: closing the stream releases it, and so does the app
  dying, because the socket drops. There is no stale key to recover.
- Only some tuners do ATSC 3.0 (two of four on a FLEX 4K; tuners 2 and 3 answer `806 Tune Failed`), and the
  firmware has no getset that says which. Sightline tries idle tuners in order and moves on after `806`.
- A guide number is known to need the stream only after the control protocol refuses it with `invalid virtual
  channel` and the stream then works; `TunerSessions` remembers that for the session.
- `bps` is always 0 on ATSC 3.0, so analysis can't use bit rate for those channels.
