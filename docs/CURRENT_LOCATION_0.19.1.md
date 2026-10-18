# Mirage 0.19.1 — direct current-location selection

## Behavior

The 0.19.0 planner hid Place options until a field was focused or edited, then hid it again when the keyboard appeared. This made the real-location choice practically undiscoverable on a phone.

Start, Destination, Place and Next stop now have an explicit **Current location** action in the same row as **Saved**, directly beneath the full-width input. Both actions remain present with the keyboard open. Clearing an entry uses the inline cross button beside the microphone. Every action retains a minimum 48dp touch target. Running-source alternatives remain in an overflow menu.

During simulation, the physical-location action is labelled **Last real location**, since it uses the real fix captured before mock output began. Current simulated position and trip-end positions remain separate choices. Idle selection keeps the existing non-mock location provider/fallback policy; it does not establish that every fallback fix is recent.

Explicit selection synchronizes editable text even when the selected name is unchanged. Next stop appends a stop without replacing earlier stops. Selection does not start simulation. A pending lookup disables Save and Start; it is bounded to 30 seconds and late results cannot overwrite a newer edit, selection, plan mode or draft. Explicit selection cancels the automatic startup recenter lookup so that it cannot race the chosen endpoint.

The current release preserves offline dictation, saved data, signing identity and the 0.19.0 Android Auto behavior. Wireless Android Auto still needs Wi-Fi; no forced Wi-Fi toggle is added. Physical phone and car acceptance remain open.

Stop reordering now uses the finger's actual position to trigger edge scrolling. A handle near the top of a tall row no longer triggers scrolling merely because the row's center enters the lower edge band. Row-center drop targeting and the alternative Move up / Move down controls are preserved.

## Verification

Version 0.19.1, versionCode 46. App validation passed on 16 October 2026: 285 JVM and 59 Android tests, zero failures/errors/skips. On 18 October the unchanged live hiking check passed locally through the backup provider, covering named search, Tucson browsing and mapped paths. The GitHub trail job remains failed after provider/network errors; its status and evidence are preserved. The exact tested 0.19.1 installer and its full hosted download were verified.

The regression suite covers visible idle Start/Destination actions, keyboard access, permission help without losing entered text, cached-real selection for both endpoints with same-label unfinished input, itinerary append, and asynchronous lookup guards. The existing physical 360×640dp / 1.3× text emulator test continues to require the input, microphone and complete first search result above the real keyboard/footer, now also requiring the direct location action.

Local Android execution is unavailable because this workstation has no Android toolchain/device. The CI upgrade baseline is the exact verified 0.19.0 acceptance APK (run 38652149784), SHA-256 `df3e29beac92b2fabf5c10c7a546cebd0c6cae3d5eaf26d0eb7196edc336a68d`. No physical GPS, microphone acoustics or wireless car test is claimed.

Initial candidate `b5724d79ce07fe07310a4bb4d73dc08129e3d580`: build 38912888672 passed; independently parsed XML records 285 JVM passes. Acceptance 38912888420 recorded 59 Android cases, 58 passed and one drag-order failure, zero errors/skips. All four current-location tests and the compact keyboard test passed. The drag screenshot showed First moved two positions instead of one. The follow-up corrects pointer-based edge scrolling and gives the single-slot test destination a full-row margin from the edge; exact intermediate and saved-order assertions remain. Four focused preflight journeys now precede the complete suite. The initial live-trail job also failed after external HTTP 504/502 errors; its evidence is retained separately.

## Completed app verification — 16 October 2026

