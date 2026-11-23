---
name: review-change
description: Independently review an Unrot plan or code change with an Opus subagent at max effort, ending with SHIP or NO-SHIP. Use twice for every non-trivial change — on the written plan before coding, and on the implementation before it ships (installed for testers, committed, or uploaded) — and whenever the user asks for a review; not as the author checking their own work in passing.
---

# Review a plan or change

## When
A non-trivial change, such as a feature, a bug fix that touches the service or overlays, or a rules or privacy change, goes through two gates:
1. **Plan gate.** Write the plan first, in `docs/plans/` or the FEAT. Review it here before any code is written. Code starts only after `SHIP`.
2. **Ship gate.** After implementation, review the exact diff here. Only after `SHIP` does it go to testers, get committed, or get uploaded. Installing on the owner's own phone to test may happen while this review runs.

Each gate counts its own rounds (step 6). A device finding made while a review is running is fixed in the same change: send it to the same reviewer as an addition, without starting a new count.

## Who reviews
- **Reviewer:** always a fresh subagent: `Agent` with `model: "opus"`, `effort: "max"`, read-only, never the author. Use another model only when the user asks for one, for example "Fable max" for a final pass.
- **Its brief holds:** this checklist, what's being reviewed (plan path, or diff and files), the why (device evidence), the hard rules that must hold, and the build command. It never holds the author's conclusions.
- **Rounds 2 and 3:** send them to the same reviewer (`SendMessage`), so it checks its own earlier findings.

## Workflow

1. **Pin down what's being reviewed:** either the full plan text or the exact diff (`git diff`, or `git diff <base>..HEAD`). Every reviewer looks at the same thing.
2. **Get a fresh pair of eyes.** If you wrote the change, hand the review to a subagent. Give it the diff and this checklist, not your conclusions.
3. **Review in risk order.**
   1. **Blocking correctness**
      - False positives: could this block DMs, posting, stories, profiles, search or regular YouTube videos?
      - Misses: is any entry point left open?
      - Loops: can Back land on a blocked screen repeatedly? Is the escalation to HOME still intact?
   2. **Overlays**
      - Cover patches must never be left on screen after leaving Instagram or YouTube, over the keyboard, or at the wrong position after rotation or a theme change.
      - The block message must not be touchable.
   3. **Privacy and Play policy**
      - No screen content leaves the device, and nothing new is read outside the two target packages.
      - Inspector dumps stay local until the user shares them.
      - The prominent disclosure still matches what the app does.
   4. **The DM exception and friction:** one reel from a DM, blocked on swipe. Every change that weakens blocking still sits behind the countdown.
   5. **Rules:**
      - `rules.json` still parses.
      - `version` was bumped if the rules changed.
      - Per-version rule sets are ordered correctly.
      - Patterns aren't so broad that they match unrelated screens.
   6. **Performance:** evaluation stays throttled, the tree snapshot is bounded, nodes are recycled below API 33, and there's no work on every content-changed event.
   7. **Compatibility:** minSdk 26 APIs are guarded, restricted settings on Android 13+ are handled, and OEM background killing is covered.
   8. **Verification:** `./gradlew :app:testDebugUnitTest :app:assembleDebug` passes, new behaviour has a test, and anything that needs a real phone is named as untested. If there's a FEAT for the work, the change covers its scope (and nothing outside it), and every acceptance row is either met or listed as still to check on a phone.
   9. **Maintainability:** only where it creates a concrete risk.
4. **Report findings by severity.** A blocker names:
   - the file or plan section
   - the concrete failure (inputs, then what goes wrong)
   - the smallest safe fix

   Nits are listed but never block.
5. **End with a verdict.** The last line is exactly `SHIP` (no blockers left) or `NO-SHIP`. From the second round on, check the earlier findings and the changed parts only.
6. **At most 3 rounds per change, then a manual reviewer.**
   - Count the rounds for one change. Fixes made in answer to a round's findings belong to the same change and don't reset the count. Don't split work into "new" changes to get more rounds.
   - If round 3 is still `NO-SHIP`, stop. Don't fix anything else and don't start a 4th automated round.
   - Write the hand-off to `docs/reviews/<YYYY-MM-DD>-<change>-manual-review.md` (create `docs/reviews/` if needed). For each open blocker, give:
     - the file:line
     - the concrete failure
     - the reviewer's suggested fix
     - what was already tried in earlier rounds
   - Add the build and test status and what's still untested on a phone.
   - Give that file to the user as the manual reviewer, and wait for their decision: fix, accept the risk, or drop the change. Only then continue, and the next review round starts again from round 1.

## Checks

- [ ] The plan was reviewed before coding, and the implementation before shipping, each by an Opus max subagent (or the model the user named).
- [ ] The reviewer was independent of the author.
- [ ] Findings are evidence-based, ordered by severity, and each blocker has a fix.
- [ ] Privacy, false positives and overlay leaks were covered explicitly.
- [ ] Tests and the build were run, or their absence was stated.
- [ ] The last line is exactly `SHIP` or `NO-SHIP`.
- [ ] No more than 3 rounds for this change. A 3rd `NO-SHIP` went to the manual reviewer with a hand-off file, not to a 4th round.
