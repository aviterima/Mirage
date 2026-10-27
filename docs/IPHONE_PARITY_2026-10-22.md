# Mirage iPhone edition — parity requirements and first-device proof

Date: 22 October 2026. Updated 26 October: the owner authorized specification followed by code. See the [implementation specification](IPHONE_IMPLEMENTATION_2026-10-26.md) and [current validation record](IPHONE_VALIDATION_2026-10-26.md). The investigation below is preserved as the parity target; statements about implementation not starting describe the original 22 October checkpoint.

## Owner request and scope

The owner requested: "I want to create an iPhone app with the same functionality as on an android."

The target is an iPhone edition that runs Mirage's plans and supplies simulated location to location-consuming apps on that iPhone. An in-app preview or a view of an Android's shared location does not satisfy this request. The earlier Android-only/view-only scope in SPEC.md is superseded for the proposed iPhone edition; it remains the description of the delivered Android product. This request does not establish that iPhone parity is technically achieved.

Use the delivered 0.19.1 behavior as the baseline, not aspirational features from earlier specifications. The baseline tested source is b91cee13aed8d020510f6409b1f8a6ec5f73bfa7, documented at 58643f922d2cd3b55496b19d435dbb567ec7780f. The existing mapped repository, Android application, signing identity, releases and evidence remain in place.

## Platform finding

Apple's public Core Location framework supplies location to an app; it does not offer Android's selectable mock-provider interface. Apple's documented simulation path uses development/testing services. A normal App Store implementation cannot be assumed to provide Mirage's device-wide output merely by porting its UI or enabling Developer Mode.

There is a more promising experimental route: an iPhone application can use a device pairing record and a local loopback VPN to communicate with developer services on the same device. Upstream StikDebug documents a location simulator and operation without a computer after initial pairing. Its published setup requires pairing, sideloading/refreshing and a loopback VPN. Its iOS compatibility table is project guidance, not a Mirage compatibility result; the iOS 26 notes also discuss JIT and must not be read as certification of every location feature.

The candidate transport is the MIT-licensed idevice library, through a narrowly scoped Swift adapter. Its API is under active development and must be pinned to an audited version. Do not copy StikDebug's AGPL or Anole's GPL application code into Mirage as if it were permissively licensed. They are evidence of feasibility, not adopted dependencies or accepted release proof.

Important unresolved constraints:

- The inspected DVT location setter accepts latitude and longitude. Android's Fix also supplies speed, bearing, accuracy and altitude. Determine what iOS actually reports before describing these as equivalent; unavailable fields must not be silently treated as supported.
- The developer-service connection must remain alive. Backgrounding, screen locking, network changes, low-power mode and process termination require physical-device evidence.
- Pairing and tunnel setup differ between OS releases. Anole's broad iOS minimum and its documented pairing flow are not sufficient to select a compatible adapter for an unknown iPhone.
- Acknowledged transport writes are not evidence that Google Maps or another consumer used the requested coordinate.
- Clearing simulation must be verified to restore fresh real location; a closed socket or successful clear call alone does not prove recovery.
- No claim is made about App Store distribution, Find My, applications that reject simulated locations, or vehicle behavior. No detection-evasion feature is requested or introduced.

## Candidate architecture

This is a proposed architecture pending device details and the first-device proof, not a selected dependency set.

1. Native iPhone planner and map UI, retaining the current field, selection and Save/Start rules.
2. A portable plan/playback layer with explicit clock and random sources, independently testable with synthetic routes.
3. A location-output protocol with explicit capabilities for coordinates, speed, course, altitude, accuracy and lifecycle. Preview output and device output must have distinct states.
4. A version-pinned developer-service adapter using locally retained pairing material and a loopback tunnel. Pairing material is sensitive: never commit it, include it in a backup, log its contents, or ask the owner to paste it into chat.
5. Independent Core Location observations and consumer checks to distinguish planned position, sent position and observed position. A stale/failed connection must visibly stop claiming valid output.
6. Credential-free saved-plan interchange compatible with Android's existing backup schema. Provider keys and device setup remain outside exported plans.

