# Mirage 0.15.2 — saved places in the planner

Status: implementation in progress; new build and acceptance results pending.

## Problem and behavior

Constructing a new route required opening the general saved library, expanding a place card, and finding Use as destination. The prominent Open action loaded the saved place as a separate plan. Typing a saved name such as Home searched online places and did not offer the stored coordinates.

Each planner location field now has a visible Saved places action. For a route, use Destination → Saved places → Home. For an itinerary, use Next stop → Saved places → Home. The starting field has its own saved selector. The selector lists only saved places, searches names, aliases and addresses, and orders favorites and recent items first. Cancel leaves the draft unchanged.

Typing a saved name also displays local matches, even without a Maps key. Selecting one uses its stored coordinates and remembered arrival/stay preferences. The selected planning mode and other endpoint remain intact; an itinerary appends one stop and retains the previous stops. Selection does not start simulation. Online place lookup remains separate and cannot overwrite a saved choice after it is selected.

Use Save in the planning footer to store a named route or itinerary without starting it. Later, Open loads the saved draft for editing. Start remains explicit.

## Acceptance required

- Fresh route: select an origin, choose saved Home as destination, save and reopen; origin and destination must survive and simulation must remain off.
- Fresh itinerary: add existing stops, choose saved Home as the next stop, save and reopen; order, stay settings and origin must survive.
- Picker cancellation must not change the draft. Saved-name and alias matches work without an API key and do not silently select ambiguous names.
- Selecting a saved place cancels stale online results; saved arrival/entrance/stay metadata survives; undo removes only the appended stop.
- Existing build, unit, lint, emulator, voice and signed-upgrade gates remain required. Upgrade from 0.15.1 must retain saved records, key preference and install identity without uninstalling.

Physical-phone confirmation is separate from emulator results. The prior 0.15.1 report does not validate this change.
