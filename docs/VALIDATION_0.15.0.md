# Mirage 0.15.0 validation

Validated October 8, 2026 UTC. Version name 0.15.0; version code 38. Source commit `43d148a132bc0ee1d098ad0a8f2b72fd8eefa5ed` on `codex/saved-destinations-indoor-arrival-20261006`.

## Build evidence

- [Android build, JVM tests, blocking lint and signing check](https://github.com/aviterima/Mirage/actions/runs/37804135648): passed.
- [Emulator acceptance, signed upgrade and live trail-provider check](https://github.com/aviterima/Mirage/actions/runs/37804135635): passed after retrying the live-provider job.
- **146 JVM tests passed**, with zero failures, errors or skips.
- **27 Android emulator tests passed**, with zero failures, errors or skips (Android 14 / API 34, Pixel 7 Pro profile).
- **Blocking lint passed.** The existing saved-library state-observation error was fixed; new lint errors now block distribution.
- The live-provider job initially failed with two HTTP 504 responses. Only that job was retried; its next request passed and returned five Echo Canyon map elements. The successful app-test and APK artifacts were preserved unchanged.

## Hiking behavior covered

Nineteen hiking JVM tests cover exact requested mileage, turning around without shortcuts, full and partial loops, rejecting disconnected or branching geometry, orienting the trail toward parking, drive/park/walk/hike/return playback, stationary parking, invalid mileage, failed route connections, cancellation and stale callbacks, preserving earlier draft stops, saving exact geometry, unsaved-state correctness, and repeated recovery on the return half of an out-and-back route. A real Echo Canyon provider response is also parsed and exercised.

Three new emulator journeys verify trail-name entry, displayed mapped length, parking selection, custom miles, preview totals, explicit confirmation, invalid mileage, cancellation and the live Add stop entry point. Existing journeys cover saved sources, large text, live-map clearance, paused continuation, save/update/reload, contextual voice, native offline model inference and Stop.

The hiking preview screenshot was visually inspected. Trail miles, additional driving/walking distance, parking time and the return-to-car behavior are readable; confirmation is reachable by scrolling. UI journeys use deterministic provider fixtures. Live Google driving/walking directions were not exercised by the new tests; their integration uses the existing routing client and is covered with deterministic route results.

## Data and network scope

Trail search uses named OpenStreetMap paths and hiking/foot relations through the VK Maps Overpass endpoint. A live query for Echo Canyon is checked separately from deterministic tests, with the response retained as evidence. Public trail services returned intermittent timeouts during development; the app reports failure and offers manual retry, with a cooldown for busy responses. Missing mapped parking can be replaced with a user-placed parking pin.

Mapped lengths are geometry estimates. A named path can be only part of an advertised trail; the interface labels this explicitly. Disconnected/branching paths and nested relations are not inferred. Real parking access, trail access and Google driving/walking connectivity require review. Physical-phone validation remains outstanding.

## Upgrade and APK

The pinned signing identity passed verification. Installing over the verified 0.14.0 APK succeeded without uninstalling. Saved fixture records, API-key preference and installation identity were preserved byte-for-byte.

The delivered APK was reconstructed from the acceptance run's unchanged transport parts. Its SHA-256 matches that run's `SHA256.txt`; ZIP integrity passed.

- Filename: `Mirage-0.15.0.apk`
- Size: **93,767,220 bytes**
- SHA-256: `eb62e6cee550df8b6b41307ddf6e98ff520d4f662cc92b7c09bb83cde9b2c74a`
- Signing certificate SHA-256: `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8`

Install over the existing app to retain saved content. No uninstall is required.

## Quick start

1. Install the update over Mirage 0.14.0 without uninstalling.
2. Tap **Hike**, or **Add stop → Hiking trail** during a simulation.
3. Search by trail name. For another region, find and choose a city or park first.
4. Review the mapped length and select mapped parking or place a parking pin.
5. Enter total on-trail miles, including the return, and preview the trip.
6. Confirm the itinerary and tap Start, or confirm when to add it to the active trip.

Driving and the walk between parking and the trail are additional to the requested hiking miles. The simulation finishes back at the car.
