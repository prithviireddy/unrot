# Capture Inspector dumps

**When:** writing the first rules, after an Instagram or YouTube update breaks detection, or when a tester reports a miss or a false positive.

## Steps
1. **Turn on developer mode.** In Unrot, tap the version text at the bottom of the home screen 7 times. "· developer mode" appears.
2. **Turn on Inspector** in the developer card. An orange **DUMP** button appears at the right edge of the screen, over every app.
3. **Dump each screen.** In Instagram or YouTube, get to the screen, wait a second for it to settle, and tap DUMP. A "Saved dump-…json" message confirms it. The standard set:

   | App | Screen |
   |---|---|
   | Instagram | Home feed with the bottom bar visible (light **and** dark theme) |
   | Instagram | Reels tab |
   | Instagram | A reel opened from the home feed |
   | Instagram | A reel opened from Explore, and one from a profile |
   | Instagram | A DM thread |
   | Instagram | A reel opened from that DM thread |
   | Instagram | Feed scrolled to "You're all caught up", and just past it ("Suggested posts") |
   | Instagram | A profile's Reels grid tab, to check it isn't over-matched |
   | YouTube | Home with the bottom bar visible |
   | YouTube | Shorts tab |
   | YouTube | A Short opened from the home feed shelf |
   | YouTube | A regular video playing, as a false-positive check |
4. **Share them.** In Unrot's developer card, tap **Refresh**, then **Share** on each dump (WhatsApp to yourself, Drive or email). Or pull them all over USB:
   ```bash
   adb exec-out run-as app.unrot tar c files/dumps > dumps.tar
   ```
   `run-as` works only on debug builds.
5. **Note the context:** the phone model and which screen each dump is. The app version is inside the dump (`versionCode`, `versionName`).

## Check
- Each dump has `package`, `versionCode`, `tree` and `matched`. `matched` shows which rule ids hit, which makes it quick to see a miss (empty `blocks` on a reel screen) or a false positive.

## Privacy
Dumps can contain text that was on screen: usernames, captions, **DM messages**. Before sharing or committing one:
- Prefer screens without private chats.
- Strip `text` and `desc` values that are personal (the `fix-bug` skill does this for test fixtures).
- Never put raw dumps in the repo. Their files are named `dump-*.json`.

## Recover
- **No DUMP button:** check that the accessibility service is on (the status card says "Blocking is on") and that both developer mode and Inspector are on.
- **"Nothing to dump":** the active window is not a window of Instagram or YouTube (for example the notification shade, or any other app), or it had no content. Open the screen in Instagram or YouTube, close anything over it, and tap again.
- **Turn it off afterwards:** switch Inspector off, so the button doesn't sit over every app.
