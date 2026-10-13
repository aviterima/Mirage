# Offline dictation and manual planning — 0.18.0

Status: implemented, awaiting build and Android acceptance. No installer from this revision has been delivered. Latest verified delivery remains 0.17.0 until the checks below pass.

On 13 October 2026, after reviewing the on-device model failures, the user chose to keep speech processing offline, dictate into editable fields, and construct itineraries with the normal manual controls. This supersedes the conversational interpretation scope for the 0.18.0 installer. The unsuccessful [Gemma experiment](GEMMA_LOCAL_UPDATE_0.18.0.md) and its unchanged quality expectations remain recorded separately.

## User behavior

- Tap the microphone beside Start, Destination, Next stop or another editable field. The bundled speech model prepares locally on first use; microphone permission is requested when needed. No Gemma download is required or offered.
- Dictated words appear in the same editable entry used for typing. Done speaking finishes capture. A subsequent recording can continue unfinished text. Interrupted words remain recoverable.
- Enter or Search places runs the normal saved-place/place search. Typing or dictating Home presents the same choices; selecting a result adds it. Text alone does not select a place, apply an instruction or start a trip.
- The actual itinerary list is the preview. Add, remove, reorder and edit stays with the existing controls. Save stores a plan; Start remains a separate action.
- Clear entry clears that entry and its pending recording/search context. Start new clears the working itinerary, unfinished entries and prior planning context while preserving the saved library.
- Names and other text stay literal and editable. Numeric stays require valid whole minutes. Date/time entries require a concrete valid date/time or the calendar. There is no natural-language conversion, semantic Review or conversational Apply in this installer.
- Credential fields remain verbatim and excluded from input-draft persistence and interaction diagnostics. Diagnostics do not record recognized words or place text.
- Speech recognition runs on the phone. Existing Maps place search and routing retain their normal network behavior. No model service or hosted interpretation is configured.

The earlier hands-free conversational controls are unavailable in this mode. Manual trip controls, including Pause/Resume and Stop, remain usable. Old background voice-command intents are rejected; late callbacks cannot execute commands or re-arm listening. Preparation is bounded, and microphone/preparation failures end the recording while preserving captured words.

## Isolation and recovery

Normal builds set `EXPERIMENTAL_LANGUAGE_MODEL=false`. Microphone readiness depends on bundled dictation rather than a language-model file. Download and inference are rejected before file/network/native work, even if an earlier large model exists. Existing downloaded files are left intact.

The planner uses ordinary lookup for both typed and dictated text, including text that resembles an instruction. Old pending model proposals cannot appear as actionable previews or block normal Save/Start. Their original words are migrated to the editable entry when that can be done without overwriting newer text, and recovery data is cleared only after durable entry storage succeeds. New itinerary still clears obsolete context.

## Verification gates

The normal CI workflow builds with the capability explicitly disabled, checks that generated BuildConfig value, runs baseline JVM tests and the offline Android suite, verifies bundled Vosk files and signing, and checks an in-place upgrade from the pinned 0.17.0 installer. It does not download Gemma. Experimental runs never produce the installer transport artifacts.

New checks cover microphone readiness without Gemma; no download UI; literal editable dictation with an interpreter that fails if called; numeric/date validation and explicit application; all model entry points rejected; typed/dictated Home search, explicit selection, Save, Clear and Start new; blocked old conversational commands; and actual native Vosk initialization with silent PCM. The silent-audio check proves bundled speech initialization, not recognition accuracy or microphone acoustics.

The former model tests and model-dependent UI/command tests retain their assertions under an explicit experimental gate. Android uses the `ExperimentalLanguageModel` annotation, and JVM command tests use the `ExperimentalLanguageModelTests` category. Normal tests exclude those out-of-scope capabilities rather than reporting them as skipped or passed. A manually dispatched acceptance run with `experimental_model=true` builds with `-PexperimentalLanguageModel=true` and selects that separate experimental gate; missing model prerequisites fail rather than skip. Its prior quality failures are not resolved by the offline release.

Build/lint/signing results, exact JVM/Android counts, upgrade evidence and APK identity will be recorded after the current checks. Physical Galaxy S25 Ultra microphone, ergonomics and long-session checks remain open.

## Validation history

Offline source `59354fd024afbfea26801d7de40f9227a5f9ca3c` passed build 38406422055. Its acceptance run 38406422434 passed the signed upgrade check but was cancelled when superseded by the Settings Start guard; no completed Android pass is claimed. Review found that the Settings Start button could bypass the footer's unfinished-entry/recording guard. Both visible controls and their actions now share readiness, with a regression covering unresolved text, active recording, clearing and late callbacks.

Source `10135c9cc88abc00b980094c6708b20ce0421c13` failed compilation in build 38407434668 and acceptance 38407435369 because the new state helper lacked an explicit import. No Android tests ran in that revision. The next source adds only that import; the behavior and regression expectations are unchanged.

Source `a329be4` passed build 38408160513: 265 JVM tests, blocking lint, the disabled-capability check, bundled speech assets and pinned signing. Acceptance 38408160892 ran all 51 Android tests: 49 passed and two failed, with zero errors/skips. Actual bundled Vosk initialization passed in 1.709 seconds, and the Settings Start guard passed in 17.368 seconds. The Home journey pressed hardware Back after the keyboard had already closed, ending its Activity before Save. A continuation journey passed its route/place/order/origin/identity/pause assertions but timed out waiting for global Compose idleness before its final moving-screen screenshot. The follow-up corrects only those test interactions; it does not change application code or the behavioral expectations. No installer was packaged from this failed run.
