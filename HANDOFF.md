# Mirage — 0.11.0 implementation handoff

## Build validation update — 2026-09-23

GitHub repository access is restored. Candidate commit `842b4bcc2f752242d3c3a39b7ef89cc60101b605` passed the full Android debug APK build, Gradle unit tests, lint, and the packaged offline voice-model check in GitHub Actions run 35804670155. PR: https://github.com/aviterima/Mirage/pull/1 .

The initial CI build exposed Java package names shadowed by the Gradle Java extension in the model-download task; explicit URI and ZipInputStream imports fixed this. The earlier source-only and repository-access blockers below are historical and superseded by this update. The APK is being prepared for user testing. Physical-phone UI, microphone, wake-word, and battery behavior remain unverified. Update compatibility with the currently installed APK's signing certificate has not been verified; do not uninstall the current app or erase its data merely to resolve an installation error.


Updated: 2026-09-22 · Candidate version: 0.11.0 (versionCode 29)
Canonical repository: https://github.com/aviterima/Mirage · baseline main: `3fd315c115f816b07336de2942ba156dd90f6303`

**Status: source implementation prepared; not published or Android-device validated.**
The connected GitHub integration can read the repository but refused the tree-write
operation with HTTP 403 `Resource not accessible by integration`. No branch, PR,
commit on GitHub, or new APK was created. The original published APK remains unchanged.

The original 0.10.0 handoff is preserved below as historical context. Where it
conflicts with this update, the current spec/report and source take precedence.

## Current documents

- [Live/voice specification](docs/LIVE_AND_VOICE_SPEC.md): behavior and release gates.
- [Implementation report](docs/IMPLEMENTATION_REPORT_0.11.0.md): code status, evidence,
  limitations and next actions.
- [Voice dependencies](docs/THIRD_PARTY_VOICE.md): runtime/model provenance.

## New source map

| File | Responsibility |
| --- | --- |
| `engine/LivePlan.kt` | Separate execution snapshot; stable stop IDs; upcoming edits; timed stays; just-in-time routing with holding fixes; append after arrival |
| `LiveUi.kt` | Live card, upcoming editor, advanced dialog, typed/voice chat panel |
| `CommandParser.kt` | Bounded offline English commands; explicit vs ambiguous Stop; multi-stop syntax |
| `Conversation.kt` | Process-scoped shared controller, search/choices, command cancellation, in-memory history, voice replies |
| `VoiceService.kt` | Offline Hello Mirage recognizer, one-shot speech, acknowledgement tones, TTS, microphone notification and release |
| `MapScreen.kt` | Switches between Live and draft planning; persistent Stop/chat; active route vs preview |
| `MockLocationService.kt` | Queue polling while holding; paused last-fix behavior; execution failure reporting |

All app files above live under `android/app/src/main/java/com/mirage/spike/`.

## What changed

1. Running simulations have a dedicated Live view. The draft planner opens only on
   request. Active geometry is independent of draft edits.
2. Upcoming-stop edits act on LivePlan; they do not silently edit only the screen.
3. Timed stays can be set/extended during a run; arrival holds accept new stops.
4. Future route/transit legs are looked up when they start; fixes continue during
   lookup. Failed routing holds position and reports the error.
5. Touch, typed commands, and spoken commands share execution state. The command
   language is explicitly bounded; no cloud LLM is configured.
6. Voice is opt-in, local Vosk recognition with Hello Mirage and two-tone acknowledgement.
   It uses a separate microphone foreground service. No boot/restart listening.
7. Build config packages a versioned English model. CI checks that asset, preserves
   Maps/gateway build environment across all Gradle steps, and limits rolling release
   publication to main-branch pushes.

## Validation truth

A standalone Kotlin JVM harness compiled the actual parser, execution state, motion
models and LivePlan with only the external Google routing client stubbed. Twenty
behavior checks passed in the final run.
Conversation also compiled with explicit Android/network stubs. This is not an
Android build or a substitute for the full existing JVM suite.

The local Gradle wrapper cannot download Gradle 8.7 (`Network is unreachable`). No
Android SDK/device/adb is available here. GitHub write denial prevents submitting
this candidate to Actions. The historical successful main workflows validate only
the old baseline, not this candidate.

## First actions in a build-capable environment

1. Apply the supplied patch against the exact baseline or copy the updated source.
2. Create a feature branch; run `cd android && ./gradlew testDebugUnitTest assembleDebug lintDebug`.
3. Verify `assets/model-en-us/am/final.mdl` and `uuid` are in the APK. Record the model
   archive SHA-256; pin it for future reproducible builds.
