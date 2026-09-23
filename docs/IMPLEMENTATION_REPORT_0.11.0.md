# Mirage — implementation and validation report

Date: 2026-09-22
Prepared for: Armando Viteri
Candidate: 0.11.0 / versionCode 29
Baseline: 0.10.0 / versionCode 28, commit `3fd315c115f816b07336de2942ba156dd90f6303`

## Executive assessment

The requested Live-interface redesign and an initial typed/voice command system
have been implemented in source. The new design separates the running simulation
from the editable draft, makes upcoming stops genuinely editable, and supports
local speech recognition activated by Hello Mirage with an audible acknowledgement.

This is an **unreleased implementation candidate**, not a verified Android release.
Core Kotlin compilation and 20 executable behavior checks passed. The full Android
build, existing JVM suite, native voice model packaging, Compose rendering, and
physical-device behavior have not been validated in this environment. No APK,
GitHub branch, or PR was published.

Two concrete environment limits prevented that final stage:

- The Gradle wrapper could not download Gradle 8.7: `Network is unreachable`.
  JDK 17 was present; Android SDK/adb were not available.
- GitHub reads succeeded, but creating the candidate tree returned HTTP 403,
  `Resource not accessible by integration`. No write was retried through an
  alternative permission path. Repository write access is needed to submit the
  candidate to Actions.

The download package preserves the source work, a patch against the exact baseline,
updated specification/handoff, and the standalone verification harness.

## Findings addressed

### The screen mixed planning with operation

The original screen left Start/End, mode selection, itinerary editing, live status,
and diagnostic tools simultaneously visible. The map could lose its route when a
draft edit invalidated the planner. The new Live view shows the executing trip and
opens planning deliberately through Change destination or Go there next. Active
geometry and preview geometry are independent.

Stop and the conversation launcher are outside the scrolling control area. Basic
actions are contextual. Advanced diagnostics, GPS signal controls and live driving
speed offset are grouped separately. Follow location disengages on a map gesture;
Whole trip frames known geometry and stop locations.

### Screen edits did not edit the running itinerary

The old itinerary was prepared as flows from a list snapshot, while the same-looking
list on screen remained editable. The new LivePlan owns stable stop IDs and exposes
an execution snapshot. Upcoming stops can be removed, reordered, assigned a different
travel mode, and given a different stay. Those changes affect future execution.
The current/completed stop cannot be reordered or removed through this editor.

Current stays can be set or extended. Snap now creates a holding LivePlan too, so it
can accept a timed stay and future destinations. A timed stay ending with no next
stop returns to indefinite holding, never to real GPS.

### Arrival queues and stale transit routes

LivePlan checks for newly appended stops during arrival holding. The location
service also polls the compatibility queue during its own hold loop. This removes
the code path where a destination queued after arrival could remain stranded.

Future legs are routed when execution reaches them, including fresh transit
requests. The route lookup emits stationary fixes while awaiting the network. A
replacement route starts from the current simulated position at the time Start
is pressed. On an execution error the service holds the last known point and
reports the problem instead of silently presenting normal progress.

### Spoken instructions need an execution contract

CommandParser and Conversation provide a shared typed/spoken path. The language
layer is local and bounded; it does not claim arbitrary LLM understanding. Clear
commands execute, ambiguous Stop asks a question, and unsupported language does
not trigger a guessed action. Google Places supplies actual location choices.
Multiple matches require a numbered selection.

Multi-stop instructions support a sequence such as `drive to A then stay for thirty
minutes then walk to B`. All place names are resolved before replacing a plan.
New instructions or Stop cancel pending conversational lookup/application work.
The process-scoped controller remains available when the Activity is backgrounded,
subject to Android foreground-service permissions and process lifetime.

## Hello Mirage implementation

VoiceService provides an opt-in microphone foreground service. A Vosk wake grammar
listens locally for Hello Mirage. On activation it prepares command capture, plays
two ascending acknowledgement tones, and opens a bounded listening window with a
partial transcript. It releases recognition during TTS so spoken acknowledgements
do not become new commands. Hands-free clarification opens a short follow-up
window; silence returns to wake listening.

The microphone can be turned off from the chat panel or persistent notification.
One-shot microphone input is also available. There is no boot-triggered listening,
no microphone audio storage, and no upload of recognition audio. The selected system
TTS engine may have its own network behavior. Place search/routing still require
Maps connectivity and the configured key/gateway.

