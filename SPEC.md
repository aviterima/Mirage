# Last-Z — Location Simulation & Testing Platform

**Status:** Draft v0.1
**Owner:** aviterima
**Purpose:** A cross-platform (**Android + iOS**) toolset that reliably and
accurately simulates device location for **QA and automated testing** of
location-aware applications. Reference consumers: **Google Maps** on Android and
**Apple Maps** on iOS — both must follow the simulated route without reverting to
real location.

---

## 1. Context & framing

Last-Z is a **location testing platform**. It feeds synthetic, realistic GPS fixes
to the OS location stack so that apps under test behave as if the device were
somewhere else, moving along a defined route.

- **Android**: an on-device app that registers as the mock-location provider
  (Developer Options → *Select mock location app*). No root.
- **iOS**: a **host-tethered** tool that drives Apple's developer
  location-simulation service over USB/tunnel (the same mechanism Xcode GPX
  playback uses). No jailbreak. See §5A.

This is the same category as Lockito / Mock GPS on Android and GeoPort /
SimVirtualLocation / Xcode on iOS — but those products are inaccurate, drop out
under real-world conditions, and lack a unified automation surface. The goal is a
platform accurate and reliable enough to be the **primary location-testing harness**
for an engineering org across both OSes, with **one route/motion definition** driving
both backends.

### 1.1 In scope
- Accurate, road-snapped route simulation with realistic motion physics.
- A control/automation API so it can drive CI/instrumented tests.
- Reliability engineering so the mock **never silently reverts to real location**.
- A slick, low-friction UI.

### 1.2 Explicitly out of scope (and why)
- **Mock-flag hiding / anti-detection.** Android tags mocked fixes with
  `Location.isMock()` (API 31+) / `isFromMockProvider()`. Apps that reject mocked
  locations do so deliberately. Last-Z will **not** attempt to strip or forge that
  flag (which would require root-level system hooks and cross into
  detection-evasion). For testing *your own* apps this is irrelevant — you control
  the consumer, so the flag never blocks you. This boundary keeps the tool squarely
  a QA instrument.
- Any use aimed at defeating a third party's location checks (game anti-cheat,
  ride-share/delivery/dating/attendance geofencing). That is a ToS violation and
  potentially fraud; it is a non-goal and the product is not designed for it.
- **iOS jailbreak / on-device iOS spoofing app.** There is no sanctioned on-device
  API to inject location into first-party apps on a stock iPhone, and jailbreak
  tweaks are fragile, insecure, and unfit for a professional test fleet. iOS uses
  Apple's own developer location service via a tethered host instead (§5A).
- **RF/GNSS signal spoofing** (transmitting fake satellite signals). Legally
  restricted, affects all nearby receivers; not a software-testing approach.

---

## 2. Goals & success criteria

| # | Goal | Success metric |
|---|------|----------------|
| G1 | **Accuracy** | Simulated fix within routing-geometry tolerance of the real road; speed within ±X% of target average over any 30 s window. |
| G2 | **Reliability** | Mock fixes delivered continuously with **zero unintended gaps** across screen-off, Doze, process restart, and Android Auto connect/disconnect. |
| G0 | **Maps apps are the reference consumers** | **Google Maps** on Android (foreground nav **and** Android Auto) **and Apple Maps** on iOS follow the simulated route with zero reversion to real location for the entire session (iOS: for the duration of the tethered session, §5A.2). |
| G3 | **Realism** | Motion indistinguishable from a real drive on the derived fields (speed, bearing, accuracy, altitude) and stop/traffic behavior. |
| G4 | **Automatability** | Full control via ADB intents / API with no UI interaction, for CI. |
| G5 | **Usability** | A non-engineer can define and run a route in < 60 s. |

The hard constraints are **G0 + G2**: **Google Maps must follow the spoofed route
and never revert to real location**, in the foreground and under Android Auto.
A test invalidated by an accidental revert is worse than no test. Everything else is
designed around that. Third-party apps that deliberately reject mocks (e.g. Uber)
are **not** a priority — they are handled by design in §1.2, not by evasion.

---

## 3. High-level architecture

