# Mirage 0.13.0 release notes

Status: candidate; release gates pending. See the validation report for final evidence.

## Changes

- Separate planning settings sheet and bounded editor keep Add stop and the playback/save footer usable. Route endpoint swap remains beside the fields.
- Shared drag-and-button future-stop editing in the planner and live itinerary, exact stay entry, arrival targets, and progress-aware undo.
- Automatic draft preservation and explicit interrupted-trip recovery with original trip history, remaining stay and simulation settings.
- Saved Places, Routes and Itineraries gain common search, favorites/recent ordering, details, rename, duplicate, voice aliases and previous-version recovery. Named updates retain record identity and metadata.
- Credential-free JSON export/import and Android item sharing, with validation and import preview. Imports preserve existing records.
- Departure scheduling, estimated timing summaries, reviewed geometric order suggestions and saved exact road-path replay. Transit and connectors use fresh routes.
- Voice fast paths for common commands and saved aliases; corrected transcript entry, cancellation, visible progress, reviewed single actions and bounded live follow-up context. Planning additions/saves keep simulation off.
- Real and simulated location markers are distinguished. Existing paused/output-health status remains visible.

## Scope and limits

The optional on-device language model remains Qwen3-0.6B Q8_0 with the existing pinned runtime. It is openly licensed, not public domain. No new microphone recognizer is included. Model download is about 610 MiB. Place search and fresh directions still require network access and a Maps key.

Follow-up context lasts up to two minutes and targets a uniquely named future stop in the same live session. It is not general conversational memory. Legacy structured multi-leg voice commands retain their existing flow. Explicit skip still means end the current stay or jump to arrival.

Order suggestions minimize a geometric estimate, preserve the final destination and require acceptance. They are not road-traffic or time-window optimization. Arrival targets warn on late arrival, not guarantee it. Recovery checkpoints are periodic; resuming an archived road leg preserves remaining geometry with proportional timing. Saved history retains one prior collection.

The Android document picker/share-sheet integrations require a suitable receiving app. Physical-phone voice accuracy, latency, wake-word, battery, thermal and ergonomic testing remain outstanding.
