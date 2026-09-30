# Mirage 0.13.0 validation

Status: signed and emulator-tested release. Physical-phone validation remains outstanding.

## Passed release

Source commit: `e76c2dacc017f638d3d519ece34c7e23cc9464e5`.

- Android build: https://github.com/aviterima/Mirage/actions/runs/36758203088
- Signed emulator acceptance: https://github.com/aviterima/Mirage/actions/runs/36758203157
- Permanent signing certificate: `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8`.
- Upgrade baseline: delivered 0.12.1, SHA-256 `0904d27fd8366880a1b9782038dfd61a7341051b0c72825961f5462e47fcccee`.

## Final evidence

- 117 JVM tests and 21 API 34 Android emulator tests: zero failures, errors or skips.
- Both linked workflow runs completed successfully. No release gate was removed or weakened.
- Signed in-place upgrade from 0.12.1 passed; saved records, API-key preference and install ID compared unchanged after installation.
- APK: 93,652,478 bytes; SHA-256 `dc1376437ee8650a1328d1f3de771db9d02ead03113cc25146baa74b115f73d3`. Downloaded archive digests and the assembled APK match CI. The delivered APK was not rebuilt or re-signed after testing.
- Real native model inference passed: 16,772 ms on the four-core emulator. This is not a physical-phone benchmark.
- The keyboard test uses 24 stops and 1.3× font scale, requires the IME to be visible, and checks that the input lies above the entire footer. The same test scrolls the order proposal to its final stop.
- Final screenshots were reviewed for the loaded planner, larger-text keyboard input and contextual voice flow. The larger-text screenshot now shows the complete typed input above the footer and keyboard.

## Evidence scope

The added JVM checks cover backup round trip and malformed input rejection, import conflicts and undo, failed storage writes, named-save identity and metadata, order proposals and arrival-target guards, archived geometry/timing, remaining archived geometry on recovery, completed-stop and checkpoint recovery fidelity, waiting for scheduled departure, progress-aware live undo, and follow-up identity/staleness/duplicate-name guards.

The added Activity checks exercise draft restoration without starting simulation, favorites/delete/undo, a 24-stop plan with larger text and keyboard entry, and a saved voice alias followed by a confirmed contextual stay edit while paused. Existing checks cover swap, drag/button reorder with save/reload, live additions and save/update, route connectors, source-template preservation, cancel, clear-map area, Stop, and actual native model inference.

## Earlier attempts

1. `043bb81524002c6e74766f9ffe48013d091968c5`: acceptance run 36751992844 failed Kotlin compilation because recovery referenced ActivityKind without its import. The import was corrected.
2. `69a91c14fa244dbb8773d79c617c2a863a09b94e`: standard run 36752959699 passed. Acceptance run 36752959500 ran 20 Android tests: 18 passed and two failed, with no skips. A loaded itinerary left its former destination in the Add-stop input; the field was corrected to remain empty. A background state emission resumed map recomposition off the main thread under the Compose test dispatcher; UI state collection was explicitly constrained to the main dispatcher. The contextual voice, native model and signed upgrade checks passed in this attempt, but it is not the delivered artifact. These failures were retained and fixed; tests were not removed or skipped.

3. `75cacadf162a4cd2a4e344893fd2953dfc96807a`: standard run 36754942097 passed all 117 unit tests. Acceptance run 36754942197 ran 21 Android tests: 20 passed, one failed, no skips. The previous UI/thread failures passed. The remaining assertion compared a used saved itinerary including its new recent-use timestamp against the original. The corrected test explicitly requires a newer timestamp and compares every other field unchanged. The full suite remains enabled. Screenshot review also caught an input/footer overlap with the keyboard open despite the old visibility assertion passing. The fix measures the entire footer and bounds the entire planner viewport, explicitly resizes for the keyboard, and scrolls the focused input into view. The strengthened test requires the keyboard to be open and the input to lie above the full footer. A scrollable order-proposal preview is included for long itineraries.

4. `d40c86dd1210c3d3532d2fffda24fa17026a2949`: runs 36757890581/36757890585 were superseded before completion by the keyboard fix. They are not release evidence.

## Practical limits

- API 34 emulator tests do not establish physical-phone microphone accuracy, wake-word behavior, latency, thermal/battery behavior, accessibility-service ergonomics or drag feel.
- Structured voice tests use typed text through the same command path. Native model inference is separately exercised with the real model. The Vosk recognizer is unchanged.
- Backup codec and store behavior are tested; the complete Android document picker/share-to-third-party-app round trip still needs phone validation.
- Recovery logic and Activity draft restoration are exercised. Device power loss, OS process eviction and every background-service policy are not exhaustively simulated. Checkpoint position can lag by about 15 seconds.
- Road paths retain geometry and archived timing data; actual playback remains governed by the selected motion settings. A resumed archived leg uses proportional remaining timing. Transit and route connectors still require fresh routing.
- Order optimization is geometric and preserves the last stop; it is unavailable with arrival targets. Arrival targets report lateness, not guarantee timely arrival.
- One previous saved collection is retained. This is not an unlimited version-history system.
- No claim is made that every case in the historical 50-case execution report was rerun.
