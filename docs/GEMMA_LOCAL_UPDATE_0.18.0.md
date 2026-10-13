# On-device model update — 0.18.0 candidate

The user approved evaluating and integrating a newer on-device model on 13 October 2026 after identifying the target phone as a Galaxy S25 Ultra (SM-S938U). Only the model identity is relevant; no device identifiers or screenshot contents belong in the repository. Online interpretation remains disabled.

This is an unreleased experiment. After its failures, the user chose [offline dictation and manual planning](OFFLINE_DICTATION_0.18.0.md) for the normal installer; advanced interpretation is now disabled by default. The previous Qwen quality failures remain recorded in [the model evaluation report](MODEL_EVALUATION_0.18.0.md). A runtime change or successful build alone does not establish suitable language quality.

## Integration

- Pin LiteRT-LM Android 0.18.0, with CPU inference and four threads for reproducible host/emulator comparison. No GPU/NPU or physical-phone performance claim is made.
- Send separate system instructions and user data through the runtime's conversation API, using a fresh conversation for every request. Remove the Qwen-specific chat tokens. Keep field, whole-plan and legacy schemas separate.
- Generated JSON must still pass the existing production validators, place resolution and explicit review/Apply flow. The runtime has no executable tools. Planning cannot start playback automatically.
- Preserve cancellation ownership: a waiting field cannot cancel another field. An active request cancels native work and waits for its terminal callback before releasing its engine.
- Download an immutable, size/SHA-256-pinned model into app-private storage excluded from Android backup. Retain partial bytes after interruption, validate HTTP range responses and verify the entire file before installation. Keep earlier model files intact during migration.
- Do not place private instructions, credentials, saved places or screenshots in host model experiments. All evaluation cases are synthetic; inference is local.

## Candidate artifacts

