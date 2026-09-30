# Mirage 0.12.1 release notes

## Changes

- Double-arrow beside Start and Destination swaps the selected route endpoints and prepares fresh directions without starting the trip.
- Visible itinerary drag handles let you change stop order. Hold near an edge to scroll a longer list.
- Tap a handle for Move up, Move down or Remove; accessibility actions also support reordering.
- Reordering preserves stay times and travel settings, refreshes the timeline, and survives saving/reloading.
- Stop removal now also clears stale route calculations.

Version code: 34. This is an additive update to 0.12.0; local voice and live itinerary saving are retained. The planning form has drag reordering; live My itinerary continues to use its existing move buttons.

Validation: 104 JVM tests and 17 Android emulator tests passed, with zero failures or skips. Signed in-place upgrade from 0.12.0 preserved stored records and settings. See VALIDATION_0.12.1.md for exact evidence and limitations.

The expanded planning settings panel can cover Add a stop; collapse it with its chevron. Physical-phone ergonomics and long-list edge scrolling remain to be validated manually.
