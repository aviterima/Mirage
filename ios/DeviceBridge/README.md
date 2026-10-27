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
and `THIRD-PARTY-NOTICES.txt`. The locked iPhone normal/build graph was audited:
**144 dependencies, 127 distinct notice texts, no unknown or unsupported Cargo
manifest license identifiers**. `LICENSE-AUDIT.json` records versions, source revisions, license
expressions and notice hashes. Ring's nested once_cell and Fiat notices are included.
The audit also includes the actual bundled **OpenSSL 3.6.3 Apache-2.0 license**,
not just the `openssl-src` Rust wrapper's licenses. Its bundled Text-Template 1.56
Perl build helper offers GPL version 1 or later or the Artistic License; the
Artistic alternative and original full notice are recorded. That helper runs
during the build and is not linked into the iPhone app.

Two provenance gaps remain explicit: `ns-keyed-archive` 0.1.5 declares
`MIT OR Apache-2.0`, and `plist-macro` 0.1.6 declares `MIT`, but neither published
crate nor its exact upstream source revision includes a license/copyright file.
The notices retain the declared license and author attribution without inventing
copyright years or missing license terms. These gaps require resolution before
distribution acceptance. The Rust standard library/toolchain and Apple SDK sit
outside Cargo.lock and are not claimed covered by this dependency audit.

Reproduce the audit with Python's standard library (no extra package needed):

```sh
cargo +1.90.0 metadata --locked --format-version 1 --filter-platform aarch64-apple-ios \
  --manifest-path ios/DeviceBridge/Cargo.toml > ios/DeviceBridge/build/license-metadata.json
python3 ios/DeviceBridge/scripts/generate-notices.py ios/DeviceBridge/build/license-metadata.json
```

The generator traverses normal and build dependency edges, preserves available
license/NOTICE/AUTHORS texts, deduplicates identical texts by SHA-256 and reports
missing notices. An unexpected license identifier or missing notice fails the audit.

The original Rust bridge uses these upstream APIs in order:

1. Strict XML/binary plist validation constructs `RpPairingFile`. Private/public
   Ed25519 keys must match; classic lockdown records are rejected.
2. `RpPairingSocket::new(TcpStream)` and `RemotePairingClient::new(socket, "Mirage")`.
   `attempt_pair_verify()` starts the handshake; `validate_pairing()` verifies
   imported credentials. The convenience `connect()` is deliberately unused
   because it falls back to full pairing. There is no PIN callback or legacy route.
3. `create_tcp_listener()` returns a port on the supplied endpoint; a second TCP
   connection uses Mirage's `tls_tunnel::connect(stream, client.encryption_key())`.
   This authenticates TLS 1.2 PSK with static OpenSSL and checks CDTunnel framing.
   Its negotiated client/server addresses, MTU and RSD port configure the software
   `Adapter`. `adapter.connect(port)` and `RsdHandshake::new(stream)` discover services.
4. `RemoteServerClient::connect_rsd(&mut adapter, &mut handshake)` and
   `server.read_message(0)`.
5. `LocationSimulationClient::new(&mut server)`; `set(latitude, longitude)` and
   `clear()` use the DVT location service.

