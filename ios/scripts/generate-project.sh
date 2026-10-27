#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PIN=21ac9944b0ab546a07422dbed86f33dd2ebd76f8
TOOL="$ROOT/build/XcodeGen"
if [[ ! -d "$TOOL/.git" ]]; then
  mkdir -p "$ROOT/build"
  git clone --quiet https://github.com/yonaskolb/XcodeGen.git "$TOOL"
fi
git -C "$TOOL" checkout --quiet --detach "$PIN"
test "$(git -C "$TOOL" rev-parse HEAD)" = "$PIN"
swift build --package-path "$TOOL" --configuration release --product xcodegen
"$TOOL/.build/release/xcodegen" generate --spec "$ROOT/project.yml" --project "$ROOT"

