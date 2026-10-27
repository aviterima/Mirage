# Native iPhone developer-service bridge

This is a real coordinate-output adapter. It is experimental and has no physical
iPhone acceptance result. A successful write means the developer service replied;
it does not establish that Core Location, Google Maps or another consumer used the
coordinate. A clear reply does not establish restoration of real location.

## Dependency and protocol

`Cargo.toml` pins the MIT-licensed idevice **0.1.68** source to
`26a28f6a46b5d85a28043c2e62febfa394c8b0a6`. `Cargo.lock` fixes the dependency graph.
Rust 1.90.0 is the development toolchain. No moving Git branch, prebuilt third-party
binary, GPL/AGPL application source, JIT control or obfuscation feature is used.
See [the upstream license](https://github.com/jkcoxson/idevice/blob/26a28f6a46b5d85a28043c2e62febfa394c8b0a6/LICENSE.txt)
and `THIRD-PARTY-NOTICES.txt`. Transitive license review remains a distribution gate.

The original Rust bridge uses these upstream APIs in order:

1. `PairingFile::from_bytes(&[u8])` and a private `IdeviceProvider` implementation.
2. `CoreDeviceProxy::connect(&provider)`, then `tunnel_info().server_rsd_port` and
   `create_software_tunnel()?.to_async_handle()`.
3. `adapter.connect(port)` and `RsdHandshake::new(stream)`.
4. `RemoteServerClient::connect_rsd(&mut adapter, &mut handshake)` and
   `server.read_message(0)`.
5. `LocationSimulationClient::new(&mut server)`; `set(latitude, longitude)` and
   `clear()` use the DVT location service.

The [pinned location implementation](https://github.com/jkcoxson/idevice/blob/26a28f6a46b5d85a28043c2e62febfa394c8b0a6/idevice/src/services/dvt/location_simulation.rs)
accepts coordinates only and requires a maintained connection. Speed, course,
altitude and accuracy are therefore reported unsupported. A dedicated OS thread
owns a current-thread Tokio runtime, software TCP adapter, remote server and its
borrowed location channel for the entire connection. A Swift actor or serial
dispatch queue alone would not guarantee the native handles' thread affinity.

Every connect/channel operation and set/clear is bounded by a 10-second timeout.
Failed or timed-out DVT operations discard the broken session; a subsequent clear
reconnects with the retained configuration and issues clear without first sending
a position. Connection teardown is separately bounded. The session above this
bridge serializes commands, sends endpoint keepalives through ordinary acknowledged
writes, and handles stale callbacks. The bridge does not report independent GPS
observations or claim recovery after destruction. Automatic destruction makes a
best-effort clear only. Connect is idempotent with identical endpoint and pairing
bytes, and rejects configuration replacement while a connection is alive.

## Device setup

The supported candidate is a paired lockdown connection over a reachable loopback
VPN endpoint, followed by a CoreDevice software tunnel. The app accepts a numeric
IPv4/IPv6 address and lockdown port (normally `62078`). It does not implement
RemotePairing/PIN setup, mount a Developer Disk Image, install a VPN, or enable
Developer Mode. Those prerequisites must be established separately on the target
phone. The configured address is the VPN's device endpoint, not necessarily
`127.0.0.1`; upstream StikDebug currently uses `10.7.0.1` with its loopback setup.
That example is not evidence that every VPN uses that address.

[StikDebug upstream setup](https://github.com/StikDebug/StikDebug#how-to-enable-jit)
documents pairing plus a loopback VPN for on-device developer services and lists
iOS 17.4 or newer. Its compatibility claims do not certify Mirage. This bridge's
API path and the app's iOS 17 UI minimum are not a complete iOS support matrix.
The target phone/OS, developer image and loopback networking must be verified.
App Store delivery, background/locked-screen continuity and CarPlay are unverified.

The app supplies an app-private, excluded-from-backup, protected local pairing
file. It must never be included in saved-plan exports. Swift reads at most 1 MiB,
passes bytes directly, and overwrites its temporary Data buffer. The Rust input
copy uses `Zeroizing`; the parsed pairing record remains in process memory as
needed for reconnection. Neither credentials nor upstream error text are logged.
There is no claim that an upstream parsed private key is itself zeroized on drop.

## Build and Xcode integration

On a Mac with full Xcode and its iOS SDK, install Rust 1.90.0 with rustup and the
chosen target. From the repository root:

```sh
rustup toolchain install 1.90.0 --profile minimal
rustup target add --toolchain 1.90.0 aarch64-apple-ios
cargo +1.90.0 test --locked --manifest-path ios/DeviceBridge/Cargo.toml
bash ios/scripts/build-device-bridge.sh aarch64-apple-ios
```

The script requires `Cargo.lock` and fails for missing tools/SDK/target; it never
substitutes a stub. Artifacts are placed in ignored
`ios/DeviceBridge/build/aarch64-apple-ios/`. Build arm64 simulator with target
`aarch64-apple-ios-sim` (or Intel simulator with `x86_64-apple-ios`) separately if
validating the linked Swift branch in Simulator. Simulator output is not hardware
acceptance. These are single-platform archives; do not link an iPhone archive
into a simulator target.

For the explicit device build configuration with `SRCROOT` at `ios/`:

| Setting | Value to append |
| --- | --- |
| `SWIFT_ACTIVE_COMPILATION_CONDITIONS` | `MIRAGE_DEVICE_BRIDGE` |
| `SWIFT_INCLUDE_PATHS` | `$(SRCROOT)/DeviceBridge/include` |
| `HEADER_SEARCH_PATHS` | `$(SRCROOT)/DeviceBridge/include` |
| `LIBRARY_SEARCH_PATHS` | `$(SRCROOT)/DeviceBridge/build/aarch64-apple-ios` |
| `OTHER_LDFLAGS` | `-lmirage_device_bridge -framework Security -framework SystemConfiguration` |

Include this notice in a distributed app's resources, complete the transitive
dependency license review, and verify the final archive's native linker
requirements. The ordinary simulator configuration omits the flag and library;
`DeviceTransport.isAvailable` is false and Connect/Send/Clear throw an actionable
error. It never returns fake device success.

`include/MirageDeviceBridge.h` is the deliberately narrow C ABI. Create allocates a
worker; connect accepts pairing bytes and endpoint; set accepts finite bounded
coordinates; clear attempts real DVT clear; destroy owns final teardown. Every
function is synchronous and returns only fixed privacy-preserving status codes.
Swift `DeviceTransport` moves calls off the UI thread and resumes async callers
after actual native completion. No handle may be destroyed while another call
uses it.

## Verification record

On Windows, Cargo 1.90.0 generated `Cargo.lock` successfully and rustfmt 1.90.0
parsed/formatted the Rust source. Tool archives were downloaded from
`static.rust-lang.org` and their published SHA-256 checksums matched; tools remain
under ignored `build/tools/`, with no system PATH or default toolchain changes.
The initial checksum check rejected a PowerShell byte-array/text mismatch; the
archive was subsequently decoded and verified before use. Native compile/test,
Apple archive linking, signing and physical iPhone checks require their recorded
CI/hardware results. The included Rust tests cover argument validation, malformed
pairing and honest disconnected failures; they do not exercise a real device.
