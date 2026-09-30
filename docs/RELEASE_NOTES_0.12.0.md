# Mirage 0.12.0 candidate release notes

Status: source candidate, not yet an approved signed APK. Version code 33. The latest delivered APK remains 0.11.3.

## User-visible changes

- The live map has a labeled **My itinerary** entry.
- The editor shows visited/current/upcoming stops, travel modes, addresses, and stays.
- Add a stop through the existing Search / Snaps / Routes / Itineraries picker while the simulation remains active.
- Edit upcoming stop order, remove stops, change travel modes, and adjust stays.
- See **Unsaved changes**; save a named itinerary; use **Save changes** to update it or **Save as new itinerary** to preserve another version.
- Save uses the actual running trip, not an unrelated draft. A failed disk write cannot report success.
- **Talk to Mirage** gains a one-time advanced-voice model download. Afterwards, the interpretation stage works on the phone without a cloud-model call.
- Advanced commands produce a review and ask for confirmation. The app validates the actual live session and saved names before applying changes.
- Basic Stop/Pause/Resume commands remain direct and do not wait for the language model.

## Download and compatibility

The separate Qwen model is about 610 MiB. Download it over Wi-Fi through Talk to Mirage. The APK itself continues to include the existing Vosk speech model. The candidate targets arm64-v8a and x86_64 Android 8.0 or later. Place lookup and road routing still require a Maps connection.

Use an in-place update when a signed build is released. Do not uninstall to work around a signing mismatch. The local development APK uses a different key and is not a suitable update for the previously delivered app.

## Limits to understand

The first local-model integration supports one change at a time. It is not a general conversational assistant. Say a real saved name or a full destination and city; vague references require clarification. Speech transcription is still Vosk, so the new language model does not guarantee better recognition of every proper name. A 40-item model catalog limit does not restrict the full touch picker.

Unsaved running edits are not a durable process-recovery checkpoint. Save a named itinerary to preserve it. Physical-phone microphone, latency, battery, and thermal behavior still require testing.

## Validation and release status

Local Android app/test packages build, and all 101 JVM tests pass. Emulator execution, signed upgrade, and phone validation remain pending. See VALIDATION_0.12.0.md. Do not transfer previous 0.11.3 pass counts to this candidate. Repository upload and the permanent signing pipeline are blocked by automatic approval review; they require explicit authorization for this source payload and destination.
