#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$(cd "$ROOT/.." && pwd)"
TESTED_SOURCE=08e4ab19ed80b350ca84c182920be145109aeed7
BRIDGE_SHA=735f1c5e16cba094860b71a8032e7f8f9cd06a9ab7668ecd2634fcbbd5364de8
BRIDGE="$ROOT/DeviceBridge/build/aarch64-apple-ios/libmirage_device_bridge.a"
SOURCE_PATHS=(ios/MirageApp ios/MirageCore ios/DeviceBridge/src ios/DeviceBridge/include
  ios/DeviceBridge/Cargo.toml ios/DeviceBridge/Cargo.lock ios/DeviceBridge/THIRD-PARTY-NOTICES.txt
  ios/project.yml ios/scripts/build-device-app.sh ios/scripts/generate-project.sh)

[[ "$(uname -s)" == Darwin ]] || { echo 'Packaging requires macOS.' >&2; exit 1; }
git -C "$REPO" diff --exit-code "$TESTED_SOURCE" HEAD -- "${SOURCE_PATHS[@]}"
git -C "$REPO" diff --exit-code HEAD -- "${SOURCE_PATHS[@]}"
[[ "$(shasum -a 256 "$BRIDGE" | awk '{print $1}')" == "$BRIDGE_SHA" ]] || {
  echo 'Native library does not match verified run 40434352309.' >&2; exit 1;
}
[[ "${1:-}" != --verify-inputs ]] || exit 0
[[ $# == 0 ]] || { echo 'Usage: package-device-app.sh [--verify-inputs]' >&2; exit 1; }

APP="$ROOT/build/DeviceDerivedData/Build/Products/Debug-iphoneos/Mirage.app"
PLIST="$APP/Info.plist"
EXECUTABLE="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleExecutable' "$PLIST")"
[[ -n "$EXECUTABLE" && "$EXECUTABLE" != */* && "$EXECUTABLE" != . && "$EXECUTABLE" != .. ]]
[[ -x "$APP/$EXECUTABLE" ]]
[[ "$(xcrun lipo -archs "$APP/$EXECUTABLE")" == arm64 ]]
xcrun vtool -show-build "$APP/$EXECUTABLE" | grep -Eq 'platform[[:space:]]+IOS[[:space:]]*$'
[[ ! -e "$APP/embedded.mobileprovision" && ! -d "$APP/_CodeSignature" ]]
if codesign --display "$APP" >/dev/null 2>&1; then
  echo 'Expected an unsigned app; refusing to label a signed bundle unsigned.' >&2
  exit 1
fi

OUT="$ROOT/build/Package"
mkdir -p "$OUT"
IPA="$OUT/Mirage-iPhone-0.1.0-unsigned.ipa"
[[ ! -e "$IPA" ]] || { echo 'Package already exists; use a fresh build directory.' >&2; exit 1; }
STAGE="$(mktemp -d "$ROOT/build/ipa-stage.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT
mkdir -p "$STAGE/Payload"
# Copy the entire Debug bundle, including debug/preview dylibs and executable modes.
ditto "$APP" "$STAGE/Payload/Mirage.app"
ditto -c -k --norsrc --keepParent "$STAGE/Payload" "$IPA"
unzip -tq "$IPA"

python3 - "$REPO" "$APP" "$IPA" "$TESTED_SOURCE" "$BRIDGE_SHA" <<'PY'
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import plistlib
import stat
import subprocess
import sys
import zipfile

repo, app, ipa = map(Path, sys.argv[1:4])
tested_source, bridge_sha = sys.argv[4:6]
def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()
def command(*args):
    return subprocess.check_output(args, cwd=repo, text=True).strip()

with (app / 'Info.plist').open('rb') as stream:
    info = plistlib.load(stream)
assert info['CFBundlePackageType'] == 'APPL'
assert info['CFBundleIdentifier'] == 'com.mirage.iphone'
assert info['CFBundleSupportedPlatforms'] == ['iPhoneOS']
assert info['DTPlatformName'] == 'iphoneos'
assert info['UIDeviceFamily'] == [1]
assert info['CFBundleShortVersionString'] == '0.1.0'
prefix = 'Payload/Mirage.app/'
bundle_files = {}
with zipfile.ZipFile(ipa) as archive:
    assert archive.testzip() is None
    for entry in archive.infolist():
        path = PurePosixPath(entry.filename)
        assert not path.is_absolute() and '..' not in path.parts
        assert entry.filename == 'Payload/' or entry.filename.startswith(prefix)
    for path in sorted(app.rglob('*')):
        if path.is_file():
            relative = path.relative_to(app).as_posix()
            entry = archive.getinfo(prefix + relative)
            digest = hashlib.sha256(archive.read(entry)).hexdigest()
            assert digest == sha(path), relative
            if path.stat().st_mode & stat.S_IXUSR:
                assert (entry.external_attr >> 16) & stat.S_IXUSR, relative
            bundle_files[relative] = digest
    executable = archive.getinfo(prefix + info['CFBundleExecutable'])
    assert (executable.external_attr >> 16) & stat.S_IXUSR

source_paths = [
    'ios/MirageApp', 'ios/MirageCore', 'ios/DeviceBridge/src',
    'ios/DeviceBridge/include', 'ios/DeviceBridge/Cargo.toml',
    'ios/DeviceBridge/Cargo.lock', 'ios/DeviceBridge/THIRD-PARTY-NOTICES.txt',
    'ios/project.yml', 'ios/scripts/build-device-app.sh', 'ios/scripts/generate-project.sh',
]
manifest = {
    'created_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
    'package': ipa.name,
    'package_sha256': sha(ipa),
    'source_commit': command('git', 'rev-parse', 'HEAD'),
    'tested_source_commit': tested_source,
    'source_objects': {p: command('git', 'rev-parse', 'HEAD:' + p) for p in source_paths},
    'native_library': {
        'sha256': bridge_sha,
        'artifact': 'iphone-device-build-evidence',
        'workflow_run': 40434352309,
        'source_commit': tested_source,
        'downloaded_artifact_zip_sha256': 'd935061c4fe5574e471a2cbbbdfb7739246210088dff5ac37cae4d2c49419eeb',
    },
    'package_workflow_run': os.environ.get('GITHUB_RUN_ID'),
    'repository': os.environ.get('GITHUB_REPOSITORY'),
    'xcode': command('xcodebuild', '-version'),
    'swift': command('swift', '--version'),
    'bundle_identifier': info['CFBundleIdentifier'],
    'version': info['CFBundleShortVersionString'],
    'build': info['CFBundleVersion'],
    'platform': 'iphoneos',
    'architecture': 'arm64',
    'configuration': 'Debug with MIRAGE_DEVICE_BRIDGE',
    'signing': 'unsigned; must be signed and provisioned before installation',
    'hardware_installation_verified': False,
    'device_output_verified': False,
    'bundle_file_sha256': bundle_files,
}
manifest_path = ipa.parent / 'source-manifest.json'
manifest_path.write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
(ipa.parent / 'SHA256SUMS.txt').write_text(
    ''.join(f'{sha(path)}  {path.name}\n' for path in [ipa, manifest_path]), encoding='utf-8'
)
print(f'Unsigned engineering IPA: {ipa}')
print(f'SHA256: {sha(ipa)}')
PY
