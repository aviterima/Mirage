# Android native crash analysis — LiteRT-LM 0.18.0

The Gemma candidate is blocked from release by both language quality and an Android native crash. Passing APK assembly, JVM tests, lint and native-library packaging does not establish working inference.

## Observed Android failure

The [acceptance run for candidate `58c3897`](https://github.com/aviterima/Mirage/actions/runs/38398142287) completed 19 tests before the first native model test failed and the instrumentation process terminated. The Android 14 x86_64 emulator reported `SIGILL`, code `ILL_ILLOPN`, on the native `execution_thread` while interpreting a synthetic saved-Home instruction. This run used a 256-token thinking budget and a JSON response schema.

| Binary evidence | Value |
| --- | --- |
| Official dependency | `com.google.ai.edge.litertlm:litertlm-android:0.18.0` |
| [AAR](https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/0.18.0/litertlm-android-0.18.0.aar) SHA-256 | `706cbb8a87739b2818111bf3a19a78b8f7781586d10f80adf3415581fab7595f` |
| Library | `jni/x86_64/liblitertlm_jni.so` |
| ELF build ID, matching the crash report | `fdb848ddbac46fc10f8e3522b4270d75` |
| Library-relative fault PC | `0x75df36` |
| Instruction at fault | `0f 0b` — `UD2`, an explicit invalid-opcode trap |

Relevant disassembly from that AAR:

```text
75df28: cmp byte ptr [r14 + 0x548], 0
75df30: jne 0x75e016
75df36: ud2
```

Nearby instruction references resolve to the strings `backtrack sampled\n` at `0x19bafc` and `set pending stop\n` at `0x19bb0e`. Those strings and the surrounding control flow match `commit_token_inner` in the pinned [LLGuidance 1.3.0 source](https://github.com/guidance-ai/llguidance/blob/v1.3.0/parser/src/constraint.rs), around `assert!(self.parser.inference_caps.ff_tokens)`. LiteRT-LM's [0.18.0 dependency lock](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/Cargo.lock) identifies that LLGuidance version.

This is **strong static evidence**, not complete symbolic proof: the published library lacks the full symbol table needed to name the faulting function directly. The observed instruction does not support dismissing the failure as an unsupported AVX instruction. Earlier emulator memory-pressure events also do not explain away this explicit fatal trap.

## Relation to the upstream defect

[Upstream issue #3463](https://github.com/google-ai-edge/LiteRT-LM/issues/3463) describes a thinking-budget/JSON-schema interaction when the model skips thinking. In [LiteRT-LM 0.18.0](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/runtime/components/constrained_decoding/thinking_budget_constraint.cc), that branch creates a new grammar state and commits its first token before computing its mask. [Fix `8b7bb5c`](https://github.com/google-ai-edge/LiteRT-LM/commit/8b7bb5c231cf269942a5f13779cc7bc01a2f4205), committed October 8 after the 0.18.0 release, initializes and uses the same grammar state for both operations. The fix was not installed or validated in this candidate.

The Windows runtime returned a catchable error for synthetic legacy and contextual-field reproductions. Although the [Rust source wraps token commitment in `catch_unwind`](https://github.com/guidance-ai/llguidance/blob/v1.3.0/parser/src/constraint.rs), the packaged Android binary instead executed the fatal trap above. **Java/Kotlin exception handling or an exact-error retry cannot recover this process from `SIGILL`.** Source-level error handling must not be represented as measured Android crash containment.

Disabling thinking for legacy commands avoids the observed legacy trigger in host tests. Field and plan requests with thinking enabled retain the vulnerable path; this change is not a general runtime fix. Arm64 device behavior remains unverified. Release requires satisfactory language quality and native Android acceptance with a configuration that addresses the crash.
