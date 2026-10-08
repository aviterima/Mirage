# Mirage 0.14.0 release notes

## What changed

- Add saved places, searched locations, map pins, saved routes or saved itineraries to a running simulation.
- Review a destination before confirming it; cancel leaves the active trip unchanged.
- Configure entrance and interior pins, indoor arrival activity, timed stays or staying until departure.
- Replace upcoming stops, adjust stay/arrival settings and undo supported edits.
- Use contextual voice additions and edits with confirmation.
- Keep more of the live map visible while retaining Saved, Add stop, Pause/Resume, Talk and Stop.

## Verification

127 JVM tests and all 24 emulator acceptance tests passed. Both previously failing checks now pass. The signed upgrade from 0.13.1 preserves saved records and settings. [Full validation and APK checksum](VALIDATION_0.14.0.md).

Install `Mirage-0.14.0.apk` as an update. Physical-phone checks remain outstanding as described in the validation report.
