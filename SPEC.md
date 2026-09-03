# Mirage — Location Simulation & Testing Platform

**Status:** Draft v0.2
**Owner:** aviterima
**Purpose:** An **Android** app that reliably and accurately simulates device
location for **QA and automated testing** of location-aware applications. The sole
reference consumer is **Google Maps**: Google Maps on the Android device must follow
the simulated route without ever reverting to real location. An **iPhone** can *view*
that spoofed location with no iOS spoofing at all, via **Google Maps location
sharing** (§5A). The iPhone never spoofs.

---

## 1. Context & framing

Mirage is a **location testing platform**. It feeds synthetic, realistic GPS fixes
to the Android OS location stack so that apps under test behave as if the device were
somewhere else, moving along a defined route.

- **Android (the only device that spoofs)**: an on-device app that registers as the
  mock-location provider (Developer Options → *Select mock location app*). No root.
  Google Maps on this device reads the mock and follows it.
- **iPhone (view-only, never spoofs)**: sees the Android's spoofed location through
  **Google Maps location sharing** — the Android account shares its live location
  (which is the mock) with the iPhone's Google account, and the iPhone sees it in
  Google Maps. No iOS tooling, no jailbreak, no tether. See §5A.

Scoping to **Google Maps only** and to **Android-only spoofing** is a deliberate
simplification: it removes the entire iOS spoofing problem (Apple's developer
location service, tunnels, Apple Maps) and lets the whole effort go into making the
Android spoof accurate and unbreakable. This is the same category as Lockito / Mock
GPS on Android — but those are inaccurate, drop out under real-world conditions, and
lack an automation surface. The goal is a tool accurate and reliable enough to be the
**primary location-testing harness** for the org.

### 1.1 In scope
- Accurate, road-snapped route simulation with realistic motion physics.
- A control/automation API so it can drive CI/instrumented tests.
- Reliability engineering so the mock **never silently reverts to real location**.
- A slick, low-friction UI.

### 1.2 Explicitly out of scope (and why)
- **Mock-flag hiding / anti-detection.** Android tags mocked fixes with
  `Location.isMock()` (API 31+) / `isFromMockProvider()`. Apps that reject mocked
  locations do so deliberately. Mirage will **not** attempt to strip or forge that
  flag (which would require root-level system hooks and cross into
  detection-evasion). For testing *your own* apps this is irrelevant — you control
  the consumer, so the flag never blocks you. This boundary keeps the tool squarely
  a QA instrument.
- Any use aimed at defeating a third party's location checks (game anti-cheat,
  ride-share/delivery/dating/attendance geofencing). That is a ToS violation and
  potentially fraud; it is a non-goal and the product is not designed for it.
- **Any iPhone spoofing.** The iPhone never has its location spoofed — not by
  jailbreak, not by a tethered developer tool, not at all. It only *views* the
  Android's shared location in Google Maps (§5A). This is a hard product boundary.
- **RF/GNSS signal spoofing** (transmitting fake satellite signals). Legally
  restricted, affects all nearby receivers; not a software-testing approach.

---

## 2. Goals & success criteria

| # | Goal | Success metric |
|---|------|----------------|
| G1 | **Accuracy** | Simulated fix within routing-geometry tolerance of the real road; speed within ±X% of target average over any 30 s window. |
| G2 | **Reliability** | Mock fixes delivered continuously with **zero unintended gaps** across screen-off, Doze, process restart, and Android Auto connect/disconnect. |
| G0 | **Google Maps is the reference consumer** | **Google Maps** on the Android device (foreground nav **and** Android Auto) follows the simulated route with zero reversion to real location for the entire session. The iPhone sees the same location via Google Maps location sharing (§5A). |
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
- **Single Android target**: everything above runs on the Android device. The iPhone
  needs no part of this — it views the resulting spoofed location through Google Maps
  location sharing (§5A), so there is no second backend to build or keep in sync.

---

## 4. Feature specification

### 4.1 Location & place search (G5)
- Search **by name, POI, landmark, or address** with autocomplete.
- Providers (pluggable): **Google Places / Geocoding (default)**, matching the
  routing source; Nominatim/Photon (OSM, self-hostable) as fallback.
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
- **The route is real, directed, road-legal geometry — not a drawn line.** Because
  it comes from a routing engine over the actual road network, it inherently obeys:
  - **One-way streets** (traversed only in the legal direction),
  - **Turn restrictions** (no-left-turn, no-U-turn, banned movements),
  - **Highway on-/off-ramp flows** (freeways are entered and exited only at real
    ramps; merges and exits follow the ramp geometry),
  - **Divided roads, roundabouts, and connectivity** as the network defines them.
  Mirage **follows** that polyline exactly, so the spoofed drive is always a path a
  real vehicle could legally take. The `MotionModel` (§4.4) adds motion on top and
  never invents or shortcuts the path.