```
┌──────────────────────────── App process ────────────────────────────┐
│                                                                      │
│  UI layer (Jetpack Compose)                                          │
│    Map screen · Route editor · Session HUD · Settings · Library      │
│                                                                      │
│  ─────────────────────────────────────────────────────────────────  │
│                                                                      │
│  Domain / engine                                                     │
│    RouteEngine ── plans path from endpoints (routing provider)       │
│    MotionModel ── turns path + params into a time series of fixes    │
│    Geocoder    ── name/POI → coordinates (+ reverse)                 │
│                                                                      │
│  ─────────────────────────────────────────────────────────────────  │
│                                                                      │
│  MockLocationService  (foreground, START_STICKY)                     │
│    Emitter loop @ N Hz → pushes to ALL providers                     │
│    Watchdog ── detects de-registration / real-fix leakage / AA       │
│    Provider adapters: LocationManager test providers + FLP mock mode │
│                                                                      │
└──────────────────────────────────────────────────────────────────────┘
        │ ADB intents / broadcast API           │ OS Location stack
        ▼                                         ▼
   CI / test scripts                    Apps under test read fixes
```

- **UI ↔ engine**: UI configures a `SimulationConfig`; engine produces a `Fix`
  stream; service emits it.
- **Engine and service are decoupled** so the service can keep emitting the last
  computed track even if the UI process is backgrounded or the editor is closed.
- **Two delivery backends over one engine**: the Android on-device service and the
  iOS host conductor (§5A) both consume the identical `Fix` stream. The diagram
  above is the Android backend; the iOS conductor replaces `MockLocationService`
  with a desktop process pushing the same stream to the device over the tunnel.

---

## 4. Feature specification

### 4.1 Location & place search (G5)
- Search **by name, POI, landmark, or address** with autocomplete.
- Providers (pluggable): Nominatim/Photon (OSM, free, self-hostable) as default;
  Google Places / Mapbox as optional keyed backends.
- Reverse geocoding for tap-on-map ("what is here").
- Recent searches + favorites (Home, Work, named pins) with one-tap teleport.

### 4.2 Static spoof
- Tap map or search → set a fixed location.
- Manual override of altitude, bearing, accuracy, and reported provider.
- **Joystick mode**: free-roam thumbstick with adjustable step speed for spot
  testing (walk-speed to drive-speed).

### 4.3 Point-to-point routed spoof (core)
- Pick **two endpoints** (search or map tap) → engine requests a **road-snapped
  route** from the routing provider (OSRM/GraphHopper default; Google/Mapbox
  Directions optional).
- Multi-stop routes: ordered waypoints.
- Route options: transport mode (drive/bike/walk/transit), avoid tolls/highways
  where the backend supports it.
- Editable polyline: drag to reshape, insert/remove waypoints.

### 4.4 Motion model / realism engine (G3)
The `MotionModel` converts a route polyline + parameters into a fixed-cadence time
series of `Fix` objects.

- **Target average speed** is the control input; instantaneous speed is drawn from
  a distribution around it so the **mean over the route equals the target**.
- **Speed limits per segment** (from routing metadata where available) cap the
  instantaneous speed; residential vs. arterial vs. highway profiles.
- **Acceleration/deceleration curves** — bounded accel/brake; speed is continuous,
  never a step change.
- **Stops**:
  - Traffic lights / stop signs at intersections — probabilistic occurrence and
    dwell time.
  - Random incidental stops (parking, congestion) — configurable rate.
- **Derived fields computed correctly** for each fix: `speed`, `bearing` (from the
  actual heading vector), `altitude` (terrain lookup or interpolated), `accuracy`
  (jittered within a configurable band), `time`, satellite count where reported.
- **Signal realism (optional, for negative testing)**: tunnel/dropout windows,
  accuracy degradation, so consumers can be tested against bad GPS.
- **Determinism switch**: a fixed RNG seed reproduces an identical track — essential
  for repeatable tests.
- **Time compression**: playback multiplier (1×…N×) so a 40-min drive runs in
  seconds while preserving the shape of the track.

#### 4.4.1 Stationary dither (never a frozen pin)
When the device is **stopped** (a Stay step, a traffic-light dwell, or paused at a
place), it must **not** report a perfectly fixed coordinate — real GPS wanders. The
MotionModel runs a stationary model:
- **Bounded random walk** around the anchor (mean-reverting / Ornstein–Uhlenbeck so
  it drifts but stays near center), updated periodically rather than every tick.
