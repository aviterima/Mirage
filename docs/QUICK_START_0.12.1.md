# Mirage 0.12.1 quick start

## Install the update

Use the tested signed 0.12.1 APK supplied with this release. Install it over Mirage 0.12.0. Do not uninstall the existing app: uninstalling removes its private saved data. If Android reports an incompatible signature, stop and report the message instead of uninstalling.

## Swap a route or rearrange an itinerary

Select both route locations, then tap the **double-arrow to the right of Start and Destination**. The fields exchange places. Mirage prepares fresh directions when available; tap **Get route** if needed, then start when ready.

When building an itinerary, **drag the handle at the right of a stop** up or down. Hold near an edge to scroll. The stop's stay time and travel settings move with it. Prefer buttons? Tap the same handle and choose **Move up** or **Move down**. Save the revised itinerary through **Saved plans**. The starting point stays fixed. If the lower settings panel covers **Add a stop**, tap its downward chevron to collapse it.

## Add and save while simulating

1. Start or continue a simulation. You can leave it running or pause it.
2. Tap **My itinerary** on the live map, then **Add stop**.
3. Search for a place, or choose a saved item under **Snaps**, **Routes**, or **Itineraries**.
4. Choose **After current stop**, **At end of trip**, or **Go now**. Review the name, address, and route before confirming. Go now resumes movement; the other placements preserve a paused simulation.
5. Reopen **My itinerary**. For a new trip, tap **Save itinerary**, enter a name, and save. For a trip already linked to a saved record, tap **Save changes**.
6. Check that the indicator changes to **All changes saved**. Later edits show **Unsaved changes** until saved again.

Saving includes the whole configured trip from its original starting point, including visited stops. It does not merely save the remaining part. To preserve the original and create a separate version, use **Save as new itinerary** with a different name.

The upcoming-stop list provides Move up, Move down, Remove, travel mode, and stay-duration controls. Adding a saved route or itinerary to a live trip preserves the source template.

## Enable and use advanced voice

Open **Talk to Mirage** and choose the advanced-voice download. Allow about 610 MiB for the model and use Wi-Fi if preferred. Wait for **Advanced voice ready**. The language interpretation then runs on the phone; Maps search and road routing still need a connection.

Make one request at a time, using the actual saved name or a clear destination and city. Examples:

- “After this take me to my saved Home location.”
- “Add the Moxy in Scottsdale at the end.”
- “Extend this stay by twenty minutes.”
- “Save this trip as Tuesday errands.”
- “Save changes.”

Review Mirage's proposed change and answer **yes** or **cancel**. If several places match, choose the numbered location and review its address. The model can misunderstand a phrase; the review is part of the workflow.

Basic Pause, Resume, Stop simulation, and explicit “save this trip as…” commands do not require the language-model download. Speech recognition remains the existing Vosk recognizer, so unusual place names may work better when typed.

## What to report during phone testing

Report the exact screen or spoken phrase, what you expected, what happened, and whether the simulation was paused. Include a screenshot when possible. Phone latency, microphone recognition, battery use, and thermal behavior still need physical-device validation.
