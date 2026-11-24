---
name: record-decision
description: Write down a load-bearing Unrot decision as a short decision record in docs/decisions/. Use when the user settles product behaviour, permissions or Play Store approach, privacy, architecture, or the rules format; not for small implementation details.
---

# Record a decision

## When it's worth a record

Write one when the choice would be expensive or confusing to revisit. For example:
- **Product behaviour:** the block style, the DM exception, friction or what gets blocked.
- **Approach:** using an accessibility overlay rather than a web wrapper.
- **Permissions, Play Store or privacy:** what the app reads, stores or sends.
- **Architecture:** no backend, or rules delivered as static JSON.
- **Distribution or business:** closed source, pricing.
- **The rules schema.**

Code-level details belong in code comments or the README.

## Workflow

1. List `docs/decisions/`. Create it on first use, with a `README.md` index. Pick the next number: `NNNN-short-title.md`.
2. Use this shape and keep it to one screen:

   ```markdown
   # NNNN. <Title>
   Status: Proposed | Accepted | Superseded by NNNN
   Date: YYYY-MM-DD

   ## Context
   What problem or question forced the decision, and the constraints.

   ## Decision
   What was chosen, stated precisely enough to check code against.

   ## Alternatives considered
   Each realistic option and why it lost.

   ## Consequences
   What this makes easier or harder, risks accepted, and what to revisit later.
   ```
3. **Status:** use *Accepted* only for choices the user explicitly made. A change of mind gets a new record, and the old one is marked *Superseded by NNNN*. Never rewrite history in place.
4. Add a line to the `docs/decisions/README.md` index. If the decision changes documented behaviour, update `README.md` in the same change.
5. Don't commit unless the user asks.

## Checks

- [ ] It clears the "worth a record" bar.
- [ ] The number is the next unused one, and the index is updated.
- [ ] Context, Decision, Alternatives and Consequences are all present.
- [ ] *Accepted* only where the user decided, and superseded records point forward.
