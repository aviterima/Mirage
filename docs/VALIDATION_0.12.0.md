# Mirage 0.12.0 validation record

Release validation date: September 30, 2026 (America/Phoenix).

## Final signed release result

- Tested code commit: `cde80b4d7a016633ff2f5f5c0f706d4ec7b393c2`.
- Standard build: https://github.com/aviterima/Mirage/actions/runs/36726089619 — success.
- Signed acceptance: https://github.com/aviterima/Mirage/actions/runs/36726089706 — success.
- 101 JVM tests: zero failures, errors, or skips.
- 15 Android API 34 emulator tests: zero failures, errors, or skips. Fourteen cover actual Activity journeys; one calls the actual native Qwen model.
- The native request returned `add_saved` / `Home` / `NEXT`. Instrumentation recorded 12,146 ms; the test report records approximately 12.142 seconds. These are four-core emulator measurements, not physical-phone latency.
- The exact previously delivered 0.11.3 APK was installed, representative saved records and settings were seeded, and the new APK was installed with `adb install -r`. Saved records, key, and install identity compared unchanged after the upgrade.
- The signing certificate SHA-256 is `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8`, matching the pinned existing identity.
- Downloaded artifact ZIP hashes matched GitHub's reported digests. Reassembled APK SHA-256 matches the passing run: `a92ed17acf582498f33213d3bc9bd47842ded9d3c349fe6f348edca3a390428d`; size 93,537,055 bytes. No rebuild or re-signing occurred after testing.
- Screenshots were reviewed for live saving and clear map/review states. The live-save journey persisted the same record ID while growing the trip and kept the simulation paused. One screenshot in the final run was obscured by a “Pixel Launcher isn’t responding” system dialog; it names the emulator launcher, not Mirage. The same unchanged itinerary UI is unobscured in the first run, and the final map screenshot is clear. The screenshot limitation is retained in the evidence and is not presented as a clean visual pass for that frame.

The first failure and corrective change are retained below. This evidence supports the listed gates, not an assertion that every possible workflow or every physical device is bug-free.

## Completed locally

- Android tasks `assembleDebug`, `assembleDebugAndroidTest`, and `testDebugUnitTest` completed successfully. Both arm64-v8a and x86_64 native libraries compiled with Release optimization (`-O3`). The locally signed APK is a development artifact, not an update for the delivered APK.
- All 101 JVM tests passed: zero failures, errors, or skips, including the six new live-save/voice-validation tests.
- Native llama.cpp integration also compiled successfully on the Linux development host.
- Official Qwen3-0.6B Q8_0 bytes downloaded and verified against SHA-256 `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`.
- Ten actual-model smoke inputs executed through the same C++ grammar and model used by Android. With the revised prompt, seven matched every expected raw field. Host cold-process durations were approximately 3.7–4.7 seconds. These are host timings, not Android-phone benchmarks.
- The other three raw outputs showed why app-side validation is necessary: a saved route was classified as a place search; an unused target field contained a saved name for Save changes; and a status paraphrase returned clarification. Application handling now resolves an exact saved target, discards unused target fields, and retains a direct status parser. These safeguards do not establish broad model accuracy.
- The earlier prompt also guessed Home for “Take me there.” The revised prompt returned clarification in this smoke test, and an independent grounding check rejects invented target words even if a future model output repeats that mistake.

The raw results are retained in `verification/voice/host-smoke-results.json`; the Android build/unit summary is `verification/voice/android-local-test-summary.json`. Exact unused-field matching is deliberately reported separately from application behavior.

## First signed CI attempt — September 30

Published source: `3e744f94634029910c6fab19ae56b57839914af6`. Standard Android workflow 36723592557 passed: APK build, 101 unit tests, packaged speech-model verification, and pinned signing certificate verification.

Acceptance workflow 36723592630 verified the in-place upgrade from the exact delivered 0.11.3 APK. Stored records, key, and install identity were preserved. Fourteen UI tests passed, including the new live add/save/update/reload journey and the existing map-space and continuation tests. The fifteenth test, actual native language-model inference, failed after 45.141 seconds on the two-core emulator. This was a deadline failure, not a complete acceptance pass.

The follow-up caps native inference threads at the available logical cores (maximum four), configures four emulator cores, distinguishes timeout from cancellation/decode failure, and records model-test elapsed time. It retains the 45-second deadline and the real inference assertion.

## Prepared tests and gates

New JVM tests cover persistent save/update with stable IDs, saving a copy without changing the original, duplicate names, dirty-state behavior as stay time elapses, failed writes, strict voice output parsing, and destination grounding.

The expanded Activity test walks through a paused live simulation, adds a saved snap, saves a named itinerary, adds a saved route, updates the same itinerary, reloads stored records, and asserts the simulation remains active and paused. A separate instrumentation test calls the real native model on Android. CI stages the verified model; without it, that test is explicitly skipped.

The pipeline is prepared to test an in-place update from the exact delivered 0.11.3 APK, hash `ec840c038e55ead26fa86dad749c85528a815e96277760d6782485ec0cc707bf`.

## Remaining physical-device validation

Phone UI feel, microphone/proper-name recognition, model download experience, latency, battery, heat, and large saved collections remain untested on the user's physical phone. The model can still misunderstand requests; confirmation and grounding checks remain necessary.

There is no claim that all historical 72 scenario definitions were executed on a physical phone. Previous intermittent Compose rendering failures remain worth monitoring, although all 14 current UI journeys passed in both signed runs.
