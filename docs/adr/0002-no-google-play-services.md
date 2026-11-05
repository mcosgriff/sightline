# No Google Play services or Google SDKs

Sightline talks to the HDHomeRun on the LAN and, if the user turns weather on, to Open-Meteo. Nothing else.
Google Play services would add a network peer, and a dependency that some phones (de-Googled ROMs, the
Obtainium audience) don't have.

## Consequences

- Fonts (Inter, Roboto Mono) are bundled in `res/font`, not downloadable fonts, which go through Play services'
  font provider. Their OFL licences are in `app/src/main/assets/licences/`.
- Compass heading comes from the platform rotation-vector sensor, and the one location fix for home
  coordinates from `LocationManager`, never FusedLocationProvider.
- No analytics, crash reporting or ads. Check any new library's dependency tree for network code and Google
  pieces before adding it.
