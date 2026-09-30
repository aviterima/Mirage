# Mirage 0.12.1 validation

## Passed signed release

- Tested source and tests: `135a9d0a8cc2a90cb358c84040db56077035cfb3`.
- Android build: https://github.com/aviterima/Mirage/actions/runs/36743166779 — success.
- Signed acceptance: https://github.com/aviterima/Mirage/actions/runs/36743166795 — success.
- 104 JVM tests: zero failures, errors or skips.
- 17 Android API 34 emulator tests: zero failures, errors or skips.
- New Activity tests clicked Swap twice, dragged a stop, used the handle's Move down menu, saved the result, verified persisted order/stay/mode, and loaded it again.
- JVM tests verify multi-position insertion with relative order preserved, invalid-index no-ops, saved stop settings, endpoint labels/coordinates/metadata, missing-endpoint guards and no automatic playback start.
- In-place upgrade from the exact delivered 0.12.0 APK passed. Saved records, API-key preference and install ID compared unchanged after `adb install -r`.
- Permanent signing certificate SHA-256: `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8`.
- Delivered APK: 93,553,439 bytes; SHA-256 `0904d27fd8366880a1b9782038dfd61a7341051b0c72825961f5462e47fcccee`. Downloaded archive digests and the reassembled APK match CI. No rebuild or re-signing after testing.
- Existing local-model regression passed; native inference logged 16,205 ms on the emulator. This is not a physical-phone benchmark.
- Reviewed `route-swapped.png` and `itinerary-dragged.png`: the double-arrow is beside the route fields and drag handles are visible beside numbered stops. The expanded lower settings panel can cover the Add a stop field; users must collapse that panel with its chevron. This existing planning-layout limitation remains and is not described as a fully resolved usability issue.

## First attempt

Commit `b967feed9f5e960dae0c5adad7ea4b1ce8df30b8` compiled both the app and instrumentation code. Its 104 JVM tests contained one failure caused by an asynchronous network request escaping a new endpoint-state test. CI supplied a real routing configuration; after the test restored the main dispatcher, that request completed during an unrelated flight test. The stack trace identifies GoogleDirectionsRouteEngine and a missing main dispatcher.

The correction explicitly gives the endpoint-state test an empty API configuration. No production code or test assertions were removed. Build 36742159569 and acceptance 36742159580 are retained as failed attempts, not release evidence.

## Limits

Physical-phone drag ergonomics and long-list edge scrolling still need manual validation. This update does not establish new physical-device voice, latency, battery or thermal results. Dragging is available in the planning form; live My itinerary retains its existing move buttons.
