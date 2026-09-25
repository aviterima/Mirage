# Mirage 50-case execution register

Baseline: `1e414c3185612f2da9bbd4a0079f178800f95b2b` (0.11.1).

This branch adds tests, not product fixes. The desired-behavior assertions deliberately fail when the baseline violates the acceptance contract. Existing tests that expect old behavior are retained to expose contract changes. Component success is not full-journey success. Maps search/routing, physical-phone behavior, upgrade persistence, external-app location consumption, audio and accessibility remain distinct validation gates.

Local execution is unavailable. GitHub CI is used for JVM tests and an API 34 Google APIs emulator. No 50-case pass claim is permitted without a recorded complete journey.

| Case | Journey | Full journey status | Available executable coverage |
|---|---|---|---|
| 1 | Create named place | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 2 | Drop precise pin | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 3 | Save real location during simulation | Pending execution / blocked beyond available coverage | JVM origin policy |
| 4 | Save simulated position | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 5 | Enter coordinates | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 6 | Building entrances | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 7 | Edit saved place | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 8 | Find saved items | Pending execution / blocked beyond available coverage | Emulator saved list |
| 9 | Duplicate names and deletion | Pending execution / blocked beyond available coverage | JVM no silent overwrite |
| 10 | Two snaps as endpoints | Pending execution / blocked beyond available coverage | Emulator endpoint actions |
| 11 | Snap and hold | Pending execution / blocked beyond available coverage | Emulator snap/provider acceptance |
| 12 | Timed snap departure | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 13 | Route from real location | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 14 | Swap endpoints | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 15 | Select travel mode | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 16 | Saved route path semantics | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 17 | Different virtual origin | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 18 | Routing failure recovery | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 19 | Highway ETA | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 20 | Transit refresh | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 21 | Compose saved routes | Pending execution / blocked beyond available coverage | Emulator composition action |
| 22 | Disconnected routes | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 23 | Insert saved stop | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 24 | Mixed-mode day | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 25 | Exact stays and departures | Pending execution / blocked beyond available coverage | JVM arrival command intent |
| 26 | Reorder draft stops | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 27 | Edit live upcoming stops | Pending execution / blocked beyond available coverage | JVM live identity/edit protection |
| 28 | Return to trip origin | Pending execution / blocked beyond available coverage | JVM effective return origin |
| 29 | Save edited active itinerary | Pending execution / blocked beyond available coverage | JVM full-template stay semantics |
| 30 | Scheduled flight journey | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 31 | Understand active state | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 32 | Map visibility | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 33 | Diagnose output warning | Pending execution / blocked beyond available coverage | JVM stale fused-output warning |
| 34 | Pause/resume | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 35 | Immediate reroute | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 36 | Next stop versus end | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 37 | Extend or end stay | Pending execution / blocked beyond available coverage | JVM exact spoken duration |
| 38 | Fast-forward | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 39 | Stop and real-location handback | Pending execution / blocked beyond available coverage | Emulator stops output; fresh real fix remains untested |
| 40 | Background and process loss | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 41 | Typed instruction | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 42 | Wake word | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 43 | Saved names in voice | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 44 | Ambiguity and cancellation | Pending execution / blocked beyond available coverage | JVM stop ambiguity and negative durations |
| 45 | Health-aware voice status | Pending execution / blocked beyond available coverage | JVM spoken health warning |
| 46 | In-place upgrade | Pending execution / blocked beyond available coverage | JVM in-memory serialization only; NOT a signed upgrade |
| 47 | Backup/reinstall | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 48 | Setup refresh | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 49 | Accessibility | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
| 50 | Combined failure recovery | Pending execution / blocked beyond available coverage | Manual/device or implementation prerequisite |
