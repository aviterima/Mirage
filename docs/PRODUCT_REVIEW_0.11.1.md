# Mirage 0.11.1 — product review and acceptance plan

Date: 2026-09-25. Candidate only; no production/main release.
Based on the 0.11.0 live/voice branch. PR: https://github.com/aviterima/Mirage/pull/2

## Product principle

A user should be able to answer three questions immediately: Is simulation actually active? Where is the simulated position now? What will happen next?

A route line, successful search, command acknowledgement, and running simulation are different states. The interface must never imply active simulation merely because a route is visible.

## Implemented in this candidate

### Timing and motion

Driving defaults to the Google route duration, allocating each step a share of the returned time. Acceleration, deceleration, and turn transitions fit inside that budget. Synthetic traffic-light waits are not appended in this mode. Manual estimated-speed mode remains available through Advanced and applies to the next driving leg. Its existing mph offset remains separate from Google timing.

Driving requests now include departure_time=now and traffic_model=best_guess. Valid duration_in_traffic values replace standard duration per leg. When absent or invalid, the standard duration remains the fallback. Details displays the source and lookup time. This is a snapshot at lookup, not continuous traffic polling. Step congestion is allocated proportionally from leg timing; it does not identify the exact physical location of every traffic delay. Saved plans are rerouted when loaded; later itinerary legs are routed when they begin.

Manual-mode classification recognizes full interstate numbers, separates ramps from surface streets, ignores incidental exit-sign text, and avoids treating keep-left/right freeway forks as turns. Manual speeds remain estimates, not posted limits.

Flight phases now have bounded taxi time, timed takeoff/climb/descent, analytic distance integration, and an ETA derived from the same schedule. Short flights scale down peak speed and altitude. This remains a generic jet following a great-circle path: it does not model real airport taxiways, runway headings, terrain elevation, aircraft performance, weather, or airline schedules.

### Map and controls

An active trip uses a compact bottom card: destination, speed or stay time, remaining travel time, Pause/Resume, Talk, Stop, and Details. Editing, upcoming stops, and advanced settings open on demand. Follow and Whole trip remain available at the top; a bookmark opens the common saved-plan menu from the active view.

Planning retains its separate form. The live map receives smaller bottom padding so the vehicle is centered in the visible map, rather than above a half-screen control panel. Actual layout on a phone, small screens, landscape, and enlarged fonts still requires device acceptance.

### Saving

The active view now saves the active execution plan and its edited stops, independent of any unrelated draft. Saved trips replay from their original start rather than resuming a precise in-flight sample. Current pending/remaining stays are rounded up to whole minutes because the existing saved format is minute-based. Saved items remain on this device and a matching name replaces the previous entry, as before.

### Honest operational status

The app explicitly labels simulation off/planning, starting, active travel, paused, preparing a route while holding, staying, holding after arrival, and unconfirmed output.

The health check no longer advances the successful-output timestamp if the Android test providers reject all updates. Google fused-provider acknowledgements have a separate timestamp, guarded against stale session callbacks. The UI checks both timestamps and health every second. Missing acknowledgements, output older than five seconds, or simulated signal dropout produce Needs attention.

These are delivery acknowledgements, not proof that every third-party application has consumed the latest location. Phone testing remains essential.

## Highest-value next improvements

1. Unify all user actions behind one explicit trip controller. Draft edits, active changes, saved-plan loading and voice should return an observable outcome from the same command path. The candidate fixes active saving, but does not claim to complete this larger refactor.

2. Make replacement versus append unambiguous. Present Go now and Add next consistently. Before replacing an active itinerary, show what is being replaced and offer an undo path. Do not require confirmation for harmless edits.

3. Add a concise trip timeline in Details: completed stops, current leg/stay, upcoming stops and expected arrival/departure times. Clearly distinguish real clock time from accelerated simulation time.

4. Persist a recoverable checkpoint. After Android stops the service or the process dies, show Simulation off and offer to resume the saved checkpoint. Never silently claim the old simulation is still running.

5. Improve saved-plan management: recent items, favorites, rename, duplicate, explicit overwrite warning and export/import. Keep one collection across snap, route and itinerary modes.

6. Make voice outcomes concrete. After a command, report the recognized destination and whether it started now, queued next, changed a stay or only selected a candidate. Retain clarification for ambiguous stop commands. Avoid claiming unrestricted conversation; this version uses a bounded offline command grammar.

7. Refresh traffic when a preview becomes stale before Start, with a visible change in ETA. Consider user-controlled refresh during a trip while keeping the simulated position continuous and preventing surprise route changes.

8. Distinguish remaining travel from scheduled stays. An itinerary completion estimate should include planned stop durations; a current-leg ETA should not imply it includes the entire day.

9. Consolidate setup into a readiness check: mock-app selection, permissions, providers, Google feed and power/background operation. Errors should state one useful next action.

10. Build a repeatable device acceptance routine and a compact support report with app version, status transitions and anonymized delivery counters. No API keys or precise location history should be exported by default.

## Acceptance gates

Automated coverage added for interstate classification, false ramps/turns, no freeway light stops, flight ground timing, flight ETA/displacement consistency, short flights, zero-length flight, Google duration pacing, fast-forward, traffic fallback, active-plan saving and truthful status presentation.

Release gates:
- Android assembly, full unit suite and lint on the final candidate commit.
- Confirm offline voice assets remain in the APK.
- Phone checks: route preview is clearly off, Start progresses through Starting, successful output becomes Active, Pause remains simulated, arrival holds, Stop ends simulation.
- Disable location or revoke mock access and confirm warning state; recover and confirm timestamps resume.
- Test background/screen-off operation, process death and reopen.
- Verify map usability and readable controls on the target Samsung phone.
- Verify traffic-enabled response under the deployed API key; standard fallback must remain visible when traffic is unavailable.
- Verify an update installs without erasing saved plans. Do not uninstall to work around a signing conflict without backing up data.

No claim of device validation, continuous live-traffic refresh, posted-limit coverage, or a production release is made by this review.
