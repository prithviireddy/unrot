# 0006. No backend; detection rules as remote static JSON
Status: Accepted
Date: 2026-11-17

## Context
The app should be free or close to it, with about $0 in running costs. Instagram and YouTube change their interfaces often, and waiting for a Play release to fix detection would be too slow.

## Decision
- **No backend and no accounts.** Settings and counters stay on the phone (DataStore).
- **Rules are data, not code.** Detection rules are a JSON file (the schema is described in `rules/Rules.kt`), with rule sets per app version, bundled in `assets/rules.json`.
- **Remote updates.** A WorkManager job downloads a copy from static hosting (GitHub Pages or Cloudflare Pages) every 12 hours. The remote copy is used only if it parses and its `version` is higher than the bundled one.
- **A hidden Inspector** (developer mode) saves the screen's accessibility tree on the phone, so rules can be written and fixed from real data.

## Alternatives considered
- **Hard-coded detection.** Every fix would need an app release.
- **A config backend.** Costs money, needs maintenance and raises privacy questions, for no benefit over static hosting.

## Consequences
- The ongoing cost is the one-time $25 Play developer fee. Hosting is free.
- Anyone can read the rules file (accepted in [0005](0005-closed-source.md)).
- Rule changes ship without a release, but a bad rules file reaches everyone. Bundled rules are the fallback, invalid files are ignored, and every rule change should go through `review-change`.
