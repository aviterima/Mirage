# Mirage 0.16.1 — place names with cities

Status: implementation and validation in progress. Version `0.16.1`, code `42`, on `codex/place-city-labels-20261012`, based on verified 0.16.0 at `dc125088bdf69a5f868c4965eb758adaa33d938f`.

## Behavior

Location lookup displays the place name followed by its city in parentheses, for example **Western Digital (Irvine)**. That same name follows the chosen result into start/destination fields, itinerary stops and new place settings. The address remains separate.

The city comes from structured Places address components: `locality`, then `postal_town` when no locality is supplied. County, state and postal code are not guessed as a city. Missing city information leaves the name unchanged; an existing identical city suffix is not appended twice.

When saving a location in Jump mode, the editable Name field starts with the selected location name. Typing a custom name keeps that edit through recomposition. Route, itinerary and active-trip names retain their existing behavior. Existing saved records and custom labels are not renamed or migrated.

Voice comparisons accept the full spoken name and city without parentheses, such as "Western Digital Irvine". All name and city words must match; ambiguous names still require clarification, and trip changes still require confirmation. Displayed and stored names keep their parentheses.

No extra place-details or geocoding request is introduced. The Places search field mask requests address components in the existing response; the gateway already forwards this field mask. No server deployment is needed. Audit privacy boundaries remain unchanged: place names, addresses and query text are not recorded.

## Verification

Pending: build, JVM tests, lint, emulator lookup-to-save journeys and the signed in-place upgrade from 0.16.0. Prior release test results do not validate these changes. Physical-phone and live Places/Directions confirmation remain separate from fixtures.
