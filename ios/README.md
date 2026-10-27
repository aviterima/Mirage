# Mirage for iPhone

Native iOS 17+ engineering candidate, developed alongside the delivered Android app. The [implementation specification](../docs/IPHONE_IMPLEMENTATION_2026-10-26.md) defines the first proof; [full parity requirements](../docs/IPHONE_PARITY_2026-10-22.md) remain the product target. This is not an accepted iPhone release.

## Build on macOS

Install full Xcode, its command line tools and Rust (Cargo/rustup). No Apple credentials are required for the unsigned validation builds.

```sh
swift test --package-path ios/MirageCore
bash ios/scripts/generate-project.sh
xcodebuild -project ios/Mirage.xcodeproj -scheme Mirage \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO build
```

The project generator is pinned to XcodeGen 2.44.1 at `21ac9944b0ab546a07422dbed86f33dd2ebd76f8`. Generated files and build outputs are ignored. The ordinary target includes explicit preview output and rejects device output. It does not silently simulate success.

For the actual native developer-service transport:

```sh
rustup toolchain install 1.90.0 --profile minimal
cargo +1.90.0 test --locked --manifest-path ios/DeviceBridge/Cargo.toml
rustup target add --toolchain 1.90.0 aarch64-apple-ios
bash ios/scripts/build-device-bridge.sh aarch64-apple-ios
bash ios/scripts/build-device-app.sh
```

The device script compiles without signing. To install, configure a development team and the same bridge settings in Xcode, sign for the owner's phone, and establish the pairing/developer-service/tunnel prerequisites described in [DeviceBridge](DeviceBridge/README.md). Do not distribute pairing records or sign using credentials committed to the repository. This app does not yet provision the loopback VPN or developer disk image. Physical operation is unverified until the device acceptance matrix passes.

## What the candidate does

- Explicitly selects device output or preview; supports static point, synthetic route, pause/resume, hold and Stop.
- Separately shows planned, transport-sent and independently observed Core Location coordinates.
- Retains a recovery marker across relaunch; requires fresh explicitly non-simulated location after clear.
- Reads/writes local saved places and Android version 1 backups, preserving full template metadata and archived geometry. Import validates the complete input before atomic replacement. Loading never starts playback.
- Plays archived route geometry as a proof trace, clearly without claiming scheduling, realism or provider parity.

The full planner, provider routing, hiking, bundled offline dictation, live itinerary edits and CarPlay acceptance remain outstanding. Coordinates are the only supported transport fields. Background execution is deliberately marked unverified and the driving loop pauses; always perform Stop and observe fresh real location before treating the device as recovered.

Validation status and evidence: [iPhone validation record](../docs/IPHONE_VALIDATION_2026-10-26.md). The GitHub workflow compiles both target variants, executes core/bridge tests and runs native UI checks. None of those tests substitutes for signed physical-device verification.
