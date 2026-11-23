# Plan: keep-alive notification and auto-launch guide (decision 0011)
Status: SHIP (plan gate, round 2, 2026-11-24). Round-2 notes N1–N5 are folded in below, under "Round-2 notes". Implementation comes after plan 0012.
Date: 2026-11-24

## Goal
Realme's "Clear all" and other phones' cleaners should not stop blocking without the user knowing. If they do, the user is told and guided to fix it.

## Sequencing (M9, M6)
The benefit of a foreground service against Oplus' one-tap clean is unproven. It also adds an Android 13+ Task Manager entry with a one-tap Stop, which skips the 30 s friction from decision 0004. So:
- **Steps 2–4** (kill detection, not-running warning, guide) ship normally.
- **Step 1** (the foreground notification) is built in full but gated by `BuildConfig.KEEP_ALIVE_FGS`: **true for debug builds, false for release**.
  - The owner tests "Clear all" on the Realme with the debug build and decides on the Task Manager trade-off.
  - Only then is the release flag turned on, as a separate change.

## Steps
### 1. Foreground notification (debug builds only, see Sequencing)
- **Manifest:**
  - `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`.
  - On the `BlockerService` `<service>`: `android:foregroundServiceType="specialUse"`.
  - `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Blocks Instagram Reels and YouTube Shorts while the user has blocking turned on; if interrupted, reels play again." />` (M7: describes the user task, not "avoid the cleaner").
- **Channel:** `blocking_status`, `IMPORTANCE_LOW` (M2), no sound, vibration or badge.
  - Created synchronously right before every `startForeground` (M1). Repeating it is harmless.
- **Notification:**
  - **Icon:** a new monochrome vector small icon `ic_stat_unrot` (M1).
  - **Text:** title per the features that are on ("Unrot is blocking Reels", "…Shorts", or "…Reels and Shorts"; m6), text "Tap to open Unrot", content intent to MainActivity (immutable).
  - **Behaviour:** `VISIBILITY_SECRET` on the lock screen (m6). **No `setOngoing(true)`** (B3), so it can be swiped away on Android 13.
  - **ID:** notification ID 10 (m4).
- **Start and stop on transitions only (B3):**
  - A local `fgsOn` flag. `startForeground` only when it goes from all features off to any on, or at connect; `stopForeground(STOP_FOREGROUND_REMOVE)` only when it goes from any on to all off, and in teardown.
  - Never call it again from unrelated `Settings.flow` emissions (counter, colours, heartbeat).
  - A pure helper `fgsTransition(prevOn, nowOn): Start|Stop|None` gets JVM tests.
  - Uses `ServiceCompat.startForeground(service, 10, notif, SPECIAL_USE on 34+ else 0)`, wrapped in `runCatching`. A failure logs a warning (release too; no user data; m7), and blocking continues.
- **Notification permission granted later** (M5): the service watches the permission state at each Settings emission. On the change from denied to granted it posts the foreground notification once more, only when `fgsOn`.
- **Onboarding** NotificationStep text (M5) becomes: "Unrot can notify you if Android stops it, and (in test builds) shows a small 'Unrot is blocking' notification while it runs." The release text only mentions the stop notice while the flag is off.
- **Developer card / Inspector dump:** shows `foreground: yes/no` (m7).

### 2. Kill detection (B1, B2, m1–m5)
- **Storage:** a separate SharedPreferences file `service_state` (not DataStore, m1), written with `commit()`.
  - **Fields:** `clean` (bool), `aliveAt` (wall ms), `boot` (`Settings.Global.BOOT_COUNT`), `version` (versionCode), `lastUpdate` (`PackageInfo.lastUpdateTime`), `blockingOn` (bool, m2).
- **On connect, in one step:**
  1. Read the previous values.
  2. `decide(...)`.
  3. Write `clean=false, aliveAt=now, boot, version, lastUpdate, blockingOn`.