4. Resolve any Android/Compose/native integration issues; do not infer success from
   the standalone checks.
5. Run the device acceptance list in the current specification. Especially test
   pause, timed stay, arrival append, transit timing, Stop during lookup, voice false
   wakes, chime readiness, screen-off behavior, and battery consumption.
6. Only after checks pass, merge/release. The candidate is not a public-release build.

Known follow-ons: unrestricted language interpretation; saved place aliases;
absolute-time scheduling; conversation persistence; full hardware wake optimization;
process-death resume. The dormant gateway accounting issues described in the prior
review were not changed in this UI/voice update.

---

# Historical baseline handoff — 0.10.0

# Mirage — engineering handoff

**Prepared:** 2026-09-22 · **Version:** 0.10.0 (versionCode 28) · **Repo:** https://github.com/aviterima/Mirage (branch `main`)
**Owner:** Armando (aviterima@gmail.com) · **Recipient:** Astra

Mirage is an Android location-simulation platform for testing location-aware apps. It
puts the phone anywhere on Earth, drives real Google-routed trips at realistic speeds,
plays whole-day itineraries with stays, rides real public transport on the timetable,
flies great-circle flights, and never drops the simulated position until the user says
Stop. Google Maps is the reference consumer; an iPhone can watch the simulated phone
through Google Maps location sharing. Everything is in this one repository.

---

## 1. What is where

| Path | What it is |
|---|---|
| `SPEC.md` | The original engineering spec (goals, architecture, never-drop rules, UX). Partly ahead of the code, partly behind; the code is the truth. |
| `README.md`, `android/README.md` | User-facing docs: features, setup, keys, automation commands, test protocol. |
| `android/` | The app. Kotlin, Jetpack Compose (Material3), single module `app`. |
| `android/app/src/main/java/com/mirage/spike/` | App code (package name is historical; applicationId is `com.mirage.app`). |
| `…/spike/engine/` | Pure-Kotlin simulation engine: routing clients, motion models, transit, flight, dwell, itinerary. No Android dependencies except `MockState` updates. |
| `…/spike/store/` | Persistence: saved plans (SharedPreferences JSON), user key + install id. |
| `android/app/src/test/` | 39 JVM unit tests (engine + ViewModel). CI blocks the release when red. |
| `.github/workflows/android.yml` | Build → tests → lint → APK artifact → rolling GitHub release `android-latest`. |
| `.github/workflows/verify-maps-key.yml` | Manual check that the `MAPS_API_KEY` secret works for all APIs. |
| `server/` | Optional API gateway (Node/Express + Firestore, Cloud Run) for hosted keys and credit metering. Not deployed. |
| `design/mockups/` | Early UI mockups (HTML). Historical only. |

### App source map