- Route metadata (per-segment speed limits, road class, ramp/junction markers) is
  retained from the provider and fed to the motion model for realistic speeds.
- Multi-stop routes: ordered waypoints.
- Route options: transport mode (drive/bike/walk/transit), avoid tolls/highways
  where the backend supports it.
- Editable polyline: drag to reshape or add a waypoint — each edit is **re-routed
  through the engine**, so a dragged path still snaps to legal roads (it never
  becomes a straight-line cheat between points).
- **Provider (chosen): Google Directions API** — its routes match what the team sees
  in Google Maps, including one-way/turn/ramp rules. Behind a `RouteEngine`
  interface so a self-hosted OSRM/GraphHopper fallback can be swapped in without
  touching the motion engine.
- **Offline note:** the mockup in `design/mockups` uses a hand-drawn grid only
  because the design sandbox has no network; the shipping app always calls the
  routing service.

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

#### 4.4.1 Stationary behaviour (a person at a place, never a frozen pin)
When the device is **stopped** (a Stay step, holding an endpoint, a Jump target), the
engine runs `DwellModel`, which behaves like a phone in someone's pocket at an office
or restaurant rather than a jittering pin:
- **Still almost all the time**: the fix sits on one spot with sub-metre GPS noise
  (σ ≈ 0.35 m), reported speed 0, accuracy 4–6 m.
- **Occasional short walks**: every 1–8 minutes the person gets up and walks at
  walking pace (~1.2 m/s ± 0.15) to another spot within the place radius (default
  20 m of the anchor), with a proper bearing and speed, then sits still again.
  About a third of walks return to the anchor, so the position never drifts away.
- **No teleporting**: every fix is continuous with the last; nothing jumps.
- **Radius scales with place size**: a small pin wanders within a few metres; a
  **large location** (campus, mall, airport) can be given a larger radius so the
  device plausibly moves around inside the venue over the day.
- Seeded for determinism.
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
  spoofed fixes from a Mirage source directly, guaranteeing their spoof regardless of
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

## 5A. iPhone view via Google Maps location sharing (no iOS spoofing)

The iPhone is **view-only** and requires **no development**. The mechanism is Google
Maps' built-in **live location sharing**:

1. The **Android** device spoofs its location (§5). Google Maps on the Android reads
   the mock as its device location.
2. The Android's Google account **shares its live location** (Google Maps → *Location
   sharing*) with the **iPhone's** Google account.
3. On the iPhone, Google Maps shows the Android account's avatar at the **spoofed**
   location. That is the iPhone "receiving location through Google Maps."

### 5A.1 What this is and isn't (honest scope)
- ✅ The iPhone's Google Maps displays the Android's spoofed position, updated as the
  Android moves along the simulated route.
- ❌ It does **not** change the iPhone's own device location. The iPhone's blue dot,
  Apple Maps, and every other iOS app still read the iPhone's real GPS. Only the
  *shared-contact view* inside Google Maps shows the spoof. Per the scope, that is
  exactly what's wanted.
- **Fidelity caveat:** Google Maps location sharing updates the shared position on
  Google's own cadence (seconds to a couple of minutes, and it can coalesce while
  stationary). So the iPhone view is **lower-frequency and slightly delayed** versus
  the high-rate mock the Android's own apps see. The Android side is the fidelity
  source of truth; the iPhone view is a real-time-ish mirror, not frame-accurate.

### 5A.2 Setup (one-time, no code)
- Both devices signed into their Google accounts; sharing enabled Android → iPhone.
- iPhone: Google Maps installed, logged in, viewing the shared person.
- Optional Mirage helper on Android: a checklist/deep-link that confirms location
  sharing is on and pointed at the right recipient before a test run, surfaced in the
  session HUD so a run never starts with sharing off.

### 5A.3 Consequence for the build
There is **no iOS codebase, no shared cross-platform engine to maintain, no tether**.
Mirage is a single **Android** project. The engine, reliability work, scheduler, and
dither all live on Android; the iPhone is satisfied entirely by Google Maps sharing.

---

## 6. UI / UX specification (G5)

The UI is a **first-class pillar**, not a shell over the engine. Bar: a non-engineer
sets and runs a realistic spoof in under 60 s, and a power user scripts a full day
without fighting the tool.

### 6.1 Design principles
1. **Map-first.** A full-bleed map is the canvas; controls float over it and never
   bury it. You always see where the device "is."
2. **One primary action at all times.** A single, state-aware primary button — its
   label and color are the app's current verb (*Set location* → *Simulate* → **STOP**).
   You never hunt for the next step.