- **Radius scales with place size**: a small pin dithers within a few metres; a
  **large location** (campus, mall, airport, depot) dithers within a configurable
  larger radius, optionally a place polygon, so the device plausibly "moves around
  inside" the venue over time.
- **Accuracy field co-varies** with the dither (looser accuracy indoors/large venue),
  and occasional larger jumps model a re-acquired fix.
- Cadence, radius, and jitter are configurable per place and seeded for determinism.
- Set radius to 0 for a hard-fixed point when a test needs an exact coordinate.

### 4.5 Playback controls
- Play / pause / resume / stop.
- Seek to any point on the route; scrub.
- Loop, reverse, and chain routes.

### 4.6 Route library & interchange
- Save / name / organize routes and sessions.
- **Import / export GPX and KML**; import an existing recorded track and replay it.
- Duplicate, reverse, edit saved routes.

### 4.7 Live HUD / telemetry
- On-map marker with heading; trailing breadcrumb.
- Overlay: current coordinates, speed, bearing, accuracy, elapsed/ETA,
  provider-emission health (see §5).

### 4.8 Automation / control API (G4)
- Control via **ADB broadcast intents** and an in-app scripting endpoint:
  - `START` with a config (inline params or a saved-route id / GPX path).
  - `PAUSE`, `RESUME`, `STOP`, `SEEK`, `SET_SPEED`, `TELEPORT lat,lng`.
  - `STATUS` returns current fix + health as structured output (for assertions).
- Config is declarative (JSON) so a test can spin up an exact scenario headlessly.
- Emits structured logs (and optional NMEA) for test capture.

---

### 4.9 Day scenario / itinerary scheduler (testbed timeline)
Compose a **whole day** of location behavior as a single scheduled scenario, so the
device autonomously moves through a realistic day while apps under test observe it.

- A **Scenario** is an ordered timeline of **steps**, each either:
  - **Stay** — dwell at a place for a duration or **until a wall-clock time**
    (e.g. "Office, 09:00–12:30"), with stationary dither (§4.4.1) active.
  - **Travel** — drive/walk/transit a routed leg to the next place, controlled by
    either a target **average speed** or an **arrive-by time** (engine solves the
    speed profile to hit the arrival, within road/speed-limit bounds).
- **Clock modes**:
  - **Real-time** — steps fire on the actual clock (start 08:00, the day plays out
    live) so the testbed mirrors a real day.
  - **Compressed** — a time multiplier runs the whole day in minutes for fast tests.
  - **Anchored** — pin the scenario start to a chosen date/time; useful for
    time-of-day-dependent app logic.
- **Feasibility check**: the editor flags impossible schedules (arrive-by earlier
  than the fastest routed time) and suggests the minimum feasible time.
- **Determinism**: a scenario seed reproduces the same day (same stops, same dither)
  for repeatable regression runs.
- **Persistence & resume**: a running scenario survives process death (§5.2) and
  resumes at the correct step + offset, so an overnight/all-day run is never lost.
- **Interchange**: scenarios save/load and export (JSON; GPX per travel leg).
- **Automation**: start/seek a scenario or jump to a named step via the control API
  (§9), so CI can place the device at "11:00, mid-commute" instantly.

## 5. Reliability engineering — "never drop out" (G2)

This is the headline requirement and the main differentiator. A test must never be
invalidated by the device silently serving a **real** fix.

### 5.1 Emit to *every* provider, continuously — **FLP first**
- **Fused Location Provider is the primary path**, because **Google Maps reads
  FLP** (Play Services location), not raw `LocationManager`. Drive it with
  `FusedLocationProviderClient.setMockMode(true)` + `setMockLocation(...)`. Mocking
  only `gps` is the #1 reason competitors "leak" a real fix into Google Maps.
- Also register mock test providers for **`gps`** and **`network`** via
  `LocationManager.addTestProvider` / `setTestProviderLocation`, so raw-`LocationManager`
  consumers stay covered too.
