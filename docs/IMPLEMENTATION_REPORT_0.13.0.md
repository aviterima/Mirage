# Mirage 0.13.0 implementation report

The release concentrates on composing a trip, saving it, changing it during simulation and recovering it later. The signed validation report is authoritative for test status and APK identity.

| Approved improvement | Result | Main implementation |
|---|---|---|
| Familiar endpoint reversal | Double-arrow beside Start/Destination; coordinates and labels exchange together | MapScreen, MirageViewModel |
| Intuitive stop order and stays | Shared drag handles, Move up/down, exact stay dialog; future edits in live trips | ReorderableStopList, ItineraryUi, LivePlan |
| Unobstructed planning controls | Scrolling planner above playback/save footer; settings in a separate sheet | MapScreen |
| Add and save while simulating | Search or saved selection, explicit placement and connector preview, Save changes/Save as new | ContinuationPlanner, ContinuationUi, MirageViewModel |
| Recovery | Debounced draft preservation, periodic trip checkpoint, explicit restore or resume | TripRecovery, LivePlan |
| Saved content management | Places/Routes/Itineraries, common search, favorites/recent, details, aliases, rename/duplicate | SavedLibraryUi, SavedItemSearch, Scenarios |
| Reversible editing | Planner/future-stop undo and one persistent previous saved collection | MirageViewModel, LivePlan, PrefsScenarioStore |
| Clear simulation state | Off/paused/travel/stay/output state, separate real/simulated markers | MapScreen, LiveUi |
| Better voice interaction | Fast common commands, aliases, transcript correction, progress/cancel, reviewed edits and bounded follow-up | SmartVoice, VoiceFollowUp, LiveUi |
| Timing and repeatability | Departure waiting, arrival targets, estimated summaries, reviewed geometric order, exact road archive | TripPlanning, RouteArchive, LivePlan |
| Backup and sharing | Validated credential-free JSON, import preview/copies, Android share sheet | BackupCodec, SavedLibraryUi, FileProvider |

## Data and upgrade behavior

New saved fields have defaults for older records. A named update keeps its ID, favorite status and aliases. Opening an item records recent use without replacing the saved content history. Additions preserve the source saved template. Imports allocate new IDs and resolve name conflicts; a rejected import writes nothing.

Working recovery data lives separately from named saved items. Saved templates include the entire configured trip, including completed stops. Resuming changes simulation only after explicit user action. Normal Stop clears live recovery. Undo cannot reverse movement or cross an advanced stop boundary.

## Voice architecture

The phone keeps Vosk for speech-to-text and uses the existing optional Qwen3-0.6B model for broader intent interpretation. Common recognized commands and exact saved aliases bypass model inference. Proposed single edits are prepared through the same continuation/save mechanisms as touch controls. The app resolves places and validates targets; the model does not provide coordinates.

Follow-up context is attached to a stable confirmed future-stop ID, expires after two minutes, and is rejected if the session, progress or unique target changes. Planning commands can add a place/saved item and save a draft without starting playback. Typed input remains available.

This release does not promise that every phrase is understood. The model is openly licensed, not public domain. Speech-recognition accuracy and real-phone resource use remain separate validation tasks.

## Deliberate limits

Geometric order suggestions are reviewed proposals, not traffic-aware or time-window scheduling. Archived road data is distinct from current directions; transit remains fresh. Recovery is checkpoint-based, and archived-leg recovery uses proportional timing. One previous saved collection is retained. Backup/import limits and physical-device validation gaps are listed in the quick start and validation report.
