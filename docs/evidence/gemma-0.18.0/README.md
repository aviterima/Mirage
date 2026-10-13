# Gemma local model evidence — 0.18.0 candidate

**The language-quality gate failed. This evidence does not establish a release.**

Actual inference used the public, checksum-verified Gemma 4 E2B/E4B LiteRT models and the official LiteRT-LM Python API 0.18.0 on a Windows AMD Ryzen 5 7640HS host, four CPU threads, no GPU, and an 8,192-token context. No user transcript, credential or itinerary was used. Host measurements are not Android acceptance or physical-phone measurements.

The [manifest](manifest.json) records immutable model revisions, measured model hashes, the installed native-library hash, configuration, canonical schema hashes, and normalized model-visible prompt hashes. Final [field](final-field-prompt.txt) and [plan](final-plan-prompt.txt) prompts are retained. No model binaries or internal reasoning text are included.

## Results

| Experiment | Exact contract passes | Latency min / median / max |
| --- | ---: | --- |
| E2B no thinking, two original blocker cases | 0/2 | 6.34 / 8.84 / 11.33 s |
| E2B 256-token reasoning, same two cases | 0/2 | 21.75 / 22.81 / 23.88 s |
| E4B no thinking, original development set | 10/20 | 6.36 / 11.88 / 14.78 s |
| E4B 256-token reasoning, original prompts | 17/20 | 13.38 / 28.16 / 44.41 s |
| E4B 256-token reasoning, field-scope and same-edit stay clarification | 19/20 | 16.41 / 26.75 / 44.80 s |
| E4B 512-token preservation probe, same clarified prompt | 0/1 | 46.50 s |
| E4B final preservation-contract sentence, 256-token probe | 0/1 | 43.52 s |
| E4B final prompts, frozen new holdout | **11/16** | **6.27 / 25.05 / 43.16 s** |

The twenty original cases became development cases after prompt adjustments. The [new sixteen-case holdout](fresh-holdout.json) was frozen before inference with SHA-256 `79626e930a0ad7641db4a877d6ebeb2432889ea4b9274cdd0765f8e9a71b45b6`, then run once on the final configuration. There was no tuning after its results. The last full twenty-case development run preceded the final preservation-contract sentence; that final sentence received the one-case probe and the sixteen-case holdout, not another full twenty-case host run.

The [reduced holdout results](fresh-results.json) retain final model output or an error class, validation, and elapsed time. Thirteen outputs passed the production-parser parity checks; eleven matched the intended meaning. The remaining five cases comprised two structurally valid wrong interpretations, two rejected plan formats, and one native runtime failure:

- An unspecified morning/evening time became 09:45 without clarification.
- A request belonging to another field triggered the native constraint assertion.
- A replacement invented `saved:id=aquarium-8`, copied a stay instead of preserving it, and put `BIKE` in an invalid parameter.
- A mode change included an invalid `place` parameter.
- “Twenty to four in the afternoon” became 16:20 instead of 15:40.

An independent review rechecked all sixteen raw outputs against parser rules and semantic expectations; all eleven reported passes were correct. No representation-only exception removes any of these five failures. Peak host resident memory in this holdout was **3,429,715,968 bytes**, and the latency range includes the 6.27-second runtime error. See [earlier development summaries](development-summary.json) for exact per-run prompt hashes and failed case IDs.

A later [three-case diagnostic](no-grammar-diagnostics.json) reused known failures with the same frozen prompts and 256-token reasoning budget but removed the runtime grammar/response format. Only **1/3 passed**: the wrong-field case returned a valid clarification without the native assertion. The missing-AM/PM case still guessed 09:45; the departure case still proposed the wrong 16:20 and additionally wrapped its JSON in a Markdown code fence rejected by the unchanged parser. These are diagnostic reruns, not fresh validation, and do not change the original **11/16** result. No further model probes followed.

## Legacy single-action path and runtime defect

The existing Home command hit a LiteRT-LM 0.18.0 constraint assertion with reasoning enabled; the identical prompt/schema passed with reasoning disabled. Eight additional synthetic legacy cases then ran with no reasoning and a 220-token output cap. [Five of eight raw JSON outputs matched exactly](legacy-results.json). All eight were accepted by the existing parser and had the intended effect according to an independent source review: unused `position` values are ignored for remove/stay, and the parser already clears the unused target on extend. This is **8/8 source-reviewed effect equivalence**, not eight actions executed on Android. The [frozen legacy cases](legacy-cases.json) and outputs preserve those distinctions.

The native failure also occurred in a contextual field case, so disabling reasoning only for legacy commands does not remove the contextual risk. The [error record](native-error.json) links the upstream issue and a fix committed after the tested 0.18.0 release. That fix was not installed or validated by these host runs.

Android acceptance subsequently terminated with a native `SIGILL`, rather than returning the catchable error measured on Windows. The [crash analysis](android-crash-analysis.md) records the exact binary/build ID, fault instruction and strong source correlation to the same assertion. Kotlin exception handling cannot contain that fatal signal; host error recovery must not be generalized to Android.

Credentials remain outside interpretation. Hosted inference was not configured or called. Physical microphone quality, Android runtime behavior, and latency/memory on the Galaxy S25 Ultra remain separate verification requirements.
