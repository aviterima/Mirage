# Mirage iPhone engineering validation — 26 October 2026

Status: Engineering checks passed for the RemotePairing implementation candidate targeting the owner's iPhone 16 Pro Max (About screenshot: iOS 26.6.1). **74 tests passed**, with zero failures or skips, and both simulator and unsigned ARM64 device app builds passed. Signing, installation and physical output acceptance remain unrun. No iPhone release or feature-parity claim.

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
| Owner hardware | Corrected 27 October: no Mac; iPad and Windows Surface. Hosted macOS CI is separate |
| Swift core behavioral tests | 42 passed; XML independently counted |
| Rust transport host tests | 14 passed; downloaded test log independently checked |
| Simulator app compilation | Passed |
| ARM64 device bridge and app compilation | Passed, unsigned |
| Native app state and pairing validation tests | 16 passed |
| Native UI tests and screenshot inspection | 2 passed; four exported screenshots inspected |
| Signing and iPhone installation | Unrun; signing identity and connected physical-test access unavailable |
| Paired device output and consumer verification | Unrun |
| Background/lock/network/low-power/termination recovery | Unrun |
| Full Android feature parity and CarPlay | Not complete |

The optional local YAML parse attempt could not run because PyYAML is not installed. Project/workflow parsing is delegated to the actual macOS/GitHub build tools, not counted as passed locally.

## Exact candidate and retained evidence

Tested source: `08e4ab19ed80b350ca84c182920be145109aeed7` on `codex/iphone-implementation-20261026`. [Run 40434352309](https://github.com/aviterima/Mirage/actions/runs/40434352309) completed successfully in both jobs: Swift/simulator `121960306523` and device bridge `121960306746`. Subsequent delivery documentation changes do not change tested code.

The native result bundle independently reports **18 passed, zero failed, zero skipped and zero expected failures**: 16 app unit tests and two UI tests. Combined with 42 core tests and 14 Rust tests, that is 74 passed. The Rust tests include real synthetic TLS handshakes with correct and incorrect PSKs and bounded tunnel-framing cases; they do not contact an owner device.

Toolchain: Xcode 16.4 build 16F6, Swift 6.1.2, iOS 18.5 SDK, Rust 1.90.0. Native tests ran on the **iPhone 16 Pro Max simulator with iOS 26.2, build 23C54, arm64**. This is distinct from the owner's physical iOS 26.6.1. The simulator runtime selection report is retained with the results.

Both downloaded artifact ZIPs match GitHub's recorded byte size and SHA-256; archive CRC checks passed. Retained locally under `C:/Users/arman/OneDrive/Documents/ChatGPT/Mirage/builds/iphone-0.1.0/evidence-08e4ab1/`:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `iphone-simulator-evidence.zip` (12704480675) | 8,824,942 | `5a10ba45cd91a641729242b8d6ede78d3f374ea84015e1c433a0fa7c92d5a947` |
| `iphone-device-build-evidence.zip` (12703388880) | 20,286,807 | `d935061c4fe5574e471a2cbbbdfb7739246210088dff5ac37cae4d2c49419eeb` |

The directory includes sanitized artifact metadata, independent verification results, extracted XML/xcresult summaries, build/test logs, simulator configuration and screenshots. The device archive is unsigned build evidence, not an installable signed delivery.

Four exact-candidate screenshots were inspected: explicit preview, Saved, Setup and coordinate entry with the real software keyboard. Inspected text and controls are readable; preview is labeled, Setup correctly reports the simulator transport unavailable, and an invalid latitude is guarded after dismissing the keyboard. The keyboard occludes part of the next field and lower controls; scrolling and Done are required. These images do not establish complete keyboard ergonomics, large Dynamic Type, landscape, small-phone layout or physical-device usability.

Build warnings remain: three Core Location delegate isolation warnings under the configured Swift 5 language mode, an interface-orientation warning, and skipped App Intents metadata extraction because no App Intents dependency is present. This is not a warning-free build or Swift 6 language-mode acceptance.

## Superseded build history

- The first bridge build failed because its required upstream `location_simulation` feature was missing. It was added; the failed/superseded runs remain visible and are not counted as successful builds.
- Source `88c0cb4c383f1b873ccb63807ea6c94ab7fe2204`, [run 40432134888](https://github.com/aviterima/Mirage/actions/runs/40432134888), passed core tests, four Rust tests, unsigned ARM64 device app compilation, simulator compilation and two UI tests. Its preview screenshot was inspected. This was the older lockdown transport before the owner supplied the exact phone/OS.
- Source `fbe95c02a18f9e45766ffd2da637613e4b9be06a`, [run 40432856693](https://github.com/aviterima/Mirage/actions/runs/40432856693), also passed six native app state/race tests and the two UI tests. Builds used Xcode 16.4 / Swift 6.1.2 with the iOS 18.5 SDK. This does not establish compatibility with iOS 26.6.1.
- Review then corrected a stale recovery label after restored output and an imported-route display-name collision. On receipt of the owner's target, the transport changed to verify-only RemotePairing; the old lockdown build is not the target-phone candidate. Superseded queued runs were canceled rather than counted as tested.
- Review of the newly used upstream native TLS helper found ignored Server Finished mismatch, unsafe CBC padding arithmetic and unchecked CDTunnel length slicing. The accepted implementation must bypass that helper: Mirage now uses vendored OpenSSL for authenticated TLS 1.2 PSK and an original bounded CDTunnel parser. Synthetic tests exercise correct and incorrect PSKs, frame truncation/fragmentation, size limits and malformed metadata. No owner-device connection was attempted during this work; compiling the superseded helper does not count as security acceptance.

## Evidence limits

Core backup tests use synthetic Android-shaped records. They verify field preservation and atomic validation, not a completed export/import journey between physical Android and iPhone devices. Native state tests use injected disposable fixtures and do not contact a phone. Remote Pairing parser tests use synthetic signing keys; no owner credentials are used. The native Files picker, security-scoped import, backup replacement confirmation and export destination flows remain untested end to end.

Third-party notices and the exact Cargo dependency audit are under `ios/DeviceBridge/`. Two upstream crates lack published notice files; those provenance gaps remain documented and must be resolved before distribution acceptance. Source compilation and a successful protocol write do not establish consumer-app location or real-location restoration.

The final path bypasses the problematic upstream native TLS helper. OpenSSL authenticates the RemotePairing-derived shared secret; the imported record does not pin the remote device's public key. That upstream device-identity trust limitation remains explicit in the bridge documentation. The owner must supply the intended trusted local endpoint during the physical proof.

## Remaining product work

Establish a signing/install/device-preparation route for the owner's Windows Surface and iPad, with no owner Mac, then complete the eight-case physical proof in the parity specification before device-output acceptance. This is the owner's explicit correction of 27 October 2026. The [macOS guide](IPHONE_MAC_SETUP.md) remains only a conditional build-host reference. This Windows task has no connected Mac, signing identity or physical phone. Then finish native planning/provider integrations, scheduled itinerary behavior, hiking, offline voice and physical UI ergonomics. Current saved-template interchange preserves those fields, but preservation does not mean the proof player executes all their semantics. No online speech substitution or external deployment is enabled.
