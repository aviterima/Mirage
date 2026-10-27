# iPhone engineering candidate — 26 October 2026

The owner authorized an iPhone edition with device output. Native proof code, a portable Swift core, Android backup interchange and a pinned experimental developer-service transport are under `ios/`. **74 tests and both simulator and unsigned device builds passed.** See the [specification](docs/IPHONE_IMPLEMENTATION_2026-10-26.md), [conditional macOS build-host reference](docs/IPHONE_MAC_SETUP.md) and [validation record](docs/IPHONE_VALIDATION_2026-10-26.md). The owner corrected the hardware context on 27 October 2026: no Mac; available devices are an iPad and a Windows Surface. The target remains iPhone 16 Pro Max showing iOS 26.6.1. A signing, installation and device-preparation route compatible with that equipment remains to be established. Hosted macOS CI does not imply owner Mac access. Signing and physical output verification remain unrun. This is not a signed iPhone release or complete Android feature parity. The iPhone-view-only statements in historical sections describe the earlier Android scope.

# Mirage 0.19.1 — direct current-location selection

Current location and Saved are visible beneath Start, Destination and Next stop, including while the keyboard is open. During simulation, the physical-location action says Last real location. Selecting it clears unfinished text correctly; pending fixes block Save/Start and cannot replace a newer edit.

App validation passed on 16 October 2026: 285 JVM and 59 Android tests, zero failures/errors/skips. On 18 October the unchanged live hiking check passed locally through the backup provider, covering named search, Tucson browsing and mapped paths. The GitHub trail job remains failed after provider/network errors; its status and evidence are preserved. The exact tested 0.19.1 installer and its full hosted download were verified. See [behavior and validation](docs/CURRENT_LOCATION_0.19.1.md).

# Mirage 0.19.0 — mobile planning and location recovery

Full-width place entry, focused keyboard search, nearby Saved controls, one scrolling itinerary and a draft-preserving Map / Edit plan view. Save and Start remain separate; offline dictation stays bundled. Fresh fused-location readback now drives bounded recovery and honest status. Wireless Android Auto compatibility still requires a physical car test; ordinary Android apps cannot force Wi-Fi off.

Validated 2026-10-14: **285 JVM tests and 55 Android emulator tests passed**, with zero failures/errors/skips independently counted from XML. Blocking lint passed with zero errors, 44 warnings and 12 hints. Pinned signing, bundled offline speech, the signed 0.18.0 upgrade and live trail checks passed. The emulator display is configured to 360×640dp with 1.3× text; its compact test uses the real keyboard, requires the focused layout and checks the footer against actual IME screen bounds. Final screenshots confirm the active field, microphone, full first saved/remote result and guarded footer above the real keyboard at 360x640dp with 1.3x text. Actual-Activity large-text search, Map/Edit draft preservation, car-help actions and both paused additions were reviewed. The settled itinerary frame shows Second, Third, First; itinerary-dragged.png captures a transient drag frame and is not used as final-order evidence. Physical phone and car behavior remain unverified. See [behavior, verification history and remaining phone/car checks](docs/MOBILE_UI_AND_CAR_RECOVERY_0.19.0.md).

# Mirage 0.18.0 — offline dictation

Voice dictation fills the same editable fields as typing, using bundled speech recognition without a large model download. Saved places such as Home share the normal search results; the itinerary list is the preview. Clear entry and Start new have separate scopes, and Save remains separate from Start. Conversational interpretation is disabled in the normal installer.

Validated October 13, 2026: **265 JVM tests and 51 Android emulator tests passed**, with blocking lint, pinned signing, bundled speech initialization and the signed upgrade from 0.17.0. Android acceptance passed on an unchanged-source rerun after an existing live-trip test's Compose idle timeout. See [behavior, exact APK identity, verification history and remaining phone checks](docs/OFFLINE_DICTATION_0.18.0.md).

# Earlier 0.18.0 conversational experiment — not released

The earlier conversational candidate added whole-plan review with corrections and Undo. **It was not released.** The [Gemma experiment](docs/GEMMA_LOCAL_UPDATE_0.18.0.md) built, but passed only 11 of 16 fresh language cases and encountered a native Android crash. Those failures remain unresolved and the capability is excluded from the normal installer. See the [historical specification](docs/CONTEXTUAL_VOICE_0.18.0.md) and [prior Qwen failures](docs/MODEL_EVALUATION_0.18.0.md).