- Emit at a **fixed cadence (default 5–10 Hz, configurable)** so no consumer ever
  reads a stale or real fix between updates.
- **Reference-consumer gate:** every reliability change is validated against
  **Google Maps navigation** (foreground + Android Auto) as the acceptance bar — if
  Maps holds the spoofed route without reverting, G0 is met.

### 5.2 Foreground service + lifecycle hardening
- `MockLocationService` runs as a **foreground service** (`START_STICKY`), with a
  persistent notification and a `location` foreground-service type.
- Request **battery-optimization exemption** (Doze allowlist) and hold a wake lock
  while a session is active, so screen-off / idle never starves the emitter.
- On process death, `START_STICKY` + a persisted "active session" record lets the
  service **resume the exact track** at the correct time offset.

### 5.3 Watchdog
A monitor coroutine independent of the emit loop that:
- Verifies test providers are still registered every tick; **re-registers** on loss.
- Reads back the current OS fix and flags **real-fix leakage** (a fix whose
  provider/flag/coords indicate it did not come from us) → immediately re-asserts.
- Surfaces health in the HUD and API `STATUS` (GREEN = emitting & no leakage,
  AMBER = re-asserting, RED = cannot mock). Optional hard-fail hook so a CI run
  **aborts the test** instead of silently trusting a leaked location.

### 5.4 Android Auto coexistence — car real, phone-apps spoofed

**Desired end state (not a bug to remove):** while driving a real route, the **car's
Android Auto Google Maps navigates on real location**, and *simultaneously* the
**apps under test on the phone keep the spoofed location**. This is the reverse of
the naive "keep the spoof everywhere" goal — the car being real is wanted.

**Why this is possible.** A single phone cannot serve two different locations through
the *global* mock API, but the car and the phone are effectively **two location
sources**: the car head unit / Android Automotive supplies its **own GPS** to Android
Auto (independent of the phone — this matches the observed behavior). So the split is
naturally: **car GPS → car nav (real)**, **phone mock → phone apps (spoofed)**.

**Primary mechanism (chosen): rely on the car's own GPS.**
- No app changes. Phone spoofs system-wide; the car's Maps uses the head unit's GPS
  and is unaffected.
- **The one real risk to engineer:** if the head unit feeds its GPS *back* into the
  phone (via the Android Auto vehicle-sensor channel / an external NMEA source) and
  the phone's fused provider **prefers that real fix over our mock**, the phone apps
  would leak real location. That is the actual failure mode behind the earlier
  "reverts to real" observation, and it is what the phone must defend against:
  - Keep FLP mock mode + test providers continuously asserted at cadence (§5.1).
  - Watchdog (§5.3) detects any real-fix leakage on the phone and immediately
    re-asserts; tighten cadence across the AA connect/disconnect edge, observed via
    `CarConnection` LiveData (`CONNECTION_TYPE_PROJECTION`) + `UiModeManager`.
  - Emit loop lives in the service, independent of UI mode.

**Fallbacks (documented; not primary):**
- **Test-SDK injection** for the *own* apps in the mixed fleet (§8A): those apps read
  spoofed fixes from a Last-Z source directly, guaranteeing their spoof regardless of
  any fused-provider fight. Black-box apps in the fleet stay on the global-mock +
  car-own-GPS path above.
- **Two-device rig** where absolute isolation is required (phone A → real car nav,
  phone B → spoofed apps).

**Phase-0 AA spike must empirically determine:** whether this car's GPS reaches the
phone's fused provider, and whether mock reliably wins — on a physical head unit and
the Desktop Head Unit (DHU). The chosen mechanism above is only "done" once the spike
shows the phone apps hold the spoof through a full AA drive while the car nav stays
real.

### 5.5 Acceptance tests for G0 + G2
- **Google Maps, foreground navigation** along a simulated route → blue dot tracks
  the spoofed route end-to-end; no reversion to real location.
- **Google Maps under Android Auto** → same, across connect and disconnect edges.
- Screen-off for 30 min → no gap > one emit interval.
- Force-stop mid-session → auto-resume within N seconds at correct offset.
- AA connect, drive a simulated route, disconnect → continuous mock throughout;
  watchdog logs zero real-fix leakage.
