# Mirage 0.19.1 — direct current-location selection

## Behavior

The 0.19.0 planner hid Place options until a field was focused or edited, then hid it again when the keyboard appeared. This made the real-location choice practically undiscoverable on a phone.

Start, Destination, Place and Next stop now have an explicit **Current location** action in the same row as **Saved**, directly beneath the full-width input. Both actions remain present with the keyboard open. Clearing an entry uses the inline cross button beside the microphone. Every action retains a minimum 48dp touch target. Running-source alternatives remain in an overflow menu.

During simulation, the physical-location action is labelled **Last real location**, since it uses the real fix captured before mock output began. Current simulated position and trip-end positions remain separate choices. Idle selection keeps the existing non-mock location provider/fallback policy; it does not establish that every fallback fix is recent.

Explicit selection synchronizes editable text even when the selected name is unchanged. Next stop appends a stop without replacing earlier stops. Selection does not start simulation. A pending lookup disables Save and Start; it is bounded to 30 seconds and late results cannot overwrite a newer edit, selection, plan mode or draft. Explicit selection cancels the automatic startup recenter lookup so that it cannot race the chosen endpoint.

The current release preserves offline dictation, saved data, signing identity and the 0.19.0 Android Auto behavior. Wireless Android Auto still needs Wi-Fi; no forced Wi-Fi toggle is added. Physical phone and car acceptance remain open.

## Verification

Candidate version 0.19.1, versionCode 46. Automated validation is pending. The previous verified release remains 0.19.0 until this candidate passes build, emulator, signed-upgrade and exact-installer checks.

The regression suite covers visible idle Start/Destination actions, keyboard access, permission help without losing entered text, cached-real selection for both endpoints with same-label unfinished input, itinerary append, and asynchronous lookup guards. The existing physical 360×640dp / 1.3× text emulator test continues to require the input, microphone and complete first search result above the real keyboard/footer, now also requiring the direct location action.

Local Android execution is unavailable because this workstation has no Android toolchain/device. The CI upgrade baseline is the exact verified 0.19.0 acceptance APK (run 38652149784), SHA-256 `df3e29beac92b2fabf5c10c7a546cebd0c6cae3d5eaf26d0eb7196edc336a68d`. No physical GPS, microphone acoustics or wireless car test is claimed.
