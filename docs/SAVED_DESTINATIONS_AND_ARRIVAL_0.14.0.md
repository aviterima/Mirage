# Mirage 0.14.0 — destination and trip enhancements

Status: local implementation candidate, not a compiled, tested, or released APK. Version code 37. Branch: `codex/saved-destinations-indoor-arrival-20261006`, based on the tested 0.13.1 release.

## Destination selection during simulation

The saved library previously opened the planning draft even during an active simulation because its active flag depended on the visible panel. The live footer now exposes Saved, and saved items offer Add to trip while the simulation is running, including holding and paused sessions. Selection opens an isolated preview; confirmation changes the trip. Saved routes can be used as destination only.

Add stop supports search, a dropped map pin, saved places, saved routes and saved itineraries. A selected single destination supports fine-tuning its pin and setting a separate entrance/parking pin. Placement offers Go now, after the current stop, or at the end. Go now can keep remaining stops. Cancel does not change the running itinerary. Map selection hides the editor and returns to its preview when the user picks a point.

## Arrival and departure

Ground routes use an optional entrance/parking pin as their routing destination. After the road leg, an indoor arrival follows short approximate walking connections to the entrance and final destination pin at 1.2 m/s. Activity choices are Inside building, Seated at table, At office, In conference room and Stay at route endpoint. Indoor stays are stationary, with normal timestamp updates. Outdoor endpoint behavior retains its previous dwell model. Flights retain direct arrival.

The user selects the indoor coordinate. An address search may locate a centroid, entrance or imprecise point. Mirage does not infer actual tables, rooms, floors, walls or accessible indoor paths. Each incoming walking connection is limited to 150 m; invalid or longer connections hold at the road endpoint and show a warning. The live addition preview shows the approximate distance and warns about excessive gaps or a destination indistinguishable from the road endpoint. Map overlays distinguish an explicit entrance and final pin.

Before the next leg, including a confirmed Go now from a stay, playback walks back to the known entrance/road anchor before routing. Recovered stays skip the incoming walk and use an explicitly saved entrance for departure when available. The implicit road anchor is not persisted across process recovery.

## Stay and itinerary editing

Stay choices are Continue immediately, a timed stay, or Stay until I leave. Manual stays block onward travel until Leave now. At the final stop, simulation remains active until Stop even with no timed stay. A manual wait is not included in later arrival estimates.

Upcoming stops can be replaced through the same destination selector, reordered, removed, or have their stay and arrival settings changed. Replacement retains the stop identity, rejects a stop that is no longer upcoming, and supports undo. A combined stay/arrival edit is one undoable operation. Existing undo expires when the trip advances.

Favorites and recent sorting remain available. Save these place settings creates or updates a saved place with its adjusted destination, entrance, arrival activity and stay preference. These fields round-trip through saved scenarios, backup and recovery, with defaults for older records. A new unrelated destination does not inherit an old entrance coordinate.

## Voice and compact status

Voice additions and saved destinations continue to require confirmation. Added contextual forms include “After [exact current or upcoming stop name], go to [destination],” “stay another [duration],” and “add this stop at the end” for a pending addition or a recently confirmed upcoming stop. Unknown or ambiguous anchors require clarification; the app does not guess a room or destination.

The compact status distinguishes travel, walking inside/outside, staying and holding. Saved is directly available, and a current stay exposes Leave now. The detailed itinerary retains its existing save, reorder and undo controls.

## Regression coverage and release gate

Added/updated source tests cover the active saved-library path and cancellation; continuous indoor walking; stationary table stays; invalid/distant pins; walking back outside, including Go now; manual waits; atomic preference undo; upcoming replacement with stable identity; isolated map-pin selection; saved entrance/stay round trips; rejecting invalid imported entrance coordinates; avoiding stale entrance reuse; and contextual voice follow-up.

Validation status on October 6, 2026: `git diff --check` passes. The tests above have not executed. Local Gradle bootstrap failed because `services.gradle.org` was unreachable. Automatic approval review rejected the public repository push pending explicit authorization naming `aviterima/Mirage`; hosted CI has not started. No new APK is available. The previously verified release remains 0.13.1.

After explicit push authorization, run the existing complete JVM and emulator acceptance workflow, fix failures, and verify signing identity and in-place upgrade from 0.13.1 before distributing the APK. Then validate the reported Saved-list flow, pin placement, entrance/interior arrival, manual departure and pause/Stop behavior on the user's phone. The APK should be delivered as a downloadable attachment, not merely a link to a build page.