- Doze (`adb shell dumpsys deviceidle force-idle`) → emission continues.

---

## 5A. iOS backend — Apple Maps, never-drop (host-tethered)

iOS has **no on-device mock-location provider** for stock devices. The sanctioned,
non-jailbreak way to make **Apple Maps and all system apps** follow a simulated
route is Apple's **developer location-simulation service**, driven from a connected
host computer. Last-Z's iOS backend is therefore a **desktop "conductor"** that
consumes the *same* `Route` + `MotionModel` output as Android and pushes fixes to
the device.

### 5A.1 Mechanism
- Host tool (cross-platform: macOS/Windows/Linux) speaks Apple's developer services
  via **`pymobiledevice3`** (`developer dvt simulate-location set` / `play <gpx>`),
  the same service Xcode uses for GPX playback.
- **System-wide effect**: the simulated fix replaces Core Location, so **Apple Maps,
  weather, geotagging, and third-party apps** all read it — no per-app hooks.
- iOS 17+ requires a **RemoteXPC tunnel** (root/sudo on the host) established before
  the developer service is reachable; on iOS < 17 the older Developer Disk Image
  path applies. The conductor abstracts the version differences.

### 5A.2 Never-drop on iOS (honest constraints)
The never-drop guarantee is **bounded by the tethered session** — an inherent
platform limit, not a tooling gap. Within a session the conductor makes it robust:
- Feed a continuous fix stream (from the shared `MotionModel`) rather than a one-shot
  set, at a fixed cadence, so Maps keeps a fresh non-real fix.
- **Connection watchdog**: detect USB/tunnel drop and auto-reconnect + re-establish
  the tunnel, resuming the track at the correct time offset.
- Prefer a stable wired connection; treat cable/port quality as a test-rig concern.
- **Known caveats to validate in the iOS spike:** iOS 18.2 removed device-side QUIC
  for the tunnel (TCP fallback needs Python 3.13+); simulate-location has had
  reliability issues on some iOS 17 point releases. Pin a known-good iOS + tool
  matrix for the lab.
- **Cannot** survive device reboot or physical disconnect the way the Android
  on-device service can — document this so tests are designed around it (the rig
  stays tethered for the duration of a run).

### 5A.3 Simulator & app-only path
If a given test only needs *your own iOS app* (not Apple Maps) to follow a route,
`xcrun simctl location` (Simulator) and Xcode GPX playback are simpler and fully
sanctioned. The conductor exposes both; the tethered developer-service path is used
when **Apple Maps / system-wide** behavior is under test.

### 5A.4 Shared engine
`RouteEngine`, `MotionModel`, and the GPX interchange are **platform-agnostic** and
live in a shared core. Android and the iOS conductor are thin delivery adapters over
the identical fix stream, so a route defined once behaves identically on both — the
key to using Last-Z as a single cross-platform harness.

---

## 6. UI / UX specification (G5)

Design principle: **map-first, one primary action visible at all times.**

- **Home / Map screen**: full-bleed map; a single search bar (place/POI/address);
  a floating primary button whose label reflects state (*Set location* →
  *Simulate route* → *Stop*). Long-press to drop a pin.
- **Route editor**: pick A and B, engine draws the snapped route; a bottom sheet
  exposes the few knobs that matter — **average speed**, transport mode, realism
  preset (Off / Realistic / Aggressive), loop, and time-compression. Advanced
  params (accuracy band, stop rates, seed) behind an *Advanced* expander.
- **Session HUD**: compact top card with live speed/coords/ETA and the
  GREEN/AMBER/RED health chip; scrubber at the bottom.
- **Library**: saved routes/pins/favorites; import/export.
- **Design language**: Material 3, dynamic color, full dark mode, large touch
  targets, minimal chrome. A first-run 3-step primer that walks the user through
  enabling the mock-location developer setting (the one unavoidable setup step),
  with a deep link to the right Settings page and a live check that it worked.
- **Motion presets** so a non-expert never has to think about distributions:
  *Realistic* (default) applies sensible stop rates, speed variance, and accuracy
  jitter; *Constant* for deterministic/simple tests.

---

## 7. Technology stack

