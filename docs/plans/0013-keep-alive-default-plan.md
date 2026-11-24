# Plan: the keep-alive notification in every build (decision 0012)
Status: SHIP (plan gate, round 1, 2026-11-24). The review's findings M1, M2 and m1–m4 and its nits are folded in below.
Date: 2026-11-24

## Goal
Every build (debug, tester, release) runs the accessibility service in the foreground with the quiet "Unrot is blocking" notification while Reels or Shorts blocking is on. The build flag that kept it test-only is removed entirely, so no stale gate is left behind.

## Steps
### 1. Manifest
- Move from `app/src/keepAliveFgs/AndroidManifest.xml` into `app/src/main/AndroidManifest.xml`:
  - `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`;
  - `android:foregroundServiceType="specialUse"` on the `BlockerService` `<service>`;
  - its `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" …>`, with the value unchanged.

  Add a one-line comment citing decision 0012.
- Delete the `FOREGROUND_SERVICE` `tools:node="remove"` line and its comment. Remove `xmlns:tools` if nothing else uses it.
- Delete `app/src/keepAliveFgs/` (the whole directory).

### 2. Build (`app/build.gradle.kts`)
- Remove the `keepAliveFgs` property and its comment, both `KEEP_ALIVE_FGS` `buildConfigField`s, the `-fgs` `versionNameSuffix` and the `sourceSets…manifest.srcFile` block.
- The `debug {}` block becomes empty: remove it if nothing else is left in it.
- A build still passed `-PkeepAliveFgs` must not fail. An unused Gradle property is simply ignored.

### 3. Code
- **`BlockerService.keepAlive`:**
  - Remove the `if (!BuildConfig.KEEP_ALIVE_FGS) return` line. Start/stop transitions, the permission re-post and the `fgsTransition` helper stay.
- **Retry a failed start (m3):**
  - The problem: when `startKeepAlive` fails, `fgsWanted` stays true and `fgsRunning` stays false, and nothing retries.
  - In the existing screen-on / user-present receiver, call `startKeepAlive()` when `fgsWanted && !fgsRunning`, at most once per 60 s (a `lastFgsRetryAt` field).
  - A swipe never changes `fgsRunning`, so this never re-posts a swiped notification (plan 0011 B3 still holds).
  - Add the 60 s gate as a pure helper `fgsRetryDue(wanted, running, nowMs, lastAtMs): Boolean` with JVM tests.
- **KDoc:** update the comment near `BlockerService.kt:201` and the `ServiceHealth.kt` header so they cite 0012 as well as 0011.
- **`Notices`:**
  - Remove `@SuppressLint("ForegroundServiceType")`, its comment and the now-unused import. The main manifest declares the type now; if lint still flags the call, put the suppression back with a comment saying why.
  - Update the KDoc on `CHANNEL_BLOCKING` and `startForeground` to cite decision 0012 and the main manifest.
- **`OnboardingScreen.NotificationStep`:** remove the `BuildConfig.KEEP_ALIVE_FGS` branch and the unused `BuildConfig` import. The single text doesn't claim that hiding the notification is harmless or harmful, since that's untested (M2):
  > "While Reels or Shorts blocking is on, Unrot runs in the foreground, which helps stop your phone from closing it. Android shows this as a small, silent \"Unrot is blocking\" notification. Allow notifications so you can see it, and so Unrot can tell you if Android stops it."
- **Sweep:** `grep -rn "KEEP_ALIVE_FGS\|keepAliveFgs\|-fgs"` over the whole repo, excluding `build/` and the 0011 history (decision 0011 and plan 0011), must find nothing.

### 4. Docs
- **Decision 0011:**
  - Add an "Amended: [0012](0012-keep-alive-on-by-default.md)" line, in the format 0008 uses.
  - Add a one-line pointer to 0012 on both overtaken consequences: "release flag stays off" and "Task Manager entry… accepted for the test build only".
  - Update 0011's status cell in `docs/decisions/README.md` and add 0012 to the index.
