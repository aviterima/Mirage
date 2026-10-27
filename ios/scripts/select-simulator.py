"""Prefer the owner's iPhone model and record the actual available runtime."""
import json
from pathlib import Path
import re
import sys

inventory = json.loads(Path(sys.argv[1]).read_text())
candidates = []
for runtime, devices in inventory['devices'].items():
    if '.iOS-' not in runtime:
        continue
    version = tuple(int(n) for n in re.findall(r'\d+', runtime.split('.iOS-')[1]))
    for device in devices:
        if device.get('isAvailable') and 'iPhone' in device['name']:
            candidates.append((device['name'] == 'iPhone 16 Pro Max', version, runtime, device))
if not candidates:
    raise SystemExit('No available iPhone simulator; physical acceptance is not substituted.')
matched, _, runtime, device = max(candidates, key=lambda row: (row[0], row[1]))
record = {'name': device['name'], 'runtime': runtime, 'udid': device['udid'],
          'requestedModel': 'iPhone 16 Pro Max', 'modelMatched': matched,
          'physicalTargetOS': '26.6.1', 'physicalAcceptance': False}
Path(sys.argv[2]).write_text(json.dumps(record, indent=2) + '\n')
print(device['udid'])
