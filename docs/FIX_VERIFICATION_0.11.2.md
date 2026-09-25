# Mirage 0.11.2 fix verification

Source under test: `2ba4848782fcfb6fe794a2e5c95bbbf183ddacba` on `codex/acceptance-50-20260925`.
Version: 0.11.2, version code 31. This is a branch test build, not a merged rolling release.

## Changes

- Saved snaps have explicit **Use as start**, **Use as destination**, and **Add as stop** actions. Open Saved plans and tap an item's name to reveal its actions; Load remains an explicit separate action.
- Saved routes have **Add to itinerary**. Disconnected routes require approval for a driving connector; cancelling leaves the draft unchanged. Saved source records are preserved. Composition edits the draft, which must be reviewed and started explicitly.
- Duplicate names are rejected case-insensitively with an explanation, preserving the original saved record.
- Saved real-location origins stay real; simulated and queued origins save their effective coordinates. Return-to-start uses the effective draft origin.
- Full replay templates retain configured stay durations rather than shortening them as time passes.
- Arrival-prefixed journeys retain their destination. Spoken seventeen and compound numbers are recognized; negative, fractional, overflowing and malformed durations are rejected.
- Spoken status and the visible warning use the same output-health diagnosis. Tap the top strip for missing/stale GPS or fused-feed details and setup checks. A moving route never proves another app is receiving its location.
- Live layout has one status strip and one compact control bar. Stop remains directly accessible. Follow location, Whole trip and Setup are in Details; speed and ETA remain in the bottom bar.

## Executed results

- JVM: **80 passed, 0 failed, 0 skipped**, including all eight previously failing component checks and three added composition/parser regressions.
- Android build, lint task, offline voice-model packaging and pinned signing-certificate checks passed.
- Emulator: **8 passed, 0 failed, 0 skipped**. Executed checks: saved-list visibility; saved snap endpoint actions; saved route composition action; a full saved-Home to saved-Office route draft; disconnected-route confirmation and cancellation; snap start with fresh GPS and fused-provider timestamps; native Stop; live panel clearance.
- Final screenshots were visually inspected: the two endpoint fields retain the selected saved places, the saved-place actions are visible, the disconnected-route dialog appears, and the live screen has compact panels.
- Live vertical gap between tagged panel bounds: **0.78333336 (78.3%)** of the full Compose root height. This includes system bars in the denominator and is a vertical-clearance measure, not total visible map-pixel area. The old generous probe measured 70.0%; the new bounds include the actual panels. Map tiles were not rendered in this API-key-free emulator build, so this does not verify Google map imagery or live Directions calls.

Distribution build: `0964f8651ab796c15ca44f3a3b05facc9124f0e2`. Its entire `android` source tree matches the emulator-tested commit; the only difference is download packaging in the workflow. APK SHA-256: `d089f8f50e879b32285faf2aafe0c3314bda14b3f5e618c3fc5c09e4af075462`.

Signing certificate SHA-256: `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8`.

Build: https://github.com/aviterima/Mirage/actions/runs/36168505351
Emulator: https://github.com/aviterima/Mirage/actions/runs/36167730553
APK ZIP: https://github.com/aviterima/Mirage/actions/runs/36168505351/artifacts/10879776334

## Verification limits

This is not sign-off of all 50 end-to-end use cases. The original baseline report remains at `ACCEPTANCE_50_EXECUTION.md`.

The emulator uses API 34, x86_64, Google APIs and a Pixel 7 Pro profile. It does not establish Samsung behavior, live traffic/routing correctness, physical GPS restoration, other apps' location consumption, long background sessions, Bluetooth voice operation, large saved collections, accessibility/font-size coverage, or persistence through a real upgrade. Backup/restore is not implemented by this patch.

The warning's cause on the user's Samsung remains unverified. This patch makes diagnosis consistent and actionable; it does not suppress the warning or claim the phone's provider issue is repaired.

The first fix run exposed an unrelated Pixel Launcher ANR over the app. Its screenshots were rejected as visual evidence. Tests now stop that emulator launcher before activity launch and reject screenshot captures covered by another package. The first unit run also exposed an asynchronous real route request from a unit fixture; that fixture now explicitly disables network APIs. No failing assertion was removed to obtain a pass. The older same-name-overwrite test was deliberately updated to the new non-destructive save contract.

The two multi-step UI tests initially found the same place name in the dialog and the draft behind it. Their selectors were scoped to the dialog; all original assertions remain. Screenshot capture now waits for a rendered idle frame.
