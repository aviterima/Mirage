# Mirage 0.15.2

Saved locations are easier to use while constructing a route or itinerary.

- **Route:** Destination → Saved places → Home selects your saved Home as the destination.
- **Itinerary:** Next stop → Saved places → Home appends Home after the stops already entered.
- **Start:** its Saved places button selects a stored starting location.
- Typing a saved name or alias also offers local matches, including when online place lookup is unavailable. The picker can search names, aliases and addresses.
- Selection preserves the rest of the draft and the place's remembered arrival/stay preferences. Cancel leaves the draft intact. Late GPS and online search responses cannot replace the selected place.

Use **Save** to keep a named route or itinerary for later. Saving does not start simulation; **Start** remains a separate action.

Install this version over 0.15.1 to retain saved places and settings. Do not uninstall first. All 164 JVM tests and 32 emulator tests passed, as did blocking lint and the signed in-place upgrade. See [validation and installer identity](VALIDATION_0.15.2.md) for the evidence and remaining physical-phone checks.
