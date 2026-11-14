# Firmware updates are downloaded from SiliconDust and uploaded over the control protocol

Until now Sightline called nothing but the HDHomeRun and, optionally, Open-Meteo. The user asked for firmware
updates from the app, and chose (2026-11-14) to have Sightline download the file from SiliconDust over picking a
file by hand or only showing that an update exists.

The tuner's own web page has an update button only while an update is pending, so how it triggers the update
couldn't be seen: neither unit had one on offer. The documented path is libhdhomerun's: `hdhomerun_config <id>
upgrade <file>` sends the image over the TCP control connection (packet types `0x0006`/`0x0007`; SPEC "Firmware
upload"). Sightline does the same. Files come from `https://download.silicondust.com/hdhomerun/`, named by the
device's `FirmwareName`; the unversioned name redirects to the newest, so one `HEAD` request finds it.

## Consequences

- SiliconDust's download server is the one host besides the tuner and Open-Meteo that Sightline calls, and only
  when the user taps Check or Install on Device tools. Only the firmware name (public, the same for every unit of
  a model) goes out: no device ID, no DeviceAuth, nothing about the phone.
- There is no checksum to check the file against. HTTPS keeps it intact in transit, and the tuner checks the image
  itself and refuses a wrong one (`ERROR: invalid image`), keeping its firmware.
- libhdhomerun forces tuners 0 and 1 free before uploading. Sightline never takes a tuner in use, so it refuses
  to start unless every tuner is idle, checks again after the download, and does no update during a channel scan.
- The device restarts and is off the network for a while. Sightline waits up to 3 minutes to see the new version,
  then records it on the device, so new sessions carry it.
- If SiliconDust renames or moves the files, Check reports it can't find them; nothing is uploaded.
- The Demo tuner serves made-up files in the same layout, so debug builds never download real firmware for it.
