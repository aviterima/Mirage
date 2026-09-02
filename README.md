# Mirage

**Programmable, realistic device location for QA and automated testing.**

Mirage is an **Android** app that simulates GPS location accurately and reliably, so
location-aware apps can be tested against synthetic routes, schedules, and places
without physically moving. It is built to be a team's **primary location-testing
harness** — not a toy mock-location app that drifts or drops out mid-test.

## What it does

- **Spoof a location or a routed drive** between any two points (search any place by
  name, not just address), following real roads.
- **Realistic motion**: target an average speed and get believable variation —
  acceleration/braking curves, stops at lights, speed limits, correct bearing and
  accuracy — not a robotic constant crawl.
- **Never drops**: engineered so **Google Maps** on the device follows the spoof
  continuously through screen-off, Doze, app restart, and Android Auto — no silent
  reversion to real location that would invalidate a test.
- **Android Auto coexistence**: the car navigates on its **own** GPS (real) while the
  phone's apps under test keep the spoofed location.
- **Plan a whole day**: a scheduled timeline of places and drives (arrive-by or
  average-speed legs) that plays out in real time or compressed — a genuine testbed.
- **Realistic idle dither**: when parked at a place, the position wanders plausibly
  within the venue instead of freezing on a pixel.
- **Automatable**: drive it over ADB/broadcast intents for CI and instrumented tests.

## iPhone

Mirage **never spoofs an iPhone.** An iPhone can *view* the Android's spoofed location
through **Google Maps location sharing** — the Android shares its live (mocked)
location and the iPhone sees it in Google Maps. No iOS tooling, no jailbreak.

## Scope & principles

Mirage is a **testing tool** for apps you own or are authorized to test. It targets
**Google Maps** as its reference consumer and does **not** implement mock-detection
evasion. See [`SPEC.md`](./SPEC.md) for the full engineering specification.

## Status

Early design. The full spec — architecture, reliability engineering, roadmap — lives
in [`SPEC.md`](./SPEC.md).
