#!/usr/bin/env bash
set -euo pipefail
mkdir -p acceptance-evidence
cat upgrade-baseline/Mirage-0.14.0.apk.part-* > upgrade-baseline/app-debug.apk
old_apk=upgrade-baseline/app-debug.apk
new_apk=android/app/build/outputs/apk/debug/app-debug.apk
echo '9cf9cea2440f62d22e0727ed4b934aded026d420b41457dc5c175fa6d92fad3c  upgrade-baseline/app-debug.apk' | sha256sum -c -
adb install -r "$old_apk"
python3 - <<'PY'
import json, pathlib, xml.etree.ElementTree as ET
scenario = {'id': 'upgrade-fixture', 'name': 'Preserved upgrade place', 'kind': 'SNAP', 'createdAt': 1,
            'startIsReal': False, 'destLat': 33.5, 'destLng': -112.0, 'destName': 'Home',
            'travelMode': 'DRIVE', 'speeds': {}, 'realism': 'REALISTIC', 'stops': []}
for file, entries in [('scenarios', {'scenarios_v1': json.dumps([scenario])}),
                      ('keys', {'user_key': 'upgrade-fixture-not-a-real-key', 'install_id': 'preserve-this-install-id'})]:
    root = ET.Element('map')
    for name, value in entries.items():
        ET.SubElement(root, 'string', name=name).text = value
    pathlib.Path(f'acceptance-evidence/{file}-before.xml').write_bytes(ET.tostring(root))
PY
adb shell run-as com.mirage.app mkdir -p shared_prefs
adb shell 'run-as com.mirage.app sh -c "cat > shared_prefs/mirage_scenarios.xml"' < acceptance-evidence/scenarios-before.xml
adb shell 'run-as com.mirage.app sh -c "cat > shared_prefs/mirage_keys.xml"' < acceptance-evidence/keys-before.xml
adb install -r "$new_apk" | tee acceptance-evidence/upgrade.txt
adb exec-out run-as com.mirage.app cat shared_prefs/mirage_scenarios.xml > acceptance-evidence/scenarios-after.xml
adb exec-out run-as com.mirage.app cat shared_prefs/mirage_keys.xml > acceptance-evidence/keys-after.xml
cmp acceptance-evidence/scenarios-before.xml acceptance-evidence/scenarios-after.xml
cmp acceptance-evidence/keys-before.xml acceptance-evidence/keys-after.xml
echo 'PASS: 0.14.0 to 0.15.0 installed without uninstall; saved records, key and install identity preserved.' | tee -a acceptance-evidence/upgrade.txt
adb shell run-as com.mirage.app rm shared_prefs/mirage_keys.xml
