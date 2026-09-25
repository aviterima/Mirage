# Mirage: executed acceptance-test results
Date: September 25, 2026

## Result and scope

The current product does not meet the acceptance requirements. The JVM suite executed three times: **77 tests, 69 passed, 8 failed**, with identical failures. Of these, 65 were existing tests; the 12 added acceptance tests produced 4 passes and 8 failures.

Product source baseline: `1e414c3185612f2da9bbd4a0079f178800f95b2b`.
Latest test implementation: `491bb1fe6a908873e9a6433290fa2d24b7094f2d`.
Branch: `codex/acceptance-50-20260925`.

No production Kotlin implementation was changed, no release was merged, and no replacement APK was published. Gradle configuration changes add instrumentation dependencies and a test runner only.

The local environment is disconnected; execution used GitHub runners. This is real automated execution, but not a 50-journey device sign-off. Component checks cover selected requirements within the broad use cases. No unit-test pass counts as a full user journey.

## Reproduced JVM failures

| Case | Executed acceptance check | Result and implication |
|---|---|---|
| 03 (related origin-policy check) | Reload a saved real-origin route while simulation is running | FAIL: effective start resolves to simulated coordinates rather than the saved real-origin policy. This is narrower than the full case of saving a fresh physical location. |
| 09 | Save a second same-name snap without replacement approval | FAIL: original record/coordinates do not survive. |
| 25 | Parse “when i arrive, drive to the office then stay for thirty minutes” | FAIL: destination journey intent is not retained as a journey command. |
| 28 | Return to start when the effective start is simulated and the draft start differs | FAIL: the return point uses the wrong origin. |
| 29 | Save full trip after two minutes of a thirty-minute stay | FAIL: the configured thirty-minute stay is not preserved for full replay. |
| 37 | Parse “extend stay by seventeen minutes” | FAIL: the requested exact spoken duration is not recognized correctly. |
| 44 | Parse “stay for -5 minutes” | FAIL: malformed negative duration is not rejected as unknown. |
| 45 | Ask the status formatter for speech while output is amber/unconfirmed | FAIL: returned description omits the degraded-output warning. |

These tests assert desired user behavior, which sometimes differs from the old contract. In particular, an existing test explicitly expects name-based replacement. Both are retained so that changing the product contract remains visible.

## New JVM checks that passed

- Stale fused-provider acknowledgement prevents the top-level status from claiming ACTIVE.
- Bare “stop” requires clarification; “stop simulation” resolves to the explicit stop command.
- Updating an upcoming stay retains the current stop identity and refuses removal of that current stop.
- Saved scenarios survive serialization through the in-memory store and loading into a fresh ViewModel. This does **not** verify disk persistence through upgrade or uninstall.

## Evidence

