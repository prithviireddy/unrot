# Features

One file per piece of work worth tracking: what the user gets, what's in and out of scope, and how we'll know it's done.
A FEAT is written once an idea is settled (with the `sharpen-feature` skill) and before any code. Use the `write-feat` skill to write one.

## Naming
`FEAT-NNNN-short-title.md`. Take the next unused number, and never reuse or renumber one.
FEAT and decision numbers are independent. A FEAT links to the decisions it depends on.

## Status
Exactly one of:
- **Draft:** being written or still has open questions.
- **Planned:** approved by the owner, not started.
- **In Progress:** code is being written (only after the owner says go).
- **In Review:** built and tested, and waiting for real-phone acceptance or `review-change`.
- **Completed:** every acceptance check has passed.
- **Deferred:** shelved. Move the idea to [`../later.md`](../later.md) with a pointer back.

## Required sections
Outcome · Scope · Non-goals · Behaviour · Acceptance (real phone + unit tests) · Risks · Decisions (links to `docs/decisions/`).

## Index
| FEAT | Title | Status |
|---|---|---|
| [0001](FEAT-0001-v0.1-mvp.md) | v0.1 MVP: block Reels and Shorts on Android | In Progress |

## Next candidates (not sharpened yet)
- Remote rules hosting: publish `rules.json` on GitHub or Cloudflare Pages and set `RULES_URL`.
- Friend test: 10 testers. What we measure, how to collect feedback, and what counts as "the idea is convincing".
- Play Store listing: accessibility declaration, prominent disclosure review, demo video, privacy policy, store text.
