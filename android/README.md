# Mirage — Phase-0 reliability spike

A minimal Android app that proves the core of Mirage: a **mock location that never
drops**, validated against **Google Maps**. It is deliberately small — a fixed-heading
drive — so the only thing under test is emission reliability.

What it does (see `SPEC.md` §5):

- Registers mock **test providers** for `gps` + `network` (`LocationManager`).
- Enables **Fused Location Provider mock mode** (`FusedLocationProviderClient.setMockMode`)
  — the path **Google Maps** actually reads.
- Emits a fix at **5 Hz** from a **foreground service** with a **wake lock**, so it
  survives screen-off and Doze.
- Runs a **watchdog** that re-registers providers if they drop and flags any
  **real-fix leakage** (a non-mock fix reaching the OS).
- Surfaces live status (health GREEN/AMBER/RED, fixes emitted, re-asserts, leak seen).

## Build & install

Open `android/` in Android Studio (Giraffe+), or from a networked machine:

```bash
cd android
./gradlew installDebug
```

Requires JDK 17 and the Android SDK (API 34). Dependencies download from Google's
Maven + Maven Central on first build.

> Note: this repo's CI/dev sandbox blocks external network egress, so the app is not
> compiled there — build it in Android Studio or a networked CI runner.

## One-time device setup

1. **Developer options → Select mock location app → Mirage Spike.**
   (The app's "Open Developer options" button takes you there.)
2. Grant **Location** (Precise) and **Notifications** when prompted.
3. Tap **Ignore battery optimizations** and allow it (keeps the service alive in Doze).

## Test protocol (acceptance for G0 + G2)

1. Tap **Start spoofing**. Health should go **GREEN**, "Fixes emitted" rising.
2. Open **Google Maps** → the blue dot should jump to the SF Ferry Building and
   **drive south-east**, following the emitted track. This is the G0 check.
3. **Screen-off test:** lock the phone for 20–30 min. Return to the app — "Fixes
   emitted" should have kept climbing with no gap; Maps still on the mock.
4. **Doze test** (via adb):
   ```bash
   adb shell dumpsys deviceidle force-idle    # force Doze
   # wait a few minutes
   adb shell dumpsys deviceidle unforce
   ```
   Emission must continue; health stays GREEN, "Real-fix leak seen" stays **no**.
5. **Process-death test:** `adb shell am kill com.mirage.spike` — `START_STICKY`
   should restart the service and resume emission.
6. **Android Auto (coexistence spike):** connect to the head unit / Desktop Head
   Unit and confirm the phone apps keep the spoof while the car navigates on its own
   GPS (SPEC §5.4). Watch "Real-fix leak seen".

## What "leak seen" means

The watchdog reads the OS last-known GPS fix and checks `Location.isMock()`. If a
**real** (non-mock) fix appears, that's leakage — the exact failure mode the full
app's reliability layer must eliminate. In this spike it flips health to AMBER and
records it, so you can see when/where it happens (e.g. across an Android Auto edge).

## Layout

```
app/src/main/java/com/mirage/spike/
  MockLocationService.kt   # foreground service: providers + FLP + emit loop + watchdog
  MockState.kt             # status bridge to the UI (StateFlow)
  MainActivity.kt          # Compose UI: setup buttons + live status
```
