# Mirage 0.16.0 validation

Validated October 11, 2026 Pacific (October 12 UTC). Version `0.16.0`, code `41`; tested source `e3a2f477e54b81a816e5d39debd9ca7ac8b5745b` on `codex/interaction-audit-20261011`.

## Automated evidence

- [Android build 38203396617](https://github.com/aviterima/Mirage/actions/runs/38203396617) passed APK compilation, 182 JVM tests, blocking lint, packaged offline voice-model checks and the pinned signing check.
- [Acceptance 38203396640](https://github.com/aviterima/Mirage/actions/runs/38203396640) passed all 36 Android emulator tests. The downloaded test XML independently confirms 182 JVM tests and 36 emulator tests, with zero failures, errors or skips.
- All four new audit UI journeys passed, including the actual Android CreateDocument chooser, saving to Downloads, returning to the app and confirming success, followed by independent checks of file bytes, schema, build identity, problem marker, privacy sentinels and size.
- The signed 0.15.2 → 0.16.0 in-place upgrade passed without uninstalling. Saved-record XML, key preference and installation identity matched byte-for-byte before and after the update.
- Live trail-service checks and the existing playback, recovery and offline/contextual voice gates passed. Live Google Places/Directions and physical-phone behavior are not newly established by the deterministic planner fixtures.

## New regression coverage

Thirteen core JVM tests cover bounded persistence, restart and per-event build identity, retention, corruption and unknown-field rejection, nonfatal storage failure, atomic replacement, pause/clear ordering and paused overflow. Five planner integration tests cover matched operation IDs, saved Home selection and save/reopen without starting, rejected inputs, stale search protection, classified route/storage errors and continuation cancel versus confirm while paused. Reports are checked against private-data sentinels.

Four emulator journeys cover user-facing marker/pause/clear controls, actual saved-place interaction evidence and redaction, large text with the keyboard, and native Android CreateDocument export to Downloads followed by independent file readback.

## Installer identity

The installer was reconstructed unchanged from the final acceptance run's four `mirage-tested-apk-part-*` artifacts. Every downloaded artifact ZIP matched GitHub's SHA-256 digest; the reconstructed APK matched the workflow checksum. All 240 APK ZIP members passed CRC checks. The binary manifest confirms the package and version below; the extracted v2 certificate fingerprint matches the existing identity. Local fingerprint extraction is not a separate cryptographic signature-verification step; the pinned CI check and Android in-place installation also passed.

- Filename: `Mirage-0.16.0.apk`
- Package: `com.mirage.app`
- Version: `0.16.0` / code `41`
- Size: 93,849,198 bytes
- SHA-256: `4d66b8631f8e006dcf8c06ff191bbd029fc8b44d7a55705771923015beeb6a5c`
- Signing certificate SHA-256: `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8`

Install over the existing app; do not uninstall first. The delivered Drive file's name and size were read back after upload. A final documentation-only commit changes five Markdown files after the tested source; production code and tests are unchanged.

## Visual evidence

Reviewed `audit-problem-marker.png`, `audit-large-text-controls.png`, `audit-paused-empty-history.png` and `audit-private-planner-report.png` from the final acceptance artifact. Audit controls and text are legible without overlapping controls, clipped labels, keyboard obstruction or system-dialog overlay. Marker captures show the complete marker ID and sharing instructions. The paused capture shows recording off, zero events and the disabled problem-marker control; Clear history is below that capture's current viewport.

The `audit-native-export-saved.png` capture still shows the Android Downloads chooser, including the created JSON file; it is not clean visual proof of the returned app confirmation. Export completion is established by the passed Compose confirmation assertion and independent file readback. This review covers the new audit surfaces, not full app layout or physical-phone acceptance.

## Earlier attempts and corrections

The initial [build 38202097044](https://github.com/aviterima/Mirage/actions/runs/38202097044) and [acceptance 38202097047](https://github.com/aviterima/Mirage/actions/runs/38202097047) failed Kotlin compilation because nullable enum parsing needed explicit generic arguments. No test results from those attempts are counted. Commit `6d3774b` corrected the inference errors without removing assertions or blocking checks.

[Build 38202429513](https://github.com/aviterima/Mirage/actions/runs/38202429513) then passed compilation, all 182 JVM tests, lint, voice packaging and signing. [Acceptance 38202429549](https://github.com/aviterima/Mirage/actions/runs/38202429549) passed the live trail-service and upgrade checks but failed its native export test while waiting for Compose confirmation through UiAutomator. That incomplete run was canceled and is not counted as a pass. Commit `e3a2f47` changed only the test: it restores Compose synchronization after the external chooser and scrolls to the confirmation, retaining all independent file-readback assertions and adding failure diagnostics. Production code is unchanged from `6d3774b`. The final runs above passed at this exact source.

## Remaining acceptance

Physical-phone confirmation remains separate from emulator results: the user's stored Home record and device layout, Android document providers, microphone/wake-word behavior, latency, battery and heat. Live Places/Directions are not newly established by deterministic planner fixtures. Local Android build and device tests were not run because this host lacks the toolchain.

Diagnostic reports contain user data even with a restricted schema. Real reports must stay out of source control. The acceptance report contains disposable fixture interactions only. Original source checkouts, main and the rolling release remain unchanged.
