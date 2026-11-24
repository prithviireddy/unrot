# Publish a rules update

**When:** detection needs fixing or extending, and the app code doesn't need to change.

**Remote rules are not live yet.** `RULES_URL` in `app/build.gradle.kts` is blank, so rules ship only inside the APK: follow steps 1–4, then [ship a build](ship-build-to-testers.md) instead of step 5. Hosting comes later, with `RULES_URL` set in a build installed on the phones.

**First response to a bad bar:** set the bar's `"mode"` to `"patch"` in `rules.json` (a plain patch in the bar colour, no redraw, no screenshots). Then fix the detection and bump `"version"`. A patch-only bar is the safe default while the rule is wrong.

## Steps
1. **Base the change on evidence:** a dump from [capture-inspector-dumps](capture-inspector-dumps.md). Follow the `fix-bug` skill: write a failing test from the dump first.
2. **Edit `app/src/main/assets/rules.json`.**
   - Prefer the most specific stable signal: view id, then description, then text.
   - For a new Instagram or YouTube version, **add a rule set** with `minVersionCode`/`maxVersionCode` rather than widening the old one.
   - **Bump `"version"`.** Phones only take a remote file whose version is *higher* than the one they have.
3. **Run the tests:**
   ```bash
   ./gradlew :app:testDebugUnitTest
   ```
4. **Review it:** run the `review-change` skill on the diff. It needs a `SHIP` verdict, with special attention to false positives (DMs, posting, regular videos).
5. **Publish** the same `rules.json` to the hosting location (commit to the Pages repo or branch, or upload to Cloudflare Pages). Keep the published file byte-for-byte identical to the one in the app repo.
6. **Commit** the app repo change with the `commit-and-changelog` skill (type `rules`, with the new version in the subject).

## Check
1. On a test phone, force a fetch: Unrot › Detection rules › **Check for updates** (shown only when `RULES_URL` is set).
2. The rules card should show `Version N (remote)`. Watch `adb logcat -s UnrotRules` for `Fetched rules vN` and `Loaded remote rules vN`.
3. Re-test the screens the change targets, plus the false-positive screens.

Without a manual check, phones pick the file up within about 12 hours, when they're online.

## Recover
- **Check for updates doesn't change the version:** the hosted file is cached, didn't parse, or its version isn't higher than the phone's. Open the URL in a browser, then check `adb logcat -s UnrotRules` for "Rules fetch failed" or "Remote rules invalid".
- **The new rules misbehave:** [roll back](roll-back-rules.md).
