# Mirage 0.16.1 — place names with cities

Status: validated October 12, 2026 (Pacific and UTC). Android build, **195 JVM tests and 38 emulator tests**, lint, signing and the in-place upgrade passed. Version `0.16.1`, code `42`, on `codex/place-city-labels-20261012`, based on verified 0.16.0 at `dc125088bdf69a5f868c4965eb758adaa33d938f`.

## Behavior

Location lookup displays the place name followed by its city in parentheses, for example **Western Digital (Irvine)**. That same name follows the chosen result into start/destination fields, itinerary stops and new place settings. The address remains separate.

The city comes from structured Places address components: `locality`, then `postal_town` when no locality is supplied. County, state and postal code are not guessed as a city. Missing city information leaves the name unchanged; an existing identical city suffix is not appended twice.

When saving a location in Jump mode, the editable Name field starts with the selected location name. Typing a custom name keeps that edit through recomposition. Route, itinerary and active-trip names retain their existing behavior. Existing saved records and custom labels are not renamed or migrated.

Voice comparisons accept the full spoken name and city without parentheses, such as "Western Digital Irvine". All name and city words must match; ambiguous names still require clarification, and trip changes still require confirmation. Displayed and stored names keep their parentheses.

No extra place-details or geocoding request is introduced. The Places search field mask requests address components in the existing response; the gateway already forwards this field mask. No server deployment is needed. Audit privacy boundaries remain unchanged: place names, addresses and query text are not recorded.

## Verification

Tested source: `769becbc4d3baab7a0cda4293c69fcd6af3794be`.

[Build 38249400772](https://github.com/aviterima/Mirage/actions/runs/38249400772) passed APK compilation, all **195 JVM tests** with zero failures or ignored tests, blocking lint, offline voice-model packaging and pinned signing. The downloaded unit report independently confirms the count and matched its artifact SHA-256. Six new HTTP-fixture tests cover structured city parsing and fallback; seven voice tests cover full-name matching, canonical binding and ambiguity rejection.

[Acceptance 38249400777](https://github.com/aviterima/Mirage/actions/runs/38249400777) completed successfully on attempt 2. Downloaded XML independently confirms **195 JVM tests across 30 suites and 38 Android tests**, with zero failures, errors or skips. Both new lookup-to-save journeys passed: accepting the prefilled name unchanged, and editing it while keeping an existing saved Home record intact. The tests exercise the real Places selection, save form, persistence and reopening with deterministic provider data. Existing workflow and audit journeys also passed.

The signed **0.16.0 → 0.16.1** in-place upgrade passed without uninstalling. Saved-record and key XML files were independently compared and are byte-identical; install identity was preserved. Live trail-service checks passed through the backup provider after an initial HTTP 504.

Acceptance attempt 1 stopped before compilation because the Gradle distribution download timed out; it produced no tests. Only the failed jobs were retried, with no source change. Earlier runs at source `74dab33` were superseded and canceled after the voice compatibility correction; they are not release evidence.

The two new save-form screenshots were inspected. The prefilled **Western Digital (Irvine)** and edited **My office** names are readable, with the Save control visible and no launcher overlay. This is limited visual evidence for the tested emulator and save form, not a complete device/layout review.

## Delivered installer

The APK was reconstructed unchanged from acceptance artifacts `mirage-tested-apk-part-00` through `03`. All seven downloaded acceptance artifact ZIPs matched their GitHub byte sizes and SHA-256 digests before extraction. The assembled APK matches the workflow's `tested-parts/SHA256.txt`.

| Identity | Value |
| --- | --- |
| File | `Mirage-0.16.1.apk` |
| Package | `com.mirage.app` |
| Version / code | `0.16.1` / `42` |
| Bytes | `93865582` |
| APK SHA-256 | `7c56e45a5619687ee446fb4ef8ddbb730c710d9d7f9e3b804ccf980b779a433e` |
| Signing certificate SHA-256 | `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8` |

Local ZIP CRC, binary manifest identity and signing-certificate fingerprint checks passed. CI performed signature verification; the local helper extracts the certificate fingerprint and does not independently verify the cryptographic signature. The final documentation commit changes only `README.md`, `HANDOFF.md`, `SPEC.md` and this report after tested source `769becbc4d3baab7a0cda4293c69fcd6af3794be`.

Physical-phone and live Places/Directions confirmation remain open. No local Android execution was performed because this host lacks the toolchain. Main, the prior feature branches, recovery checkout and rolling release were preserved; no server deployment was performed.
