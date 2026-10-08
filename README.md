# Mirage 0.15.0 — named hiking trips

Search for a trail by name, review its mapped length, choose parking and enter the total on-trail miles. Mirage builds a drive → park → hike → return-to-car itinerary and preserves the exact mapped path when saved. See [the specification and quick start](docs/HIKING_0.15.0.md) and [release notes](docs/RELEASE_NOTES_0.15.0.md).

Validated October 8, 2026 UTC: **146 JVM tests and 27 emulator tests passed**, with no failures, errors or skips. Blocking lint and the signed in-place upgrade from 0.14.0 passed. Live Echo Canyon lookup passed on retry; public trail-service availability is intermittent. See [validation, limitations and APK identity](docs/VALIDATION_0.15.0.md). Tested source: `43d148a132bc0ee1d098ad0a8f2b72fd8eefa5ed`.

# 0.14.0 validated build

Live saved/search/map destinations, entrance and indoor pins, stay choices, upcoming-stop replacement, remembered place settings and contextual voice enhancements are implemented on `codex/saved-destinations-indoor-arrival-20261006`. See [the specification and validation scope](docs/SAVED_DESTINATIONS_AND_ARRIVAL_0.14.0.md). Validated on October 8, 2026 UTC (October 7 Pacific): 127 JVM tests and all 24 emulator acceptance tests passed, with no failures or skips. Live-map clear height is 75.19%; signed upgrade from 0.13.1 preserves saved records and settings. See [validation and APK identity](docs/VALIDATION_0.14.0.md). Physical-phone validation remains outstanding; historical evidence below remains version-specific.

# Mirage 0.13.1 — saved sources and Place terminology

Live Add stop now presents Search for a place, Saved places, Saved routes and Saved itineraries together, without horizontal scrolling. The visible interface consistently uses Place. Existing saved records remain compatible.

- [Specification, quick start, implementation report and validation](docs/PLACE_AND_LIVE_SOURCES_0.13.1.md)
- [Underlying 0.13.0 feature specification](docs/USABILITY_RECOVERY_VOICE_SPEC_0.13.0.md)

Validation is pending for 0.13.1. Earlier release records below are retained for provenance and do not describe the current validation status.

---

# Mirage 0.13.0 — signed and emulator-tested

Improves planning and live stop editing, saved content management, interrupted-trip recovery, route/timing controls, backup/sharing and contextual on-device voice. **117 JVM tests and 21 Android emulator tests passed, with no failures, errors or skips.** The signed update from 0.12.1 preserved saved records, API-key preference and install identity. Physical-phone validation remains outstanding.

- [Current specification](docs/USABILITY_RECOVERY_VOICE_SPEC_0.13.0.md)
- [Quick start](docs/QUICK_START_0.13.0.md)
- [Implementation report](docs/IMPLEMENTATION_REPORT_0.13.0.md)
- [Release notes](docs/RELEASE_NOTES_0.13.0.md)
- [Validation and APK identity](docs/VALIDATION_0.13.0.md)
- [Voice dependencies and licenses](docs/THIRD_PARTY_VOICE.md)

The records below describe earlier releases and are retained for provenance. The current specification and validation report supersede conflicting historical descriptions.

---

# Current release: Mirage 0.12.1

Adds a route endpoint swap button and visible itinerary drag handles. 104 JVM tests and 17 Android emulator tests passed. Signed upgrade from 0.12.0 preserved stored data. Physical-phone validation remains outstanding.

- [Route and stop editor specification](docs/ROUTE_AND_STOP_EDITOR_SPEC_0.12.1.md)
- [Quick start](docs/QUICK_START_0.12.1.md)
- [Release notes](docs/RELEASE_NOTES_0.12.1.md)
- [Validation](docs/VALIDATION_0.12.1.md)

The specification above extends the existing 0.12.0 live itinerary and voice behavior. Earlier release records follow for provenance.

---

# Current release: Mirage 0.12.0

Release validation: 101 JVM tests and 15 Android emulator tests pass, with zero failures or skips. The signed in-place upgrade from 0.11.3 preserves stored data.

Updated September 30, 2026 (America/Phoenix). **Signed and emulator-tested release APK: 0.12.0. Physical-phone validation remains outstanding.**

- [Current live-itinerary and local-voice specification](docs/LIVE_ITINERARY_AND_LOCAL_VOICE_SPEC_0.12.0.md)
- [Quick start: live saving and advanced voice](docs/QUICK_START_0.12.0.md)
- [Release notes and installation limits](docs/RELEASE_NOTES_0.12.0.md)
- [Validation evidence and outstanding gates](docs/VALIDATION_0.12.0.md)
- [Voice dependencies and licenses](docs/THIRD_PARTY_VOICE.md)

The current specification supersedes conflicting live-save and voice descriptions below. Historical implementation and validation notes are retained for provenance; they are not the status of 0.12.0.

---

## Historical documentation

# Mirage

> **Engineering handoff:** see [HANDOFF.md](HANDOFF.md) for the current state, architecture, known issues and roadmap.

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

## 0.11.0 source candidate — Live view and Hello Mirage

The working update separates execution from draft planning, adds editable upcoming
stops, typed commands, and offline voice activation. See the
[live/voice specification](docs/LIVE_AND_VOICE_SPEC.md) and
[implementation and validation report](docs/IMPLEMENTATION_REPORT_0.11.0.md).
The published 0.10.0 APK has not been replaced by this work. A full Android build
and device acceptance are still required.
