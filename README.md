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
  NodeSnap   snapshot of the accessibility tree
  Detection  runs the JSON rules  → blocks / markers / covers
  Policy     feature toggles, DM exception (one reel, blocked on swipe)
  Action     Back · tap a view (Home tab for "all caught up") · opaque patch over the Reels/Shorts tab icon
App (Compose)  onboarding, toggles with a 30 s friction delay, blocked-today counter, hidden developer mode
RulesWorker    every 12 h: download rules (if RULES_URL is set) + notify if the service was killed
```
Rules live in `app/src/main/assets/rules.json` (schema documented at the top of `rules/Rules.kt`).
The bundled set is a starting guess and is **not yet verified on a device**.

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
These test the bundled rules against synthetic trees and the DM-exception and caught-up policy.
