# Mirage 0.16.0 — interaction audit

Status: implementation complete; automated build and acceptance pending. Package `com.mirage.app`, version code 41. Development branch: `codex/interaction-audit-20261011`, based on 0.15.2 at `2db5905f5b4f051d8a2621c92ef004d513103b61`.

## Purpose and use

An interaction timeline makes a reported problem reproducible: what the person tried, which planner state was active, and whether each action succeeded, was blocked, failed, was canceled or produced a stale result. It supports debugging and reviewing interaction design. It does not perform automatic learning, screen recording, full crash capture or remote telemetry.

Open **Setup → Interaction audit**, or **Settings → Interaction audit** from the planner. Recording is enabled on first use. After reproducing a problem, choose **Mark a problem**, then **Export diagnostic report**. Android asks where to save the JSON file. Share that file in the support conversation with what you expected, what happened and the displayed marker ID. The file is sent only when the user separately shares it.

**Record interactions** pauses or resumes recording; existing events remain when paused. **Clear history** removes events and markers after confirmation, preserving saved places and trips. Previously exported reports are independent files. A visible storage warning identifies incomplete persistence; failed clear or preference writes are reported as failures.

## Recorded evidence

- App opening; planning mode and start-source changes; edits to resolved location fields.
- Saved-place picker opening/cancellation, selection and invalid selection; local match-count changes and debounced remote search request/result/cancellation/stale response.
- Route request, success, blocked validation and classified failure; save/load outcomes; stop edits and simulation requests/controls.
- Continuation editing/confirmation, voice interpretation versus explicit confirmation, and service startup or permission/provider/playback failures.
- Timestamps, process-local random session ID, sequence, operation correlation, typed reason codes and safe state: planning mode, active field, stop count, endpoint availability, route phase, running/paused state and origin policy. Report metadata includes app version, Android API level, a random report ID and retention/drop counts.

Typing is coalesced; GPS fixes, continuous provider ticks and rendered screens are not recorded. This is a targeted interaction trace, not a guarantee that every internal operation is captured. Asynchronous writes can lose the newest pending events if the process is killed; a full queue drops records without blocking travel.

## Privacy and persistence

The schema has no field for coordinates, place/trip names, saved IDs, addresses, query text, voice recordings/transcripts, API keys, automation tokens, installation identity, raw exception messages, provider payloads, URLs or account details. Export is reconstructed from validated typed records, so unknown properties in a corrupt stored file are not forwarded.

The log remains in private Android `noBackupFilesDir`, outside normal app backup. Retention is bounded by 1,000 events, 512 KiB and seven days; age limits are applied when the store is opened, changed or exported. A bounded queue and a single background writer serialize records and controls. Writes atomically replace the file. Corrupt/unreadable history pauses recording instead of silently enabling it. Diagnostics failures do not interrupt route planning or simulation.

No server endpoint, analytics SDK, automatic upload, runtime self-modification or training pipeline is introduced. Diagnostic exports remain user data and must not be committed to the public source repository.

## Acceptance

Required: existing build/unit/lint/signing/emulator/voice gates; upgrade from 0.15.2 without uninstalling; bounded and persistent history; corruption handling; nonfatal I/O errors; clear/pause ordering; exported privacy sentinels absent; matched asynchronous operation IDs; actual saved-place interaction evidence; accessible controls with large text and keyboard. Physical-phone confirmation remains separate from emulator results.
