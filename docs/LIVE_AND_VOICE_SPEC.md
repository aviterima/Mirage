# Mirage 0.11 — Live operation and conversational control

Specification revision: 2026-09-22
Baseline: 0.10.0 / versionCode 28 / GitHub commit 3fd315c115f816b07336de2942ba156dd90f6303
Implementation candidate: 0.11.0 / versionCode 29
Status: source candidate; Android assembly and device acceptance outstanding.

## Objective

Make a running Snap, Route, or Itinerary easy to understand and change. The user
must be able to see what is happening now, what happens next, and what an action
will change. Typed instructions and spoken instructions must invoke the same
operations. The location simulation remains active during planning, place lookup,
route lookup, pauses, stays, and arrival holds. Only an explicit Stop restores the
real location, except platform failures/process death described in the handoff.

This specification supersedes conflicting live-screen and planning/execution
behavior in the original SPEC.md. It does not change Mirage's positioning as an
honest mock-location testing tool or remove Android's mock flags.

## Separate the draft from execution

A draft contains editable locations and proposed settings. A LivePlan contains
stable stop identities, execution order, current index, activity, geometry, and the
remaining stay duration. Editing the draft cannot mutate a running trip. Editing
an upcoming live stop changes that trip's remaining execution, not an unrelated
screen-only list.

An active snapshot exposes IDLE, ROUTING, TRAVELING, STAYING, or HOLDING. Pause is
orthogonal: the foreground location service suspends advancement at emission and
keeps publishing the last delivered position. It must not display a draft stop as
the current live stop.

Completed/current stop identities cannot be removed or reordered through the
upcoming-stop editor. Upcoming stops support removal, reordering, stay duration,
and travel mode. Live stay extension/set-duration is a separate operation on the
current activity. Route factories use the actual previous endpoint and current
transit timetable when the leg begins. The next leg is not routed hours in advance.

During route lookup, emit stationary fixes at the last position at the normal
cadence. On lookup failure, hold that position, show the failure, and allow a new
destination. Do not silently continue queued trips after a failed leg.

## Live screen

Starting a simulation automatically opens Live view. The active route remains
visible while a draft is being edited; draft geometry is a distinct preview.
Upcoming stop markers are shown independently of the active route geometry.

The status card shows:

- The active destination or holding place.
- Traveling speed and estimated remaining time, or remaining simulated stay time.
- The next stop and its planned stay.
- Actionable service errors or signal warnings.

Touch actions depend on the active state:

| State | Actions |
| --- | --- |
| Traveling | Pause; jump to arrival; change destination; go there next |
| Staying | Pause/resume; leave now; extend by 15 minutes; upcoming stops |
| Holding after Snap/arrival | Go somewhere; set a 30-minute stay; go there next |
| Paused | Resume; relevant destination/plan controls |
| Routing | Hold current position; allow Stop or a replacement instruction |

The chat launcher and Stop remain outside the scrolling control area. Stop is
labelled `Stop simulation · return to real location`. The map has Follow location
and Whole trip controls. Dragging the map disables follow until the user restores
it. Whole trip frames the known route and stop locations; it does not invent road
geometry between future stops.

Map taps only select draft endpoints when planning is open. Ordinary taps on the
Live map do not silently change a destination.

## Planning during a run

`Change destination` opens a new draft from the current simulated position. Its
start action replaces the remaining journey. Playback continues until the new
plan is armed. The actual execution origin is refreshed when Start is pressed.

`Go there next` appends the new stop(s) after the active plan's existing stops.
If there is no LivePlan (legacy automation/error hold), the compatibility queue
is polled during holding as well as at route completion. A queued destination
must not remain stranded after arrival.

Appending a stop while the current activity is a timed stay respects the remaining
stay. `Leave now` ends that stay and continues. Appending while indefinitely holding
starts the next leg. A new timed stay at the final stop can delay an appended trip.
When a final timed stay ends with nothing scheduled next, keep holding; never
restore real GPS implicitly.

Advanced controls contain live fast-forward, estimated driving-limit offset,
GPS presets/dropouts, and diagnostic counters. Draft driving speed/time-scale
controls apply to a replacement plan. They must not unexpectedly alter the current
run while the user is planning an appended stop. Walking/biking speeds and realism
remain per-leg settings captured by the routing/motion factory; there is no promise
that every motion parameter can be changed mid-leg.

## Chat contract

The conversation shows user instructions, results, errors, and place choices. It is
bounded in memory to the latest 100 lines and is not persisted. Closing the panel
has no effect on simulation. A new instruction cancels pending conversational
lookup/application work; cancelling a pending instruction does not stop playback.

The initial implementation uses an offline, deterministic English command parser.
It is not an unrestricted LLM assistant. Unsupported language is explained without
executing a guessed command. The supported forms include:

