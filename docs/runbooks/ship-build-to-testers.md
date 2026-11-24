# Ship a build to testers

**When:** putting a new APK on your own phone or on the friend-test phones. (The Play Store is a separate FEAT.)

## Before you build
1. Run the `review-change` skill on everything since the last shipped build. It needs a `SHIP` verdict.
2. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, so the new APK installs over the old one.
3. Update `CHANGELOG.md` with what testers will notice (`commit-and-changelog` skill).
4. **Before any Play upload** (not part of this runbook): the store's accessibility declaration must say the service takes screenshots of the bar's tabs (decision [0008](../decisions/0008-redraw-bottom-bar-sampled-colour.md)), and the store screenshot must show the disclosure (saved pictures, [0009](../decisions/0009-save-bar-pictures-on-phone.md)).

## Build
```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```
The output is `app/build/outputs/apk/debug/app-debug.apk`. Rename it when sharing, for example `unrot-0.1.1.apk`.

**Every build carries the keep-alive notification** (decision [0012](../decisions/0012-keep-alive-on-by-default.md), amending [0011](../decisions/0011-keep-alive-notification.md)). The foreground-service permissions and the special-use subtype are in the main manifest, `app/src/main/AndroidManifest.xml`.

**Before any Google Play upload** (release builds carry the foreground-service permissions): the Play Console's special-use foreground-service declaration must be filled in first, with the subtype text from `app/src/main/AndroidManifest.xml` and a demo video of the notification and blocking running. Without it, Play rejects the build. An F-Droid release would need no declaration (see decision [0012](../decisions/0012-keep-alive-on-by-default.md)).

**Signing:** debug builds are signed with `%USERPROFILE%\.android\debug.keystore`. Testers can only update in place if every build is signed with the **same** key, so back that file up somewhere safe. If it's lost, testers have to uninstall (losing their settings and counts) and reinstall.

## Install
- **Your phone, over USB:**
  ```bash
  adb install -r app/build/outputs/apk/debug/app-debug.apk
  ```
- **A tester's phone:** send the APK over WhatsApp, Drive or Telegram. They open it and allow "Install unknown apps" for the app they opened it from.

## First-run steps to tell testers
1. Open Unrot and follow onboarding.
2. **Android 13+:** when the Accessibility switch is greyed out, go to App info › ⋮ › **Allow restricted settings**, then turn on Unrot in Accessibility › Installed apps. Onboarding links to both.
3. Battery: set Unrot to unrestricted. **Xiaomi:** also turn on Autostart. **Samsung:** add it to "Never sleeping apps". **Oppo, Realme, OnePlus:** allow auto launch.
4. Allow notifications. The silent "Unrot is blocking" notification shows that Unrot runs in the foreground, and Unrot can tell them if Android stops it. With notifications denied on Android 13+, Unrot is still listed in "Active apps".
5. **Oppo, Realme, OnePlus:** the guide (Settings › Apps › Auto launch, and App battery management › Allow background activity) is shown on Home after a kill. Check it on their phone.

## Check
- While blocking is on, the silent "Unrot is blocking" notification is shown (unless notifications are denied).
- The status card says "Blocking is on". The bottom bar is redrawn without the Instagram Reels tab and the YouTube Shorts tab (on Android 8–10, or with Simple patch, the tab is patched instead).
- Work through the acceptance table of the current FEAT ([FEAT-0001](../feats/FEAT-0001-v0.1-mvp.md) for v0.1).

## Recover
- **"App not installed" on update:** different signing key, or a lower `versionCode`. Uninstall, then reinstall.
- **Accessibility switch still greyed out:** restricted settings weren't allowed. Some Xiaomi phones need it allowed again after an update.
- **Blocking stops after a while:** the battery settings weren't applied. Re-check step 3 of the first-run steps for that phone brand. The Home card "Unrot was stopped" and "Switched on, but your phone stopped it" say the same; "Fix it" opens the guide.
