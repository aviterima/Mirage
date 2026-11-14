# Location controls and recordings — 14 November 2026

Status: Android 0.20.0 (47) and iPhone 0.2.0 (2) candidates implemented;
final checks pending. No new release claimed.
Scope: both Android and iPhone, confirmed by the owner during implementation.
Android extends the existing full planner; iPhone extends its existing native
engineering app. iPhone signing/install/device-output limitations remain explicit.

## Requested behavior

- A visible map button centers and zooms on the current emulated location. It
  changes only the camera, works while paused, and is unavailable without output.
- Saved places appear immediately while entering a live stop, before online
  matches and without requiring a network key. The Saved places button opens a
  complete list narrowed by the same entered name, alias, or address. Planner
  saved-place selection also carries the current unfinished search into its list.
- Record real journeys or emulated days explicitly. Save timestamped locations,
  distinguish their source, preserve stays and interruptions, suggest an editable
  descriptive name, and offer a separate explicit Replay action.
- Real recording accepts fresh non-mock fixes only while simulation is off.
  Emulated recording captures accepted emitted locations, including stationary
  stays and successive sessions. Missing output is a gap, never a fabricated path.
- Recordings persist privately on the device outside automatic backup, with a
  visible active state and Stop/Save control. No automatic upload or sharing.
- Replay preserves elapsed timing and coordinates using Mirage's existing output
  service; it does not ask a routing provider to reconstruct the trip. Interrupted
  recording data must remain recoverable. Replay and recording remain separate
  from ordinary saved places and itineraries.

## Implementation boundaries

Keep the existing branch, signing identity, saved library, and uncommitted iPhone
work intact. Preserve original output-provider acceptance and cancellation checks.
Android real recording uses a user-started location foreground service; no hidden
background start or automatic collection. Permissions are requested when needed.
iPhone real recording requires any existing output connection to be stopped and
cleared, with fresh real-location recovery, before collection begins. Its real,
emulated and preview recordings remain separate. Saved-stop sequences on iPhone
currently use straight segments; road routing and saved stay settings are not
applied. The UI states these limits before playback.
All-day battery and lock-screen behavior require physical-device validation.

## Using the new controls

- **Android map:** tap the target button to return to the current emitted
  location, including while paused. The camera zooms in without changing the
  simulated position. In Add stop, type a name, alias or address to see saved
  matches immediately, or open Saved places with that same filter.
- **Android recordings:** open Recordings from the map header, choose Record
  real journey with simulation stopped, or Record emulated day. Stop and save
  ends collection; closing the list does not. Saved rows provide Rename,
  Delete and Replay. Replay opens a separate confirmation and speed choice.
- **iPhone stops and map:** Plan stops offers local saved matches and a Saved
  locations picker sharing the address filter. Choosing a saved place adds it to
  the proposed sequence; Play sequence is a separate action. Device proof has
  a zoom button for the current output or explicitly labeled preview position.
- **iPhone recordings:** use the Recordings tab. Clear and recover an existing
  output connection before Record real journey. Emulated and preview recordings
  are labeled separately. Stop and save recording, Rename and Replay remain
  explicit actions. Connect the selected output in Device proof before replay.

Suggested names include the source and date/time. Recordings remain local to
the phone. Speed choices are 1×, 2×, 5× and 10×; gaps hold the last known point
until recorded locations resume.

## Acceptance

Exercise live Add stop with no API key, saved aliases/addresses and late online
responses; verify saved arrival/stay metadata and no implicit playback. Exercise
map centering while running and paused. Verify recording persistence, editable
names, real/mock isolation, monotonic time, gaps, stays, stop/restart boundaries,
interrupted writes and replay cancellation. Compile app and tests, run relevant
unit/UI checks, retain explicit failed/unrun checks. Existing release gates apply.

## Additional ideas, proposed only

Replay timeline scrubber; Fit whole journey; trim start/end or remove
private sections; duplicate a recording into an editable itinerary; recording
quality/gap markers; optional end-of-day reminder to stop and name a recording.
Replay speed selection at 1×, 2×, 5× and 10× is already implemented.

## Platform references

- [Android location permissions](https://developer.android.com/develop/sensors-and-location/location/permissions)
- [Android background location](https://developer.android.com/develop/sensors-and-location/location/background)
- [iOS background location](https://developer.apple.com/documentation/corelocation/handling-location-updates-in-the-background)

## Verification

Candidate version 0.20.0, code 47. Local Android tooling is unavailable; exact-source
CI runs on a separate snapshot branch without changing the mapped worktree branch
or index, and without including existing uncommitted iPhone work. Historical 0.19.1
passes do not certify these changes.

Recording uses one-second moving samples and compressed stationary samples with
the final observed point retained. Suggested names include source and date/time;
the user can rename them. Real recording rejects mock, stale and future-dated
monotonic fixes. Emulated samples represent accepted GPS-provider writes, not proof
of a destination app consuming them. Interruption gaps hold the previous point
during replay, with no invented connecting path. Replay begins at the first known
location; leading time without a fix is not simulated. Recordings are limited to
48 hours, 180,000 samples and 64 MiB each; the library permits 200 recordings.

Physical real GPS accuracy, screen-lock survival, all-day battery use and output
consumption in other apps remain unrun. iPhone adds explicit real-location recording
with background-location capability, separate emulated/preview recordings, stored
sample replay, map centering and searchable saved-stop selection. Its existing
device-output background interruption policy stays enforced: emulated recordings
mark the interruption as a gap and can span subsequent foreground sessions. This
does not claim continuous all-day iPhone emulation, full Android planning parity,
or a signed/installable iPhone distribution. The owner uses Windows/iPad; validation
uses hosted macOS, not owner-run Xcode.

### Validation history retained

- Android intermediate `d1c44efc1525833d270e832a85a1d9759761e84a`
  passed 309 JVM tests and 67 of 68 interface tests. The remaining existing
  large-text test expected Saved to discard its `Library` filter and show Home.
  The corrected test verifies the inherited filter, changes it explicitly to
  Home, and retains its layout and playback assertions. All nine new interface
  and Android-storage tests passed in that intermediate run.
- A subsequent recording regression on both platforms preserves the last
  stationary observation before movement starts. This prevents early departure
  or a false gap caused by compressed stationary samples. Its final-source
  Android regression is included among 310 passing JVM tests.
- The first iPhone attempt failed to compile a test that called Swift's
  `isEmpty` property as a function; corrected before subsequent testing.
- iPhone intermediate `5a1150c8cd1c2e9dce921eea62f25865ce3f7a8f`
  passed native compilation and 23 native unit tests. Its recording interface
  test reached Rename but could not locate the visible native text field:
  UIKit exposed its `Recording name` placeholder instead of the SwiftUI
  identifier and reported a different modal type. The corrected test locates
  that exact placeholder and retains title, visibility, edited-value,
  persistence and replay assertions. This interrupted run is not an interface
  acceptance pass.
- Inspection of that compiled unsigned iPhone app found generated version
  `1.0`/build `1` and device family `[1,2]`. Explicit target/plist metadata now
  sets `0.2.0`/build `2` and iPhone family `[1]`; simulator and device build
  checks assert the actual compiled metadata and background-location capability.
- Final iPhone source also guards against starting real recording with an idle
  output connection. A native regression covers clearing, fresh real-location
  recovery and continued real capture after backgrounding. Physical lock-screen
  acceptance remains separate.

Superseded runs and their failures remain recorded in private task evidence.
No failed, cancelled or unrun check is treated as a pass, and no test was waived.
