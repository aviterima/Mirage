# Mirage 0.14.0 validation record

Validated October 8, 2026 UTC (October 7 Pacific). Version name 0.14.0, version code 37.

## Source and evidence

- Tested source commit: `474e27511365442f2dd583c9809810252aabdde1`.
- Branch: `codex/saved-destinations-indoor-arrival-20261006`.
- [Android build, unit tests, lint and signing verification](https://github.com/aviterima/Mirage/actions/runs/37718387231): success.
- [Emulator acceptance and in-place upgrade](https://github.com/aviterima/Mirage/actions/runs/37718387401): success.
- 127 JVM tests: zero failures, errors or skips.
- 24 Android emulator tests: zero failures, errors or skips. Android 14 / API 34, Pixel 7 Pro profile.
- APK version remains 0.14.0; this corrects the previously undistributed candidate.

## Corrections verified

The Saved-library test previously matched the destination label in both the editor and underlying preview. Destination and Cancel selection are now scoped to the editor. The test checks that cancellation preserves the active plan, then reopens the same saved item, confirms it, and verifies the destination, trip origin and running state.

The live header and footer have smaller exterior vertical margins. Touch target sizes and system-bar insets are preserved. The unchanged 75% clear-map-height check now measures **75.19231%**, up from **73.846155%**. The map-preview clearance test also passes.

All other acceptance journeys passed, including adding saved routes and itineraries during simulation, paused-state preservation, saved-source visibility at larger text size, save/update/reload during simulation, contextual voice confirmation, actual offline native model inference, route endpoint swap, itinerary reordering, and native Stop.

## Upgrade and delivered APK

The pinned signing identity was restored and checked. Installing over the verified 0.13.1 baseline succeeded without uninstalling. Saved scenario records, API-key preference and installation identity were preserved byte-for-byte by the upgrade check.

The delivered APK was reconstructed from the successful acceptance run's unchanged transport parts. Its SHA-256 matches the workflow's `SHA256.txt` exactly; ZIP integrity also passed.

- Filename: `Mirage-0.14.0.apk`
- Size: **93,685,242 bytes**.
- SHA-256: `9cf9cea2440f62d22e0727ed4b934aded026d420b41457dc5c175fa6d92fad3c`

Install as an update over the existing Mirage app. Do not uninstall first if you want to retain saved places, routes and itineraries.

## Scope and remaining device checks

These results validate the automated suite, not every physical-device or live-network condition. Phone-specific microphone/wake-word behavior, screen-off operation, battery use, and indoor-pin placement still require physical-phone acceptance. Indoor walking is an approximate user-selected pin connection; it does not infer actual rooms, tables, floors or accessible paths.

The map-clearance screenshot captures startup before location-output confirmation; the separate fresh-provider test passes. The saved-library confirmation screenshot shows active simulation and the confirmed destination. The snapshots verify layout and journey state, not final map-camera framing on every device.