The [pinned Remote Pairing implementation](https://github.com/jkcoxson/idevice/blob/26a28f6a46b5d85a28043c2e62febfa394c8b0a6/idevice/src/remote_pairing/mod.rs),
[OpenSSL protocol configuration](https://github.com/jkcoxson/idevice/blob/26a28f6a46b5d85a28043c2e62febfa394c8b0a6/idevice/src/remote_pairing/tunnel.rs)
and [location implementation](https://github.com/jkcoxson/idevice/blob/26a28f6a46b5d85a28043c2e62febfa394c8b0a6/idevice/src/services/dvt/location_simulation.rs)
provide this path. The location API
accepts coordinates only and requires a maintained connection. Speed, course,
altitude and accuracy are therefore reported unsupported. A dedicated OS thread
owns a current-thread Tokio runtime, software TCP adapter, remote server and its
borrowed location channel for the entire connection. A Swift actor or serial
dispatch queue alone would not guarantee the native handles' thread affinity.

`src/tls_tunnel.rs` bypasses the pinned upstream custom native TLS helper. Review
found that helper accepted mismatched Finished data, subtracted untrusted CBC
padding without checking its size, and sliced an untrusted CDTunnel response
length. Mirage leaves the pinned upstream source unchanged and does not call that
helper. OpenSSL verifies TLS records, padding, MACs and Finished authentication.
The local adapter permits TLS 1.2 PSK suites only, requires the complete 32-byte
pair-verify secret, and fails when callback buffers cannot hold it. There is no
unauthenticated or certificate-suite fallback. Disabling certificate checks is
appropriate to these PSK-only suites: peer authentication comes from the secret
and the verified TLS handshake, not an X.509 certificate.

The original CDTunnel reader accepts a 1–16 KiB body after an exact 10-byte header,
requires `serverHandshakeResponse`, validates distinct unicast IPv6 addresses,
MTU 1280–16000, and RSD port 1–65535 before narrowing integers. Fragmented frames
are read completely; bytes after the frame remain available for tunnel packets.
All TLS/parser errors are reduced to the fixed connection failure status. This
hardening and the synthetic tests do not establish physical-device compatibility.

Tunnel establishment, RSD/DVT establishment, channel creation, and each set/clear
are separately bounded by a 10-second timeout.
Failed or timed-out DVT operations discard the broken session; a subsequent clear
reconnects with the retained configuration and issues clear without first sending
a position. Connection teardown is separately bounded. The session above this
bridge serializes commands, sends endpoint keepalives through ordinary acknowledged
writes, and handles stale callbacks. The bridge does not report independent GPS
observations or claim recovery after destruction. Automatic destruction makes a
best-effort clear only. Connect is idempotent with identical endpoint and pairing
bytes, and rejects configuration replacement while a connection is alive.

## Device setup

Owner equipment correction, 27 October 2026: **no Mac; iPad and Windows Surface**.
The Mac commands below are conditional reference procedures for a separately
available build/setup host, not the owner's default workflow. A signing,
installation and developer-service setup path compatible with the owner's
equipment remains to be established. Successful hosted macOS CI does not close
that gap.

The identified acceptance target is **iPhone 16 Pro Max on iOS 26.6.1**. This is an
unverified target, not a support claim. The sole device path is verified Remote
Pairing over a reachable loopback VPN endpoint. The app accepts a numeric IPv4/IPv6
address and Remote Pairing port; `10.7.0.1:49152` is an upstream setup example,
not a guaranteed endpoint. Enter the address and current port supplied by the
installed setup. `_remotepairing._tcp.` service discovery can establish the port;
Mirage does not perform discovery, install a VPN, or provision trust.

[SideInstaller's maintainer notes](https://github.com/FrizzleM/SideInstaller/blob/main/NOTES.md#ios-264-lockdownd-hangs-up-on-loopback-connections)
document lockdown loopback failures beginning at iOS 26.4 and distinguish their own
measurements from reports on 26.x. This motivates removing the classic `62078`
route; it does not establish success on this target. The app UI's iOS 17 minimum
is not a transport support matrix. App Store delivery, background/locked-screen
continuity and CarPlay remain unverified.

Before connecting, the phone needs an authorized signed app installation,
[Developer Mode](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device),
an already trusted Remote Pairing record, working loopback networking, and an
OS-compatible developer image that exposes `com.apple.instruments.dtservicehub`.
[StikJIT's developer-image documentation](https://github.com/StikDebug/StikJIT/blob/main/INTEGRATION.md#built-in-stikjit-keep-the-ddi-cache-in-the-helper)
describes the iOS 26.4+ change to a five-file cryptex DDI, including cryptex info and
a root hash, and preparation per boot. Mirage requires that preparation externally;
it does not download, personalize, or install a DDI. These developer-service
prerequisites do not imply that location output needs JIT or its special entitlements.

The imported plist must have these exact types:

| Key | Value |
| --- | --- |
| `public_key` | Data, 32 bytes, the public key corresponding to `private_key` |
| `private_key` | Data, 32-byte Ed25519 seed |
| `identifier` | String, nonempty after whitespace trimming, at most 256 UTF-8 bytes; original bytes are preserved |
| `alt_irk` | Optional Data, exactly 16 bytes when present |

XML and binary property lists are accepted, at most 1 MiB. `HostID`, `SystemBUID`,
`HostCertificate`, or `HostPrivateKey` rejects the record even when remote keys
are also present. Renaming a classic pairing file cannot convert it.
Status `10` means Remote Pairing verification failed. Status `11` means the
developer service, Developer Mode, or developer image was unavailable according
to the returned service error. Network/protocol failures remain `4`, timeouts `7`.

### External pairing and image preparation

The pinned upstream CLI supplies a USB route to create and export the required
file; Mirage itself only verifies it. The commands below are source-backed setup
instructions, **not executed or hardware-validated by this project**. Use a Mac
with full Xcode, compatible device support assets, Rust/C build prerequisites, and
the phone unlocked and trusted over USB. Connect only the intended phone: this CLI
selects the first USB device when no device selector is supplied.

Build the separate upstream tool at the same revision, with credential tracing
compiled out. This tool is not linked or distributed with Mirage:

```sh
cargo +1.90.0 install idevice-tools --git https://github.com/jkcoxson/idevice \
  --rev 26a28f6a46b5d85a28043c2e62febfa394c8b0a6 --locked --bin idevice-tools \
  --no-default-features --features ring,tracing/max_level_off,tracing/release_max_level_off
```

In a new private setup directory outside the repository, create the record using
the trusted USB control channel. Choose a fresh output path so another pairing
file is not overwritten:

```sh
umask 077
mkdir -p "$HOME/Library/Application Support/Mirage-Setup"
pairing_output="$HOME/Library/Application Support/Mirage-Setup/remote-pairing.plist"
test ! -e "$pairing_output" || exit 1
RUST_LOG=off idevice-tools remotepairing_lockdown pair Mirage-Setup "$pairing_output"
RUST_LOG=off idevice-tools remotepairing_lockdown verify "$pairing_output" Mirage-Setup
```

The [pinned command implementation](https://github.com/jkcoxson/idevice/blob/26a28f6a46b5d85a28043c2e62febfa394c8b0a6/tools/src/remotepairing_lockdown.rs)
performs pairing over the already trusted USB service and writes the Remote
Pairing schema. Its USB pairing flow is expected not to ask for a PIN; a failure
does not create usable trust. The CLI's pair/verify handlers can return process
success after printing a protocol error, so check its explicit paired/recognized
result and the file's existence. Transfer the private file to the phone through a
trusted local method, then use Mirage's import control. Keep it out of repository
files, saved plans, screenshots, and shared logs.

The same pinned CLI has `cryptexd install-ddi` for an unpacked, compatible Xcode DDI
Restore directory. Once those assets are present, a source-backed preparation
command is:

```sh
RUST_LOG=off idevice-tools cryptexd install-ddi \
  /Library/Developer/DeveloperDiskImages/iOS_DDI/Restore
```

That path is the upstream example; use the actual compatible asset path for the
installed Xcode. The [pinned cryptex command](https://github.com/jkcoxson/idevice/blob/26a28f6a46b5d85a28043c2e62febfa394c8b0a6/tools/src/cryptexd.rs)
personalizes/installs the DDI through USB and requires its Apple service/network
prerequisites. This project has not validated the command or asset compatibility
on 26.6.1. After preparation, configure the phone's loopback VPN endpoint and its
current Remote Pairing service port in Mirage. A pairing success over USB does
not prove the later loopback tunnel or location service works.

### External loopback and service discovery

This is a source-backed setup candidate, unexecuted on the owner's phone.
[LocalDevVPN](https://github.com/jkcoxson/LocalDevVPN) links its
[official App Store listing](https://apps.apple.com/us/app/localdevvpn/id6755608044).
On the phone, install/open it, tap Connect and approve the requested VPN
configuration. Keep Wi-Fi enabled, as required by the
[StikDebug setup instructions](https://github.com/StikDebug/StikDebug).
In LocalDevVPN Settings → Network Configuration, use **Device IP** as Mirage's
host, omitting the subnet suffix. The upstream
[constants](https://github.com/jkcoxson/LocalDevVPN/blob/main/LocalDevVPN/Constants.swift)
default to Device IP `10.7.0.1/32` and Tunnel IP `10.7.1.1/32`; Mirage needs
`10.7.0.1`, not the tunnel's other address. Respect the actual installed settings.

With the intended phone and Mac on the same local Wi-Fi network, use Bonjour to
find the current Remote Pairing port. The service type is used by
[StikDebug's discovery code](https://github.com/StikDebug/StikDebug/blob/main/StikDebug/Device/JITEnableContext.swift);
[Apple's Bonjour guidance](https://developer.apple.com/forums/thread/687897)
documents the `dns-sd` browse/resolve commands:

```sh
dns-sd -B _remotepairing._tcp. local.
```

Note the intended iPhone's service instance, then press Control-C. Resolve that
exact instance (replace the placeholder while retaining quotes):

```sh
dns-sd -L 'YOUR_IPHONE_SERVICE_INSTANCE' _remotepairing._tcp. local.
```

Read the resolved port, then press Control-C. Enter that port in Mirage beside
LocalDevVPN's Device IP. Do not use a similarly named phone or assume that the
example port `49152` has been discovered. If no service appears or the intended
device cannot be identified, the endpoint prerequisite is unresolved. A VPN
connected indicator and a Bonjour resolution are preliminary checks only; the
physical matrix must still verify RemotePairing, DVT output and restoration on
iPhone 16 Pro Max / iOS 26.6.1. No VPN installation or owner-device connection was
performed by this project.

The app supplies an app-private, excluded-from-backup, protected local pairing
file. It must never be included in saved-plan exports. Swift reads at most 1 MiB,
passes bytes directly, and overwrites its temporary Data buffer. The Rust input
copy and parser's temporary private seed use `Zeroizing`; the parsed pairing
record remains in process memory as needed for reconnection. Neither credentials
nor upstream error text are logged. Both `tracing/max_level_off` and
`tracing/release_max_level_off` disable all upstream tracing macros at compile
time, including credential formatting; this is checked by a host test. This is
not a claim that every internal crypto/protocol allocation is zeroized on drop.

## Build and Xcode integration

On a Mac with full Xcode and its iOS SDK, Perl, and make, install Rust 1.90.0 with
rustup and the chosen target. From the repository root:

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

`openssl` 0.10.81 with its `vendored` feature locks `openssl-src`
300.6.1+3.6.3, which builds OpenSSL 3.6.3 as static libraries. The build script
exports the selected `SDKROOT`, `IPHONEOS_DEPLOYMENT_TARGET=17.0`,
`OPENSSL_NO_VENDOR=0`, and `OPENSSL_STATIC=1`. The locked openssl-src build code
maps `aarch64-apple-ios` to `ios64-cross`, supports both simulator targets, and
derives OpenSSL's `CROSS_TOP`/`CROSS_SDK` from the compiler's iOS sysroot. The final
Rust static archive incorporates its native static dependency objects; the Xcode
target should not link a host OpenSSL dylib. The actual iOS build/link passed in
the exact-source CI run recorded below.

For the explicit device build configuration with `SRCROOT` at `ios/`:

| Setting | Value to append |
| --- | --- |
| `SWIFT_ACTIVE_COMPILATION_CONDITIONS` | `MIRAGE_DEVICE_BRIDGE` |
| `SWIFT_INCLUDE_PATHS` | `$(SRCROOT)/DeviceBridge/include` |
| `HEADER_SEARCH_PATHS` | `$(SRCROOT)/DeviceBridge/include` |
| `LIBRARY_SEARCH_PATHS` | `$(SRCROOT)/DeviceBridge/build/aarch64-apple-ios` |
| `OTHER_LDFLAGS` | `-lmirage_device_bridge -framework Security -framework SystemConfiguration` |

Include these notices in a distributed app's resources, resolve the recorded
notice provenance gaps, and verify the final archive's native linker
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
archive was subsequently decoded and verified before use. The first CI compile
failed because the DVT location module also requires the `location_simulation`
Cargo feature. That feature was added without changing the lock graph.
[CI run 40432134888](https://github.com/aviterima/Mirage/actions/runs/40432134888)
passed all **4 Rust host tests**, the **ARM64 iPhone native archive build** and
the **iPhone app compile/link with the native bridge enabled** at `88c0cb4`.
That run predates the Remote Pairing replacement and cannot validate its code.
The current bridge has **14 host tests**, including synthetic XML/binary records,
key/type/length validation, mixed legacy rejection, static tracing suppression,
fixed prerequisite errors, exact PSK callback lengths, matching and mismatched
PSK handshakes through in-memory duplex streams, malformed/truncated tunnel
frames, bounded fields, and fragmented framing that preserves subsequent bytes.
All **14 host tests**, the ARM64 archive and the unsigned device app compile/link
passed at `08e4ab19ed80b350ca84c182920be145109aeed7` in
[run 40434352309](https://github.com/aviterima/Mirage/actions/runs/40434352309).
The [top-level iPhone validation record](../../docs/IPHONE_VALIDATION_2026-10-26.md)
retains exact artifacts and the separate simulator test results. No host test
exercises a real device. Signing and physical iPhone checks require their
separately recorded results.