3. **Progressive disclosure.** Three knobs by default (where, how fast, how real);
   everything else lives under *Advanced*. Novices see a clean face; experts get depth.
4. **Status is never ambiguous.** A persistent health pill (GREEN spoofing / AMBER
   re-asserting / RED real-location) is visible whenever a session runs — the tester
   must always know, at a glance, that the spoof is holding.
5. **Direct manipulation.** Drag the route line, drag pins, drag timeline steps.
   Numbers are for confirmation, gestures are for control.
6. **Calm, legible motion.** Material 3 expressive, dynamic color, real dark mode,
   large touch targets, generous type. Animation clarifies state changes; it never
   decorates.

### 6.2 Primary screens

- **Home / Map** — full-bleed map; a single top **search bar** (place, POI, landmark,
  or address with autocomplete); **favorites row** (Home/Work/saved pins) as chips;
  a bottom **primary action button**. Long-press drops a pin; tap a result to preview
  it in a peek sheet with *Set here* / *Route from here*. A small **joystick toggle**
  enters free-roam.
- **Route setup (bottom sheet, expandable)** — A→B fields (each opens search),
  the snapped route drawn live. The sheet shows only:
  - **Average speed** (slider + unit toggle, big and central),
  - **Transport mode** (drive/bike/walk segmented control),
  - **Realism** (segmented: *Constant · Realistic · Busy*),
  plus *Loop* and *Speed ×* (time compression). An **Advanced** expander reveals
  accuracy band, stop rates, dither radius, update Hz, RNG seed. Drag the route line
  to reshape; tap *+* to add a waypoint.
- **Live session HUD** — collapses the setup sheet into a compact top card: current
  **speed, coordinates, bearing, ETA**, the **health pill**, and a **scrubber** along
  the bottom to seek/scrub the route. One giant **STOP**. Tapping the card expands
  full telemetry.
- **Day planner (scenario timeline)** — a vertical, reorderable timeline of **Stay**
  and **Travel** steps (drag to reorder, swipe to delete). Each step is a card
  (place name, time or duration, mode/speed); **Travel** legs show their mini-route.
  A top strip picks the clock mode (*Real-time · Compressed ×N · Anchored @time*) and
  flags any infeasible arrive-by in red with a suggested minimum. A **play head**
  shows where "now" is during a run; tap any step to jump the device there.
- **Library** — saved routes, scenarios, pins, and favorites as cards with map
  thumbnails; search/filter; import/export (GPX/KML/JSON); duplicate, reverse, rename.
- **Settings** — providers (map/routing/geocoding endpoints), units, default realism,
  automation/ADB info, and the mock-location setup status.

### 6.3 Onboarding (the one unavoidable setup step)
A first-run flow makes enabling **Developer Options → Select mock location app**
painless: a 3-step primer with a **deep link** straight to the right Settings page, a
plain-language "why," and a **live check** that flips to a green ✓ the instant Mirage
is selected — so users never get stuck on the single piece of required setup. Also
surfaces the optional **iPhone location-sharing** setup (§5A) as a skippable card.

### 6.4 Interaction & feedback details
- **Health pill** is always tappable → a sheet explaining current state and, if RED,
  a one-tap *Re-assert* and a link to what's wrong (e.g. mock app not selected).
- **Presets over parameters**: *Realistic* is the default and needs zero tuning;
  distributions/seeds exist but are never in the novice's way.
- **Confirm-by-preview**: before a run, the route/scenario animates a fast preview so
  the tester sees the shape before committing.
- **Errors are actionable**, never codes: "Mock app not selected → Open Settings",
  "Location sharing is off → Turn on".
- **One-handed**: primary controls sit in the bottom third; nothing critical in the
  top corners.
- **Accessibility**: full TalkBack labels, ≥48dp targets, dynamic type, WCAG-AA
  contrast in both themes, no color-only status (pill has icon + text).

### 6.5 Visual language
Material 3 (Material You dynamic color), an accent reserved for the primary action and
the live route, a distinct semantic palette for the health pill (green/amber/red with
icons), map-appropriate light/dark map styles, rounded 16–28dp surfaces, and motion
that tracks state (route draws on, HUD slides up, play head advances). A visual mockup
of Home, Route setup, Live HUD, and Day planner accompanies this spec.

---

## 7. Technology stack

