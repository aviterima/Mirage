#!/usr/bin/env bash
set -euo pipefail
# Hosted macOS entry point. No Apple login, device pairing or public publishing.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$ROOT/.." && pwd)"
MODE="${1:-}"
if [[ "$MODE" == --dry-run && $# == 1 ]]; then
  METHOD=dry-run
elif [[ "$MODE" == --method && $# == 2 && ( "$2" == release-testing || "$2" == app-store-connect ) ]]; then
  METHOD="$2"
else
  echo 'Usage: archive-signed-app.sh --dry-run | --method release-testing|app-store-connect' >&2
  exit 2
fi
[[ "$(uname -s)" == Darwin ]] || { echo 'A hosted macOS build runner is required.' >&2; exit 2; }
for tool in xcodebuild xcrun security codesign openssl python3; do
  command -v "$tool" >/dev/null || { echo 'A required Apple signing/build tool is missing.' >&2; exit 2; }
done
BRIDGE="$ROOT/DeviceBridge/build/aarch64-apple-ios/libmirage_device_bridge.a"
[[ -f "$BRIDGE" && -d "$ROOT/Mirage.xcodeproj" ]] || { echo 'Generate the project and build the device bridge first.' >&2; exit 2; }
SOURCE="$(git -C "$REPO" rev-parse HEAD)"
git -C "$REPO" diff --quiet HEAD -- ios || { echo 'Archive requires unchanged checked-out source.' >&2; exit 2; }
[[ -z "$(git -C "$REPO" ls-files --others --exclude-standard -- ios)" ]] || {
  echo 'Untracked iOS input files would invalidate source provenance.' >&2; exit 2;
}
BUILD_NUMBER="${GITHUB_RUN_NUMBER:-1}"
[[ "$BUILD_NUMBER" =~ ^[1-9][0-9]*$ ]] || { echo 'A positive numeric build number is required.' >&2; exit 2; }
OUT="$ROOT/build/Distribution"
[[ ! -e "$OUT" ]] || { echo 'Distribution output exists; use a fresh build workspace.' >&2; exit 2; }
mkdir -p "$OUT"
umask 077
PRIVATE="$(mktemp -d "${RUNNER_TEMP:-${TMPDIR:-/tmp}}/mirage-signing.XXXXXX")"
KEYCHAIN=""
PROFILE_DESTINATION=""
PROFILE_INSTALLED=0
cleanup() {
  set +e
  if [[ -f "$PRIVATE/keychains.json" ]]; then
    python3 - "$PRIVATE/keychains.json" <<'PY'
import json, subprocess, sys
subprocess.run(['security', 'list-keychains', '-d', 'user', '-s', *json.load(open(sys.argv[1]))], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
PY
  fi
  [[ -z "$KEYCHAIN" ]] || security delete-keychain "$KEYCHAIN" >/dev/null 2>&1
  if [[ "$PROFILE_INSTALLED" == 1 ]]; then rm -f -- "$PROFILE_DESTINATION"; fi
  # PRIVATE comes directly from mktemp, never from a user-supplied path.
  rm -rf -- "$PRIVATE"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ARCHIVE="$OUT/Mirage.xcarchive"
COMMON=( -project "$ROOT/Mirage.xcodeproj" -scheme Mirage -configuration Release
  -sdk iphoneos -destination 'generic/platform=iOS' -archivePath "$ARCHIVE"
  -derivedDataPath "$PRIVATE/DerivedData"
  'SWIFT_ACTIVE_COMPILATION_CONDITIONS=$(inherited) MIRAGE_DEVICE_BRIDGE'
  "SWIFT_INCLUDE_PATHS=\$(inherited) \"$ROOT/DeviceBridge/include\""
  "HEADER_SEARCH_PATHS=\$(inherited) \"$ROOT/DeviceBridge/include\""
  "LIBRARY_SEARCH_PATHS=\$(inherited) \"$ROOT/DeviceBridge/build/aarch64-apple-ios\""
  'OTHER_LDFLAGS=$(inherited) -lmirage_device_bridge -framework Security -framework SystemConfiguration'
  INFOPLIST_KEY_MirageNativeDeviceBridge=enabled "CURRENT_PROJECT_VERSION=$BUILD_NUMBER"
  STRIP_INSTALLED_PRODUCT=NO COPY_PHASE_STRIP=NO )
if [[ "$METHOD" == dry-run ]]; then
  xcodebuild "${COMMON[@]}" CODE_SIGNING_ALLOWED=NO archive > "$OUT/dry-run-archive.log" 2>&1 || {
    tail -n 80 "$OUT/dry-run-archive.log"; exit 1;
  }
  APP="$ARCHIVE/Products/Applications/Mirage.app"
  [[ ! -e "$APP/embedded.mobileprovision" && ! -d "$APP/_CodeSignature" ]]
  if codesign --display "$APP" >/dev/null 2>&1; then echo 'Dry-run archive unexpectedly signed.' >&2; exit 1; fi
  python3 - "$APP" "$OUT/dry-run-validation.json" "$SOURCE" "$BRIDGE" <<'PY'
import hashlib, json, pathlib, plistlib, re, subprocess, sys
app, output = map(pathlib.Path, sys.argv[1:3])
info = plistlib.loads((app/'Info.plist').read_bytes())
assert info['CFBundleIdentifier'] == 'com.mirage.iphone'
assert info['CFBundleSupportedPlatforms'] == ['iPhoneOS']
assert info['MirageNativeDeviceBridge'] == 'enabled'
exe = app / info['CFBundleExecutable']
assert subprocess.check_output(['xcrun','lipo','-archs',str(exe)], text=True).strip() == 'arm64'
symbols = subprocess.check_output(['nm','-g',str(exe)], text=True)
for symbol in ('create','connect','set','clear','destroy'):
    assert re.search(r'\b_mirage_device_' + symbol + r'\s*$', symbols, re.M)
record = {'schema':1, 'source_commit':sys.argv[3], 'configuration':'Release', 'native_device_bridge_verified':True,
          'bridge_sha256':hashlib.sha256(pathlib.Path(sys.argv[4]).read_bytes()).hexdigest(),
          'signature_verified':False, 'export_performed':False, 'physical_installation_verified':False,
          'device_output_verified':False, 'ready_for_recipient':False}
output.write_text(json.dumps(record, indent=2)+'\n')
PY
  echo 'Unsigned Release archive validated. No installable signed package was produced.'
  exit 0
fi
for name in MIRAGE_IOS_CERTIFICATE_P12_BASE64 MIRAGE_IOS_CERTIFICATE_PASSWORD MIRAGE_IOS_PROFILE_BASE64 MIRAGE_IOS_TEAM_ID; do
  [[ -n "${!name:-}" ]] || { echo 'Required Apple signing secret is missing.' >&2; exit 2; }
done
if [[ "$METHOD" == release-testing ]]; then
  [[ -n "${MIRAGE_IOS_RECIPIENT_UDID:-}" ]] || { echo 'Registered recipient device is required.' >&2; exit 2; }
fi
python3 - "$PRIVATE" <<'PY'
import base64, os, pathlib, sys
folder=pathlib.Path(sys.argv[1])
try:
    for name, filename in [('MIRAGE_IOS_CERTIFICATE_P12_BASE64','certificate.p12'), ('MIRAGE_IOS_PROFILE_BASE64','profile.mobileprovision')]:
        (folder/filename).write_bytes(base64.b64decode(os.environ[name], validate=True))
except Exception:
    raise SystemExit('Invalid encoded signing secret.')
PY
openssl pkcs12 -in "$PRIVATE/certificate.p12" -clcerts -nokeys \
  -passin env:MIRAGE_IOS_CERTIFICATE_PASSWORD -out "$PRIVATE/certificate.pem" > "$PRIVATE/openssl.log" 2>&1 || {
  echo 'Could not decode signing certificate.' >&2; exit 1;
}
openssl x509 -in "$PRIVATE/certificate.pem" -checkend 0 -noout >/dev/null || { echo 'Signing certificate expired.' >&2; exit 1; }
openssl x509 -in "$PRIVATE/certificate.pem" -outform der -out "$PRIVATE/certificate.der"
security cms -D -i "$PRIVATE/profile.mobileprovision" > "$PRIVATE/profile.plist" 2> "$PRIVATE/profile.log" || {
  echo 'Could not decode provisioning profile.' >&2; exit 1;
}
python3 "$ROOT/scripts/verify-signed-app.py" prepare --profile "$PRIVATE/profile.plist" \
  --certificate "$PRIVATE/certificate.der" --team "$MIRAGE_IOS_TEAM_ID" --method "$METHOD" --output "$PRIVATE/ExportOptions.plist"
IDENTITY="$(python3 -c 'import hashlib,sys; print(hashlib.sha1(open(sys.argv[1],"rb").read()).hexdigest().upper())' "$PRIVATE/certificate.der")"
UUID="$(/usr/libexec/PlistBuddy -c 'Print :UUID' "$PRIVATE/profile.plist")"
PROFILE_FOLDER="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
mkdir -p "$PROFILE_FOLDER"
PROFILE_DESTINATION="$PROFILE_FOLDER/$UUID.mobileprovision"
[[ ! -e "$PROFILE_DESTINATION" ]] || { echo 'Refusing to overwrite an existing provisioning profile.' >&2; exit 1; }
cp "$PRIVATE/profile.mobileprovision" "$PROFILE_DESTINATION"
PROFILE_INSTALLED=1
KEYCHAIN="$PRIVATE/signing.keychain-db"
KEYCHAIN_PASSWORD="$(openssl rand -hex 32)"
security create-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN" >/dev/null 2>&1
security set-keychain-settings -lut 21600 "$KEYCHAIN" >/dev/null 2>&1
security unlock-keychain -p "$KEYCHAIN_PASSWORD" "$KEYCHAIN" >/dev/null 2>&1
python3 - "$PRIVATE/keychains.json" "$KEYCHAIN" <<'PY'
import json, pathlib, shlex, subprocess, sys
existing=shlex.split(subprocess.check_output(['security','list-keychains','-d','user'], text=True))
pathlib.Path(sys.argv[1]).write_text(json.dumps(existing))
subprocess.run(['security','list-keychains','-d','user','-s',sys.argv[2],*existing], check=True)
PY
security import "$PRIVATE/certificate.p12" -k "$KEYCHAIN" -P "$MIRAGE_IOS_CERTIFICATE_PASSWORD" \
  -T /usr/bin/codesign -T /usr/bin/security >/dev/null 2>&1
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$KEYCHAIN_PASSWORD" "$KEYCHAIN" >/dev/null 2>&1
xcodebuild "${COMMON[@]}" CODE_SIGN_STYLE=Manual "DEVELOPMENT_TEAM=$MIRAGE_IOS_TEAM_ID" \
  "CODE_SIGN_IDENTITY=$IDENTITY" "PROVISIONING_PROFILE_SPECIFIER=$UUID" \
  "OTHER_CODE_SIGN_FLAGS=--keychain $KEYCHAIN" archive > "$PRIVATE/archive.log" 2>&1 || {
  echo 'Signed archive failed. Private signing logs have not been published.' >&2; exit 1;
}
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportPath "$OUT/Export" \
  -exportOptionsPlist "$PRIVATE/ExportOptions.plist" > "$PRIVATE/export.log" 2>&1 || {
  echo 'Signed export failed. Private signing logs have not been published.' >&2; exit 1;
}
[[ -f "$OUT/Export/Mirage.ipa" ]] || { echo 'Exported IPA is missing.' >&2; exit 1; }
python3 "$ROOT/scripts/verify-signed-app.py" verify --ipa "$OUT/Export/Mirage.ipa" \
  --certificate "$PRIVATE/certificate.der" --team "$MIRAGE_IOS_TEAM_ID" --method "$METHOD" \
  --source "$SOURCE" --output "$OUT/signing-validation.json"
echo 'Signed package verified. Keep archive/export private; physical acceptance and recipient delivery remain pending.'
