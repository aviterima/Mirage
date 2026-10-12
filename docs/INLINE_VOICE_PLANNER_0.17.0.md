# Mirage 0.17.0 — voice in the normal planner

Status: implemented candidate; CI validation pending. Version `0.17.0`, code `43`, on `codex/inline-voice-planner-20261012`, based on verified 0.16.1 at `3f268d56b618129051d8b1cff461a5d31d690f83`.

## Approved behavior

Start, Destination and Next stop each have a microphone beside the normal editable text field. The microphone targets that field. If the separately downloaded voice model is missing, it opens Voice setup with download progress, cancel/retry and a return to typing. When ready, the microphone starts dictation directly, subject to microphone permission. Completing a download does not start recording automatically.

Dictation fills the same editable entry used for typing. Ordinary place names use the existing saved-place matches and Places result list. Explicit instructions offer Review instruction and confirmation in the planner. Ambiguous instruction results reuse the same place-result component. Only the current response is shown; the planner does not add a chat-history panel or a second itinerary.

The existing ordered itinerary is the preview. It includes travel/stay estimates and highlights the latest added stop. Save and Start remain separate. Planning input is never passed to the legacy command path that starts playback; unsupported compound requests ask for one change at a time. Current voice-model language limits remain, including no invented coordinates.

Clear entry clears only the selected input and pending request. Start new confirms before clearing the working itinerary, names/endpoints, route, timing, undo, linked saved identity, dictated words, previous instructions, choices, follow-up context and persisted recovery draft. Saved library items remain. A running/starting simulation blocks this reset. Pending speech, search and recovery writes are invalidated so old results cannot recreate the cleared plan.

The existing live-trip command surface remains available. This change integrates voice into normal planning; it does not claim new acoustic recognition accuracy, unlimited compound instructions or automatic simulation start. Voice and typed content remain excluded from the interaction audit.

## Verification plan and current limits

New JVM coverage checks reset isolation, field targeting, stale results, recovery generation, reply/confirmation and dictation cancellation. Five new Android journeys cover the missing/ready model gate, field placement, dictated/typed result equivalence, sequential additions, preview, reset versus clear, and larger text with the keyboard.

CI must compile, pass all JVM and Android journeys, run blocking lint, verify packaged voice and permanent signing, and install over the exact 0.16.1 APK while retaining data. The expanded emulator suite has a 50-minute job budget. Results, tested source and exact installer identity will be recorded here when complete.

Physical microphone acoustics, wake-word behavior, phone ergonomics, and a new real network download are not established by deterministic UI tests. Local Android execution remains unrun because this host lacks the Android toolchain. No server deployment is required.
