#!/usr/bin/env bash
set -uo pipefail
mkdir -p acceptance-evidence
cd android || exit 2
set +e
if [ "${MIRAGE_EXPERIMENTAL_MODEL:-false}" = "true" ]; then
  ./gradlew --no-daemon -PexperimentalLanguageModel=true \
    -Pandroid.testInstrumentationRunnerArguments.annotation=com.mirage.spike.ExperimentalLanguageModel \
    connectedDebugAndroidTest
  test_result=$?
else
  # Fail early on paused status, drag order and compact keyboard layout before
  # running all journeys. Keep the established evidence names and JSON schema.
  ./gradlew --no-daemon -PexperimentalLanguageModel=false \
    -Pandroid.testInstrumentationRunnerArguments.class=com.mirage.spike.ContinuationUiAcceptanceTest#addingSnapAfterCurrentStopDoesNotResumePausedSimulation,com.mirage.spike.ContinuationUiAcceptanceTest#savedItineraryAddsAllStopsAndPreservesItsStaysAndOriginal,com.mirage.spike.WorkflowUiAcceptanceTest#dragStopThenSaveAndReloadPreservesNewOrder,com.mirage.spike.PlannerVoiceUiAcceptanceTest#compactPlannerKeepsNextStopAndSearchResultAboveFooterWithKeyboard \
    connectedDebugAndroidTest > ../acceptance-evidence/paused-preflight.log 2>&1
  test_result=$?
  cat ../acceptance-evidence/paused-preflight.log
  python3 - <<'PY'
import json, pathlib, xml.etree.ElementTree as ET
cases = []
for path in pathlib.Path('app/build/outputs/androidTest-results').rglob('*.xml'):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter('testcase'):
        cases.append({'class': case.get('classname'), 'name': case.get('name'),
                      'failure': case.find('failure') is not None,
                      'error': case.find('error') is not None,
                      'skipped': case.find('skipped') is not None})
pathlib.Path('../acceptance-evidence/paused-preflight.json').write_text(json.dumps(cases, indent=2))
expected = {
    ('com.mirage.spike.ContinuationUiAcceptanceTest', 'addingSnapAfterCurrentStopDoesNotResumePausedSimulation'),
    ('com.mirage.spike.ContinuationUiAcceptanceTest', 'savedItineraryAddsAllStopsAndPreservesItsStaysAndOriginal'),
    ('com.mirage.spike.WorkflowUiAcceptanceTest', 'dragStopThenSaveAndReloadPreservesNewOrder'),
    ('com.mirage.spike.PlannerVoiceUiAcceptanceTest', 'compactPlannerKeepsNextStopAndSearchResultAboveFooterWithKeyboard'),
}
assert len(cases) == len(expected) and {(case['class'], case['name']) for case in cases} == expected, 'Focused test selection did not run exactly the four expected journeys'
assert not any(case[key] for case in cases for key in ('failure', 'error', 'skipped')), 'Focused regression checks failed'
PY
  preflight_record_result=$?
  if [ "$preflight_record_result" -ne 0 ]; then test_result="$preflight_record_result"; fi
  if [ "$test_result" -eq 0 ]; then
    ./gradlew --no-daemon -PexperimentalLanguageModel=false \
      -Pandroid.testInstrumentationRunnerArguments.notAnnotation=com.mirage.spike.ExperimentalLanguageModel \
      connectedDebugAndroidTest
    test_result=$?
  fi
fi
adb pull /sdcard/Download/mirage-acceptance ../acceptance-evidence/
adb logcat -d -s AndroidRuntime TestRunner MirageModelTest > ../acceptance-evidence/logcat.txt
adb logcat -b crash -d > ../acceptance-evidence/native-crash.txt
adb shell dumpsys activity exit-info com.mirage.app > ../acceptance-evidence/process-exits.txt
exit "$test_result"
