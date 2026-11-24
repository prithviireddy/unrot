---
name: write-feat
description: Write or update an Unrot feature spec (FEAT) in docs/feats/ once an idea is settled, or move a FEAT's status forward. Use after sharpen-feature has an approved summary, or when work starts, finishes or is shelved; not for unsettled ideas or for decisions alone (use record-decision).
---

# Write a FEAT

## Workflow

1. **Check it's settled.** There should be an approved summary from `sharpen-feature`. If anything material is still open, go back to `sharpen-feature` instead of guessing.
2. **Read the context:** [`docs/feats/README.md`](../../../docs/feats/README.md) for format and statuses, the decisions in `docs/decisions/` it touches, and [`docs/later.md`](../../../docs/later.md) if the idea comes from there.
3. **Create** `docs/feats/FEAT-NNNN-short-title.md` with the next unused number. Sections:
   - **Status:** one of `Draft`, `Planned`, `In Progress`, `In Review`, `Completed`, `Deferred`, plus an optional short note.
   - **Outcome:** what the user gets, in plain words.
   - **Scope:** numbered, concrete behaviour.
   - **Non-goals:** what's explicitly out, pointing to `later.md` or other FEATs.
   - **Acceptance:** a table of real-phone checks (each one observable, with a ☐ result column) plus the unit tests that must pass. Always include a **false-positive** check: DMs, posting, stories, profiles and regular videos still work.
   - **Risks:** what could make it fail. Detection guesses, app updates, OEM behaviour.
   - **Decisions:** links to `docs/decisions/`. If a load-bearing choice isn't recorded yet, use `record-decision` first.
4. **Set the status.**
   - **Draft** while you're still writing it.
   - **Planned** once the owner approves it.
   - **Never** set **In Progress** until the owner explicitly says to start coding.
5. **Update the index** table in `docs/feats/README.md`. If the idea came from `later.md` or the "Next candidates" list, remove it there.
6. **Later status changes:**
   - **In Review:** built and unit tests pass, waiting on phone checks or `review-change`.
   - **Completed:** only when every acceptance row is ticked. Record the app versions and phone it was checked on.
   - **Deferred:** move the idea back to `later.md` with a link to the FEAT.

## Checks

- [ ] It came from an approved, settled idea.
- [ ] The number is the next unused one, and the index is updated.
- [ ] Acceptance has observable real-phone checks, including false positives, plus unit tests.
- [ ] Non-goals and risks are stated, and decisions are linked.
- [ ] The status matches reality. Nothing is marked In Progress without the owner's go-ahead.
