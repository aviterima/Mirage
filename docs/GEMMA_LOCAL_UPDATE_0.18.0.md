# On-device model update — 0.18.0 candidate

The user approved evaluating and integrating a newer on-device model on 13 October 2026 after identifying the target phone as a Galaxy S25 Ultra (SM-S938U). Only the model identity is relevant; no device identifiers or screenshot contents belong in the repository. Online interpretation remains disabled.

This is an unreleased integration candidate. The previous Qwen quality failures remain recorded in [the model evaluation report](MODEL_EVALUATION_0.18.0.md). A runtime change or successful build alone does not establish suitable language quality.

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

## Verification in progress

The current integration selects E4B with a bounded 256-token reasoning budget and a 90-second request deadline. Output budgets include reasoning: 500 tokens for fields and legacy commands, 1200 for plans. This is a candidate selection for testing, not a quality claim. E2B failed both initial smoke cases; E4B with reasoning passed those two, while E4B without reasoning still made errors elsewhere. Full results are pending.

The host evaluation uses the same LiteRT-LM version and production prompt/schema material, with the original 20-case corpus and 16 independently frozen fresh cases. Field values, operation payloads and meaningful clarifications must pass production-equivalent validation, not merely parse as JSON. Preserve failed controls and distinguish exact representation checks from actual resolved-plan semantics.

Build, unit tests, native Android inference, signed upgrade, download behavior and physical S25 Ultra measurements remain to be recorded for this integration. Latest verified delivery remains 0.17.0. No 0.18.0 APK is released by this document.

The first source `1a090ad` was superseded by `d92fc08`, which adds a context-cap rejection and fixes the resolver fixture's Kotlin type. Build 38396771020 compiled and passed unit tests but failed blocking lint because AGP's bundled lint could not read Kotlin 2.4 metadata. A follow-up pins the documented independent lint upgrade to 9.4.1; checks are not suppressed. Its compiler metadata was independently inspected as Kotlin 2.4.0-dev-2631-for-lint. The d92fc08 acceptance run was still in progress at supersession, not a completed pass.

The original-prompt E4B host evaluation passed 10/20 cases without reasoning and 17/20 with 256 reasoning tokens. The latter produced structurally valid output for all 20, but missed a field-scope clarification, applied an inserted stop's dwell to the wrong later stop, and emitted an explicit dwell instead of the preserve sentinel during replacement. No quality gate or expectation was relaxed. One general prompt revision clarifies field scope and requires an added/replaced place's stated dwell to be included in that same edit, with subsequent indices recomputed. Full rerun and fresh-case verification are pending.