| Concern | Choice | Notes |
|---|---|---|
| Language / UI | **Kotlin + Jetpack Compose** | Modern, testable. |
| Min / target SDK | Min 26, target latest | `isMock` behavior differs pre/post API 31 — handle both. |
| Map | **MapLibre GL Native** (default) | Open, no key lock-in; Google Maps SDK optional. |
| Routing | **Google Directions API** (default, chosen) | Matches Google Maps' own routing (one-way/turn/ramp rules); needs an API key + billing. OSRM/GraphHopper remain a self-host fallback behind the same `RouteEngine` interface. |
| Geocoding | **Google Places / Geocoding API** (default) | Name/POI search consistent with the routing source. Photon/Nominatim as self-host fallback. |
| Location out | `LocationManager` test providers **+** `FusedLocationProviderClient` mock mode | Both, always (§5.1). |
| Car integration | **androidx.car.app** (`CarConnection`) | Projection state detection. |
| iPhone view | **Google Maps location sharing** (no code) | Android shares live (spoofed) location → iPhone views it (§5A). |
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

For apps the firm builds, an optional **Mirage test SDK** lets an app read spoofed
fixes directly from the Mirage engine instead of (or in addition to) the OS location
layer. This guarantees a per-app spoof that is immune to fused-provider priority
fights — the mechanism behind the "phone apps spoofed while the car stays real"
fallback (§5.4).

- **Test-only wiring**: the SDK is a debug/test dependency; production builds use the
  real `LocationManager`/FLP. A `LocationSource` abstraction swaps the Mirage source
  in under test (e.g. via a build flavor or a test `LocationProvider`).
- **Same stream**: it consumes the identical `Fix`/`Scenario` stream as the OS-level
  backends, so behavior matches across own and black-box apps.
- **Black-box apps** in the mixed fleet cannot use this and rely on the global mock +
  car-own-GPS path (§5.4); the SDK covers the own apps where hard isolation matters.

## 9. Control API (draft)

ADB intent surface (exact action strings TBD in impl):

```
# start a saved route at 50 km/h average, realistic motion, deterministic
adb shell am broadcast -a com.mirage.CONTROL --es cmd START \
  --es route "route_id_or_gpx_path" --ef avgSpeedKmh 50 \
  --es preset realistic --el seed 42

adb shell am broadcast -a com.mirage.CONTROL --es cmd TELEPORT --ef lat 37.42 --ef lng -122.08
adb shell am broadcast -a com.mirage.CONTROL --es cmd SET_SPEED --ef avgSpeedKmh 30
adb shell am broadcast -a com.mirage.CONTROL --es cmd PAUSE
adb shell am broadcast -a com.mirage.CONTROL --es cmd STATUS   # -> structured log
```

`STATUS` output includes current fix, session offset, and health
(GREEN/AMBER/RED) so an instrumented test can assert the mock is actually driving
before it trusts results.

---

## 10. Phased roadmap

- **Phase 0 — Spikes (de-risk first).**
  1. Foreground mock-provider service emitting to `gps` + FLP; prove continuous
     emission with screen off + Doze, validated against **Google Maps**.
     → **Built: `android/` (Mirage Spike).** See `android/README.md` for the test
     protocol. Remaining: run the acceptance protocol on hardware.
  2. **Android Auto coexistence spike** (§5.4) — on DHU + a physical unit, confirm
     whether the car's GPS reaches the phone's fused provider and prove the phone
     apps hold the spoof while the car nav stays real.
  3. **iPhone-sharing check** (§5A) — no code: confirm the Android's spoofed location
     shows on the iPhone via Google Maps location sharing, and measure the update
     latency so tests account for it.
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
- **Phase 5 — Library & interchange.** Save/organize, GPX/KML import/export,
  multi-stop, loop/reverse, joystick. Optional **test-SDK** (§8A) for own apps.
- **Phase 6 — Polish.** Offline maps, transport-mode profiles, onboarding primer
  (incl. the iPhone location-sharing setup), design pass.

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
| Google Directions/Places cost, key management & rate limits | Chosen for fidelity; cache routes, restrict the API key, monitor billing; OSRM/Photon self-host fallback kept behind the same interfaces. |
| `isMock` semantics differ across API levels | Handle both; document that mock-rejecting apps are unsupported by design (§1.2). |
| Head unit with independent GPS | Documented limitation — phone-side mock only (§5.4). |
| iPhone view lags / coarsens (location-sharing cadence) | Documented fidelity limit (§5A.1); Android is the source of truth; measure latency in the Phase-0 check. |
| Location sharing silently off before a run | Pre-run sharing check + HUD indicator on Android (§5A.2). |
| Google Maps could change/limit mock following or sharing | Single-consumer risk; monitor across Google Maps/Play Services updates in the reliability suite. |

---

## 13. Legal / policy note

Mirage is built and documented as a **location testing tool** for apps you own or
are authorized to test. It does not implement mock-detection evasion. Using
location simulation to circumvent a third party's location-based controls or terms
of service is out of scope and unsupported.
