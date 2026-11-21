---
name: review-change
description: Independently review an Unrot plan or code change and end with SHIP or NO-SHIP. Use before installing a build on a phone for testers, before a Play upload, or when the user asks for a review; not as the author checking their own work in passing.
---

# Review a plan or change

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
5. **End with a verdict.** The last line is exactly `SHIP` (no blockers left) or `NO-SHIP`. From the second round on, check the earlier findings and the changed parts only. Allow at most 3 rounds; after that, escalate to the user.

## Checks

- [ ] The reviewer was independent of the author.
- [ ] Findings are evidence-based, ordered by severity, and each blocker has a fix.
- [ ] Privacy, false positives and overlay leaks were covered explicitly.
- [ ] Tests and the build were run, or their absence was stated.
- [ ] The last line is exactly `SHIP` or `NO-SHIP`.
