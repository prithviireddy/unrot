# 0004. A 30-second wait before weakening blocking
Status: Accepted
Date: 2026-11-17

## Context
Users can always turn blocking off. The goal is to make impulsive "just one reel" decisions slower, without tricks that Play dislikes.

## Decision
- Any change that **weakens** blocking shows a 30-second countdown before it can be confirmed. That covers turning off Reels, Shorts or caught-up blocking, and turning on the DM exception.
- Changes that **strengthen** blocking apply at once.
- The default button is "Keep blocking".

## Alternatives considered
- **Typing a sentence.** Stronger, but more annoying.
- **A wait that grows** with each use per day. A possible later step.
- **No friction.**

## Consequences
- It doesn't stop someone from disabling the accessibility service in system settings. We accept that (see [0001](0001-android-first-accessibility-overlay.md)).
- The wait length is a setting (`frictionSeconds`), so it can be tuned after the friend test.
