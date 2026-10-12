# Mirage 0.15.2 validation

Validated October 11, 2026 Pacific (October 12 UTC). Version: `0.15.2` / code `40`. Tested source: `51de0d09c488180a5df24f98485665e006d4fbdd` on `codex/planner-saved-places-20261011`.

## Automated evidence

- [Android build 38198768320](https://github.com/aviterima/Mirage/actions/runs/38198768320): passed compilation, 164 JVM tests (zero failures or ignored tests), blocking lint, packaged voice-model checks and the pinned signing-certificate check. The test count was read from the downloaded unit report.
- [Acceptance 38198768314](https://github.com/aviterima/Mirage/actions/runs/38198768314): passed all 32 Android emulator tests, with zero failures, errors or skips. Its downloaded XML also confirms 164 JVM tests with zero failures, errors or skips. The three new Home-selection journeys and the large-text/keyboard journey passed, along with existing playback, recovery and offline/contextual voice tests.
- In-place upgrade from 0.15.1 passed without uninstalling. Saved-record XML, key preference and installation identity matched byte-for-byte before and after the update.
- The separate live trail-service job passed. Online Places/Directions and physical-phone behavior were not newly validated by the deterministic planner journeys.
- Independent source review found no actionable issues. Original source checkouts and their branches were preserved; implementation uses a linked worktree of the existing repository.

## Regression scope

New JVM coverage checks saved destination/start selection, real and simulated origin policy, prior itinerary stops, arrival/entrance/stay metadata, local names and aliases without network access, save/reopen without starting, invalid source rejection, stale search cancellation, remote lookup errors and delayed automatic GPS fixes.

New emulator journeys select Home in a fresh route, append Home to an existing itinerary and choose Home by a typed alias. They check cancellation, save/reopen and simulation remaining off. The existing large-text/keyboard journey also appends Home after 24 stops.

## Installer identity

The installer was reconstructed unchanged from the acceptance run's four `mirage-tested-apk-part-*` artifacts. Every artifact ZIP matched GitHub's SHA-256 digest; the reconstructed APK matched the workflow checksum. All 239 APK ZIP members passed CRC checks. The binary manifest confirms the package and version below; the extracted v2 certificate fingerprint matches the existing identity. Local fingerprint extraction is not a separate cryptographic signature-verification step; the pinned CI check and Android in-place installation also passed.

- Filename: `Mirage-0.15.2.apk`
- Package: `com.mirage.app`
- Version: `0.15.2` / code `40`
- Size: 93,799,988 bytes
- SHA-256: `d4f0f3f46f36aecab01815a2264053698ea9215f2a30f24b674f461dd1bddc2d`
- Signing certificate SHA-256: `e51683b8f4161d31fe4e81fe788438640151f8efb9aeba693a4ce21ad72cc8b8`

Install over the existing app; do not uninstall first. The delivered Drive file's name and size were read back after upload. A final documentation-only commit follows the tested source.

## Visual evidence

Reviewed `planner-new-route-saved-home.png`, `planner-new-itinerary-saved-home.png`, `planner-saved-home-typeahead.png` and `large-text-keyboard-planner.png` from the acceptance artifact. They show the direct Destination/Next stop Saved places controls, Home selected in a route and appended after Office, and simulation off. The keyboard/large-text capture shows Saved places and the planning footer remaining visible.

These captures are not covered by the earlier release's Pixel Launcher dialog. Some upper planner text is partially rendered in the itinerary and typeahead captures, so this is not a complete layout sign-off; physical-phone layout confirmation remains open.

## Remaining acceptance

Physical-phone verification remains outstanding, including the user's stored Home record and device layout, microphone/wake-word behavior, latency, battery and heat. Emulator and fixture results do not establish those phone-specific outcomes.