The build downloads the versioned English model and bundles it into APK assets.
CI now verifies that the acoustic model is actually present. This was intentionally
implemented as an explicit download/extraction task rather than relying on an
unverified model AAR to contain the data.

Wake performance, model vocabulary/accent recognition, output volume, Bluetooth,
interruptions, and screen-off reliability remain device acceptance items. This
implementation uses a local grammar recognizer, not a measured hardware low-power
wake detector. Its battery performance has not been claimed or measured.

## Files changed

| Area | Main files |
| --- | --- |
| Execution state | `engine/LivePlan.kt`, `engine/MotionModel.kt` |
| Live interface | `LiveUi.kt`, `MapScreen.kt` |
| Planning integration | `MirageViewModel.kt` |
| Location service | `MockLocationService.kt` |
| Conversational commands | `CommandParser.kt`, `Conversation.kt` |
| Voice service | `VoiceService.kt`, `AndroidManifest.xml` |
| Build/release | `app/build.gradle.kts`, `.github/workflows/android.yml` |
| Tests | `CommandParserTest.kt`, `engine/LivePlanTest.kt`, `verification/` |
| Documentation | `SPEC.md`, `HANDOFF.md`, both READMEs, `docs/` |

## Verification evidence

| Check | Result | Meaning / limitation |
| --- | --- | --- |
| Baseline GitHub read | Passed | main matched the attached archive commit |
| Core source compilation | Passed | Actual parser, LivePlan, MockState, motion models and API configuration compiled; HTTP routing client stubbed |
| Core runtime harness | 20 checks passed | Actual flow/parser/state behavior exercised; no Android device/provider involved |
| Conversation source compilation | Passed with explicit stubs | Kotlin type/syntax check of controller against Android/network stand-ins; not Android linkage validation |
| Changed app Kotlin syntax | Passed | Static parse check; not Android API or rendering validation |
| Manifest XML | Passed | Well-formed XML; permissions still need runtime/device validation |
| Diff whitespace/conflict check | Passed | No patch whitespace errors |
| Existing/new Android JVM suite | Not run | 50 test declarations present: 39 baseline plus 11 new tests |
| Gradle Android assembly/lint | Blocked | Gradle distribution download unavailable |
| GitHub candidate CI | Not run | Connected integration refused tree creation |
| Device / visual / voice acceptance | Not run | No Android device or emulator available |

The standalone environment used OpenJDK 17 and the Kotlin compiler/coroutines
included with JetBrains Kotlin Jupyter kernel 0.19.0.944, in Kotlin language mode
2.0. The app's actual Gradle configuration remains Kotlin 2.0.21. Standalone
success is useful evidence but is not equivalent to the exact Gradle build.

The 20 checks covered ambiguous/explicit Stop, multi-stop parsing, queue intent,
negative phrases, spoken duration setting/extension, duration bounds, numbered
place selection, rejection of an unsupported queued Snap, draft isolation,
continued fixes during route lookup, arrival holding, completed-stop protection,
timed holds, live extension, respecting a stay before an appended stop, leaving
now, future reorder/mode changes, and future stay editing.

## Remaining limitations and release requirements

1. **Build and device gates are outstanding.** Do not substitute the old green
   main workflows or this report for a successful build of this candidate.
2. **Conversational language is bounded.** General LLM interpretation, personal
   Home/Office aliases, and arbitrary paraphrases are not implemented.
3. **Absolute-time scheduling is not implemented.** Relative timed stays are
   supported; “leave at 3 PM” is not yet a recognized command.
4. **Background startup is restricted by Android.** Enable voice while Mirage is
   visible. Starting a new location foreground service from an idle background
   state may require reopening the app. Screen-off controls during an existing
   run need testing on the owner's phone.
5. **Model packaging is unverified until assembly.** Record/pin the downloaded
   model checksum at the first successful build. Runtime size and power cost must
   be measured. Include full dependency notices before public distribution.
6. **History/session recovery remains unchanged in scope.** Chat is in memory;
   a killed process does not resume an armed simulation.
7. **Existing dormant gateway concerns remain open.** This UI/voice task did not
   change purchase-token/account-allocation race conditions, deploy the gateway,
   rotate keys, add billing, or establish release signing.

Recommended next action: enable repository write access, push this candidate to a
feature branch, run the full checks, then install the resulting debug APK for the
specific acceptance sequence in LIVE_AND_VOICE_SPEC.md. Merge to main only after
that evidence supports release. The workflow change prevents a feature-branch push
from replacing the rolling main APK.
