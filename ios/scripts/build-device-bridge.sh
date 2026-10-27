#!/usr/bin/env bash
set -euo pipefail

# Only builds the native library. Does not sign, install, pair, start a VPN,
# mount developer images, connect to an iPhone, or publish an artifact.
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
bridge_dir="$(cd -- "$script_dir/../DeviceBridge" && pwd)"
target="${1:-aarch64-apple-ios}"
case "$target" in
  aarch64-apple-ios) sdk=iphoneos ;;
  aarch64-apple-ios-sim|x86_64-apple-ios) sdk=iphonesimulator ;;
  *) printf '%s\n' 'Expected aarch64-apple-ios, aarch64-apple-ios-sim or x86_64-apple-ios.' >&2; exit 2 ;;
esac

if [[ "$(uname -s)" != Darwin ]]; then
  printf '%s\n' 'A Mac with the full Xcode iOS SDK is required.' >&2
  exit 2
fi
for command in cargo rustup xcrun xcodebuild perl make; do
  command -v "$command" >/dev/null || { printf 'Missing required tool: %s\n' "$command" >&2; exit 2; }
done
rustup target list --toolchain 1.90.0 --installed | grep -Fx "$target" >/dev/null || {
  printf 'Install the requested Rust target first: rustup target add --toolchain 1.90.0 %s\n' "$target" >&2
  exit 2
}
sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
[[ -d "$sdk_path" ]] || { printf '%s\n' 'Xcode iOS SDK is unavailable.' >&2; exit 2; }
[[ -f "$bridge_dir/Cargo.lock" ]] || {
  printf '%s\n' 'Cargo.lock is required. Generate/review it with cargo generate-lockfile --manifest-path ios/DeviceBridge/Cargo.toml, then commit it before release.' >&2
  exit 2
}

export IPHONEOS_DEPLOYMENT_TARGET=17.0
export SDKROOT="$sdk_path"
# openssl-src 300.6.1+3.6.3 supports all targets above and obtains its iOS
# CROSS_TOP/CROSS_SDK from cc's SDK sysroot. Force the locked vendored static
# build so a developer's unrelated OPENSSL_NO_VENDOR setting cannot select a
# host or system OpenSSL library instead.
export OPENSSL_NO_VENDOR=0
export OPENSSL_STATIC=1
export BINDGEN_EXTRA_CLANG_ARGS="--sysroot=$sdk_path"
export CARGO_TARGET_DIR="$bridge_dir/target"
cargo +1.90.0 build --manifest-path "$bridge_dir/Cargo.toml" --locked --release --target "$target"
output_dir="$bridge_dir/build/$target"
mkdir -p "$output_dir"
cp "$bridge_dir/target/$target/release/libmirage_device_bridge.a" "$output_dir/"
cp "$bridge_dir/include/MirageDeviceBridge.h" "$bridge_dir/include/module.modulemap" "$output_dir/"
shasum -a 256 "$output_dir/libmirage_device_bridge.a"
printf 'Built native device bridge at %s\n' "$output_dir"
