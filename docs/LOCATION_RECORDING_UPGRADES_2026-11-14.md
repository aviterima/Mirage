# Location controls and recordings — 14 November 2026

Status: implementation in progress; no new installer or validation pass claimed.
Scope: Android implementation, matching the reported live Add stop flow. iPhone
parity remains separate and is not implied by Android work.

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

Pending implementation and checks. Historical 0.19.1 passes do not certify these changes.
