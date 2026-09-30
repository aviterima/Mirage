# Mirage 0.13.0 approved specification

Status: implementation in progress; not a passed release. Supersedes conflicting editor requirements in 0.12.1. Existing simulation, permanent signing and data retention requirements remain.

## Approved scope

- One consistent itinerary editor for planning and active trips, including draggable future stops, exact stay editing, accessible alternatives and undo without rewinding movement.
- No overlapping planning/settings panels. Add stop, Save and playback actions remain discoverable; advanced settings open separately. Keyboard and larger text must not hide focused fields.
- Direct Add stop entry with Next stop, End of trip and Go there now, preserving existing stops and pause state unless explicitly changed.
- Automatically preserve working drafts and interrupted live trips. Recovery never automatically starts location simulation. Explicit saves update named templates; source templates remain unchanged when appended elsewhere.
- Unified saved browser with Places, Routes and Itineraries, search, favorites, recent items, rename, duplicate, item previews, contextual actions and recoverable deletion.
- Backup/import/share of saved content with schema validation, import preview, duplicate handling and no credentials.
- Clear simulation/off/paused/travel/stay/output-interrupted states and differentiated real/simulated locations.
- AI voice: immediate basic controls, visible listening/understanding/review states, editable transcription, cancellation, saved aliases, bounded follow-up context and action-specific confirmation. No invented place coordinates or silent destructive action.
- Timing summaries with explicit estimated versus routed labels. Exact-route replay distinct from endpoint recalculation; connection previews remain explicit.
- Later-expansion recommendations are also approved: schedule constraints, optional order optimization and itinerary sharing. These require usable preview/accept/revert behavior, not silent changes.

## Acceptance

Exercise saved endpoints → swap → itinerary composition → reorder → stay → save → simulation → add while paused → undo → update → interruption/recovery. Include backup round trip, malformed imports, duplicate names, named-save identity, failed storage writes, voice follow-up staleness/cancellation, keyboard, larger text and long lists. Re-run existing JVM, emulator, native model and signed upgrade gates. Physical phone voice, wake-word, thermal and ergonomic validation requires the user's phone and cannot be inferred from emulator results.

## Implementation tracking

All items above are approved. Keep unfinished items explicitly identified in validation; do not describe candidate functionality as tested or implemented until supported by code and evidence.
