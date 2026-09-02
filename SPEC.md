# Last-Z — Location Simulation & Testing Platform

**Status:** Draft v0.1
**Owner:** aviterima
**Purpose:** An Android app that reliably and accurately mocks device location for
**QA and automated testing** of location-aware applications.

---

## 1. Context & framing

Last-Z is a **location testing tool**. It registers as Android's mock-location
provider (Developer Options → *Select mock location app*) and feeds synthetic,
realistic GPS fixes to the OS location stack so that apps under test behave as if
the device were somewhere else, moving along a defined route.

This is the same category as Lockito, "Mock GPS", GPS JoyStick, etc. — but those
products are inaccurate, drop out under real-world conditions, and lack an
automation surface. The goal here is a platform accurate and reliable enough to be
the **primary location-testing harness** for an engineering org.

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

---

## 2. Goals & success criteria

| # | Goal | Success metric |
|---|------|----------------|
| G1 | **Accuracy** | Simulated fix within routing-geometry tolerance of the real road; speed within ±X% of target average over any 30 s window. |
| G2 | **Reliability** | Mock fixes delivered continuously with **zero unintended gaps** across screen-off, Doze, process restart, and Android Auto connect/disconnect. |
| G0 | **Google Maps is the reference consumer** | Google Maps (foreground nav **and** Android Auto projection) follows the simulated route with zero reversion to real location for the entire session. |
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

### 5.4 Android Auto / car-mode handling
**Observed problem:** when Android Auto projection starts, the device reverts to the
real location.

**Why it happens (design assumptions to validate in Phase 0):**
- AA connection changes the UI mode to car and can trigger a location-settings /
  provider re-evaluation, and Play Services may re-prioritize a real GNSS fix; our
  test-provider registration and/or FLP mock mode can be reset by that transition.
- Note the honest limit: **a car head unit with its *own* GPS hardware is
  independent of the phone.** Last-Z controls what the *phone and phone-hosted apps*
  report; it cannot rewrite a fix that the car computes on its own hardware. The
  workaround targets the phone-side revert, which is what affects apps under test.

**Workaround design:**
- Observe projection state via the **Car App Library** `CarConnection` LiveData
  (`CONNECTION_TYPE_PROJECTION`) and `UiModeManager` car-mode transitions.
- On any connect/disconnect edge, **re-run full provider setup** (re-`addTestProvider`,
  re-enable FLP mock mode) and resume the emit loop without a visible gap.
- Tighten the watchdog cadence during a car-mode transition window.
- Keep the emit loop running regardless of UI mode (it lives in the service, not the
  UI).
- **Phase 0 spike** validates this on a physical head unit and the Desktop Head Unit
  (DHU) emulator before the rest of the reliability work is called done, since the
  exact revert mechanism must be confirmed empirically.

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
SimulationConfig(route, motionParams, loop, reverse)
Fix(LatLng, speed, bearing, accuracy, time, provider)    // emitted
SessionState(configId, active, offsetMs, health)
```

---

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
     emission with screen off + Doze.
  2. **Android Auto revert spike** (§5.4) — confirm the real mechanism on DHU +
     a physical unit and prove the re-assert workaround.
- **Phase 1 — Vertical slice.** Map + search → drop pin → point-to-point snapped
  route → simulate with target average speed + basic realism → live HUD. No-root.
- **Phase 2 — Realism engine.** Speed distribution, accel curves, traffic-light /
  stop model, correct derived fields, determinism seed, time compression.
- **Phase 3 — Reliability hardening.** Watchdog + leakage detection, AA handling,
  auto-resume, G2 acceptance suite.
- **Phase 4 — Automation API.** ADB/broadcast control + structured STATUS; example
  Espresso/CI integration.
- **Phase 5 — Library & interchange.** Save/organize, GPX/KML import/export,
  multi-stop, loop/reverse, joystick.
- **Phase 6 — Polish.** Offline maps, transport-mode profiles, onboarding primer,
  design pass.

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

---

## 13. Legal / policy note

Last-Z is built and documented as a **location testing tool** for apps you own or
are authorized to test. It does not implement mock-detection evasion. Using
location simulation to circumvent a third party's location-based controls or terms
of service is out of scope and unsupported.
