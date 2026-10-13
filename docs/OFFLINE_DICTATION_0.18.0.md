# Offline dictation and manual planning — 0.18.0

Status: **verified October 13, 2026**. The normal 0.18.0 installer provides offline dictation and manual planning. Tested source is `90eb1b6dc6b4a27bf06f9711c0aa19536871ed08`; application code is unchanged from `a329be452016fc6a82996f99b695edf36027152b`. Final handoff changes documentation only.

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

## Verified results and installer

[Build 38411851231](https://github.com/aviterima/Mirage/actions/runs/38411851231) and [acceptance 38411853403, attempt 2](https://github.com/aviterima/Mirage/actions/runs/38411853403/attempts/2) passed. Independently parsed XML records **265 JVM tests and 51 Android tests**, with zero failures, errors or skips. Seven model-dependent JVM methods and 13 experimental Android methods retain their assertions in the separate opt-in suite and are not included in those totals.

Blocking lint passed with 44 warnings and 11 hints. The sole added warning concerns optional-parameter ordering in a private Compose helper. The disabled-capability check, bundled voice assets, pinned signing and native Vosk initialization passed. The actual speech initialization test used silent PCM and completed in 1.010 seconds. The Home/Save/Start new journey passed in 21.552 seconds, the shared footer/Settings Start guard in 18.841 seconds, the moving-route journey in 20.311 seconds and the previously timed-out paused-continuation journey in 23.271 seconds.

The signed 0.17.0-to-0.18.0 upgrade succeeded without uninstalling. Independently compared seeded key/install-identity and saved-scenario files were byte-identical; this fixture does not certify every user record or preference. All three trail checks passed through fallback after an initial HTTP 504. Eleven selected evidence/installer archives matched metadata SHA-256 and byte count and passed ZIP CRC checks. The APK was assembled from the six exact acceptance parts and matches the workflow checksum.

Installer: `Mirage-0.18.0.apk`, **131,902,929 bytes**, package `com.mirage.app`, version **0.18.0**, code **44**.

- APK SHA-256: `9d8b18a32d0230b8cfae8acc15dd931af4b60ae2f31d7d31de21ca9468876c83`.
- Signing certificate SHA-256: `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8`.
- Local checks covered all 248 ZIP entries, binary manifest and certificate fingerprint. These local checks are not cryptographic signature verification; CI `apksigner` verification and Android's signed upgrade acceptance establish that separately.
- Bundled speech assets and Vosk libraries for `arm64-v8a` and `x86_64` were verified.

Delivery uses `Mirage-0.18.0.zip` because the upload connector rejects individual files over 100 MiB. The ZIP is **88,651,923 bytes**, SHA-256 `3afc007a4ddef5f7fee00bfc89c2da2654b5ab742bbf096af688e18101c6980d`, and contains only the APK. Streaming extraction reproduces the exact APK SHA-256 above. Hosted metadata confirms the ZIP name and byte count. A complete download of the hosted copy also matches the ZIP SHA-256, passes CRC and reproduces the exact APK SHA-256. The private download link remains outside this repository. Download, extract the ZIP, then open the APK to update the installed application.

Final reset and Settings screenshots were reviewed alongside the unchanged application layout captures. Editable fields, microphones, Saved places and separate Save/Settings/Start new controls are readable; reset leaves simulation off. The Settings screenshot shows the final enabled Start state, while test assertions establish its intermediate guards. Larger-text keyboard captures retain the editable field and microphone but can clip scrolled preview content. Physical Galaxy S25 Ultra microphone accuracy, ergonomics and long-session checks remain open. Deterministic search fixtures do not establish live Places/Directions acceptance; no local Android toolchain or physical-device run is claimed.

## Validation history

Offline source `59354fd024afbfea26801d7de40f9227a5f9ca3c` passed build 38406422055. Its acceptance run 38406422434 passed the signed upgrade check but was cancelled when superseded by the Settings Start guard; no completed Android pass is claimed. Review found that the Settings Start button could bypass the footer's unfinished-entry/recording guard. Both visible controls and their actions now share readiness, with a regression covering unresolved text, active recording, clearing and late callbacks.

Source `10135c9cc88abc00b980094c6708b20ce0421c13` failed compilation in build 38407434668 and acceptance 38407435369 because the new state helper lacked an explicit import. No Android tests ran in that revision. The next source adds only that import; the behavior and regression expectations are unchanged.

Source `a329be4` passed build 38408160513: 265 JVM tests, blocking lint, the disabled-capability check, bundled speech assets and pinned signing. Acceptance 38408160892 ran all 51 Android tests: 49 passed and two failed, with zero errors/skips. Actual bundled Vosk initialization passed in 1.709 seconds, and the Settings Start guard passed in 17.368 seconds. The Home journey pressed hardware Back after the keyboard had already closed, ending its Activity before Save. A continuation journey passed its route/place/order/origin/identity/pause assertions but timed out waiting for global Compose idleness before its final moving-screen screenshot. The follow-up corrects only those test interactions; it does not change application code or the behavioral expectations. No installer was packaged from this failed run.

Source `90eb1b6dc6b4a27bf06f9711c0aa19536871ed08` changes only two Android test files and two documents after `a329be4`. Build 38411851231 passed. Acceptance 38411853403 attempt 1 ran all 51 Android tests: 50 passed and one existing paused-trip journey timed out waiting for Compose idleness while reading the paused header, with zero errors/skips. Both corrected journeys passed. The paused-state/output-validity predicate had passed before the timeout, but later rendered-header stability assertions were not reached. No app fatal exception, signal, OOM or process kill appeared in that test's logcat. All five downloaded evidence archives matched metadata size/SHA-256 and passed CRC checks; 265 JVM tests passed and upgrade fixture files remained byte-identical. The full emulator job was rerun once without source, assertion or infrastructure changes; attempt 1 is retained as a failure.

Attempt 2 passed all 51 Android tests on that unchanged source, with zero failures, errors or skips. The successful rerun does not establish freedom from emulator/Compose timing sensitivity. Its exact APK is the verified installer described above; failed, superseded and experimental attempts remain distinct evidence.