| Instruction | Meaning |
| --- | --- |
| Pause / pause here / hold here | Freeze advancement; continue mock fixes |
| Continue / resume | Resume advancement |
| Stop | Ask whether to pause or end simulation |
| Stop simulation / return to my real location | Explicitly stop simulation |
| Jump to arrival / skip ahead | Finish current travel leg, or end current stay |
| Leave now / end this stay | End the current timed stay |
| Stay for thirty minutes | Set remaining/current-arrival stay to 30 minutes |
| Extend stay by ten minutes / stay another half an hour | Add to the stay |
| Drive/walk/bike/fly/take transit to PLACE | Replace the remaining journey |
| After this, drive to PLACE | Append after the existing stops |
| Snap to PLACE | Move immediately to a holding session |
| Drive to A then stay for thirty minutes then walk to B | Build a multi-stop plan |
| Go there now instead / go there next | Reinterpret the preceding destination request |
| Remove the second upcoming stop | Remove by upcoming ordinal, not original index |
| What happens next / status | Describe actual active execution |
| Fast-forward five times | Change the live simulated-time factor |
| Number two / the second one | Select a listed place |
| Cancel / never mind | Cancel pending instruction |
| Help / what can I say | List supported examples |

The parser accepts numeric durations and common spoken English durations, up to
24 hours. It rejects unrecognized/negative-command phrases rather than triggering
a substring match. Examples are functional syntax, not promises of arbitrary
paraphrase support. Named personal aliases such as Home/Office are not inferred.

Google Places resolves names and addresses. Multiple matches are presented with
names and addresses and selected by touch or spoken ordinal. Do not silently pick
a business branch. The complete multi-stop request is resolved before replacing
a trip. Request-generation checks and cancellation prevent late search results
from applying after a newer instruction or Stop.

Replies distinguish acceptance from execution: `Starting… Finding the route now`
is an acknowledgement that planning has begun, not proof that GPS consumers have
received the first new fix. Live status is the authority for current execution.

## Hello Mirage

Voice is explicitly enabled by the user while Mirage is visible. The app requests
RECORD_AUDIO and starts a microphone foreground service with a persistent
notification and Microphone off action. No automatic boot/restart listening.
A microphone tap also supports one command without leaving hands-free enabled.

The wake phrase is `Hello Mirage`. A local Vosk recognizer constrained to that
phrase and an unknown-word alternative listens for activation. Command recognition
uses the same local model with its full vocabulary only during a bounded window.
The first candidate uses a grammar-based local recognizer, not a hardware-DSP wake
engine; power use and false activations require measurement on the target phone.

On activation, release/reset the wake recognizer, prepare the command recorder,
play two short ascending acknowledgement tones, then show Listening and accept
speech. The user should wait for the chime before speaking the instruction. A
visible partial transcript makes recognition errors apparent. Command windows
expire after 15 seconds. A timeout returns to wake listening (or turns off one-shot
voice). Recognition pauses during spoken output to prevent self-triggering.

For a clarification in hands-free mode, open another bounded listening window
after the spoken question, allowing a short answer without another wake phrase.
If no answer arrives, return to wake listening. Stop listening and release the
recorder/model when the microphone is turned off or the service is destroyed.

Recognition audio is processed locally, not uploaded. Place queries and routing
still use the configured Maps connection. Speech output uses the device TTS engine;
its own offline/network settings apply. The application does not save microphone
audio. Chat text remains in process memory and includes requested locations.

The English model is downloaded from the versioned upstream URL during APK build,
then bundled as assets. It is not downloaded on the user's first voice request.
Asset extraction checks paths and verifies the acoustic model exists. CI checks
that the APK actually includes the model. A first successful build must record the
upstream model checksum for future reproducible builds; this checksum is not yet
verified in the current constrained environment.

## Background operation

The command controller is process-scoped, so closing the chat sheet or switching to
Google Maps does not discard its execution context. Existing simulation controls
can be processed while the screen is off if Android continues the microphone
service. Incoming calls, audio routing, battery policies, permission revocation,
and OS termination may interrupt listening; failures must be visible.

Android restricts creating microphone/location foreground services from the
background. Enable hands-free while the app is visible. Starting a brand-new
simulation from a background idle app may require opening Mirage; this is not a
system-assistant privilege. Background restart after process death is not promised.

## Acceptance and release gate

Before shipping this candidate:

1. Assemble the APK with JDK 17, Gradle 8.7, Android SDK 34 and the configured Maps key.
2. Run all JVM unit tests, inspect lint, and verify the bundled model asset.
3. Validate Snap, route, and itinerary Live transitions at small and large font sizes.
4. Confirm Stop and chat remain reachable with keyboard open/closed and when controls scroll.
5. Confirm changing the draft does not clear the active route or change live settings.
6. Add/remove/reorder future stops; edit mode/stay; verify the actual journey follows the edit.
7. Set and extend a live stay; pause/resume it; append a destination while holding; leave now.
8. Test routing failure/cancellation, delayed place results, and Stop during network requests.
9. Validate stale transit departures are refreshed at execution time.
10. Test wake phrase, chime timing, partial transcript, spoken clarification, timeout,
    recognition during TTS, denied/revoked microphone permission, and microphone off.
11. Test with Google Maps visible, screen locked, Bluetooth connected, music/calls,
    and an extended screen-off run. Measure false wakes and battery consumption.
12. Confirm only main-branch pushes can update the rolling APK release.

## Deliberately outside this candidate

An unrestricted cloud LLM interpreter; personal place aliases; absolute clock-time
scheduling (for example, leave at 3 PM); persisted conversation; live mutation of
every speed/traffic setting; recovery after process death; production gateway,
Play Billing, and public-release signing. These remain explicit follow-on work.
