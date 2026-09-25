#!/usr/bin/env bash
set -uo pipefail
mkdir -p acceptance-evidence
cd android || exit 2
set +e
./gradlew --no-daemon connectedDebugAndroidTest
test_result=$?
adb pull /sdcard/Download/mirage-acceptance ../acceptance-evidence/
adb logcat -d -s AndroidRuntime TestRunner > ../acceptance-evidence/logcat.txt
exit "$test_result"
