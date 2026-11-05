# The application id and the release signing key never change

`io.cosgriff.sightline` and the key the first release is signed with are permanent. Android, and Obtainium,
treat a different id as a different app and refuse an update signed with a different key, so either change
would strand every installed copy.

## Consequences

- The release key lives only in the `release` GitHub environment's secrets and an offline backup.
- Debug builds use `io.cosgriff.sightline.debug`, so both can be installed side by side.
