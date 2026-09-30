# Mirage 0.12.0 — live itinerary and local voice specification

Updated September 29, 2026 (America/Phoenix). Status: implementation candidate; not a released APK. This document supersedes conflicting live-save and voice behavior in earlier specifications. Existing GPS simulation, routing, signing, and data-retention requirements remain in force.

## Product outcome

The user can continue an existing simulation with an entered destination, saved snap, route, or itinerary, edit the resulting trip, and save it without stopping. The map remains the main screen. Voice and touch operate on the same live itinerary and persistent save operation.

The primary interaction is **My itinerary → Add stop → choose a place or saved item → review → add → Save changes**. A first save requests a name. Later saves update that same record; Save as new creates a separate record. Adding content from a saved route or itinerary does not alter that source template.

## Live itinerary

The live map exposes a labeled My itinerary button. Opening it shows the entire trip in sequence, including visited, current, and upcoming stops. Each stop includes its name, retained address, travel mode, and configured stay duration. Upcoming stops can be moved up/down, removed, assigned a travel mode, and given longer/shorter stays. Editing a future stop does not restart the current leg. Closing the sheet returns to the map.

The editor displays either Unsaved changes or All changes saved. The indicator compares the current configured stop list with the last successful saved snapshot. Progress along a road, arrival, and the stay countdown must not make a saved trip appear edited. Adding/removing/reordering stops, changing a configured stay, or changing travel mode must mark it unsaved.

Save itinerary stores a full replay template from the live plan's origin, including the configured stops, modes, addresses, and stays. It does not store the remaining fraction of a stay as the future replay duration. It saves as an ITINERARY even when there is only one stop, so the item is found under Itineraries. The simulation and pause state are preserved.

Save changes updates the linked saved record by stable ID. Save as new requires a distinct nonblank name and preserves the original. Duplicate names are rejected. An unsuccessful persistence write must not report Saved or clear the dirty indicator. A record removed from storage cannot be silently recreated through Update; the user must save a new record. A newly diverted plan is a new trip unless it is explicitly linked to a saved source.

Loading a saved route or itinerary into the initial planner carries its saved identity into the live plan. Appending a saved item to a different running trip does not change that running trip's saved identity. The editor shows the name of the saved record that Save changes will update.

## Continuation placement

All addition sources retain the existing three choices:

| Choice | Result |
| --- | --- |
| Go now | Continue from the latest simulated position; preserve unvisited stops unless explicitly changed in the visual review. |
| After current stop | Insert after the current stop and its stay, before later stops. |
| At end of trip | Append after all remaining stops. |

Saved snaps contribute coordinates without teleporting. Saved routes and itineraries can include a connecting journey to a disconnected saved origin. Travel-to and instant-snap remain distinct actions. The source template is preserved. A paused simulation stays paused when adding after the current stop or at the end. Go now explicitly resumes.

The expanded picker is an editing surface. Review on map collapses it to a short bar. The map-space gate remains at least 75% clear vertical space on the acceptance emulator in collapsed live/review states, not while the expanded editor is open.

## Local language understanding

The candidate uses Qwen3-0.6B Q8_0 through a pinned llama.cpp native runtime. It is an openly licensed model under Apache 2.0, not a public-domain dedication. Its role is to classify a single request and fill a bounded action schema. It is not a general chat assistant and does not execute model-generated code.

The language-model file is downloaded separately through Talk to Mirage. The pinned official file is 639,446,688 bytes (about 610 MiB). The UI displays download progress, cancellation, retry, and readiness. A download is only installed after size and SHA-256 verification. Partial files are discarded on failure. The APK does not repeatedly carry the language model. A normal in-place APK upgrade preserves the downloaded file.

The model runs locally after download, with thinking disabled, a 2,048-token context ceiling, four CPU threads, a 220-output-token limit, and a 45-second inference deadline. Work runs off the UI thread. Idle model weights are released after one minute. These are implementation limits, not promised phone latency. The first download uses the network; place search and road routing continue to use the configured Maps services.

Existing Vosk speech recognition and Android text-to-speech remain. The language model does not itself improve acoustic transcription. Microphone, wake-word, and physical-device battery measurements require device validation. Runtime ABIs in this candidate are arm64-v8a and x86_64; 32-bit Android devices are not supported by this build.

## Supported voice operations

The model may propose add_saved, add_place, save_new, save_changes, move, remove, stay, extend, status, pause, resume, or clarify. It returns target, placement, minutes, and upcoming position in a grammar-constrained JSON object. The app rejects unknown keys/actions, invalid numbers, missing required targets, out-of-range durations, and invalid stop positions.

Saved targets are resolved against actual saved records. The user must say the target name; inferred destinations whose words are absent from the request are rejected. A unique exact saved-name match takes precedence over an external search when the model mislabels it as add_place. Place matches retain their full address and require review; multiple results require choosing one. No model-generated coordinates are accepted.

Mutating advanced-voice actions produce a short app-generated review and require yes/confirm or cancellation. The app checks the live session identity and stop list again before confirmation. Current-stay changes additionally require the same current stop. If another action changed the trip, confirmation is rejected and the user is asked to repeat the request. A saved-item addition uses the same ContinuationPlanner preview and commit as touch. A voice save uses the same saveActiveScenario method as the itinerary screen. Voice reorder/removal targets unique upcoming stops and uses the same LivePlan methods.

Exact Pause, Resume, Cancel, and Stop simulation commands bypass model inference. Stop must not wait for an inference request. Ordinary deterministic status commands also remain direct. Saving by the explicit phrase “save this trip as [name]” remains available before downloading the model. The existing basic command parser remains as a fallback when the model is unavailable.

The first release supports one proposed action per conversational turn. Compound requests such as “add a route, go home, then save it” are not a promised capability. Vague references such as “there” require an actual destination name. The catalog supplied to the model is capped at 40 records; the full saved collection remains accessible by touch.

## Acceptance gates

1. Compile the APK and instrumentation package, including arm64 and x86 native code.
2. Pass the existing unit suite plus live-save, failed-write, dirty-state, strict voice-schema, and grounding checks.
3. Exercise add → save → add → update-existing through the real Activity; verify stable ID, persisted stop growth, and continued paused simulation.
4. Verify Save as new preserves the original and duplicate names fail clearly.
5. Run real on-device native model inference against the pinned model; a missing model must be visible as a skipped test, not called a pass.
6. Re-run the live map-space, continuation, saved-source, cancellation, and Stop acceptance tests.
7. Verify a permanently signed update from the delivered 0.11.3 APK preserves stored records and settings without uninstalling.
8. Record model smoke-test outputs and latency separately from phone/emulator results. A small smoke suite is not proof of general language reliability.
9. Update SPEC, README, HANDOFF, dependency provenance, and the release validation report together. Repost the documentation package with the release status clearly marked.

## Explicit limits

There is no claim that all prior 72 scenario definitions have been executed on a physical phone. Process-death restoration of a running simulation is not added by this work; named saved itineraries persist, while unsaved live edits remain session state. This candidate does not replace speech recognition, provide unrestricted multi-action conversation, or guarantee correct hotel selection without reviewing the name and address.