- **Decision 0012 (m1, m2, nits):**
  - **The Stop button:** it is advertised in quick settings with no confirmation, and Force stop is buried. Stop on stock Android hasn't been tested. Phones without a cleaner problem get only the cost.
  - **Hiding is not turning off:** turning off the channel only hides the notification. The service stays in the foreground and in "Active apps".
  - **The kill card is late:** it only appears once Unrot is opened.
  - **F-Droid:** word it conditionally ("if distributed through F-Droid; see the distribution decision, still to be written, which would supersede 0005").
  - **Wording:** "Keep it off" doesn't say "silently", and "Realme, Oppo and OnePlus" becomes "measured on Realme".
- **FEAT-0001:**
  - Item 9a: the notification is in every build.
  - Row A16a: drop "(build with `-PkeepAliveFgs`)". Record what was actually run on 2026-11-24 (one "Clear all" per phone with the notification visible; auto launch not varied). Keep the five-run protocol unticked.
  - Add new rows A16d–A16h, all still to check on a phone (see §5).
  - Add 0012 to the Decisions footer and the RMX3771 (Android 15) to the status line.
- **`docs/runbooks/ship-build-to-testers.md`:**
  - Remove "Tester builds are made without `-PkeepAliveFgs`…".
  - Repoint the subtype-text reference to the main manifest.
  - Reword the Play paragraph: release builds carry the foreground-service permissions, so a Google Play upload would need the special-use declaration and video first.
  - First-run step 4 and the Check section mention the "Unrot is blocking" notification. With notifications denied on 13+, Unrot is still listed in "Active apps".
  - The signing line uses `%USERPROFILE%\.android\debug.keystore`, not the personal path.
- **`README.md`:** the Kill check line says the foreground notification is on while Reels or Shorts blocking is on (decision 0012).
- **`CHANGELOG.md` (new, M2):** under 0.1.0, "Unrot now shows a small, silent 'Unrot is blocking' notification while blocking is on. It helps stop your phone from closing Unrot (for example Recents › Clear all on Realme)." Also the note for existing testers: they onboarded under the old "nothing else gets a notification" text.

### 5. Checks
**Automated (author):**
- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease`: all tests pass, lint has 0 errors, and both build types build.
- The merged release manifest (`app/build/intermediates/merged_manifest/release/…`) has both permissions, `foregroundServiceType="specialUse"` and the property.
- The release `mapping/usage.txt` no longer lists `startKeepAlive` or `Notices.startForeground` as removed.

**On a phone or emulator (M1, M2), FEAT-0001 rows A16d–A16h, still to check:**
- **A16d, Android 8–12** (an API 26/27 and an API 31/32 emulator):
  - after turning the service on: `isForeground=true`, the notification shows, and 2 minutes of logcat have no crash;
  - one Short is blocked;
  - note what shows when the channel is turned off.
- **A16e, restart and teardown on a Realme:**
  - reboot;
  - Kill Unrot now;
  - Accessibility off, then on;
  - Reels and Shorts off, then on.

  Each time, record the notification and `isForeground`. On Android 12+ the notification can take up to 10 s to appear.
- **A16f, a fresh install on Android 13+:** onboard once with "Allow" and once with "Not now". With "Not now", the service is still foreground and listed in "Active apps".
- **A16g, a hidden notification:** on both Realme phones, run "Clear all" three times with the notification swiped away, and three times with the channel off. If Unrot gets killed, onboarding and 0012 must say to keep the notification visible (a follow-up change).
- **A16h, release:** sign the release APK with the debug key and smoke-test it once. Otherwise, mark release as untested until the F-Droid work.

## Risks
- **A failed start:** `startForeground` on 14+ needs the declared type, which is now always declared.
  - A synchronous failure is caught, and blocking goes on; the m3 retry tries again later.
  - **An asynchronous failure is not caught.** A "Bad notification for startForeground" crash arrives after the call. If it happened, the service would crash-loop on that Android version, which is why A16d covers Android 8–12.
- **Release minify:** R8 used to remove this path from release builds. It's now reachable, which the usage.txt check confirms. There's no reflection.
- **Android 8–12:** the foreground notification can't be swiped away. Accepted in 0012.
