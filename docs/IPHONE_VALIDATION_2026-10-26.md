# Mirage iPhone engineering validation — 26 October 2026

Status: RemotePairing implementation candidate for the owner's iPhone 16 Pro Max (About screenshot: iOS 26.6.1). Exact-candidate compilation and behavioral validation are in progress. No iPhone release, signed installer or feature-parity claim.

## Source and preservation

Existing source: `C:/CodexProjects/workspaces/Mirage/worktrees/planner-saved-places`, Android baseline documentation commit `58643f922d2cd3b55496b19d435dbb567ec7780f`. Work extends the existing uncommitted iPhone models and parity specification. Android sources and installers remain unchanged.

Validation snapshots use the isolated `codex/iphone-implementation-20261026` branch. The mapped worktree remains on its existing branch with its working files preserved. No replacement application repository or relocated source was created.

## Checks

| Check | Status |
| --- | --- |
| Specification written before implementation | Complete |
| Local JSON fixture parsing | Passed for synthetic three-item backup |
| Local whitespace check | Passed |
| Local Swift / Xcode execution | Unavailable on this Windows host |
| Swift core behavioral tests | Pending macOS CI |
| Rust transport host tests | Pending macOS CI |
| Simulator app compilation | Pending macOS CI |
| ARM64 device bridge and app compilation | Pending macOS CI |
| Native UI tests and screenshot inspection | Pending macOS CI |
| Signing and iPhone installation | Unrun; signing identity and connected physical-test access unavailable |
| Paired device output and consumer verification | Unrun |
| Background/lock/network/low-power/termination recovery | Unrun |
| Full Android feature parity and CarPlay | Not complete |

The optional local YAML parse attempt could not run because PyYAML is not installed. Project/workflow parsing is delegated to the actual macOS/GitHub build tools, not counted as passed locally.

## Superseded build history

- The first bridge build failed because its required upstream `location_simulation` feature was missing. It was added; the failed/superseded runs remain visible and are not counted as successful builds.
- Source `88c0cb4c383f1b873ccb63807ea6c94ab7fe2204`, [run 40432134888](https://github.com/aviterima/Mirage/actions/runs/40432134888), passed core tests, four Rust tests, unsigned ARM64 device app compilation, simulator compilation and two UI tests. Its preview screenshot was inspected. This was the older lockdown transport before the owner supplied the exact phone/OS.
- Source `fbe95c02a18f9e45766ffd2da637613e4b9be06a`, [run 40432856693](https://github.com/aviterima/Mirage/actions/runs/40432856693), also passed six native app state/race tests and the two UI tests. Builds used Xcode 16.4 / Swift 6.1.2 with the iOS 18.5 SDK. This does not establish compatibility with iOS 26.6.1.
- Review then corrected a stale recovery label after restored output and an imported-route display-name collision. On receipt of the owner's target, the transport changed to verify-only RemotePairing; the old lockdown build is not the target-phone candidate. Superseded queued runs were canceled rather than counted as tested.

## Evidence limits

Core backup tests use synthetic Android-shaped records. They verify field preservation and atomic validation, not a completed export/import journey between physical Android and iPhone devices. Native state tests use injected disposable fixtures and do not contact a phone. Remote Pairing parser tests use synthetic signing keys; no owner credentials are used. The native Files picker, security-scoped import, backup replacement confirmation and export destination flows remain untested end to end.

Third-party notices and the exact Cargo dependency audit are under `ios/DeviceBridge/`. Two upstream crates lack published notice files; those provenance gaps remain documented and must be resolved before distribution acceptance. Source compilation and a successful protocol write do not establish consumer-app location or real-location restoration.

## Remaining product work

Complete the eight-case physical proof in the parity specification before device-output acceptance. Then finish native planning/provider integrations, scheduled itinerary behavior, hiking, offline voice and physical UI ergonomics. Current saved-template interchange preserves those fields, but preservation does not mean the proof player executes all their semantics. No online speech substitution or external deployment is enabled.
