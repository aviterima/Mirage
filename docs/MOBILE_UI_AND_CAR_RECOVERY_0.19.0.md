# Mobile planning and car location recovery — 0.19.0

Status: implementation in progress, not a verified release. Baseline: verified 0.18.0 at `159972b0e33575b90903421e73e7c296f4de8d9c`. Work remains in the existing `planner-saved-places` worktree and `codex/contextual-voice-input-20261012` branch. Version 0.19.0 / 45 is reserved for this change.

## Request and design

The user reported cramped, clumsy planning on a phone and asked for another UI pass informed by leading map apps. The user also reported that location reverts while connected to Android Auto and returns to the simulated location after Wi-Fi is manually disabled, with several reconnection attempts.

The design follows a visible sequence: choose places, review the plan, explicitly Start. Full-width search fields and nearby results take priority over secondary controls. A Show map / Edit plan control preserves the draft while making the map usable. The itinerary follows place entry and results; its detailed controls can be collapsed. Save and Start remain separate and unfinished entries continue to block Start from every entry point. Offline microphones continue to fill editable text without interpretation or hosted processing.

Reference patterns: [Google Maps destination and trip selection](https://support.google.com/maps/answer/10271256), [Android secondary content in bottom sheets](https://developer.android.com/develop/ui/compose/quick-guides/content/create-bottom-sheet), and [Android minimum 48dp touch targets](https://support.google.com/accessibility/android/answer/7101858). These inform the changes; a visual resemblance does not establish usability on the user's phone.

## Android Auto: evidence and boundaries

The current service emits to GPS, network and Google fused location. Before this change its watchdog inspected only cached GPS, did not actively recover from observed real-location leakage, and did not consistently retry failed fused mock-mode activation. Those are source-confirmed defects independent of the unverified cause in the user's car.

Wireless Android Auto uses Wi-Fi. Disabling Wi-Fi may fix the symptom by ending projection. This is a hypothesis, not proof that Wi-Fi scanning is the source of the real location. Car hardware can supply location to supported car apps, but the public API does not establish which source Google Maps used in this incident. Mirage's accepted mock writes do not prove the location shown by another application or a head unit.

Ordinary apps targeting Android 10 or newer cannot enable or disable Wi-Fi. Mirage targets API 34. There is no supported ordinary-app switch that can force Wi-Fi off and keep it off. No root, device-owner provisioning, accessibility click automation, permission escalation or hidden settings change is introduced. The app can explain the limit and open the system Wi-Fi settings for the user.

The recovery change adds bounded, coalesced retry after transient fused failures and fresh real-location readback, with lifecycle ownership so stale completions cannot revive a stopped run. Fused mock acceptance and observed readback are distinct signals. Cached observations predating the current run must not be treated as a new leak. Persistent failures remain visible rather than being reported as healthy.

Sources: [Android Wi-Fi toggle restrictions](https://developer.android.com/about/versions/10/privacy/changes#enable-disable-wifi), [Google fused mock mode](https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient#setMockMode(boolean)), [car hardware location APIs](https://developer.android.com/training/cars/apps/library/car-hardware-api), and [Android Auto connection requirements](https://support.google.com/androidauto/answer/6348029).

## Acceptance

Required: JVM recovery-policy tests, existing baseline tests, Android planner interaction tests at narrow width and larger text, signed 0.18.0-to-0.19.0 upgrade preserving seeded records and key/install identity, blocking lint, packaged offline speech and signing checks, and visual review of screenshots. CI must run against the final production source; failures and unrun checks are recorded separately.

Physical follow-up remains essential: compare phone Maps and the car display with Android Auto disconnected, wired and wireless; keep the same simulated point; record whether Mirage reports recovery or stale output; repeat manual Wi-Fi-off and inspect reconnection behavior. Test parked. Neither emulator coverage nor successful fused writes certifies Google Maps or a head unit. Phone ergonomics, physical microphone accuracy, long sessions and live Places/Directions remain open until observed.

## Verification record

Pending. No installer is certified by this document yet. The latest verified installer remains 0.18.0.

Initial source `844dae9f7e7a4490649c8befdbee638f686f4688` failed compilation in the two new car-help callbacks because a local keyboard-dismiss helper was out of scope. No JVM, Android, upgrade or lint result is claimed for that source. Its predecessor `bc258067` was superseded and cancelled.

Corrected source `721ed1ffe17d09ca9e6168a5eeb020d61f7a077b` compiled and passed **283 JVM tests**, independently counted from XML with zero failures/errors/skips. Build 38602264804 then failed blocking lint with two MissingPermission errors in the new fused-mode/write calls (44 warnings, 12 hints). Acceptance 38602264671 ran **54 Android tests: 51 passed, three failed, zero errors/skips**, independently counted. Failures were a running-state discontinuity during saved-place continuation, combined keyboard/result readiness in the new compact fixture, and nested-scroll visibility of the 25th itinerary stop. The later compact geometry assertions were unrun. No unchanged retry is used to dismiss these failures.

The signed 0.18.0-to-0.19.0 upgrade and trail checks passed at `721ed1f`; seeded scenario and key/install-identity files independently compared byte-identical. Four selected evidence ZIPs matched GitHub size/SHA-256 and passed CRC. Evidence is retained under the registered entry folder at `builds/0.19.0/evidence-721ed1`. Visual review confirmed the full-width fields and preserved Map/Edit draft, but also showed crowded keyboard search and secondary footer labels. Follow-up changes and complete acceptance remain pending.