| Concern | Choice | Notes |
|---|---|---|
| Language / UI | **Kotlin + Jetpack Compose** | Modern, testable. |
| Min / target SDK | Min 26, target latest | `isMock` behavior differs pre/post API 31 — handle both. |
| Map | **MapLibre GL Native** (default) | Open, no key lock-in; Google Maps SDK optional. |
| Routing | **OSRM / GraphHopper** (self-host or hosted) | Google/Mapbox Directions optional, keyed. |
| Geocoding | **Photon / Nominatim** | Google Places / Mapbox optional. |
| Location out | `LocationManager` test providers **+** `FusedLocationProviderClient` mock mode | Both, always (§5.1). |
| Car integration | **androidx.car.app** (`CarConnection`) | Projection state detection. |
| iOS delivery | **`pymobiledevice3`** developer location service, over RemoteXPC tunnel | Host-tethered conductor; system-wide incl. Apple Maps (§5A). |
| iOS Simulator / app-only | `xcrun simctl location`, Xcode GPX | Optional simpler path when Apple Maps isn't the target. |
| Shared core | Platform-agnostic route/motion engine + GPX | Feeds both Android app and iOS conductor identically. |
| Concurrency | Coroutines + Flow | Emit loop, watchdog. |
| Persistence | Room | Routes, favorites, session state. |
| DI | Hilt | |
| Build | Gradle (Kotlin DSL), single-module to start | Split into `:app` / `:engine` / `:mock-service` when it grows. |

Backend endpoints (routing/geocoding) are **pluggable interfaces** so the org can
point them at self-hosted OSM services for privacy, cost, and offline control.

---

## 8. Core data model (sketch)

```
LatLng(lat, lng, altitude?)
Place(id, name, LatLng, kind)                       // search result
RouteSpec(waypoints: List<LatLng>, mode, options)   // request
Route(polyline: List<LatLng>, segments: List<Segment>)   // snapped result
Segment(geometry, speedLimit?, roadClass)
MotionParams(avgSpeed, variance, accelMax, stopModel, accuracyBand, seed?, timeScale)
DitherParams(radius, cadence, model=OU, placePolygon?)    // stationary (§4.4.1)
SimulationConfig(route, motionParams, loop, reverse)
Fix(LatLng, speed, bearing, accuracy, time, provider)    // emitted
SessionState(configId, active, offsetMs, health)

// Day scenario (§4.9)
Scenario(id, name, steps: List<Step>, clockMode, anchorTime?, seed?)
Step = Stay(place, until|duration, dither: DitherParams)
     | Travel(toPlace, mode, avgSpeed | arriveBy, motionParams)
ScenarioState(scenarioId, stepIndex, offsetMs, active)
```

---

## 8A. Test-SDK injection (fallback for own apps in a mixed fleet)

For apps the firm builds, an optional **Last-Z test SDK** lets an app read spoofed
fixes directly from the Last-Z engine instead of (or in addition to) the OS location
layer. This guarantees a per-app spoof that is immune to fused-provider priority
fights — the mechanism behind the "phone apps spoofed while the car stays real"
fallback (§5.4).

- **Test-only wiring**: the SDK is a debug/test dependency; production builds use the
  real `LocationManager`/FLP. A `LocationSource` abstraction swaps the Last-Z source
  in under test (e.g. via a build flavor or a test `LocationProvider`).
- **Same stream**: it consumes the identical `Fix`/`Scenario` stream as the OS-level
  backends, so behavior matches across own and black-box apps.
- **Black-box apps** in the mixed fleet cannot use this and rely on the global mock +
  car-own-GPS path (§5.4); the SDK covers the own apps where hard isolation matters.

## 9. Control API (draft)

ADB intent surface (exact action strings TBD in impl):

```
# start a saved route at 50 km/h average, realistic motion, deterministic
adb shell am broadcast -a com.lastz.CONTROL --es cmd START \
  --es route "route_id_or_gpx_path" --ef avgSpeedKmh 50 \
  --es preset realistic --el seed 42

adb shell am broadcast -a com.lastz.CONTROL --es cmd TELEPORT --ef lat 37.42 --ef lng -122.08
adb shell am broadcast -a com.lastz.CONTROL --es cmd SET_SPEED --ef avgSpeedKmh 30
adb shell am broadcast -a com.lastz.CONTROL --es cmd PAUSE
adb shell am broadcast -a com.lastz.CONTROL --es cmd STATUS   # -> structured log
```