Gemma source `58c3897` passed 269 JVM tests, lint, signing and upgrade checks; Android acceptance stopped after 19 passes and one native crash, leaving 35 tests unrun. Follow-up source `1d67ecd` passed 269 JVM tests and completed all 56 Android tests, but one pending-plan correction case failed (55 passed, zero errors/skips). Its production-context native planner test passed; the experimental capability remains blocked. Earlier Qwen UI passes do not establish Gemma acceptance.

# Mirage 0.17.0 — voice in the planner

Use the microphone beside Start, Destination or Next stop, or type in the same field. Missing voice files open Voice setup; recognized words stay editable. The existing itinerary shows the plan and highlights new stops. Clear entry and Start new have separate scopes. Validated October 12, 2026: **210 JVM tests and 44 emulator tests passed**, with blocking lint, pinned signing and the in-place upgrade from 0.16.1. Acceptance passed on an unchanged-source rerun after two emulator idle timeouts. See [behavior, exact APK identity, failure history and remaining phone checks](docs/INLINE_VOICE_PLANNER_0.17.0.md).

# Mirage 0.16.1 — place names with cities

Looked-up locations use **Name (City)**, for example **Western Digital (Irvine)**. Saving a location in Jump mode prefills that editable name. Validated October 12, 2026: **195 JVM tests and 38 emulator tests passed**, with blocking lint, pinned signing and the in-place upgrade from 0.16.0. See [behavior, exact APK identity and remaining phone checks](docs/PLACE_NAMES_0.16.1.md).

# Mirage 0.16.0 — local interaction audit

**Setup → Interaction audit** (also available from planner Settings) records a bounded local timeline of actions, outcomes and safe app state. After a problem, mark it and export a diagnostic report to share for debugging. Place names, coordinates, typed queries, voice content and credentials are excluded. Recording can be paused and history cleared; reports are never uploaded automatically. See [the specification and quick start](docs/INTERACTION_AUDIT_0.16.0.md).

Validated October 11, 2026 Pacific: **182 JVM tests and 36 emulator tests passed**, including native report export and the in-place upgrade from 0.15.2. Blocking lint, pinned signing and live trail-service checks passed. See [validation, exact APK identity and remaining phone checks](docs/VALIDATION_0.16.0.md). Tested source: `e3a2f477e54b81a816e5d39debd9ca7ac8b5745b`.

# Mirage 0.15.2 — choose saved places while planning

The Start, Destination and Next stop fields have a direct **Saved places** button. To plan a route home, choose **Destination → Saved places → Home**. In Itinerary mode, the same picker appends Home to the existing stop list. Typing a saved name or alias also offers local matches. Saving a plan does not start simulation.

Validated October 11, 2026 Pacific: **164 JVM tests and 32 emulator tests passed**; blocking lint, pinned signing and the in-place upgrade from 0.15.1 passed. See [behavior and acceptance scope](docs/PLANNER_SAVED_PLACES_0.15.2.md), [release notes](docs/RELEASE_NOTES_0.15.2.md) and [validation with APK identity and remaining phone/layout checks](docs/VALIDATION_0.15.2.md). Tested source: `51de0d09c488180a5df24f98485665e006d4fbdd`. Earlier validation below applies only to the stated version.

# Mirage 0.15.1 — browse trails from the route planner

Choose **Route** or **Itinerary** in the top planner, then **+ Add hiking trail**. Leave the trail name blank to browse nearby trails, or enter a city/park and tap **Browse hiking trails**. If several areas match, choose one. A specific trail name remains optional.

Results show mapped trail length and straight-line miles from the current real or simulated location. The selected search area and the last planned stop do not replace the location used for distance labels. The trip preview separately shows the routed drive from the itinerary's last stop, parking time, selected hiking mileage and the walk between parking and the trail. Adding the hike retains existing stops and any existing route destination.

Transient provider failures try one backup server; both providers are bounded, cached and subject to a cooldown. Browsing shows a limited sample of up to 20 mapped trails or sections, not a complete catalog. See [release notes](docs/RELEASE_NOTES_0.15.1.md) and [validation](docs/VALIDATION_0.15.1.md).

Validated October 9, 2026: **153 JVM tests and 29 emulator tests passed**; blocking lint, live trail lookup with fallback and the in-place upgrade from 0.15.0 passed. Tested source: `10b5ecd53e64680f132feb9781f04ff3f176de93`. Screenshot review was limited by an emulator Pixel Launcher dialog; physical-phone validation remains outstanding.

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
