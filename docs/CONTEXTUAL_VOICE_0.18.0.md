# Contextual input — 0.18.0 candidate

Status: implementation and verification in progress. The verified public delivery remains 0.17.0. No 0.18.0 acceptance or model-quality pass is claimed.

## Requested behavior

Every app-owned editable text field offers a microphone beside the text. Typed and spoken instructions use the same field and controls. Missing voice assets open setup; installing them does not begin recording. Exact credential entry stays outside interpretation, persistent input drafts and diagnostics.

The route and itinerary planner accepts free-form instructions with multiple places, stays, travel modes and departure time. A structured proposal is reviewed in the existing itinerary preview before a single atomic apply. Place ambiguity uses the existing result component. Clarifications and corrections retain unrelated parts of the pending plan. One Undo restores the whole interpreted transaction. Planning, interpretation and saving never start playback.

Listening supports more than one recognizer segment and an explicit finish control. Interrupted partial input remains editable. Local draft persistence covers unfinished entries and pending reviews; Start new clears the old planning context durably, invalidates late results and preserves saved library records. Clear entry remains separate from Start new.

Date/time fields include an app-owned microphone entry alongside calendar selection. Natural-number words remain intact until reviewed. Status appears beside the entry: Listening, Interpreting, Ready for review.

## Boundaries and verification

The local model proposes schema-limited values without coordinates or execution commands. The application validates every edit, resolves place references, rejects stale proposals and requires an explicit Apply. Input or context exceeding model limits is rejected visibly rather than silently truncated. Literal phrase routing is not evidence of free-form interpretation.

Required checks: meaningful reducer/transaction/persistence tests, Android input/review/correction/undo/reset journeys, real-model held-out cases, signed upgrade from the checksum-pinned 0.17.0 APK, blocking lint, artifact checksum/identity verification. A fixture-driven UI pass does not establish model quality or physical microphone performance.

Initial provisional Windows CPU tests of the pinned 0.6B weights exposed incorrect corrections and irrelevant clarification questions. Prompt refinement and real-model evaluation are in progress. This result blocks claiming reliable free-form interpretation; a larger local or optional remote model may require a product decision. No alternate model or remote processing is enabled by this candidate.
