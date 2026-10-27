# Mirage for iPhone — implementation specification

Date: 26 October 2026. Owner authorized specification followed by implementation. This extends the [parity requirements](IPHONE_PARITY_2026-10-22.md); it does not reduce the requested product to an in-app location preview. Android 0.19.1 remains the delivered baseline.

## Product and delivery

Build a native SwiftUI iPhone application alongside Android in `ios/`, using the existing repository and preserved worktree. Target iOS 17 or later for the UI; this minimum is not a promise that developer-service output works on every supported UI OS. The owner identified an iPhone 16 Pro Max; the supplied About screenshot shows iOS 26.6.1. The owner confirmed Mac access. Signing access and transport compatibility on that exact OS remain to be established. The intended product operates on the phone after initial pairing; a permanently connected computer is not the default architecture.

The first executable milestone is a device proof with Connect, Set test point, Play synthetic route, Pause/Resume, Hold and Stop. It includes a map, separate planned/sent/observed coordinates, a capability report and an honest recovery state. Native planning and credential-free Android backup interchange can be developed alongside this proof. Full parity and release remain gated on physical output verification; an app animation or successful transport write cannot close that gate.

## Architecture and contracts

- `MirageCore`: Foundation-only Swift package. Existing saved-plan models, strict Android backup v1 codec, deterministic geometry/playback and an output session state machine. No Apple UI dependency and no credentials.
- Native app: SwiftUI, MapKit visualization and Core Location observation. Files remain local. System location requests are read-only observations and never described as a mock-provider API.
- Device output: narrow version-pinned `idevice` adapter using verify-only RemotePairing for the owner's iOS 26.6.1 target. Upstream guidance identifies a connection and developer-image change in iOS 26.4; the earlier classic lockdown path is not the target architecture. Require a separately prepared Remote Pairing record, loopback endpoint and compatible cryptex developer services. Do not silently fall back to creating a new pairing, legacy credentials or a different transport. Keep third-party library licensing explicit. An unavailable adapter must fail Connect with an actionable setup state; it must never return a successful device write.
- Simulator preview: explicit separate output mode. Preview starts only by a deliberate action, does not connect a phone and always identifies itself as preview.
- Build: reproducible Xcode project generation/build instructions and macOS CI for package tests and simulator compilation. No provisioning identity or pairing record in source control. No automatic distribution or release.

## Playback requirements

Coordinates must be finite and inside latitude/longitude bounds. Route points, duration, dwell and timestamps are validated before playback. Use elapsed monotonic time, deterministic interpolation and injected test time. Pause freezes route progress; resume continues from the same position. Arrival retains the endpoint; Hold retains the current point until explicit Stop. Do not infer supported speed/course/altitude/accuracy from a coordinate-only transport.

No new Start may race a pending connection, write or clear. Serialize device commands. Ignore stale callbacks after state changes. Failure after a possible write leaves a recovery-needed state. Stop attempts clear even after connection/write failure, and successful clear remains “awaiting real location” until a fresh, non-simulated observation arrives. Missing simulation-source metadata must not be treated as proof of real location. Background suspension, termination and tunnel loss must not leave a green valid-output claim; retain a recovery-needed marker across app launch.

## Planner and persistence requirements

Use explicit selected coordinates; unfinished input and pending selection block Save/Start. Preserve the draft when switching Map/Edit. Save and Start are different actions. Saved places, routes, stop order, dwell/leave rules, modes, arrival metadata and frozen routes use Android `mirage-backup` version 1, at most 2 MiB and 1,000 items. Validate all imported items before replacing storage. Preserve the existing store on malformed input, unsupported versions or write failure. Plan backups never import or export credentials or pairing material; Setup imports pairing records separately into protected local storage. Template load never starts playback.

Full provider routing, hiking, offline dictation, live itinerary continuation and vehicle behavior remain required parity work. A first proof must not show unsupported controls as completed features or silently substitute a direct line for a road route. Synthetic routes must be labeled as such. No hosted speech or conversational model is introduced.

## Acceptance

1. Package tests: invalid geometry and backup rejection; complete Android field round trips; atomic import; deterministic interpolation, pauses, arrival/hold; output write/clear failures and stale observations.
2. Native build: compile the actual iPhone target with macOS/Xcode and build the simulator artifact. Simulator compilation does not establish signed installation or hardware behavior.
3. UI: explicit preview/device distinction, disabled incomplete actions, accessible labels, scrolling and Dynamic Type; real keyboard on small and large iPhones. Capture and inspect screenshots before visual acceptance.
4. Device proof: paired connection, static point and moving route independently observed through Core Location and Google Maps, then background/lock/network/low-power/termination cases and real-location restoration. Record exact OS/build, cadence and unavailable fields. Follow the detailed eight-case matrix in the parity document.
5. Release: all required tests pass for the exact candidate; signing and physical device acceptance completed. Failed, blocked and unrun checks stay visible. No iPhone release or full-parity claim before these gates close.

## Implementation record

This specification was written before the new implementation. Source changes, executed checks and remaining gaps are recorded in `IPHONE_VALIDATION_2026-10-26.md` as work proceeds. Existing uncommitted `Models.swift`, `Package.swift`, parity document and `SPEC.md` amendment are retained and extended.
