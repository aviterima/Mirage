# Mirage 0.12.0 validation record

Candidate date: September 29, 2026 (America/Phoenix).

## Completed locally

- Android tasks `assembleDebug`, `assembleDebugAndroidTest`, and `testDebugUnitTest` completed successfully. Both arm64-v8a and x86_64 native libraries compiled with Release optimization (`-O3`). The locally signed APK is a development artifact, not an update for the delivered APK.
- All 101 JVM tests passed: zero failures, errors, or skips, including the six new live-save/voice-validation tests.
- Native llama.cpp integration also compiled successfully on the Linux development host.
- Official Qwen3-0.6B Q8_0 bytes downloaded and verified against SHA-256 `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031`.
- Ten actual-model smoke inputs executed through the same C++ grammar and model used by Android. With the revised prompt, seven matched every expected raw field. Host cold-process durations were approximately 3.7–4.7 seconds. These are host timings, not Android-phone benchmarks.
- The other three raw outputs showed why app-side validation is necessary: a saved route was classified as a place search; an unused target field contained a saved name for Save changes; and a status paraphrase returned clarification. Application handling now resolves an exact saved target, discards unused target fields, and retains a direct status parser. These safeguards do not establish broad model accuracy.
- The earlier prompt also guessed Home for “Take me there.” The revised prompt returned clarification in this smoke test, and an independent grounding check rejects invented target words even if a future model output repeats that mistake.

The raw results are retained in `verification/voice/host-smoke-results.json`; the Android build/unit summary is `verification/voice/android-local-test-summary.json`. Exact unused-field matching is deliberately reported separately from application behavior.

## Prepared tests and gates

New JVM tests cover persistent save/update with stable IDs, saving a copy without changing the original, duplicate names, dirty-state behavior as stay time elapses, failed writes, strict voice output parsing, and destination grounding.

The expanded Activity test walks through a paused live simulation, adds a saved snap, saves a named itinerary, adds a saved route, updates the same itinerary, reloads stored records, and asserts the simulation remains active and paused. A separate instrumentation test calls the real native model on Android. CI stages the verified model; without it, that test is explicitly skipped.

The pipeline is prepared to test an in-place update from the exact delivered 0.11.3 APK, hash `ec840c038e55ead26fa86dad749c85528a815e96277760d6782485ec0cc707bf`.

## Pending / blocked

- Signed APK: not built. Automatic approval review rejected the upload to the existing public `aviterima/Mirage` repository, including after the connected account and repository permissions were verified.
- Android emulator test execution, including actual Android native model inference: not run for this candidate.
- In-place signed upgrade: not run for 0.12.0.
- Physical-phone UI, microphone, model download experience, latency, battery, thermal behavior, and large saved collections: not tested.

Previous 0.11.3 had 95 passing JVM tests and 13 passing emulator tests on retry. Those historical results are not evidence that this changed candidate passes. The earlier intermittent Compose rendering crash remains a regression risk to watch.
