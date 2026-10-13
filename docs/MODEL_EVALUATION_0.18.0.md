# Contextual input model evaluation — 0.18.0 candidate

Status: **model quality failed; this is not release acceptance**. The verified delivery remains 0.17.0. No replacement model or hosted interpretation has been enabled. Local-model tuning stopped after the experiments below; the interpretation approach needs a product decision.

## Method and provenance

These were actual local inference runs, not mocked responses. All instructions, saved IDs, places and draft contexts were synthetic. No real user transcript, credential or itinerary was sent to a service. Network use downloaded public model/runtime files; inference ran on localhost.

The host used Windows, an AMD Ryzen 5 7640HS, four CPU threads, no GPU offload and an 8,192-token context. The portable [official llama.cpp b11429 release](https://github.com/ggml-org/llama.cpp/releases/tag/b11429) ZIP matched SHA-256 `1283323272b04cd07905816a597a0da810918102de958f4ff6f7bbaa70ed2efe`. **This differs from Android's pinned runtime**, `931351ea50dfdd3ee249606f655eef2e9a629daf`. Host timings and memory are not phone measurements or proof of the Android JNI implementation.

| Model | Verified bytes | SHA-256 |
| --- | ---: | --- |
| [Qwen3-0.6B Q8_0](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF), current app model | 639,446,688 | `9465e63a22add5354d9bb4b99e90117043c7124007664907259bd16d043bb031` |
| [Qwen3-4B Q4_K_M](https://huggingface.co/Qwen/Qwen3-4B-GGUF), experiment only | 2,497,280,256 | `7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5` |

The 4B download used immutable revision `bc640142c66e1fdd12af0bd68f40445458f3869b`. It did not replace the app's model. Both local server processes were stopped after verifying their executable paths; synthetic evidence was retained.

The first corpus contains eight field cases and twelve plan cases in [natural-language-cases.json](../android/app/src/androidTest/assets/natural-language-cases.json). It covers final corrections, current-value edits, aliases, units, relative dates, multiple stops, pending-plan replacement, pronouns, ambiguous references and departure clearing. Once prompts were revised using observed failures, these twenty cases became a development set. Six additional cases were then held out from the new examples. Few-shot examples used different names, numbers and instructions; they were model demonstrations, not a phrase-routing fallback.

## Results

| Experiment | Result | Request latency: min / median / max |
| --- | --- | --- |
| 0.6B, initial prompt and grammar | 1/20 coarse matches; its sole match merely echoed the ambiguous instruction as a question | 1.69 / 3.92 / 5.92 s |
| 0.6B, concise field-specific prompts and mutually exclusive value/question grammar | 0/20 | 0.89 / 2.41 / 5.31 s |
| 4B, same concise approach, greedy decoding | 3/20 production-valid matches | 2.94 / 7.89 / 24.31 s |
| 4B, distinct few-shot dialogues | 3/10 checks; **only 1/6 fresh held-out cases** passed | 4.97 / 14.41 / 18.22 s |

The 4B coarse checker originally counted 4/20. One apparent mode-change match included an illegal extra `place` value, so the production parser rejects it; the corrected count is 3/20. The native quality tests also reject a clarification that merely repeats the user's instruction. Structural success and useful interpretation are separate requirements.

Representative semantic failures:

- The 0.6B model changed an hour-and-a-half correction into 200 minutes, which is structurally valid but wrong.
- The 4B model changed that correction into 60 minutes, omitted the city from a place query, and guessed AM for an ambiguous departure.
- In the fresh few-shot cases, a quarter-hour correction became 20 minutes; an ambiguous hotel request changed both hotel stops; Wednesday at 07:45 became the supplied current Monday at 09:00.
- A pending replacement removed stop 2 and then tried to insert after the now-missing stop 2. Other outputs invented extra changes or lost the requested venue.

Measured peak host working sets across these experiments were approximately **2.09 GB for 0.6B** and **5.00 GB for 4B**. These include server/context allocations and are not Android minimum-RAM estimates.

## Framing, grammar and thinking controls

The existing legacy Home instruction passed with both a raw chat-formatted prompt and explicit special-token tokenization. This checked the basic harness framing against the established single-action path.

Without a grammar, the simple instruction “Return minutes only: thirty minutes, actually forty five” produced 30 from 0.6B and 45 from 4B. The contextual hour-and-a-half case still produced 60 from 4B with the grammar removed. Sampling at temperature 0.7, top-p 0.8, top-k 20 and presence penalty 1.5 also returned 60. The [official model card](https://huggingface.co/Qwen/Qwen3-4B-GGUF#best-practices) recommends non-greedy sampling; applying those settings did not by itself fix these cases.

A bounded 4B thinking experiment, without a grammar during reasoning, corrected the duration to 90 in **27.1 seconds / 406 generated tokens**. A two-stop thinking experiment took **92.8 seconds / 1,214 tokens**, already beyond the candidate's 90-second native deadline, and still lost “art museum,” added an unrequested start change and returned an invalid target. These probes do not establish a reliable thinking-based solution.

Full requests, raw responses and timings were retained for the 4B, few-shot and framing probes; raw results were retained for the earlier 0.6B passes. Those local evidence files are synthetic. No private delivery links or machine paths are published here.

## Candidate validation and remaining work

The candidate retains the legacy interpretation API and adds separate field/plan schemas. It rejects unknown operations, extra keys, invalid units/references, partial edits accompanying clarification, and oversized input without truncation. Credentials bypass interpretation. Seven JVM tests cover these boundaries; two native-model tests evaluate the twenty-case corpus through the production prompt/parser. Each category stops and fails at its first incorrect case; a pass still requires every case in that category. Test date/time is injected as 2026-10-12 at 09:00 America/Phoenix, while production uses the actual local clock. Passing structural tests would not repair the measured language failures.

At latest tested source `d1926db`, CI passed the Android/native build, all 259 JVM tests, blocking lint and signing checks. Emulator acceptance ran 55 tests: 53 passed and only the two native-model categories failed, with no errors or skips. The earlier three UI-selector failures at `e8244d7` are resolved and all UI journeys passed. These build checks do not establish language quality, physical microphone performance or release acceptance.

At `e8244d7`, the actual Android production prompt/parser failed `plan_two_stops` after 20,265 ms and `field_minutes_correction` after 5,027 ms. Each category stopped at its first failure, so only those two corpus cases ran, not all twenty. The established legacy single-action model test passed in 13,291 ms. This confirms that the new Android interpretation path also fails its quality gate; the synthetic test logs record case IDs and timing, not actual user text. These emulator timings are not physical-phone measurements.

The latest `d1926db` run repeated those failures with unchanged production code: `plan_two_stops` failed after 21,194 ms and `field_minutes_correction` after 5,846 ms; the legacy `add_saved` test passed in 14,959 ms. This was validation of the UI-test corrections, not another model-tuning experiment. Both native quality gates remain failing and block the candidate.

Cancellation now belongs to the coroutine holding the model mutex. Cancelling a queued caller cannot cancel another field's request. The active owner cancels native work, waits for that worker to exit, and only then releases the mutex. A reserved native generation token is checked before inference and during decoding, so cancellation between worker launch and JNI entry cannot be lost when inference begins. The old global `SmartVoice.cancel` model cancellation was removed. This race analysis is a source review. The CI build compiles the fix, but no dedicated Android cancellation stress test has established its runtime behavior.

The evidence does not justify switching to the larger local model or describing broad free-form interpretation as complete. The user's engine choice is pending. Neither option below is a completed fallback in the current candidate:

- **Local dictation and manual planning only:** disable the new free-form field/plan interpretation and remove or clearly separate its Review controls. Dictation uses bundled Vosk recognition, while today's microphone readiness gate still requires the 610 MB Qwen download; decouple dictation setup from that interpretation-model requirement. Verify editable recognized words, manual search/selection, recovery and the remaining release checks before shipping. Broad interpretation remains deferred.
- **Opt-in hosted interpretation:** select a provider/model, implement explicit opt-in and explain the transmitted context, including instructions, draft details and saved-place names, aliases and addresses. Keep credentials excluded; define network failure, cancellation and cost behavior while retaining local editing. Evaluate the selected engine with fresh held-out semantic cases and the production validation/review flow before making a quality claim. Hosted quality has not been evaluated here and cannot be assumed to improve on the measured local results.

No hosted provider is configured or invoked by this work. Both choices still require their relevant acceptance and delivery checks; 0.18.0 is not released.
