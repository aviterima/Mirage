# Mirage 0.12.1 route and stop editor specification

This extends the 0.12.0 live-itinerary and local-voice specification. Live saving and voice behavior remain governed by that specification.

## Route endpoints

Show a 48 dp double-arrow button immediately to the right of the Start and Destination fields. Its accessible name is “Swap start and destination.” Disable it until both coordinates are selected; uncommitted search text is not a selected location.

Tapping swaps coordinates and labels together, retains available place metadata, clears outstanding search results, cancels old directions and any pending automatic start, and prepares new directions when routing is available. Never reverse an old road polyline: opposite directions can use different roads. The user still explicitly starts the simulation.

A real or simulated origin becomes a fixed destination at the location resolved at the moment of swapping. The new start is explicit; subsequent real-location refinements must not change it. Swapping a draft does not change an active simulation. Swapping twice restores explicit endpoints and their available metadata.

## Itinerary building

Keep Start above the numbered stops. Put a visible drag handle at the right of each stop. Drag the handle to move a stop anywhere in the list; ordinary swipes scroll. Highlight the moving row and destination row. Hold near the upper/lower edge to scroll long lists. Commit the insertion when released; cancelled gestures leave the original order intact.

Move the entire stop record, including address, place identifier, stay duration, travel mode, speed and routing preferences. Intermediate stops keep their relative order. Recalculate the timeline and invalidate stale directions. Save and reload must preserve the new order and all stop settings. Duplicate names remain independent records.

Tapping a handle opens Move up, Move down and Remove. Disable moves beyond either end. Supply accessibility Move up/down actions. Start remains fixed; this feature reorders the itinerary's destination stops. During an active simulation, My itinerary retains its existing upcoming-stop Move up/down controls; drag handles apply to the planning form in this release.

## Release gates

Run JVM state/persistence tests, actual Activity endpoint-swap and drag/save/reload tests, the existing simulation and on-device language-model suite, and an in-place signed upgrade from 0.12.0. Review screenshots of both new controls. Physical-phone ergonomics remain a follow-up validation task.