| File | Role |
|---|---|
| `MainActivity.kt` | Host activity. Permissions (live state), settings deep links, **adb command entry** (`--es cmd …`) → `CommandBus`. |
| `MapScreen.kt` | The whole UI (large, ~1,300 lines): map, Start/End boxes, Snap/Route/Itinerary selector, inline itinerary builder with timeline, planning sheet, live HUD, Setup dialog (device checks, key entry + test, automation token), saved-plans dialog, stay-time dialog. Also `realLocation()` (fresh non-mock fix) and the heading-arrow marker. |
| `MirageViewModel.kt` | All planning state and actions: endpoints, plan mode, per-mode speeds, routing, itinerary chain, timeline estimate, saved plans, live controls, queue-after-arrival, automation `runCommand()`. |
| `MockLocationService.kt` | The never-drop foreground service. Feeds LocationManager test providers (gps+network) **and** the Fused Location Provider at 5 Hz; watchdog; leak detection; generation guard against races; pause; queued plans; GPS-signal simulation; notification with Pause/Stop actions; hands back to real GPS on Stop and nudges a fresh real fix. |
| `MockState.kt` | Service → UI status bridge (`StateFlow<MockStatus>`), atomic updates. |
| `CommandBus.kt` | Shared flow carrying adb commands to the ViewModel. |
| `Theme.kt` | Material colour scheme (indigo). |
| `engine/Models.kt` | `LatLng`, `TravelMode` (DRIVE/BIKE/WALK/TRANSIT/FLY), `Realism`, `RouteSpec/Result/Segment`, `TransitDetails`, `Fix` (a synthetic GPS sample with progress/ETA). |
| `engine/ApiConfig.kt` | Where Google web APIs are reached: DIRECT (key) or HOSTED (gateway + install id). `CreditsState`. |
| `engine/RouteEngine.kt` | Google Directions client + `parseDirections()` (steps, transit details). |
| `engine/PlacesClient.kt`, `engine/Geocoder.kt` | Places API (New) text search (type-ahead), Geocoding fallback. |
| `engine/KeyTester.kt` | Exercises every API with a tiny request; per-API OK/reason for the Setup dialog. |
| `engine/MotionModel.kt` | Paced motion for bike/walk (and drive when no steps): target average speed, variance, stops. Also **`PlaybackSource`** (the UI→service handoff: current flow, live time scale, pause, skip, over-limit, signal, queue) and `Signal` presets. |
| `engine/DriveModel.kt` | Real driving: per-step posted-limit estimate, cruise at limit + N, braking for turns/ramps, traffic lights on surface streets. |
| `engine/TransitModel.kt` | Real transit: walk, wait for the scheduled departure, ride with vehicle profiles and station halts, arrive on time. |
| `engine/FlightModel.kt` | Great-circle flight with taxi/climb/cruise/descent profile. |
| `engine/DwellModel.kt` | A person at a place: still with GPS noise, occasional walk to a nearby spot and back. Used for holds and itinerary stays. |
| `engine/Itinerary.kt` | Chains legs + stays into one stream; dwells where the leg actually ended. |
| `engine/PolylineCodec.kt` | Google encoded polyline decoder (bounds-checked). |
| `store/Scenarios.kt`, `store/PrefsScenarioStore.kt` | Saved plans model + JSON + SharedPreferences store. |
| `store/PrefsKeyStore.kt` | User-pasted key and stable install id. |

---

## 2. How it works (the 5-minute version)

1. **Planning** happens in `MirageViewModel`. The user picks a plan mode (Snap / Route / Itinerary), fills the Start/End boxes (search with type-ahead, map tap, or ⌖ menu: real location / current simulated position / where the current trip ends), and taps Get route or Start.
2. **Routing** calls Google Directions (driving/bicycling/walking/transit). For flights nothing is called; a great-circle path is computed locally.
3. **Arming**: the ViewModel builds a `Flow<Fix>` from the right model and puts it in `PlaybackSource.current`, then starts `MockLocationService`. While running, a new Start either replaces the playback or, if the start was "where the current trip ends", is **queued** to begin on arrival.
4. **The service** collects the flow and pushes each `Fix` to the LocationManager test providers and to the Fused Location Provider (what Google Maps reads). When the flow completes it **holds** the last point with `DwellModel` until Stop. Every Start/Stop bumps a generation counter; stale ticks are dropped.
5. **Live controls** (fast-forward, pause, skip, over-limit speed, GPS signal) are `@Volatile` fields on `PlaybackSource` read every tick by the models and the service, so they take effect immediately.
6. **Stop** removes the test providers, turns mock mode off, and requests a fresh real fix so the phone and Google Maps snap back to reality at once. The app then re-anchors the Start box to the real position.

Key non-obvious facts:
- Apps only appear in Developer options → "Select mock location app" if the manifest declares `ACCESS_MOCK_LOCATION`. Mirage does. This selection is a one-time step.
- Android 14 throws if a `location`-type foreground service is started without location permission; the service checks first.
- Turning mock mode off does **not** clear the fused provider's cached last fix; you must request a fresh one (`nudgeRealFix`, `realLocation()`), rejecting anything still flagged mock.
- Google does not sell speed-limit data to standard keys. `DriveModel` infers limits from road class + Google's own step timing (25/30/35/45/65 mph). It is an estimate.
- Unit tests run against a stub `android.jar` whose JSON classes return nothing; `org.json:json` is a test dependency for that reason.

---

## 3. Build, CI, release

- **Local:** Android Studio (Giraffe+) or `cd android && ./gradlew installDebug` (JDK 17, SDK 34). Put `MAPS_API_KEY=…` in `android/local.properties` (gitignored).
- **CI:** every push to `main` builds a debug APK, runs the unit tests (blocking), runs lint (non-blocking), uploads artifacts, and refreshes the rolling prerelease **`android-latest`**.
- **Download:** https://github.com/aviterima/Mirage/releases/download/android-latest/app-debug.apk
- **Secrets/variables (repo Settings → Secrets and variables → Actions):**
  - `MAPS_API_KEY` (secret) — present as of 2026-09-11; verified working for Geocoding, Directions, Places (New).
  - `MIRAGE_API_BASE` (variable) — empty. Set to the Cloud Run URL to switch builds to hosted keys (see §7).
