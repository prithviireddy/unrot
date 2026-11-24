# Unrot

Android app that blocks Instagram Reels and YouTube Shorts with an AccessibilityService. Private repo.

## Docs
- [Decisions](docs/decisions/README.md): why it works this way
- [Features](docs/feats/README.md): what we're building, with acceptance checks
- [Runbooks](docs/runbooks/README.md): dumps, rules updates, rollbacks, shipping builds
- [Later](docs/later.md): shelved ideas and what would bring them back

## How it works
```
BlockerService (AccessibilityService, only Instagram + YouTube)
  NodeSnap     snapshot of the accessibility tree (the bar's own tab nodes are kept live, for taps)
  Detection    runs the JSON rules → blocks / markers / covers / the bottom bar layout
  Policy       feature toggles, DM exception (one reel, blocked on swipe)
  Action       Back · tap a view (Home tab for "all caught up") · patch over a tab that can't be redrawn
  Bar          redraws the bottom bar without the Reels/Shorts tab (bar/BarController, service/BarCapture, BarOverlay)
App (Compose)  onboarding, toggles with a 30 s friction delay, blocked-today counter, hidden developer mode
RulesWorker    every 12 h: download rules (if RULES_URL is set) + the watchdog notice if blocking is on but the service is off or not running
Kill check     on connect, the previous run's record (service_state) and exit reasons decide whether a kill
               happened (Home card, notice). A foreground notification while blocking is on, in every build (decision 0012)
```
**The bar redraw.** On Android 11 and later, Unrot draws its own copy of Instagram's and YouTube's bottom bar with the
Reels or Shorts tab left out, and the remaining tabs spaced evenly. Each tab's picture is cropped from an accessibility
screenshot of the app's own bar, so the selected pill, unread badges and the profile picture stay current. A tap is
forwarded to the real tab underneath, so each tab opens the right screen ([0008](docs/decisions/0008-redraw-bottom-bar-sampled-colour.md)).
- **Screenshots.** On Android 14+ only the app's window is captured. On Android 11–13 the redrawn bar is hidden for a
  moment to capture, so the plain patch shows meanwhile.
- **Saved pictures.** The tab pictures are kept as small PNGs in the app's private storage, once disclosure v3 is accepted,
  so a restart needs no new screenshot. They are never sent anywhere. Pictures are deleted when the app's version or the
  rules change, when Simple patch is turned on, or with "Delete saved bar pictures" on the home screen ([0009](docs/decisions/0009-save-bar-pictures-on-phone.md)).
- **Fallbacks.** Android 8–10 (no screenshots), Simple patch, and any bar that can't be redrawn safely get a plain patch in
  the bar's colour over the Reels or Shorts tab.
- **Probes and the fast block.** While the bar is hidden, a bar-back probe watches its window and redraws it from the
  cache when it comes back (after a story, a DM or Home). Each target event also starts a block probe that reads only the
  Reels and Shorts view ids; once two probes agree, the reel is blocked (about half a second from the event). A bar drawn
  before its probe confirms it is untouchable, and it swallows taps for at most 600 ms ([0010](docs/decisions/0010-provisional-bar-swallows-taps.md)).

Rules live in `app/src/main/assets/rules.json` (schema documented at the top of `rules/Rules.kt`). The bundled set comes from
Inspector dumps of Instagram 456 and YouTube 21.45 and 21.46 (on Android 13 and 16). Tune it with the Inspector when an app updates.

## Build and install
```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
On Android 13+, a sideloaded APK can't enable Accessibility until you open
App info › ⋮ › **Allow restricted settings**. Onboarding explains this.

## Tuning rules with the Inspector
1. In Unrot, tap the version text at the bottom **7 times** to turn on developer mode, then turn on **Inspector**.
2. A DUMP button appears at the right edge. Open the screen in Instagram or YouTube (Reels tab, a reel opened from
   the feed, a reel opened from a DM, a DM thread, the "You're all caught up" marker, the Shorts player, the Shorts tab) and tap DUMP.
3. Share the dumps from Unrot (developer card › Share). Each one has the app's version, the tree, and the rule ids that matched.
4. Edit `rules.json`, bump `"version"`, and rebuild. Once hosting is set up, publish instead.

## Publishing rules (no app release)
Host `rules.json` on GitHub Pages or Cloudflare Pages and set `RULES_URL` in `app/build.gradle.kts`.
The app uses the remote copy only when its `version` is higher than the bundled one, and ignores files
that fail to parse or use a newer schema.

## Tests
```bash
./gradlew :app:testDebugUnitTest
```
These test the bundled rules against synthetic trees and the real dumps, the DM-exception and caught-up policy, the bar
controller (colour sampling, crops, taps, the bar-back redraw) and the service's pure block and action verdicts. There are
259 tests. Device checks (the feature acceptance table) are listed in [FEAT-0001](docs/feats/FEAT-0001-v0.1-mvp.md).