- [Initial JVM run](https://github.com/aviterima/Mirage/actions/runs/36161002291)
- [Repeated JVM run](https://github.com/aviterima/Mirage/actions/runs/36161798021)
- [JVM HTML reports](https://github.com/aviterima/Mirage/actions/runs/36161798021/artifacts/10876660973)
- [Emulator run](https://github.com/aviterima/Mirage/actions/runs/36161798008)

The initial emulator attempt, run 36161002210, failed before test execution because the runner executed separate script lines in separate shells, losing the working directory. The harness was fixed in the second test commit. That first attempt is an infrastructure failure and supplies no UI verdicts.


## Final emulator results

API 34 / Android 14, Google APIs image, x86_64, Pixel 7 Pro profile. Six instrumentation checks executed: **3 passed, 3 failed**.

| Check | Result | Limit |
|---|---|---|
| Saved fixture list displays | PASS | Does not cover searching a large collection. |
| Snap starts and both output acknowledgements are fresh | PASS | Does not establish another app's consumption, long-duration holding, or the cause of the user's Samsung warning. |
| Stop via native Android UI taps | PASS | Confirms running/starting becomes false and off status displays; does not assert a fresh physical GPS fix. |
| Saved snap has independent endpoint actions | FAIL | Required actions absent on the actual saved-plan screen. |
| Saved route has an itinerary-composition action | FAIL | Required action absent on the actual saved-plan screen. |
| Live central map-space probe | FAIL | Measured 0.70032054 versus the probe's 0.75 threshold. |

The layout probe measures the vertical gap between the Follow control and the live title, divided by the full Compose root height. It is a generous central gap because panel padding is not deducted. It is **not** a full pixel-area measurement and is not normalized to exclude system-bar insets. It documents the current layout; exact compliance with the report's usable-area target still requires inset-normalized measurements and visual inspection on the user's device.

The second emulator run produced a Compose ripple/animation-thread exception in the Stop probe. The Stop implementation was not changed. Replacing only the test driver with native UIAutomator taps passed in the final run. The earlier exception remains documented as test-framework interference, not a confirmed product Stop defect.

The original screenshot extraction path disappeared when Gradle cleaned up the installed app after tests. Capture was moved to a shell-owned Download directory in the emulator. The final run successfully pulled **11 screenshot files**. Those images are retained with XML/HTML reports and filtered logs. They have not been visually inspected in this disconnected local environment.

Latest evidence:
- [Final JVM run](https://github.com/aviterima/Mirage/actions/runs/36162857211)
- [Final JVM reports](https://github.com/aviterima/Mirage/actions/runs/36162857211/artifacts/10876398279)
- [Final emulator run](https://github.com/aviterima/Mirage/actions/runs/36162856973)
- [Emulator screenshots and reports](https://github.com/aviterima/Mirage/actions/runs/36162856973/artifacts/10876498568)

Across the latest suites there are **83 automated checks: 72 passed and 11 failed**. These are not 83 full user journeys, and are not a claim that all 50 use cases were executed. The eleven failures include the layout probe, whose measurement limits are specified above.

## Fifty-case execution register

**FAIL** means an executed component or screen requirement failed. **PARTIAL PASS** means a limited check passed, with remaining requirements stated. **NOT RUN / BLOCKED** distinguishes incomplete coverage and feature prerequisites from actual test results. No complete 50-case device sign-off is claimed.

| Case | Journey | Recorded result | Evidence or remaining work |
|---|---|---|---|
| 1 | Create named place | NOT RUN / BLOCKED | Place creation implemented indirectly; independent place workflow missing. |
| 2 | Drop precise pin | NOT RUN / BLOCKED | Explicit safe pin-selection workflow missing; live pin editing not executed. |
| 3 | Save real location during simulation | FAIL — related component | Saved real-origin route resolves to simulated coordinates; fresh physical-location capture not exercised. |
| 4 | Save simulated position | NOT RUN / BLOCKED | Direct Save simulated position workflow missing. |
| 5 | Enter coordinates | NOT RUN / BLOCKED | Validated coordinate-entry UI missing. |
| 6 | Building entrances | NOT RUN / BLOCKED | Building/entrance relationship UI missing. |
| 7 | Edit saved place | NOT RUN / BLOCKED | Independent place editing and reference semantics missing. |
| 8 | Find saved items | PARTIAL PASS | Emulator displays saved fixture collection; search and large-collection workflow untested. |
| 9 | Duplicate names and deletion | FAIL — component | Duplicate name destroys original without explicit replacement. |
| 10 | Two snaps as endpoints | FAIL — emulator UI | Saved snap endpoint action absent on actual Saved plans screen. |
| 11 | Snap and hold | PARTIAL PASS | Snap starts and both provider timestamps become fresh on API 34 emulator; extended hold and receiving apps untested. |
| 12 | Timed snap departure | NOT RUN / BLOCKED | Saved-place composition prerequisite missing; timed departure journey not executed. |
| 13 | Route from real location | NOT RUN / BLOCKED | Live route/search API journey not executed in emulator without Maps key. |
| 14 | Swap endpoints | NOT RUN / BLOCKED | Endpoint-swap workflow missing. |
| 15 | Select travel mode | NOT RUN / BLOCKED | Full per-mode map/search journey not executed. |
| 16 | Saved route path semantics | NOT RUN / BLOCKED | Exact-path route persistence unsupported; route-recalculation journey not executed. |
| 17 | Different virtual origin | NOT RUN / BLOCKED | Explicit transition choice workflow missing. |
| 18 | Routing failure recovery | NOT RUN / BLOCKED | Network/provider failure-injection journey not executed. |
| 19 | Highway ETA | NOT RUN / BLOCKED | Live Maps ETA comparison not executed; engine unit coverage is not this journey. |
| 20 | Transit refresh | NOT RUN / BLOCKED | Live transit schedules/refresh not exercised. |
| 21 | Compose saved routes | FAIL — emulator UI | Add-to-itinerary action absent on actual Saved plans screen. |
| 22 | Disconnected routes | NOT RUN / BLOCKED | Saved-route composer prerequisite missing. |
| 23 | Insert saved stop | NOT RUN / BLOCKED | Saved-place insertion prerequisite missing. |
| 24 | Mixed-mode day | NOT RUN / BLOCKED | Mixed-mode full journey not exercised; routing/service data required. |
| 25 | Exact stays and departures | FAIL — component | Arrival/destination/stay command loses journey intent; clock-time departure remains untested. |
| 26 | Reorder draft stops | NOT RUN / BLOCKED | Complete visual reorder/remove/Undo journey not exercised. |
| 27 | Edit live upcoming stops | PARTIAL PASS | Live current identity protected while upcoming stay changes; complete interactive edit journey not executed. |
| 28 | Return to trip origin | FAIL — component | Return uses stale draft origin instead of effective simulated origin. |
| 29 | Save edited active itinerary | FAIL — component | Full-trip save shortens current stay after elapsed time. |
| 30 | Scheduled flight journey | NOT RUN / BLOCKED | Real flight schedule integration absent. |
| 31 | Understand active state | NOT RUN / BLOCKED | Five-second comprehension requires usability observation; only status components tested. |
| 32 | Map visibility | FAIL — layout probe | Central clear-height upper bound is 70.03% of full Compose root, below this probe's 75% criterion. System-inset-normalized target and visual QA remain outstanding. |
| 33 | Diagnose output warning | PARTIAL PASS | Stale fused acknowledgement triggers warning; user's warning cause and recovery remain unresolved. |
| 34 | Pause/resume | NOT RUN / BLOCKED | Service-level pause/resume on device not exercised in this pass. |
| 35 | Immediate reroute | NOT RUN / BLOCKED | Moving-origin route replacement with live API not exercised. |
| 36 | Next stop versus end | NOT RUN / BLOCKED | Insertion-position controls incomplete. |
| 37 | Extend or end stay | FAIL — component | Seventeen-minute spoken extension not parsed as requested. |
| 38 | Fast-forward | NOT RUN / BLOCKED | Full UI timing/fast-forward journey not exercised; existing engine checks do not replace it. |
| 39 | Stop and real-location handback | PARTIAL PASS | Native UI taps start a snap, stop output, and display simulation off. Fresh physical fix and receiving-app handback untested. |
| 40 | Background and process loss | NOT RUN / BLOCKED | Screen lock, battery restrictions and process-loss journey not executed. |
| 41 | Typed instruction | NOT RUN / BLOCKED | Typed command through live search/application not exercised. |
| 42 | Wake word | NOT RUN / BLOCKED | Microphone, wake phrase, audio and screen-lock device trials not executed. |
| 43 | Saved names in voice | NOT RUN / BLOCKED | Saved-name resolution missing in voice controller. |
| 44 | Ambiguity and cancellation | FAIL / PARTIAL PASS | Negative duration rejection fails; bare Stop clarification passes. Search-cancellation races untested. |
| 45 | Health-aware voice status | FAIL — component | Spoken status omits unconfirmed output warning. |
| 46 | In-place upgrade | PARTIAL PASS | In-memory serialization/fresh ViewModel only; signed device upgrade not executed. |
| 47 | Backup/reinstall | NOT RUN / BLOCKED | Backup/restore feature absent. |
| 48 | Setup refresh | NOT RUN / BLOCKED | Settings-return and setup-check freshness journey not exercised. |
| 49 | Accessibility | NOT RUN / BLOCKED | Large-text and screen-reader journey not executed. |
| 50 | Combined failure recovery | NOT RUN / BLOCKED | Combined failure/Stop race injection not executed. |

The register has 11 cases with failing requirements, six with partial passing evidence, and 33 with no executed journey in this pass. Case 03's probe addresses related saved-origin policy rather than the entire physical-location capture use case. Case 44 includes both a failing negative-duration check and passing Stop clarification.

## Required work before release confidence

1. Fix silent overwrite, origin-policy errors, full-template stay semantics, and misleading speech.
2. Add the missing saved-place endpoint and saved-route composition workflows.
3. Make command parsing reject malformed durations and retain complete journey intent.
4. Redesign the live panels, then measure usable map area with system insets and large-text settings.
5. Rerun the existing failed probes after fixes without weakening assertions to match the defects.
6. Implement full journeys for the remaining cases, with live API tests where appropriate.
7. Complete physical-device validation for background operation, audio, provider recovery, signed upgrades, data durability, and real-location handback.

No replacement build should be described as bulletproof or fully validated on the basis of the current results.
