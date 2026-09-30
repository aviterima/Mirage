# Mirage 0.12.0 release notes

Status: signed and emulator-tested release. Version code 33. Install over 0.11.3 without uninstalling.

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

The separate Qwen model is about 610 MiB. Download it over Wi-Fi through Talk to Mirage. The APK itself continues to include the existing Vosk speech model. This build targets arm64-v8a and x86_64 Android 8.0 or later. Place lookup and road routing still require a Maps connection.

Use the supplied signed APK for an in-place update. Do not uninstall to work around a signing mismatch. The local development APK uses a different key and is not a suitable update for the previously delivered app.

## Limits to understand

The first local-model integration supports one change at a time. It is not a general conversational assistant. Say a real saved name or a full destination and city; vague references require clarification. Speech transcription is still Vosk, so the new language model does not guarantee better recognition of every proper name. A 40-item model catalog limit does not restrict the full touch picker.

Unsaved running edits are not a durable process-recovery checkpoint. Save a named itinerary to preserve it. Physical-phone microphone, latency, battery, and thermal behavior still require testing.

## Validation and release status

The final signed build passed 101 JVM tests and 15 emulator tests, with zero failures or skips. These include live add/save/update/reload, the map-space gate, saved-item continuation, Stop, and real offline native inference. The upgrade from the exact delivered 0.11.3 APK preserved stored records and settings without uninstalling.

Native inference took approximately 12.1 seconds on the four-core acceptance emulator. This is not a phone benchmark. The first two-core run reached the 45-second deadline; inference now caps worker count at the available cores, up to four. The deadline and actual-model assertion remain in force. See VALIDATION_0.12.0.md for both runs.

Tested source: `cde80b4d7a016633ff2f5f5c0f706d4ec7b393c2`.
APK SHA-256: `a92ed17acf582498f33213d3bc9bd47842ded9d3c349fe6f348edca3a390428d`.
APK size: 93,537,055 bytes. Physical-phone voice and battery testing is still needed.