- **Clean marker (B2):**
  - In `onUnbind`, **synchronously before teardown** (`commit()`), write `clean=true`.
  - Also on `ACTION_SHUTDOWN`: add it to the existing screen receiver.
- **Heartbeat:** `aliveAt` and `blockingOn` are updated at connect, on screen-off, and at most every 10 minutes (m1).
- **The decision** is a pure function `KillCheck.decide(prev, now, exits)` returning `None | Killed(atMs) | Crashed(atMs)`:
  - **No card** when any of these hold:
    - there's no previous record;
    - the previous run ended `clean`;
    - `blockingOn` was false;
    - `boot` changed (a reboot, B1);
    - `version` or `lastUpdate` changed (an update, B1).
  - **On API 30+**, use `ActivityManager.getHistoricalProcessExitReasons(packageName, 0, 5)` records newer than the previous connect:
    - `REASON_PACKAGE_UPDATED` (34+) means no card;
    - `REASON_CRASH`, `REASON_CRASH_NATIVE` and `REASON_ANR` give **Crashed** (our bug, m3);
    - `REASON_USER_REQUESTED` with version/update unchanged gives Killed (the Task Manager Stop or a cleaner);
    - other reasons (`REASON_OTHER`, `REASON_LOW_MEMORY`, `REASON_SIGNALED`…) give Killed.
    - The card uses the record's timestamp.
  - **Below API 30:** Killed at `aliveAt`.
  - The exit description is logged in debug builds (M3: see what Oplus' o-kill reports).
- **Showing it:**
  - **Home card:** saved in Settings: `lastKillAt` and the kind.
    - **Killed:** "Your phone stopped Unrot at <time, system 12/24 h>, so blocking was off until it restarted." + "Fix it" (opens the maker's guide; neutral wording, m5) + "Dismiss".
    - **Crashed:** "Unrot stopped unexpectedly at <time>." + "Dismiss".
    - The card hides on Dismiss or Fix it, or 7 days after `lastKillAt`.
  - **Notification:** on the `service_status` channel, created before posting (m4). ID 11. At most one every 6 hours (m4), and only when notifications are allowed.

### 3. "Switched on but not running" (M4)
- **Home:** on resume, after a 2.5 s grace period, if `BlockerService.isEnabled(ctx)` (the setting) is true and the service isn't in `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)` (bound services), StatusCard shows "Switched on, but your phone stopped it" + "Fix it" (the guide) + "Open Accessibility settings".
- **ServiceWatchdog:** applies the same check, and notifies on `service_status` (ID 1 as today).
- **The check** is a pure helper with JVM tests over (enabled, bound, sinceResume).

### 4. Guide (M8)
- **Oppo / Realme / OnePlus guide text:**
  - **Realme UI 4–5 / ColorOS 12–13:**
    - Settings › Apps › **Auto launch** › turn on Unrot.
    - Settings › Battery › More settings › **App battery management** › Unrot › turn on **Allow background activity**.
    - If you don't see these, open App info › Battery usage and turn on **Allow auto launch** and **Allow background activity**.
  - Also: turn off **Sleep standby optimisation** if present.
  - Optional: lock Unrot in Recents.
- **Buttons:** "Open App info" plus "Open battery settings" (the existing intent).
- **Remove** the wrong "pull down on Unrot" line.
- **Mark** the exact Realme UI 4 labels as "to verify on the owner's RMX3370" in FEAT-0001.

### 5. Docs (m9)
- **FEAT-0001:**
  - Scope item: "Kill protection (decision 0011)".
  - Acceptance rows:
    - A16a: survives "Clear all" (debug build with the foreground notification). Record yes or no.
    - A16b: after a kill, the card appears; after a reboot, update or Accessibility off/on, it doesn't.
    - A16c: "Switched on but not running" shows when the service isn't bound.
- **Decision 0011:** add the consequences found in review:
  - **Task Manager entry and one-tap Stop** (M6).
  - **The notification comes back after every restart** (m8).
  - **The release flag stays off** until the owner decides.
- **Runbook `ship-build-to-testers`:** the Play special-use declaration, with a video, before turning the release flag on.
- **README "How it works":** add a line on this.

## Tests
- **JVM:** `fgsTransition`; `KillCheck.decide` (clean, reboot, update, version change, crash, ANR, user-requested, low memory, blocking off, no previous record); the not-running helper.
- **Device (debug, M3):**
  - `dumpsys activity services app.unrot` shows `isForeground=true types=0x40000000` after connect and after a restart.
  - "Clear all" five times, with and without auto launch, plus `dumpsys activity processes` before and after.
  - Kill on purpose with `adb shell run-as app.unrot kill -9 <pid>`: the card appears.
  - `cmd activity stop-app app.unrot`: the card appears ("Killed"); note whether the service comes back.
  - `adb reboot`, `adb install -r`, and Accessibility off then on: no card.
  - Swipe the notification away, block a few reels, wait 2 minutes: it stays gone (B3).

## Risks
- A bad notification crashes the app asynchronously, so blocking goes down. Mitigated by creating the channel synchronously before every start, using a valid monochrome icon, and testing on API 26/27 and 34+ emulators (M1).
- Exceptions that `startForeground` throws synchronously (SecurityException, IllegalArgumentException for a type not in the manifest, the 34+ missing/invalid type exceptions, ForegroundServiceStartNotAllowedException) are caught, and blocking continues.

## Round-2 notes (folded in; these win over the text above)
- **N1: the gate is an explicit opt-in, not "debug only".** Tester builds are debug APKs, so "debug only" would still reach testers.
  - **Code gate:** `BuildConfig.KEEP_ALIVE_FGS = project.hasProperty("keepAliveFgs")`. It's off by default in every build type and on only when built with `-PkeepAliveFgs`, for the owner's test.
  - **Manifest entries** (the two permissions, `foregroundServiceType` and `<property>`): live in `app/src/debug/AndroidManifest.xml`, merged onto `.service.BlockerService` with `tools:node="merge"`, so release builds carry no foreground-service permission.
  - **Runbook `ship-build-to-testers`:** tester builds are made without `-PkeepAliveFgs`.
- **N2: `blockingOn`.** Written on the first Settings emission and on every `fgsTransition`, not at connect with default settings. Blocking counts as on when Instagram or YouTube blocking is on and the user has finished onboarding; caught-up alone doesn't count.
- **N3: `KillCheck` spec.**
  - **Storage:** `connectAt` is stored, and exit records count only if newer than the previous `connectAt`.
  - **API 30+, no newer record:** None.
  - **Reasons that give None:** `REASON_PERMISSION_CHANGE`, `USER_STOPPED`, `PACKAGE_STATE_CHANGE`, `DEPENDENCY_DIED`, `EXIT_SELF`.
  - **Gives Crashed:** `INITIALIZATION_FAILURE`.
  - All of these get JVM cases.
- **N4: card wording.** "Unrot was stopped at <time> (by the phone or from the task manager), so blocking was off until it restarted." Below API 30 it says "around <time>".
- **N5: watchdog.** It waits 5 s and checks again before notifying "stopped", and cancels ID 1 only when the service is enabled **and** bound. The "switched on but not running" notice gets its own text: "Your phone stopped Unrot. Open Unrot to fix it."
- **Nits:**
  - **Notification title:** generic, "Unrot is blocking", so changing features never brings back a dismissed notification.
  - **`fgsOn`:** counts Instagram or YouTube only.
  - **Deliberate kill on the device:** use a debug-only `Process.killProcess(Process.myPid())` action in the developer card, since `run-as kill` may be refused.
  - **Not-running check:** also requires `!BlockerService.running`, not just the bound-services list.
  - **CHANGELOG:** an entry at commit.
  - **If the owner rejects the notification:** a new decision that supersedes 0011.
