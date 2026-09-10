# Mirage — Android app

A location-simulation platform for testing location-aware apps. Search any place by
name, get a real **Google Directions** route, and simulate a realistic trip along it at
a target average speed — or plan a whole day as an itinerary with a stay at each stop —
from a **never-drop** location service validated against Google Maps.

## Features

- **Map-first UI** (Jetpack Compose + Google Maps): the map is always visible; the
  planning sheet takes at most half the screen and collapses to one line.
- **Search like a ride-hailing app**: type a name or address and pick from a list of
  matches with address and distance (Places API), biased to what the map shows.
- **Real routing**: Google Directions returns directed, road-legal geometry (one-ways,
  turn restrictions, ramps). Drive, bike and walk modes; each keeps its own speed.
- **Realism engine**: target average speed with natural variance, smooth
  accel/braking, traffic-light stops (Steady / Realistic / Heavy traffic).
- **Flights**: point-to-point great-circle flight with taxi, climb, cruise (~550 mph
  at 35,000 ft), descent and landing — no routing API needed.
- **Itinerary**: an ordered list of stops, each with its own travel mode, speed and
  time on site, played as one continuous day.
- **Jump**: find a place and simply be there, no route.
- **Holding a point behaves like a person at a place**: still with sub-metre GPS noise,
  and every few minutes a short walk to a spot inside the building and back.
- **Fast-forward** (2×/5×/10×) for testing long flights and all-day itineraries.
- **Start / Stop semantics**: arriving holds the endpoint; only Stop (in the app or
  from the notification) hands location back to the real GPS.
- **Never-drop service**: feeds the mock to LocationManager test providers (gps +
  network) AND the Fused Location Provider (what Google Maps reads) at 5 Hz from a
  foreground service with a wake lock, plus a watchdog that re-asserts on provider
  drop and flags real-fix leakage.
- **Live HUD**: speed, health, current step, progress and ETA, coordinates, and a
  live notification with a Stop action.

## The one required setup: a Google Maps API key

The app builds and the location engine runs without a key, but the **map, search and
routing** need a Google Maps Platform key with these APIs enabled:
**Maps SDK for Android**, **Directions API**, **Geocoding API**, **Places API (New)**.
The key's *Application restrictions* must be **None** (the app calls the REST APIs
directly); restrict it by API instead.

Provide it one of two ways:

- **Android Studio (local build):** create `android/local.properties` with
  `MAPS_API_KEY=AIza...` (this file is gitignored). Open `android/` → Run.
- **CI build (no IDE):** add a GitHub Actions secret named **`MAPS_API_KEY`**
  (repo → Settings → Secrets and variables → Actions). The workflow injects it and
  publishes the APK to the rolling `android-latest` release.

> Keep the key private, restrict it to those four APIs, and set a Cloud budget alert.

## Build

Android Studio (Giraffe+), or:

```bash
cd android
./gradlew installDebug     # JDK 17 + Android SDK 34
```

CI (`.github/workflows/android.yml`) builds a debug APK on every push, uploads it as an
artifact, and refreshes the `android-latest` release.

## One-time device setup

The ⚙ button in the app shows each step with a live ✓ / ✗:

1. **Developer options → Select mock location app → Mirage** (Mirage appears there
   because it declares `ACCESS_MOCK_LOCATION`).
2. Grant **Location** (precise) and **Notifications**.
3. Allow **Ignore battery optimizations** so the simulation survives Doze.

## Using it

1. Search a place and pick it from the list, or long-press the map for the start and
   tap for the destination.
2. Choose Drive / Bike / Walk / Fly, set the average speed and realism.
3. **Get route** (or **Plot flight**) → **Start**. Google Maps follows the moving
   position. The HUD shows speed, step, progress and ETA.
4. On arrival Mirage **holds** the endpoint. **Stop** returns to the real location.

For a day: switch to **Itinerary**, add stops in order (each with its minutes on site;
tap a stop's icon to change how you travel to it), then **Start itinerary**.

## Live controls while simulating

- **Pause here / Resume**: freeze in place (still spoofing), then continue.
- **Skip ahead**: jump to the end of the current leg or stay.
- **Fast-forward**, **cruise over the limit** (driving) and **GPS signal** (Good / Urban /
  Poor / Indoor, plus 15 s or 60 s dropouts) all apply immediately.
- The Start box defaults to the **current simulated position**; its ⌖ menu also offers
  **Where the current trip ends**, which queues the new plan to begin on arrival instead
  of replacing what is playing. In Itinerary mode the stop box can add **a stop here** or
  **a stop where the trip ends**.

## Automation (adb)

Every command is an activity start with extras; the token is shown in ⚙ Setup.

```bash
A="com.mirage.app/com.mirage.spike.MainActivity"; T=<token from Setup>
adb shell am start -n $A --es cmd pause --es token $T
adb shell am start -n $A --es cmd resume --es token $T
adb shell am start -n $A --es cmd skip --es token $T
adb shell am start -n $A --es cmd stop --es token $T
adb shell am start -n $A --es cmd timescale --es value 10 --es token $T
adb shell am start -n $A --es cmd speed_over --es value 5 --es token $T
adb shell am start -n $A --es cmd signal --es preset urban --es token $T      # good|urban|poor|indoor
adb shell am start -n $A --es cmd drop --es seconds 30 --es token $T
adb shell am start -n $A --es cmd snap --es lat 33.4484 --es lng -112.0740 --es name Office --es token $T
adb shell am start -n $A --es cmd route --es lat2 33.5091 --es lng2 -112.0263 --es mode drive --es token $T   # from the current position
adb shell am start -n $A --es cmd plan --es name "Lunch run" --es token $T    # a saved plan
```

## Reliability test

- Google Maps follows the route with no reversion to real location.
- Screen-off 20–30 min → no gap; Doze (`adb shell dumpsys deviceidle force-idle`) →
  emission continues; `adb shell am kill com.mirage.app` → the service restarts and
  reverts to real location (no stale spoof).
- Watch **leak** in the HUD — it must stay `no`, including across an Android Auto
  connect/disconnect.

## Layout

```
app/src/main/java/com/mirage/spike/
  MainActivity.kt          host, permissions, settings deep-links
  MapScreen.kt             map, search, planning sheet, live HUD, setup dialog
  MirageViewModel.kt       endpoints, routing, itinerary, search state
  MockLocationService.kt   the never-drop foreground service
  MockState.kt             service → UI status bridge
  Theme.kt                 Mirage colour scheme
  engine/                  routing, Places, motion, flight, dwell, itinerary models
```
