#!/usr/bin/env bash
set -uo pipefail
mkdir -p acceptance-evidence
cd android || exit 2
set +e
if [ "${MIRAGE_EXPERIMENTAL_MODEL:-false}" = "true" ]; then
  ./gradlew --no-daemon -PexperimentalLanguageModel=true \
    -Pandroid.testInstrumentationRunnerArguments.annotation=com.mirage.spike.ExperimentalLanguageModel \
    connectedDebugAndroidTest
else
  ./gradlew --no-daemon -PexperimentalLanguageModel=false \
    -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.mirage.spike.ExperimentalLanguageModel \
    connectedDebugAndroidTest
fi
test_result=$?
adb pull /sdcard/Download/mirage-acceptance ../acceptance-evidence/
adb logcat -d -s AndroidRuntime TestRunner MirageModelTest > ../acceptance-evidence/logcat.txt
adb logcat -b crash -d > ../acceptance-evidence/native-crash.txt
adb shell dumpsys activity exit-info com.mirage.app > ../acceptance-evidence/process-exits.txt
exit "$test_result"
