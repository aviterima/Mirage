# Mirage 0.13.1 — saved sources and Place terminology

## Specification

Every live Add stop entry point opens the shared continuation sheet. Its first controls show Search for a place, Saved places, Saved routes and Saved itineraries in two rows without horizontal scrolling. These remain available after choosing an item. Selection previews the addition; confirmation applies it using Go now, After current stop or At end of trip. Existing route connection, destination-only, itinerary stay times, pause preservation and cancellation behavior remain in force.

Use Place throughout the visible interface: planning mode, primary action, summaries, accessibility descriptions, help, automation responses and voice prompt. The action to start a held location reads “Start at …”. Internal SNAP enum/storage values and the old `snap` automation/voice aliases remain compatible; no migration, deletion or renaming of saved records is needed. `place` is the canonical automation command.

## Quick start

During simulation, tap **Add stop**, then **Saved places**, **Saved routes** or **Saved itineraries**. Select the saved item, review where to insert it, and confirm. A saved route can include travel to its saved start or use only its destination. Adding after the current stop or at the end preserves the current simulation and its pause state. Use **My itinerary** to review, reorder and save the updated trip.

On the planning screen, select **Place**, choose the location and tap **Start at …** to hold there. Existing saved places remain available automatically after updating.

## Implementation report

The previous source selector was horizontally scrollable, defaulted to Search, and used generic Places/Routes/Itineraries labels. The choices existed but were easy to overlook. The replacement is an explicitly labelled, full-width two-column source selector above placement controls. Source switching also clears the previous saved-use identifier to avoid attributing a later addition to the wrong saved item.

Visible Snap labels were found in the planning mode, start action, saved summary, location-button accessibility description, help and automation responses. These now use Place or an action-specific description. Existing serialized records are unchanged. Native voice/model behavior is otherwise unchanged.

## Validation

Pending signed CI and emulator acceptance. New regression coverage enters Add stop while travelling, confirms that all four source choices are visible without horizontal scrolling, adds a saved route and a legacy stored place, and checks that current-stop identity, origin and running state are preserved. A separate large-text test checks all source controls at 1.3× font scale. Existing continuation tests cover saved itineraries, pauses, cancellation and saving. The upgrade gate installs over 0.13.0 with the same signing identity and verifies retained data.

Physical-phone confirmation remains outstanding. Historical validation reports refer only to their stated versions.
