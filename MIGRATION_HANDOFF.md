# Portfolio engineering handoff — 04 — Mirage

Migration date: 25 September 2026. Owner: Armando Viteri.

## Source authority

Reference inspected before this documentation change: 3fd315c115f816b07336de2942ba156dd90f6303.

Canonical repository: https://github.com/aviterima/Mirage . main is the integrated source. The 0.11.0 voice/chat candidate is PR #1. A newer 0.11.1 highway-speed/flight-timing candidate is draft PR #2, branch codex/highway-speed-0.11.1, observed at 034ac3cf02f0d4f774cb27f0688d200cdb169933 on September 25. Its build checks were in progress during migration. Keep both candidates distinct from main until release acceptance; recheck the PRs before continuing. Preserve HANDOFF.md, UX specifications, wake-word requirements, Android history, existing signing identity and APK provenance. Migration does not imply physical-device acceptance or a merged release.

## Continuing work

Read HANDOFF.md, README.md, android/README.md and the current UX/voice candidate specification. Codex owns engineering; Work owns requirements, decisions and project operations. Follow existing test/release gates, preserve the mock-location declarations, and retain a working Stop path. Do not expose build keys or signing material.

The canonical operating folder is 04 — Mirage, with PROJECT BRIEF, CURRENT STATE, DECISIONS, BACKLOG / NEXT ACTIONS, HANDOFF, RELEASES / CHANGELOG, CANONICAL REPOSITORY, DEVELOPMENT / DEPLOYMENT, WORK / CODEX BOUNDARY and asset/reference registers. Read CURRENT STATE first. Update those same document identities after work instead of creating competing final versions.

## Responsibility and release boundary

Work maintains research, strategy, content and operating documents. Codex implements and validates engineering changes in this repository. Hand off an approved requirement, source baseline and acceptance criteria; return the branch/commit, test evidence, build artifact and deployment identifier when applicable. Existing repository instructions and later owner decisions remain applicable.

This documentation migration changes no application code, live service, domain, Worker, signing key, access policy or production data. It does not provision a separate Codex cloud environment. Credentials stay in GitHub, Cloudflare, the host secret configuration, the model/email service or other existing service-specific stores. Never copy credential values into this document.