`STATUS` output includes current fix, session offset, and health
(GREEN/AMBER/RED) so an instrumented test can assert the mock is actually driving
before it trusts results.

---

## 10. Phased roadmap

- **Phase 0 — Spikes (de-risk first).**
  1. Foreground mock-provider service emitting to `gps` + FLP; prove continuous
     emission with screen off + Doze, validated against **Google Maps**.
  2. **Android Auto coexistence spike** (§5.4) — on DHU + a physical unit, confirm
     whether the car's GPS reaches the phone's fused provider and prove the phone
     apps hold the spoof while the car nav stays real.
  3. **iOS conductor spike** (§5A) — establish the RemoteXPC tunnel and drive
     `simulate-location play` against **Apple Maps** on the target iOS version;
     prove continuous streaming + tunnel auto-reconnect; pin the iOS/tool matrix.
- **Phase 1 — Vertical slice.** Map + search → drop pin → point-to-point snapped
  route → simulate with target average speed + basic realism → live HUD. No-root.
- **Phase 2 — Realism engine.** Speed distribution, accel curves, traffic-light /
  stop model, correct derived fields, determinism seed, time compression, and
  **stationary dither** (§4.4.1).
- **Phase 3 — Reliability hardening.** Watchdog + leakage detection, AA handling,
  auto-resume, G2 acceptance suite.
- **Phase 4 — Automation API.** ADB/broadcast control + structured STATUS; example
  Espresso/CI integration.
- **Phase 4.5 — Day scenario scheduler.** Timeline of Stay/Travel steps, clock
  modes (real-time / compressed / anchored), feasibility checks, resume-on-restart
  (§4.9).
- **Phase 5 — Library & interchange + iOS conductor.** Save/organize, GPX/KML
  import/export, multi-stop, loop/reverse, joystick; ship the iOS conductor driving
  the shared engine (§5A) with the tunnel watchdog. Optional **test-SDK** (§8A) for
  own apps.
- **Phase 6 — Polish.** Offline maps, transport-mode profiles, onboarding primer,
  design pass; unified control API spanning both backends.

---

## 11. Testing strategy

- **Unit**: MotionModel (mean speed equals target over route; continuity of speed;
  stop statistics; deterministic seed reproducibility).
- **Instrumented**: service emits to a test consumer; leakage watchdog fires on
  injected real fix; auto-resume after force-stop.
- **Reliability suite (§5.5)** run in CI on device/emulator matrix.
- **Field**: physical drive-route replays validated against recorded ground-truth
  GPX; Android Auto head-unit runs.

---

## 12. Risks & open questions

| Risk / question | Mitigation / next step |
|---|---|
| Exact cause of AA location revert | Phase 0 spike on DHU + hardware before committing the workaround design. |
| FLP mock mode reset by Play Services updates/car mode | Watchdog re-asserts; test across Play Services versions. |
| OEM battery killers terminate the service | Battery-optimization exemption + `START_STICKY` + auto-resume; document per-OEM setup. |
| Routing/geocoding cost & rate limits | Default to self-hostable OSM stack; keyed providers optional. |
| `isMock` semantics differ across API levels | Handle both; document that mock-rejecting apps are unsupported by design (§1.2). |
| Head unit with independent GPS | Documented limitation — phone-side mock only (§5.4). |
| iOS never-drop is tethered-bounded | Inherent platform limit; conductor auto-reconnects the tunnel, rig stays wired for a run (§5A.2). |
| iOS tooling churn (tunnel/QUIC, per-version breakage) | Pin a known-good iOS + `pymobiledevice3` + Python matrix; iOS spike gates the design (§5A.2). |
| iOS reboot/disconnect ends simulation | Test design keeps the device tethered for the session; document clearly. |

---

## 13. Legal / policy note

Last-Z is built and documented as a **location testing tool** for apps you own or
are authorized to test. It does not implement mock-detection evasion. Using
location simulation to circumvent a third party's location-based controls or terms
of service is out of scope and unsupported.
