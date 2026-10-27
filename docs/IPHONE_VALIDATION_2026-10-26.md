# Mirage iPhone engineering validation — 26 October 2026

Status: implementation candidate; compilation and behavioral validation in progress. No iPhone release, signed installer or feature-parity claim.

## Source and preservation

Existing source: `C:/CodexProjects/workspaces/Mirage/worktrees/planner-saved-places`, Android baseline documentation commit `58643f922d2cd3b55496b19d435dbb567ec7780f`. Work extends the existing uncommitted iPhone models and parity specification. Android sources and installers remain unchanged.

Validation snapshots use the isolated `codex/iphone-implementation-20261026` branch. The mapped worktree remains on its existing branch with its working files preserved. No replacement application repository or relocated source was created.

## Checks

| Check | Status |
| --- | --- |
| Specification written before implementation | Complete |
| Local JSON fixture parsing | Passed for synthetic three-item backup |
| Local whitespace check | Passed |
| Local Swift / Xcode execution | Unavailable on this Windows host |
| Swift core behavioral tests | Pending macOS CI |
| Rust transport host tests | Pending macOS CI |
| Simulator app compilation | Pending macOS CI |
| ARM64 device bridge and app compilation | Pending macOS CI |
| Native UI tests and screenshot inspection | Pending macOS CI |
| Signing and iPhone installation | Unrun; development team/phone not supplied |
| Paired device output and consumer verification | Unrun |
| Background/lock/network/low-power/termination recovery | Unrun |
| Full Android feature parity and CarPlay | Not complete |

The optional local YAML parse attempt could not run because PyYAML is not installed. Project/workflow parsing is delegated to the actual macOS/GitHub build tools, not counted as passed locally.

## Remaining product work

Complete the eight-case physical proof in the parity specification before device-output acceptance. Then finish native planning/provider integrations, scheduled itinerary behavior, hiking, offline voice and physical UI ergonomics. Current saved-template interchange preserves those fields, but preservation does not mean the proof player executes all their semantics. No online speech substitution or external deployment is enabled.
