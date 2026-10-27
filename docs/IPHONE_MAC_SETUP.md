# Mirage iPhone proof — conditional macOS build-host reference

Owner target: iPhone 16 Pro Max; the supplied About screenshot shows iOS 26.6.1. The owner corrected the hardware context on 27 October 2026: no Mac; available devices are an iPad and a Windows Surface. These local Xcode instructions do not describe the owner's equipment or their next step. A signing, installation and device-preparation route compatible with that equipment remains to be established. Hosted macOS CI does not imply owner Mac access. The signed installation, Remote Pairing preparation and physical output checks below have **not** been performed by this project. Passing CI does not establish compatibility with that phone/OS.

## Optional macOS host prerequisites

Use full Xcode with an iOS SDK/device-support setup that recognizes the target phone, Xcode command-line tools, Rust/rustup, Git, Perl and make. In Xcode Settings → Accounts, sign in directly to the Apple account that will sign the app. Connect the unlocked iPhone by USB, trust the Mac and enable [Developer Mode](https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device). Keep Apple credentials and device pairing files out of chat and the repository.

Obtain the existing repository's iPhone branch in a new folder on the Mac; do not overwrite an existing checkout:

```sh
git clone --branch codex/iphone-implementation-20261026 \
  https://github.com/aviterima/Mirage.git Mirage-iPhone
cd Mirage-iPhone
rustup toolchain install 1.90.0 --profile minimal
rustup target add --toolchain 1.90.0 aarch64-apple-ios
swift test --package-path ios/MirageCore
cargo +1.90.0 test --locked --manifest-path ios/DeviceBridge/Cargo.toml
bash ios/scripts/generate-project.sh
bash ios/scripts/build-device-bridge.sh aarch64-apple-ios
bash ios/scripts/build-device-app.sh
```

The last command validates the device-enabled app without signing. The ordinary Xcode project opened without the bridge build settings supports preview only. Use the complete device-enabled signing command below for the phone proof.

## Sign and install on the owner's phone

Find the development team in Xcode's Signing & Capabilities panel. Get the intended iPhone's hardware UDID from Xcode's device window or Finder; use that UDID for both commands below, rather than the different CoreDevice UUID displayed by some command-line listings. Set the two placeholders to that UDID and the owner's team ID; neither value is an account password. Apple's [device installation documentation](https://developer.apple.com/documentation/xcode/interacting-with-devices-using-the-command-line) describes `devicectl`, and its [registered-device guidance](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices) covers provisioning.

```sh
MIRAGE_TEAM_ID='YOUR_TEAM_ID'
MIRAGE_PHONE_ID='YOUR_IPHONE_IDENTIFIER'
MIRAGE_IOS_ROOT="$PWD/ios"

xcodebuild -project "$MIRAGE_IOS_ROOT/Mirage.xcodeproj" -scheme Mirage \
  -sdk iphoneos -destination "platform=iOS,id=$MIRAGE_PHONE_ID" \
  -derivedDataPath "$MIRAGE_IOS_ROOT/build/SignedDevice" \
  -allowProvisioningUpdates -allowProvisioningDeviceRegistration \
  DEVELOPMENT_TEAM="$MIRAGE_TEAM_ID" CODE_SIGN_STYLE=Automatic \
  CODE_SIGNING_ALLOWED=YES \
  'SWIFT_ACTIVE_COMPILATION_CONDITIONS=$(inherited) MIRAGE_DEVICE_BRIDGE' \
  "SWIFT_INCLUDE_PATHS=\$(inherited) \"$MIRAGE_IOS_ROOT/DeviceBridge/include\"" \
  "HEADER_SEARCH_PATHS=\$(inherited) \"$MIRAGE_IOS_ROOT/DeviceBridge/include\"" \
  "LIBRARY_SEARCH_PATHS=\$(inherited) \"$MIRAGE_IOS_ROOT/DeviceBridge/build/aarch64-apple-ios\"" \
  'OTHER_LDFLAGS=$(inherited) -lmirage_device_bridge -framework Security -framework SystemConfiguration' \
  build

xcrun devicectl device install app --device "$MIRAGE_PHONE_ID" \
  "$MIRAGE_IOS_ROOT/build/SignedDevice/Build/Products/Debug-iphoneos/Mirage.app"
```

These commands authorize Xcode to register the selected phone with the signing team when needed and manage development provisioning through the signed-in account, then install the app on that phone. If signing, device registration or OS support fails, retain the error and resolve that prerequisite; there is no fallback signed IPA or claimed TestFlight/App Store release.

## Prepare and run the physical proof

Follow the [bridge setup instructions](../ios/DeviceBridge/README.md#external-pairing-and-image-preparation) for USB-created Remote Pairing credentials, the matching cryptex developer image and the phone's loopback tunnel. Those instructions are source-backed and unexecuted here. The endpoint's current port must be confirmed; the sample `10.7.0.1:49152` is not a compatibility guarantee. Import the private Remote Pairing file through Mirage's Setup tab. A classic lockdown plist will be rejected.

The loopback VPN provider, startup and service-port discovery still require validation on this exact phone/OS. Mirage does not include or configure a VPN. The [external loopback setup procedure](../ios/DeviceBridge/README.md#external-loopback-and-service-discovery) documents LocalDevVPN and Mac Bonjour discovery from upstream sources. Those instructions are a setup candidate, not a completed phone check. Do not treat successful signing, USB pairing or developer-image preparation as proof that the on-phone endpoint is ready.

Run the [eight-case device acceptance matrix](IPHONE_PARITY_2026-10-22.md#first-device-proof-and-acceptance-gates). Begin with Connect and a static synthetic test point; independently compare Core Location and Google Maps. Stop, then require a fresh real-location observation. Only proceed to moving routes, interruptions, lock/background and extended sessions after the basic cases pass. Mirage's current driving loop stops in the background and marks output uncertain; full background operation remains unfinished.

Record the exact OS build, signed app source commit, setup versions and observed outcomes. Transport acknowledgements, a successful build or the app's own map marker do not prove other apps used the location. Full planning, hiking, offline dictation and CarPlay remain separate parity work.
