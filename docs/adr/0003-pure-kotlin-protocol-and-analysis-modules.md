# The protocol and the analysis are pure-Kotlin Gradle modules

The first SPEC called for one `app` module with packages. Sightline is split into Gradle modules the way
Waitlight is instead:

- `:protocol` — Kotlin/JVM. The HDHomeRun packet codec, discovery, the control client, the status parser,
  the HTTP helpers.
- `:analysis` — Kotlin/JVM. Signal health, the dip detector, aggregations and the diagnosis rules.
- `:data` — Android library. Room, DataStore, repositories, the Hilt module.
- `:app` — Compose UI, the monitor service, the Glance widget.

"`protocol` has no `android.*` imports" was a rule review had to catch. As a JVM module the compiler enforces it,
its tests run in seconds without Robolectric, and the module could later run headless on a home server (an open
question in SPEC).

## Consequences

- Android pieces the protocol needs (MulticastLock, the Wi-Fi broadcast address) are passed in from `:data` or
  `:app`.
- Module dependencies run one way: `:app` → `:data` → `:analysis` → `:protocol`.