A continuously connected computer is an alternate transport only if acceptable to the owner. It must not quietly become a requirement of an edition expected to run on the phone after setup.

## Required behavior

Every row is an iPhone requirement, not a completed feature.

| Area | Baseline to preserve | iPhone verification needed |
| --- | --- | --- |
| Location output | Jump/hold, continuous travel and explicit Stop; arriving holds the endpoint | Device coordinates, movement cadence, consumer behavior, real-location recovery |
| Motion | Drive, walk, bike, transit and flight; route geometry, speed/time settings and realism | Golden route samples plus observed speed/course/accuracy/altitude capabilities |
| Itinerary | Ordered stops, dwell/leave rules, departures/arrive-by, pause/resume/skip and live continuation | State-machine tests and complete native UI journeys |
| Places | Search, saved names/aliases, favorites, editable names, current and last-real location | Provider integration, permission handling, late-result cancellation and field guards |
| Arrival | Entrances and arrival/stay behavior, per-stop preferences | Preserved plan fields and actual playback transitions |
| Hiking | Named and area browsing; drive/park/hike/return geometry | Provider behavior, exact saved paths and route transitions |
| Planning UI | Map/Edit draft preservation, reorder, visible Current/Saved controls, keyboard and large-text usability | Real keyboard and accessibility tests on target iPhone sizes |
| Voice | Bundled offline dictation into editable fields; manual selection/Save/Start | Actual microphone and offline speech tests; no silent substitution with online recognition |
| Persistence | Saved places/plans and credential-free backup/import | Android-to-iPhone and iPhone-to-Android round trips, atomic invalid-input rejection |
| Reliability | Honest output state, bounded recovery, retained plan and meaningful stop semantics | Lock/background/network interruption, long sessions and recovery on physical devices |
| Diagnostics/automation | Local privacy-preserving diagnostics and deliberate control commands | An iOS-appropriate interface; ADB commands themselves are Android-specific |
| Car use | Phone-side location with car behavior separately checked | CarPlay and wired/wireless device/vehicle tests; no inference from Android Auto tests |

Normal 0.19.1 uses offline dictation. Gemma/conversational interpretation remains disabled and quality-blocked and is not an iPhone parity requirement.

## Existing source to port or adapt

- `android/app/src/main/java/com/mirage/spike/engine/Models.kt`: modes, route segments, motion parameters and Fix fields.
- `android/app/src/main/java/com/mirage/spike/MockLocationService.kt`: Android fused-provider/test-provider output and recovery semantics; replace platform bindings, preserve behavioral requirements.
- `android/app/src/main/java/com/mirage/spike/store/Scenarios.kt`: saved plans and per-stop metadata.
- `android/app/src/main/java/com/mirage/spike/store/BackupCodec.kt`: `mirage-backup`, version 1; 2 MiB limit, at most 1,000 items, complete-input validation before writes.
- `docs/CURRENT_LOCATION_0.19.1.md`, `docs/MOBILE_UI_AND_CAR_RECOVERY_0.19.0.md` and `docs/OFFLINE_DICTATION_0.18.0.md`: current interaction requirements and known acceptance limits.

The engine has Android/JVM dependencies and global playback-state coupling. There is no existing iOS-ready shared module; avoid describing a source copy as a completed port.

Saved templates are distinct from live-session checkpoints. The backup format must not be described as transferring every active speed override, signal state or playback clock. Preserve existing route archives and explicit recovery semantics; restoration must not automatically start playback.

## First-device proof and acceptance gates

Before committing to full UI implementation, create the smallest signed native proof with explicit Connect, Set test point, Play synthetic route, Hold and Stop controls. Use synthetic test coordinates, an owner-controlled device and a version-pinned transport. It must expose missing capabilities and distinguish transport acknowledgement from observed location.