Officially documented [Gemma 4 LiteRT models](https://developers.google.com/edge/litert-lm/models/gemma-4) are hosted in the public LiteRT community repositories. Downloads were independently checked against their pinned metadata.

| Candidate | Revision | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| E2B `gemma-4-E2B-it.litertlm` | `b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1` | 2,588,147,712 | `181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c` |
| E4B `gemma-4-E4B-it.litertlm` | `2eee7ac325f20eb8c9ac1d0e972f7c84663062da` | 3,659,530,240 | `0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0` |

The runtime AAR is `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, SHA-256 `706cbb8a87739b2818111bf3a19a78b8f7781586d10f80adf3415581fab7595f`. It contains arm64-v8a and x86_64 native libraries. Its versioned source tag resolves to `b2f686e2ed4718fb84ec398a61dd59ca0f0aff27`.

## Result: language quality blocks release

E4B improved on the earlier Qwen candidate but did not pass the required language checks. The final frozen prompt passed **11 of 16 previously unseen cases**. Do not deliver this integration as a verified 0.18.0 update. See the separate [offline dictation verification record](OFFLINE_DICTATION_0.18.0.md) for the subsequent installer. No hosted provider is enabled, and no physical S25 Ultra inference or microphone performance has been measured.

The production candidate uses a fresh conversation, CPU with four threads, an 8192-token context, greedy sampling and JSON-constrained output. Field and plan requests allow 256 reasoning tokens, included in their respective 500- and 1200-token output budgets. Legacy commands use no reasoning and a 220-token output budget after reproducing the SDK error described below. The overall request deadline remains 90 seconds; cancellation waits for native ownership to finish before another request can begin.

The host evaluation used `litert-lm-api 0.18.0` on Windows, the exact pinned models and production prompt/schema material, and synthetic inputs only. Timing and process RSS are host measurements, not phone measurements. Structural validity includes production-equivalent semantic payload validation; valid JSON by itself is insufficient. Exact expected results were not weakened to improve the score.

| Experiment | Expected result | Production-valid output | Scope |
| --- | ---: | ---: | --- |
| E2B smoke, no reasoning | 0/2 | — | Both initial blockers remained; 256 reasoning tokens also failed both |
| E4B original prompts, no reasoning | 10/20 | 16/20 | Development corpus |
| E4B original prompts, 256 reasoning tokens | 17/20 | 20/20 | Development corpus |
| E4B clarified prompts, 256 reasoning tokens | 19/20 | 20/20 | Development corpus; source `58c3897` prompt |
| E4B final preservation rule, 256 reasoning tokens | 0/1 | 1/1 | Single preservation regression probe; not a full development rerun |
| E4B final prompts, 256 reasoning tokens | 11/16 | 13/16 | Frozen fresh corpus, including one native SDK error |
| E4B legacy, no reasoning | 5/8 exact; 8/8 equivalent under existing action code | 8/8 | Separate synthetic legacy regression, independently source-reviewed |

The clarified development run fixed an unrelated-field request being silently ignored and an inserted stop's stay being applied to the wrong later stop. Its remaining failure copied a numeric stay instead of returning the preserve sentinel. A general preservation instruction and a separate 512-token reasoning probe did not fix it. A numeric stay can clear an existing “until I leave” preference even when the visible minutes are unchanged. Normalizing all equal numbers to preservation would misinterpret a legitimate switch to a timed stay, so no such workaround or expectation change was made.

The fresh failures were concrete: guessing 09:45 without AM/PM; an SDK error on an instruction unrelated to the field; an invalid saved-place reference and unsupported replacement payload; a mode edit with an illegal place payload; and converting “twenty to four in the afternoon” to 16:20 rather than 15:40. No subsequent tuning used these fresh cases. Frozen synthetic cases, final outputs, settings and prompt hashes are retained in [the evidence bundle](evidence/gemma-0.18.0/).

The clarified development run took 16.407–44.796 seconds per request (median 26.750). The fresh run took 6.266–43.157 seconds (median 25.054), including the failed SDK request; peak process RSS was 3,429,715,968 bytes. These results do not establish acceptable phone latency or memory usage.

The legacy raw-output differences were unused fields already ignored or stripped by the existing parser/action code. This is distinct from the plan preservation failure, where the numeric field can change behavior. The 8/8 source-reviewed equivalence is not eight Android journey passes.

## Runtime limitation

The released LiteRT-LM 0.18.0 has an upstream constrained-output defect when enabled reasoning is skipped: `self.parser.inference_caps.ff_tokens`. The [reported issue](https://github.com/google-ai-edge/LiteRT-LM/issues/3463) matches the host failure, and the [upstream correction](https://github.com/google-ai-edge/LiteRT-LM/commit/8b7bb5c231cf269942a5f13779cc7bc01a2f4205) landed after the release. No newer published Android artifact was available at evaluation. This reproduced on both a legacy request and a fresh contextual field request.

Disabling reasoning avoided this error for the legacy corpus, so those requests now use that profile. Disabling it for all requests is not an established solution: the contextual development corpus passed only 10/20 without reasoning. No automatic retry, schema weakening or unreleased native dependency was introduced. The adapter sanitizes returned native exceptions and retains editable input, but the measured Android failure is a fatal signal. The [matched binary and source analysis](evidence/gemma-0.18.0/android-crash-analysis.md) strongly links its explicit trap to the same grammar assertion. A Java/Kotlin exception retry cannot recover that process. Field/plan requests remain exposed; arm64 behavior is unverified.

A final diagnostic removed runtime grammar/response-format enforcement while retaining the same prompts, 256 reasoning tokens and strict application validation. It reused three known failed cases, so it is not fresh validation: only 1/3 passed. The unrelated-field request produced a clarification without the SDK error; the unspecified AM/PM request still guessed 09:45, and the departure request still produced 16:20 instead of 15:40, now inside rejected Markdown fencing. This workaround was not adopted in production or tested on Android, and no further probes were run.

## Android verification

Source `58c38971b6e2be2271b3ab1dd74348ff2b0fc94c` passed [build 38398142161](https://github.com/aviterima/Mirage/actions/runs/38398142161), including 269 JVM tests, blocking lint, pinned signing and packaged-native-library checks. Its [acceptance run 38398142287](https://github.com/aviterima/Mirage/actions/runs/38398142287) ended with an instrumentation process crash during the first native model test after 19 UI passes. Independently parsed XML records 20 of 55 planned tests: 19 passes, one failure, zero errors/skips, and 35 tests unrun. Logcat identifies `SIGILL` / `ILL_ILLOPN` in `liblitertlm_jni.so` on `execution_thread`, after memory pressure. This is distinct from the recoverable host `ff_tokens` error; its cause remains under investigation. The signed 0.17.0-to-0.18.0 upgrade passed, with saved records and key/identity fixture files independently byte-identical. The live trail check passed through fallback. All three downloaded acceptance evidence archives matched metadata size/SHA-256 and passed CRC verification. No complete Android acceptance is claimed.

Source `1d67ecd08a2b765631a97cea5d4c4d548fd1936f` adds the final preservation instruction, disables legacy reasoning and adds a real native planner test through production JSON context, pending correction, saved-place resolution and explicit Apply. It makes two model requests without an interpreter stub and checks that proposing/correcting a plan does not change the committed plan or start playback. Its [build 38400629577](https://github.com/aviterima/Mirage/actions/runs/38400629577) passed 269 JVM tests, blocking lint, signing and packaged-native-library checks. Its [acceptance 38400629515](https://github.com/aviterima/Mirage/actions/runs/38400629515) completed all 56 Android tests: 55 passed, one pending-plan correction case failed, and none errored or skipped. The production-context native planner test passed in 153.860 seconds, the legacy test in 53.284 seconds and the field test in 268.917 seconds; the plan category failed after 540.044 seconds. There was no process crash in this run. The signed upgrade and live trail checks passed, and saved-scenario/key fixture files were independently byte-identical. All three downloaded acceptance archives matched metadata size/SHA-256 and passed CRC verification. This completed run still fails the experimental acceptance gate and does not establish S25 Ultra performance.

The first source `1a090ad` was superseded by `d92fc08`, which added a context-cap rejection and fixed the resolver fixture's Kotlin type. Build 38396771020 compiled and passed 269 unit tests but failed blocking lint because AGP's bundled lint could not read Kotlin 2.4 metadata. Source `58c3897` pins the documented independent lint upgrade to 9.4.1; no lint check was suppressed. Its compiler metadata was independently inspected as Kotlin 2.4.0-dev-2631-for-lint. Earlier superseded acceptance runs are incomplete evidence, not passes.

## Subsequent product decision

The local conversational model is not ready for release. The user explicitly chose offline dictation with editable fields and manual itinerary controls. That subsequent implementation separates basic dictation from the large model readiness gate, disables the conversational Review path and rejects inference before native work in the normal APK. Its [separate offline acceptance](OFFLINE_DICTATION_0.18.0.md) must pass before delivery. Existing experimental tests retain their assertions behind an explicit developer build/test option; they are not counted as offline passes or skips. Hosted interpretation remains unauthorized and disabled.