- **Signing:** debug-signed only. No release keystore exists yet. A Play Store build needs an upload key + Play App Signing (see §8).
- The environment that produced this code kept resetting the local git remote to a deleted repo (`Last-Z`); the canonical remote is `https://github.com/aviterima/mirage`. Nothing else depends on Last-Z.

---

## 4. Device setup and validation status

One-time on the phone: Developer options → Select mock location app → Mirage; grant precise Location and Notifications; allow "Ignore battery optimizations". The ⚙ Setup dialog shows a live ✓/✗ per step.

**Validated on a real device by the owner (Android, Phoenix AZ):** map opens on the real location, type-ahead search, drive routes followed by Google Maps, Snap, hold behaviour, Stop returning to real location, planning a new leg while simulating, fast-forward.

**Built and unit-tested but NOT yet exercised on a device:** Transit mode, flights end-to-end, itinerary stays over a long day, queue-after-arrival, pause/skip, GPS signal presets and dropouts, DriveModel lights/limits feel, saved plans across an app update, adb automation, Android Auto coexistence (SPEC §5.4), Doze survival over hours (SPEC test protocol). Treat these as the first field-test list.

---

## 5. Tests

`android/app/src/test/…` — 39 tests, ~20 s in CI:

- `EngineTest`: MotionModel endpoint/progress/no-teleport/fast-forward/zero-speed; DwellModel stays inside the building and walks; FlightModel profile; ItineraryModel dwells at the road-snapped endpoint; PolylineCodec.
- `TimeScaleTest`: live fast-forward across models.
- `TransitTest`: synthetic walk-wait-ride-arrive trip; Directions transit parsing; no-connection message.
- `LiveControlsTest`: limit inference, city drive (limit+5, lights, arrives), live over-limit, skip, queue, signal presets.
- `MirageViewModelTest`, `ItineraryBuilderTest`, `ScenarioTest`, `AutomationTest`: the planning state machine, timeline, reorder, saved plans round trip, adb commands and token.

There are no instrumented (device) tests and no UI tests. The service and Compose screen are covered only by manual testing.

---

## 6. Known issues, caveats, debt

