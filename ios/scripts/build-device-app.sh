#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
test -f "$ROOT/DeviceBridge/build/aarch64-apple-ios/libmirage_device_bridge.a"
mkdir -p "$ROOT/build"
xcodebuild -project "$ROOT/Mirage.xcodeproj" -scheme Mirage \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath "$ROOT/build/DeviceDerivedData" \
  CODE_SIGNING_ALLOWED=NO \
  'SWIFT_ACTIVE_COMPILATION_CONDITIONS=$(inherited) MIRAGE_DEVICE_BRIDGE' \
  "SWIFT_INCLUDE_PATHS=\$(inherited) \"$ROOT/DeviceBridge/include\"" \
  "HEADER_SEARCH_PATHS=\$(inherited) \"$ROOT/DeviceBridge/include\"" \
  "LIBRARY_SEARCH_PATHS=\$(inherited) \"$ROOT/DeviceBridge/build/aarch64-apple-ios\"" \
  'OTHER_LDFLAGS=$(inherited) -lmirage_device_bridge -framework Security -framework SystemConfiguration' \
  build 2>&1 | tee "$ROOT/build/device-build.log"
