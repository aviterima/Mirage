# Mirage 0.13.0 quick start

## Install

Install the supplied signed APK over 0.12.1 to retain saved data. Do not uninstall. The validation report identifies the exact tested APK and signing certificate.

## Plan and save

The double-arrow beside Start and Destination exchanges route endpoints. Choose Itinerary to build a list. Add a stop at the bottom of that list. Drag a stop's right-hand handle to reorder; tap the handle for Move up/down. Tap its stay to enter an exact number of minutes or an arrival target.

Start, Save/Save changes, Settings and Talk are in the planning footer. Settings opens a separate sheet. Long plans scroll above the footer; the keyboard resizes the available area. Undo reverses the latest stop-list edit.

Use **Saved plans** to browse Places (formerly Snaps), Routes and Itineraries. Search matches names, addresses, aliases and stop names. Favorites appear first, followed by recently used items. Tap a card's title for its details and contextual actions; **Open** replaces the current draft. The three-dot menu offers favorite, rename, duplicate, voice aliases, share/export and delete. **Undo library change** restores the prior saved collection, including after reopening the app. There is one previous version, not unlimited history.

A named draft uses **Save changes** to update the same item. Saving with a new name creates another item. Favorites and aliases remain attached when updating. An automatically preserved draft is recovery data; use explicit Save to keep a named template.

## Edit while simulating

Tap **Add stop** on the live controls or in **My itinerary**. Search or choose a saved Place, Route or Itinerary. Pick **After current stop**, **At end of trip**, or **Go now**; review the address, route and any connector. After-current/end additions preserve pause. Go now explicitly resumes movement. Keep remaining stops enabled to retain the rest of the trip.

My itinerary distinguishes completed, current and upcoming stops. Reorder future stops using the same handles as the planner. Edit stay duration, mode or an arrival target. Undo restores future edits only while the current stop has not advanced; it never rewinds actual travel.

**Save changes** updates the linked saved itinerary; **Save as new itinerary** preserves the source. Saving includes the configured whole trip, including completed stops and original start.

## Recover an interrupted trip

On reopening, choose **Restore for editing** to keep simulation off, or explicitly resume an interrupted live trip. Resume starts from the checkpoint and remaining stay, with completed stops retained in the saved trip. Normal Stop clears the live recovery record. Position checkpoints occur periodically (about 15 seconds), so recovery is not a frame-perfect replay. Exact road-path recovery uses the remaining geometry and proportional timing.

## Timing and route choices

Set **Departure** for a future start. Arrival targets warn when a stop is reached late; they do not guarantee arrival or automatically change speed. The planner summary is an estimate and includes stays and simulation time scale. **Suggest order** proposes a shorter geometric order with the final destination fixed. Review before accepting; use Undo to restore. Suggestions are unavailable when arrival targets would need constraint-aware optimization.

After calculating a road route, Settings offers **Replay exact road path**. Save it to retain geometry and timing. Otherwise routes recalculate when used. Transit remains fresh. Exact replay starts from its stored origin; disconnected routes require a connecting leg. Maps search, fresh road/transit routes and connectors still require the Maps key and network.

## Improved voice

Open **Talk to Mirage**. Basic controls and common recognized commands use the fast path. Advanced language interpretation uses the optional, locally running Qwen3-0.6B model (about 610 MiB). This is an openly licensed model, not public-domain software. The existing Vosk speech recognizer remains in use.

Examples:

- “Add Home after this.” Saved names and aliases work without downloading the language model.
- “Add the Moxy in Scottsdale at the end.” Choose among matches and review the address.
- “Make its stay forty-five minutes.” Refers to the most recently confirmed, uniquely identified upcoming stop for up to two minutes in the same session.
- “Actually put it after Office.” The named reference must be unique and upcoming.
- “Save this trip as Tuesday errands.”
- “Save changes.”
- “Pause,” “continue,” “what happens next,” or “stop simulation.”

Review proposed changes and tap Confirm or say yes. Cancel abandons pending work. **Correct these words** lets you edit the transcript. Listening, understanding and review states show progress. Named-place additions and saving also work while planning, without starting simulation. Contextual follow-ups currently apply to upcoming stops in a live trip. Use one change per request for reviewed AI actions; the older structured multi-leg command flow remains available.

The local model improves interpretation, not microphone recognition. Type difficult place names when needed. Real-device microphone accuracy, wake-word behavior, speed, battery and temperature still need phone testing.

## Backup and share

In Saved plans, Export backup writes the collection without API keys or device credentials. Import backup validates the complete file, shows a preview and imports copies; conflicting names receive suffixes. The limit is 2 MiB, 1,000 items and 100 stops per item. Use a card's Share this item to open Android's share sheet, or Export this item for a file. Imported content does not start simulation.
