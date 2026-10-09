# Mirage 0.15.1 validation

Validated October 9, 2026. Version code 39; package `com.mirage.app`.

## What changed

- Trail name is optional. Browse near the current real/simulated location, or enter a city/park and browse. A unique area resolves automatically; multiple matches ask for a selection. Named search remains available.
- **+ Add hiking trail** is in the top **Route / Itinerary** planner. Existing stops and an existing route destination are preserved; the hike is appended after them. The live **Add stop → Hiking trail** entry remains available.
- Search results and selected trails show straight-line miles to mapped trail access from the current real or simulated location. The search city and the itinerary endpoint do not replace that reference. The real location refreshes on opening the editor; simulated distances change with simulated movement. Missing location is stated explicitly.
- Trail length and requested hiking mileage are separate from distance to the trail. The preview shows the actual routed drive from the itinerary's last stop, parking time and round-trip parking connection. The hike returns to the car.
- Transient server errors, timeouts and incomplete replies try one backup provider. Failed providers have a one-minute cooldown; complete responses are cached for 15 minutes. Both-server failure remains visible and asks the user to retry.

## Verification

| Check | Result |
| --- | --- |
| JVM tests | 153 passed; 0 failures, errors or skips |
| Android emulator tests | 29 passed; 0 failures, errors or skips |
| Blocking Android lint | Passed |
| APK build and pinned signing identity | Passed |
| In-place upgrade from 0.15.0 | Passed without uninstall; saved records, key preference and installation identity preserved byte-for-byte |
| Live Echo Canyon named search | Passed; 5 map elements |
| Live Tucson browsing with no trail name | Passed; bounded metadata sample and 34 complete geometry elements |
| Live fallback | Primary returned HTTP 504 for Tucson; backup completed both metadata and geometry requests |
| Delivered APK integrity | Reassembled unchanged from acceptance-run parts; SHA-256 matches workflow; ZIP integrity passed; manifest confirms 0.15.1 / code 39 |

New regression coverage exercises blank-name city browsing, city disambiguation, failed city lookup without searching the old area, real/simulated reference selection independent of itinerary endpoints, preserving a route destination before a hike, bounded map-element selection, disconnected sections without fabricated joins, provider fallback and cache, the top planner entry, and distance labels separate from trail length. Existing playback, saving, recovery, continuation and voice tests remain in the suite.

## Evidence and exact source

- Tested source: `10b5ecd53e64680f132feb9781f04ff3f176de93`
- [Build, unit tests and blocking lint](https://github.com/aviterima/Mirage/actions/runs/37922606370), job `113794001359`: success.
- [Emulator acceptance and live trail service](https://github.com/aviterima/Mirage/actions/runs/37922606239), emulator job `113793886905`, service job `113793886567`: success.
- Unit-test XML, emulator XML, upgrade records and screenshots are in that acceptance run's artifacts. The delivered APK comes from its `mirage-tested-apk-part-*` artifacts.

## Practical limits

- Public trail providers can be busy, time out, or rate-limit requests. A previous candidate's live check encountered HTTP 429 after metadata retrieval; the final source's full check passed, including automatic fallback. Fallback improves recovery but does not guarantee availability.
- Browsing returns a limited sample: at most 1,000 lightweight map elements, geometry for at most 80 elements across 20 named groups, and up to 20 displayed trails or sections. It is not an exhaustive catalog. Refine with a park or trail name for more targeted results.
- Mapped named paths may represent only part of an advertised trail. Branching/disconnected ways are displayed only as individually continuous sections. Geometry is not joined across gaps, and distances are estimates from mapped data.
- Emulator trail/route journeys use deterministic provider and routing fixtures. The separate live check covers the public trail service. Live Google Places/Directions and a physical phone were not newly exercised in this release.
- Both reviewed hiking screenshots were obscured by a **Pixel Launcher isn't responding** system dialog in the emulator. The 29 test cases completed successfully, but these captures are not clean visual-validation evidence. No clean visual or physical-phone acceptance is claimed.

## Installer identity

- Filename: `Mirage-0.15.1.apk`
- Size: 93,767,220 bytes
- SHA-256: `09848c451b72fb8742d6a070f543a9be4aabfbc9fd7c0f4fd36613c841b6ddb1`
- Existing signing certificate SHA-256: `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8`

Install over the existing app. Do not uninstall if you want to retain saved itineraries and settings.
