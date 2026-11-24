# 0005. Closed source
Status: Accepted
Date: 2026-11-17

## Context
Open source builds trust for an app that asks for accessibility access, but it makes the app easier to clone.

## Decision
The app's source code stays in a private repository. The detection rules file is hosted publicly (see [0006](0006-no-backend-remote-json-rules.md)), which is acceptable because it only contains view ids and patterns.

## Alternatives considered
- **A public GitHub repo.** More trust, but easier to clone.
- **Decide later.**

## Consequences
- Trust has to come from elsewhere: a clear prominent disclosure, collecting no data, no account, no analytics, and a plain privacy policy.
- No open-source license and no public contributor setup.
