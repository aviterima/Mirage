# Mirage 0.17.0 — voice in the normal planner

Status: implemented and automated acceptance passed October 12, 2026 (Pacific and UTC). Version `0.17.0`, code `43`, on `codex/inline-voice-planner-20261012`, based on verified 0.16.1 at `3f268d56b618129051d8b1cff461a5d31d690f83`. Tested source: `add03c4a8825d146fbbdf6f589c1026510656456`. Physical-phone acceptance remains open.

## Approved behavior

Start, Destination and Next stop each have a microphone beside the normal editable text field. The microphone targets that field. If the separately downloaded voice model is missing, it opens Voice setup with download progress, cancel/retry and a return to typing. When ready, the microphone starts dictation directly, subject to microphone permission. Completing a download does not start recording automatically.

Dictation fills the same editable entry used for typing. Ordinary place names use the existing saved-place matches and Places result list. Explicit instructions offer Review instruction and confirmation in the planner. Input is buffered until a place or instruction is confirmed, so typing a save instruction character by character preserves the selected endpoints. An unfinished entry blocks Start; explicit Clear removes that field's selection. Ambiguous instruction results reuse the same place-result component. Only the current response is shown; the planner does not add a chat-history panel or a second itinerary.

The existing ordered itinerary is the preview. It includes travel/stay estimates and highlights the latest added stop. Save and Start remain separate. Planning input is never passed to the legacy command path that starts playback; unsupported compound requests ask for one change at a time. Current voice-model language limits remain, including no invented coordinates.

Clear entry clears only the selected input and pending request. Start new confirms before clearing the working itinerary, names/endpoints, route, timing, undo, linked saved identity, dictated words, previous instructions, choices, follow-up context and persisted recovery draft. Saved library items remain. A running/starting simulation blocks this reset. Pending speech, search and recovery writes are invalidated so old results cannot recreate the cleared plan.

The existing live-trip command surface remains available. This change integrates voice into normal planning; it does not claim new acoustic recognition accuracy, unlimited compound instructions or automatic simulation start. Voice and typed content remain excluded from the interaction audit.

## Using the planner

1. Choose Route or Itinerary, then type into Start, Destination or Next stop, or tap that field's microphone. If Voice setup opens, download the required files and choose Use microphone when ready.
2. Edit the recognized words if needed. Choose a saved place or search result from the same list used for typing. Explicit planning instructions offer Review instruction and a confirmation before changing the plan.
3. Review the stops in the existing itinerary list. The most recently added stop is marked Added; tap a stop name for its details.
4. Use Save to keep the plan. Start is a separate action and stays disabled while a field contains an unfinished edit or a voice instruction is awaiting a response.
5. Use a field's Clear entry control to clear that entry. To discard the working plan and its instruction context, choose Start new and confirm. Saved library items remain available.

## Verification

New JVM coverage checks reset isolation, field targeting, stale results, recovery generation, reply/confirmation and dictation cancellation. Six new Android journeys cover the missing/ready model gate, field placement, dictated/typed result equivalence, sequential additions, preview, reset versus clear, larger text with the keyboard, and incremental typed save instructions in both endpoint fields.

Build [38268232274](https://github.com/aviterima/Mirage/actions/runs/38268232274) passed compilation, **210 JVM tests**, blocking lint, packaged-voice checks and pinned signing. Acceptance [38268232278, attempt 2](https://github.com/aviterima/Mirage/actions/runs/38268232278/attempts/2) passed **44 Android tests** on the exact tested source. Downloaded XML reports were independently parsed: zero failures, errors or skips. All six new planner journeys, 11 planner voice-session tests, the saved-alias regression and both previously timed-out live-trip journeys passed. Recognition and Places results in the UI journeys use deterministic fixtures; they do not establish physical microphone or live Places/Directions acceptance.

The signed 0.16.1 → 0.17.0 installation succeeded without uninstalling. The exact baseline APK checksum was pinned. Before/after files were byte-identical for the seeded saved SNAP/place record and the key/install-identity preferences. This fixture does not test every saved itinerary or preference. The live trail check passed with fallback after an HTTP 504 from its first geometry provider; the successful original trail job was reused for the same source/run when the failed emulator job was rerun.

Six screenshots from the successful attempt were reviewed: inline microphones/shared result list, missing-model setup, the two-stop preview, larger text with the keyboard, the cleared itinerary and saved-Home selection. The new-stop highlight and normal Save/Settings/Start new footer are readable. The larger-text capture shows the focused Next stop field and mic above the keyboard, but not a complete search-result row or footer; the scrolled preview also clips part of its feedback text at the viewport boundary. These captures do not establish complete layout or phone-ergonomics acceptance.

## Failed and superseded attempts

Acceptance run [38264557801](https://github.com/aviterima/Mirage/actions/runs/38264557801) at `4fd683e1e78d74664ca63ce2a87448699bb5ea13` passed 42 of 44 Android tests. The new reset test incorrectly scrolled a fixed footer control; it now asserts visibility and clicks directly. The existing saved-alias journey missed its result while a deferred keyboard scroll moved that result out of view. The arbitrary 150 ms scroll delay was removed, and the test waits for the IME to appear before scrolling and tapping with the keyboard still open. This failed run is diagnostic evidence, not release validation. Earlier candidate runs were superseded while review corrections were added.

Acceptance 38268232278, attempt 1, passed 42 of 44 Android tests, including all six new planner journeys and the saved-alias journey. Two existing live-trip journeys timed out in Compose's idle synchronization while fetching semantics. In the paused-addition case, the stop, paused state and location-output assertions had already passed; the save journey stalled before its first save action. Logcat showed slow frames and renderer contention, without an app crash or ANR. Attempt 2 reran the emulator on unchanged source and passed all 44 tests. The first attempt remains diagnostic evidence; the retry does not prove the suite is free of timing sensitivity.

## Exact installer

| Property | Verified value |
| --- | --- |
| File | `Mirage-0.17.0.apk` |
| Package | `com.mirage.app` |
| Version / code | `0.17.0` / `43` |
| Bytes | `93,898,350` |
| SHA-256 | `d84c9f19be7b20b54bcbdc1fb117ccf5ff0ebf1816a304683560866e4d21592a` |
| Signing certificate SHA-256 | `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8` |

All seven selected artifact ZIPs matched GitHub metadata size and SHA-256 and passed CRC checks. The four APK parts came from the successful emulator attempt and were assembled without modification; the resulting APK matches its workflow checksum. Local inspection checked every APK ZIP member, the binary manifest and the signer fingerprint. Local certificate extraction is not cryptographic signature verification; the build job ran `apksigner verify`, while the emulator's signed in-place installation accepted the exact delivered APK. The uploaded installer's name and byte count were confirmed through metadata readback; its private delivery link remains in the chat, not this repository.

The final commit changes only README.md, HANDOFF.md, SPEC.md and this report after testing. Original main, recovered source, earlier feature branches, the rolling release and production services remain unchanged.

## Remaining checks

Physical microphone acoustics, wake-word behavior, phone ergonomics, and a new download through the app on a real network are not established by deterministic UI tests. The CI preparation downloaded and checksum-verified the model separately. Live Places/Directions acceptance remains open. Local Android execution remains unrun because this host lacks the Android toolchain. No server deployment is required.
