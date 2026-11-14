# Location controls and recordings — 14 November 2026

Status: Android 0.20.0 candidate implemented; checks pending. No new release claimed.
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
All-day battery and lock-screen behavior require physical-device validation.

## Acceptance

Exercise live Add stop with no API key, saved aliases/addresses and late online
responses; verify saved arrival/stay metadata and no implicit playback. Exercise
map centering while running and paused. Verify recording persistence, editable
names, real/mock isolation, monotonic time, gaps, stays, stop/restart boundaries,
interrupted writes and replay cancellation. Compile app and tests, run relevant
unit/UI checks, retain explicit failed/unrun checks. Existing release gates apply.

## Additional ideas, proposed only

Replay scrubber and speed selection; Fit whole journey; trim start/end or remove
private sections; duplicate a recording into an editable itinerary; recording
quality/gap markers; optional end-of-day reminder to stop and name a recording.

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