Record the device model, exact OS build, app/adapter versions, setup steps and results for each case:

1. Establish a paired developer connection. Report setup failures without beginning playback.
2. Set a static test position; independently observe it through Core Location and Google Maps. A marker drawn by the proof app is insufficient.
3. Replay a deterministic route including turns, stationary periods and arrival. Measure observed location cadence, latency and derived fields against the planned trace.
4. Keep Google Maps in the foreground, then lock the phone and repeat with screen off. Exercise relevant Wi-Fi/cellular transitions and low-power mode. Run an extended session after short cases pass.
5. Hold an endpoint until explicit Stop. Pause/resume must preserve the itinerary state.
6. Exercise tunnel loss, application backgrounding/termination and relaunch. Record whether simulation continues, stops or leaves a stale location, and whether recovery can occur without a computer. Do not promise Android-like process recovery unless demonstrated.
7. Stop/clear and independently verify fresh real-location updates. Test failed clear and interrupted connections; show unresolved recovery explicitly.
8. Repeat on any supported OS build before declaring compatibility. Test CarPlay separately if included in the target.

Do not mark full parity if any required behavior is unsupported. A reduced capability set requires a separate product decision. All failed and unrun checks remain recorded.

## Work completed and prerequisites

Completed: existing source and release review, upstream feasibility/source review, parity requirements and proof design. No third-party code has been imported. No app has been compiled, signed, installed or run on an iPhone. No iPhone device acceptance has passed.

This environment is Windows; neither Swift nor Xcode's `xcodebuild` was available on PATH during the check. There is no established Mac build runner or connected iPhone test session in this task. A Mac with Xcode or an appropriate macOS build runner is needed to build and sign the native proof; physical acceptance requires the target phone.

Pending owner information, asked in chat:

- iPhone model and exact iOS version/build.
- Access to a Mac, or another acceptable macOS build/signing route.
- Whether the app must operate entirely on the phone after setup, or a computer connection during playback is acceptable.

The candidate after-setup on-device path should be evaluated before assuming that a permanent computer connection is necessary.

## Sources consulted

These are upstream documentation/source claims inspected on 22 October 2026, not Mirage hardware results.

- [Apple Core Location](https://developer.apple.com/documentation/corelocation)
- [Apple location simulation in tests](https://developer.apple.com/documentation/xcode/simulating-location-in-tests)
- [Apple application runtime security](https://support.apple.com/guide/security/security-of-runtime-process-sec15bfe098e/web)
- [StikDebug upstream: location simulator, setup and compatibility](https://github.com/StikDebug/StikDebug)
- [idevice upstream: developer-service transport, license and versioning caveat](https://github.com/jkcoxson/idevice)
- [idevice coordinate setter and maintained connection, inspected source revision](https://github.com/jkcoxson/idevice/blob/26a28f6a46b5d85a28043c2e62febfa394c8b0a6/idevice/src/services/dvt/location_simulation.rs)
- [StikDebug location session and keepalive implementation](https://github.com/StikDebug/StikDebug/blob/main/StikDebug/Services/LocationSimulationSession.swift)
- [StikDebug pairing setup guide](https://github.com/StikDebug/StikDebug-Guide/blob/main/pairing_file.md)
- [Anole upstream: on-device and Mac simulation implementation](https://github.com/laatortuejaune/anole)
- [Anole on-device backend, inspected source revision](https://github.com/laatortuejaune/anole/blob/a5c122556ea58e7430351a8da60f97a22b94f15c/Sources/AnoleiOS/IDeviceBackend.swift)
- [idevice pair-host OS-specific setup, inspected source revision](https://github.com/jkcoxson/idevice/blob/26a28f6a46b5d85a28043c2e62febfa394c8b0a6/tools/src/pair_host.rs)
- [Apple CarPlay entitlements](https://developer.apple.com/documentation/carplay/requesting-carplay-entitlements)

This record is local and uncommitted. It does not change the Android release identity or certify an iPhone release.