Tested source: `b91cee13aed8d020510f6409b1f8a6ec5f73bfa7`. [Build 38918788140](https://github.com/aviterima/Mirage/actions/runs/38918788140) passed. The emulator job in [acceptance 38918788139](https://github.com/aviterima/Mirage/actions/runs/38918788139) passed all **59 Android tests**, and independently parsed XML confirms **285 JVM tests**, zero failures/errors/skips. Four focused preflight cases (paused continuation, saved itinerary, exact drag order and compact keyboard) also passed; these repeat existing cases rather than increasing the unique count.

Blocking lint passed: zero errors, 44 warnings and 12 hints. Pinned v2 signing and bundled offline-speech checks passed. The signed 0.19.0-to-0.19.1 upgrade passed without uninstalling; seeded saved scenarios and key/install-identity files were independently compared byte-for-byte.

Final emulator screenshots show visible Current/Last real location shortcuts and usable input, microphone, clear, result and footer controls above the real keyboard at 360×640dp with 1.3× text. The drag image shows the intended intermediate Second/First order; the settled reload shows Second/Third/First after the deliberate Move down action. Pending lookup visibly disables Start/Save. These checks do not establish physical-phone GPS accuracy or wireless-car behavior.

The exact acceptance APK was assembled locally: 131952117 bytes, SHA-256 `0794b4b3c5fadb0cf6039c4afde5ed0c006b16a73982fe7da4f7543591a0c04b`, package `com.mirage.app`, version 0.19.1/code 46. Ten selected build/acceptance archives matched metadata sizes/SHA-256 and passed ZIP CRC checks; their recorded source is the tested revision. Local manifest and certificate-fingerprint checks passed; CI performed cryptographic signature verification. The local ZIP is 88691281 bytes, SHA-256 `d8f54d7a6a6c5cdcda72655e58fbdef04e584c6ec701761e8dad3192b4db5e51`, contains only this APK and reproduces its checksum when extracted. Delivery was held on 16 October pending the live hiking check. On 18 October, after that unchanged check passed locally, the ZIP was uploaded and downloaded in full. The hosted copy matched the exact ZIP size/SHA-256, passed CRC checks, contained only the APK, and reproduced its SHA-256. Sharing settings were unchanged; the private installer link remains in chat.

**Historical external-service failures — 16 October 2026.** The initial b91cee1 trail attempt passed named search but failed city browsing after HTTP 504/502. The second attempt failed named search after HTTP 504 and a read timeout; the third failed named search after HTTP 504/502. Both providers were tried. All failed evidence is retained separately. No trail test was weakened, skipped or marked as passing. Delivery remained held on that date. The successful unchanged local check below resolved the outstanding live-service validation without a waiver.

## Live hiking retry and hosted delivery — 18 October 2026

The GitHub retry (job `117795674887` in acceptance run `38918788139`) failed named search: the primary returned HTTP 504 and the backup returned network unreachable. Its failed artifact `12151910269` is retained. The overall GitHub acceptance workflow remains **failure**, while its emulator job passed.

The identical committed `.github/scripts/check-trail-service.py` then ran on this Windows workstation with normal TLS verification. It exited **0** and printed its final city-geometry PASS, observed at 2026-10-18 21:42:51 UTC. All three stages succeeded through `https://overpass-api.de/api/interpreter`: Echo Canyon named search returned **5** elements; Tucson browsing returned **1000**; selected mapped geometry returned **34**. The primary failed local certificate validation and was skipped during its existing retry cooldown for the middle stage; TLS verification was not disabled. This is successful fallback, not evidence that the primary recovered.

The working script SHA-256 is `c54d83ac66a3d32b047a10564ee0e75cc8c6ae4480112364f56f884b9c9db1be`; it matches the committed tested source after expected CRLF normalization. Independent offline verification reproduced every script assertion from saved responses and confirmed the 34 geometry IDs exactly match the selected records. The script, assertions, endpoint order and providers were unchanged. No test was waived.

Release verification therefore combines the passed CI app checks with the identical live-provider check passed locally and the exact hosted installer readback. It does not relabel the failed CI workflow. The smoke check covers live provider lookup and geometry, not every trail, city geocoding, parking/routing, future provider availability, physical phone GPS or wireless Android Auto. Those physical acceptance gaps remain open.
