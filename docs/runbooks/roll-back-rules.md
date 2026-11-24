# Roll back a bad rules file

**When:** a published rules update blocks something it shouldn't (DMs, posting, regular videos) or stops blocking reels.

## Know this first
A phone keeps the last remote file it downloaded, and it only replaces it with a file whose `version` is **higher**. Republishing an older file with a lower version therefore does nothing on phones that already have the bad one. **Rolling back means publishing the good rules with a new, higher version.**

## Steps
1. Find the last good rules: `git log -- app/src/main/assets/rules.json`, then `git show <good-commit>:app/src/main/assets/rules.json`.
2. Restore that content into `rules.json` and set `"version"` to **the bad version + 1**.
3. Run `./gradlew :app:testDebugUnitTest`.
4. Publish it right away ([publish-rules-update](publish-rules-update.md), step 5). For a rollback to known-good content, the review can come after publishing.
5. Commit with type `rules`, for example `rules(rules): v7, roll back v6 (blocked DM threads)`.
6. Tell testers to open Unrot › **Check for updates**, or wait up to about 12 hours.

## Check
Same as [publish-rules-update](publish-rules-update.md#check). The rules card should show the new version, and the bad behaviour should be gone.

## Emergency off switch
To stop all blocking on the phones fast while you work out a fix, publish a version whose targets are empty:
```json
{ "schema": 1, "version": <bad+1>, "apps": {} }
```
Blocking and patches stop on the next fetch, and the app keeps running. Follow up with a proper fix at a higher version.

## Afterwards
Write a regression test from the dump that showed the problem (`fix-bug` skill), so the same rule can't come back.
