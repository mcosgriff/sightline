# Protocol captures

Real output from your HDHomeRun, used as golden test data and to confirm the field names in `SPEC.md`. Capture this before milestone 1 if you can.

Install the CLI (`brew install hdhomerun` on macOS, or build libhdhomerun from GitHub), tune a channel in any app or with the commands below, then run:

```
hdhomerun_config discover                          > discover.txt
hdhomerun_config <DEVICE_ID> get /sys/model        > sys-model.txt
hdhomerun_config <DEVICE_ID> get /sys/version      > sys-version.txt
hdhomerun_config <DEVICE_ID> get /tuner0/status    > tuner0-status-idle.txt
hdhomerun_config <DEVICE_ID> set /tuner0/vchannel 9.1
sleep 3
hdhomerun_config <DEVICE_ID> get /tuner0/status    > tuner0-status-tuned.txt
hdhomerun_config <DEVICE_ID> get /tuner0/vstatus   > tuner0-vstatus.txt
hdhomerun_config <DEVICE_ID> get /tuner0/lockkey   > tuner0-lockkey.txt
curl -s http://<DEVICE_IP>/discover.json           > discover.json
curl -s http://<DEVICE_IP>/lineup.json             > lineup.json
curl -s http://<DEVICE_IP>/status.json             > status.json
hdhomerun_config <DEVICE_ID> set /tuner0/channel none
```

Optional, for golden packet tests: capture the raw traffic while running a few of the commands above.

```
sudo tcpdump -i any -w control.pcap port 65001
```

Drop the files in this folder. Use an idle tuner (not one Plex or another DVR is recording on).