1. **`MapScreen.kt` is too big.** It grew feature by feature. First refactor candidate: split into `TopCard`, `PlanningSheet`, `LiveHud`, `SetupDialog`, `SavedPlansDialog`, and move `realLocation()` into a `LocationSource` class.
2. **Speed limits are inferred**, not looked up. If accuracy matters, Google Roads API `speedLimits` needs an asset-tracking-enabled account; OpenStreetMap `maxspeed` via Overpass is a free alternative worth a spike.
3. **Transit uses the timetable at Get-route time.** If Start is delayed past the departure, the ride leaves immediately rather than waiting for the next one. Fix: re-route on Start when the first departure is in the past.
4. **Timeline in the builder is an estimate** (straight-line × 1.3 at the leg's speed). Real times come from routing at Start. Could route each leg on add for exact times at the cost of API calls.
5. **The API key is baked into the APK** (`BuildConfig.MAPS_API_KEY`). Fine for internal use; for any public distribution use the gateway (§7) and an app-restricted key for map tiles only. The key currently in the secret was pasted into chat during development and should be **regenerated** before any public release.
6. **Fast-forward advances position faster than reported speed.** Consumers that sanity-check speed vs displacement may flag it. Documented as a testing feature.
7. **Sticky-restart after process death reverts to real location** (by design: nothing armed survives a kill). A "resume last plan" would need persisting the armed plan.
8. **Lint is non-blocking** and the report is uploaded but nobody reads it. Worth a pass.
9. Locale: miles/mph only. No km toggle yet.
10. `SPEC.md` has sections describing things not built (day-scenario clock modes, saved-route sharing, AA spike) and a few implementation notes the code has moved past (e.g. dither description). Reconcile when convenient.

---

## 7. Hosted keys and credits (dormant, ready)

Everything needed to stop asking users for a Google key exists but is not switched on:

- `engine/ApiConfig.kt` routes Directions/Places/Geocoding either directly (key) or via a gateway (`MIRAGE_API_BASE`) with an anonymous install id header; 402 → "out of credits"; the balance header is shown in Setup.
- `server/index.js` is the gateway: Cloud Run + Firestore; free credits per install, one credit per call, Play purchase verification and acknowledgement for credit packs. `server/README.md` has the deploy commands and the two-key setup (server key unrestricted by app; tiles key app-restricted).
- To turn it on: deploy the server, set the `MIRAGE_API_BASE` repository variable, rebuild. No code change.
- Not built: the in-app "Buy credits" button (Play Billing client). Only meaningful once a Play listing and products exist.

---

## 8. Roadmap (owner-prioritised)

Done in order requested: search with pick list · realistic hold · inline itinerary builder with stays · transit · saved plans · pause/skip · GPS signal · real driving model · queue after arrival · adb automation · key setup in-app · gateway plumbing.

Next, in the order I would do them:
1. **Field-test §4's unvalidated list** and fix what falls out. Especially transit and long itineraries.
2. **Record & replay**: write every emitted fix to GPX/CSV; replay a GPX recorded on a real drive. Big value for reproducing field bugs.
3. **Share plans** between phones (export/import JSON via the share sheet).
4. **Reverse route / waypoints** in Route mode; **scheduled start** and **repeat N times** for soak tests.
5. **Quick Settings tile** for Stop; **km/mi** toggle.
6. **Refactor `MapScreen.kt`** (§6.1) before the UI grows further.
7. **Play Store** (only if the owner decides to ship): release keystore + Play App Signing, foreground-service declaration video, data-safety form, privacy policy, gateway deployed, Play Billing for credits, trademark check on "Mirage". Positioning: developer/QA tool, not a "fake GPS" consumer app.

---

## 9. Decisions log (why things are the way they are)

- **Google Directions over OSRM/OpenRouteService**: road-legal directed geometry (one-ways, ramps) and transit timetables in one API. OSRM was blocked from the dev environment anyway.
- **Feed both LocationManager test providers and the Fused Location Provider**: Google Maps reads the fused provider; other apps read either. Feeding both is what makes it "never drop".
- **Only Stop reverts to real location**; arriving holds the endpoint. The owner was explicit about this after an earlier version reverted on arrival.
- **Start box defaults to the current simulated position while running**; "where the trip ends" queues rather than replaces. Both are owner requirements for building routes on top of a running simulation.
- **Hold behaviour models a person at a desk**: still with sub-metre noise plus slow drift, a walk to a nearby spot every few minutes and back. Earlier random jitter looked like static and Google's location sharing showed it as "not updating".
- **Tests gate the release** after ten builds shipped with regressions. Rule adopted: fix the code, not the test, unless the test is wrong.
- **Android Auto**: the car keeps its own GPS; Mirage does not try to reach the head unit (SPEC §5.4). iPhone never spoofs; it watches through Google Maps sharing.
- **No detection evasion**: fixes are flagged as mock (`isMock`), the app declares itself honestly. This is a testing tool.

---

## 10. Automation reference

Token shown in ⚙ Setup (first 8 chars of the install id).

```bash
A="com.mirage.app/com.mirage.spike.MainActivity"; T=<token>
adb shell am start -n $A --es cmd pause     --es token $T
adb shell am start -n $A --es cmd resume    --es token $T
adb shell am start -n $A --es cmd skip      --es token $T
adb shell am start -n $A --es cmd stop      --es token $T
adb shell am start -n $A --es cmd timescale --es value 10 --es token $T
adb shell am start -n $A --es cmd speed_over --es value 5 --es token $T
adb shell am start -n $A --es cmd signal    --es preset urban --es token $T   # good|urban|poor|indoor
adb shell am start -n $A --es cmd drop      --es seconds 30 --es token $T
adb shell am start -n $A --es cmd snap      --es lat 33.4484 --es lng -112.0740 --es name Office --es token $T
adb shell am start -n $A --es cmd route     --es lat2 33.5091 --es lng2 -112.0263 --es mode drive --es token $T
adb shell am start -n $A --es cmd plan      --es name "Lunch run" --es token $T
```

---

## 11. First week for Astra

1. Clone, add `MAPS_API_KEY` to `android/local.properties`, run `./gradlew testDebugUnitTest`, then `installDebug` on a phone. Do the one-time mock-app selection.
2. Walk the three modes with Google Maps open beside it. Then the §4 unvalidated list.
3. Read `MockLocationService.kt` top to bottom; it is the part that must never regress.
4. Pick §8 item 1 or 2. Add a test for anything you touch in `engine/`.

Questions about intent that the code does not answer: Armando is the product owner and has been very specific about UX; ask rather than guess.
