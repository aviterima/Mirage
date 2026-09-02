# Mirage — Android app

A location-simulation / GPS-testing app. Set two points on a Google Map (or search
any place by name), get a real **Google Directions** route, and spoof a realistic
drive along it at a target average speed — with a **never-drop** location service
validated against Google Maps.

## Features (current)

- **Map-first UI** (Jetpack Compose + Google Maps): full-bleed map, search any place
  by name, long-press to set start (A), tap to set destination (B).
- **Real routing**: Google Directions returns directed, road-legal geometry
  (one-ways, turn restrictions, highway ramps). Mirage follows that polyline.
- **Realism engine**: target average speed with natural variance, smooth
  accel/braking, realistic stops, correct bearing; stationary dither when idle.
- **Never-drop service**: feeds the mock to LocationManager test providers (gps +
  network) AND the Fused Location Provider (what Google Maps reads), at 5 Hz, from a
  foreground service with a wake lock (survives screen-off / Doze), plus a watchdog
  that re-asserts on provider drop and flags real-fix leakage.
- **Live HUD**: speed, health (GREEN/AMBER/RED), coordinates, fixes/re-asserts/leak.

Planned next: day-scenario timeline, saved-routes library, Android Auto coexistence
polish. See `../SPEC.md`.

## The one required setup: a Google Maps API key

The app builds and the reliability engine runs without a key, but the **map, search,
and routing** need a Google Maps Platform key with these APIs enabled:
**Maps SDK for Android**, **Directions API**, **Geocoding API**.

Provide it one of two ways:

- **Android Studio (local build):** create `android/local.properties` with
  `MAPS_API_KEY=AIza...` (this file is gitignored). Open `android/` → Run.
- **CI build (no IDE):** add a GitHub Actions secret named **`MAPS_API_KEY`**
  (repo → Settings → Secrets and variables → Actions). The workflow injects it, so
  the built APK carries the key. Download the APK from the Actions run artifacts.

> Keep the key private, restrict it to those three APIs, and set a Cloud budget alert.

## Build

Android Studio (Giraffe+), or:

```bash
cd android
./gradlew installDebug     # JDK 17 + Android SDK 34
```

CI (`.github/workflows/android.yml`) builds a debug APK on every push and uploads it
as an artifact.

## One-time device setup

1. **Developer options → Select mock location app → Mirage.**
2. Grant **Location** (Precise) and **Notifications**.
3. Allow **Ignore battery optimizations** (keeps the service alive in Doze).

(The map screen has shortcuts for all three under the bottom controls.)

## Using it

1. Search a place, or long-press the map for A and tap for B.
2. Set the **average speed**, transport mode, and realism.
3. **Get route** → **Start simulation**. Open Google Maps — the blue dot drives the
   route. The HUD shows live status.
4. **Stop** to end and hold the last position (with dither).

## Reliability test (G0 + G2)

- Google Maps follows the route with no reversion to real location.
- Screen-off 20–30 min → no gap; Doze (`adb shell dumpsys deviceidle force-idle`) →
  emission continues; `adb shell am kill com.mirage.app` → service restarts.
- Watch **"leak"** in the HUD — it must stay `no`, including across an Android Auto
  connect/disconnect.

## Layout

```
app/src/main/java/com/mirage/spike/
  MockLocationService.kt   # foreground service: providers + FLP + playback + watchdog
  MockState.kt             # live status bridge (StateFlow) to the UI
  MirageViewModel.kt       # endpoints, speed, routing, sim control, search
  MapScreen.kt             # Google Map UI + search + controls + live HUD
  MainActivity.kt          # host + permissions
  engine/
    Models.kt              # LatLng, RouteSpec/Result, MotionParams, Fix, ...
    PolylineCodec.kt       # Google encoded-polyline decoder
    RouteEngine.kt         # RouteEngine + GoogleDirectionsRouteEngine
    Geocoder.kt            # GoogleGeocoder (name -> coordinates)
    MotionModel.kt         # route -> realistic Fix stream (+ dither, PlaybackSource)
```
