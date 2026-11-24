---
name: fix-bug
description: Fix a reproducible Unrot bug at its root cause, with a regression test. Use when blocking misfires (a reel plays, the wrong screen is blocked, a patch is misplaced or stuck) or the app misbehaves; only after the user has said to work on it.
---

# Fix a bug

## Workflow

1. **Reproduce it and capture evidence.**
   - **Detection bugs:** get an Inspector dump of the exact screen from the user ([capture-inspector-dumps runbook](../../../docs/runbooks/capture-inspector-dumps.md)). Note the Instagram or YouTube version from the dump, the phone, and whether it's a *miss* (it should have been blocked) or a *false positive* (it shouldn't have been).
   - **Behaviour bugs:** loops, stuck patches, counters or friction. Get the steps to reproduce and `adb logcat -s UnrotService UnrotRules UnrotInspector`.
2. **Find the root cause.**
   - Is it a rule that's too narrow or too broad?
   - A new app version that needs its own rule set (`minVersionCode`/`maxVersionCode`)?
   - Policy state (the DM pass or entering/leaving screens)?
   - Or the service itself (throttling, the keyboard check, the cover poll)?
3. **Write a failing test first.**
   - Turn the dump into a fixture under `app/src/test/resources/dumps/`. Strip personal text first: usernames, captions and DM content. Keep the ids, descriptions, classes and bounds.
   - Assert the expected detection or decision in `RulesAndPolicyTest.kt` or a new test.
   - If there's no dump-to-`NodeSnap` loader yet, add one in test code.
   - Confirm the test fails before the fix.
4. **Fix the root cause.**
   - **For rules:** prefer the most specific stable signal (view id, then description, then text). Add a rule set for the new app version rather than widening the old one, and bump `version` in `rules.json`.
   - **Never** fix a miss with a pattern so broad it risks blocking DMs, posting or regular videos.
   - **No shortcuts:** no swallowed exceptions, and no "skip" flags that hide the symptom.
5. **Verify.**
   - Run `./gradlew :app:testDebugUnitTest`, then `:app:assembleDebug`.
   - Ask the user to confirm on the phone. Say plainly that it's untested on a device until they do.
   - If only `rules.json` changed and remote rules are set up, ship it with the [publish-rules-update runbook](../../../docs/runbooks/publish-rules-update.md). Otherwise, ship a new build.
6. If the same kind of breakage keeps coming back, suggest a lasting fix to the user (a new rule field, an Inspector improvement or a test) instead of patching it each time.

## Checks

- [ ] The bug was reproduced from a dump or logs, not guessed.
- [ ] A regression test failed before the fix and passes after it.
- [ ] Fixtures contain no personal text.
- [ ] Rule changes bumped `version` and didn't broaden matching beyond the screen in question.
- [ ] Tests and the build pass, and real-device confirmation is requested.
