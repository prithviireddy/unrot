# 0012. The keep-alive notification is on in every build
Status: Accepted
Date: 2026-11-24
Amends: [0011](0011-keep-alive-notification.md): its "release flag stays off" and "accepted for the test build only" consequences

## Context
Decision 0011 built a quiet foreground notification ("Unrot is blocking"), but only in the owner's `-PkeepAliveFgs` test builds. Tester and release builds had no notification and no foreground-service permission. Its open questions were tested on 2026-11-24 on two Realme phones, once per phone with the notification visible (FEAT-0001 A16a):

| | Normal build | `-fgs` build |
|---|---|---|
| "Clear all" on Realme UI 4.0 (Android 13) | Unrot killed, off for about 41 s | Unrot survives |
| "Clear all" on Realme UI 6.0 (Android 15) | Unrot killed (`o-kill`), off for 41 s, reels played for about 10 s | Unrot survives |
| "Active apps" Stop (Android 15) | Not listed | Listed. One tap stops Unrot until the user opens the app; the kill card reports it then |

Realme UI 4.0 has no "Active apps" list at all.

## Decision
- **Always on.** While Reels or Shorts blocking is on (including during onboarding), the service runs in the foreground with the notification. This applies to every build: debug, tester and release. The `-PkeepAliveFgs` flag, the `BuildConfig.KEEP_ALIVE_FGS` gate, the `-fgs` version suffix and the separate manifest are removed. The permissions and the special-use type move into the main manifest.
- **How it behaves is unchanged from 0011.** It's silent, low-importance, hidden on the lock screen, and swipeable on Android 13+.
- **A failed start is retried.** It's retried when the screen is turned on, at most once a minute.

## Alternatives considered
- **Keep it off (the earlier choice that day).** On the Realme phones tested, a "Clear all", which people do routinely and without meaning to stop Unrot, opens a 41 s hole in blocking. The kill card and notice report it only after the restart.
- **On only for Oppo, Realme and OnePlus.** Phones without a cleaner problem gain nothing measured from the notification and still get the Stop entry, so this would spare them that cost. It was rejected because it's harder to reason about, other makers' cleaners also kill, and it would need per-maker manifests or a runtime switch.
- **A user setting to turn it off.** It can be added later if testers ask. Turning off the channel in Android's settings is not the same thing: it only hides the notification, while the service stays in the foreground and in "Active apps".

## Consequences
- **One-tap Stop.** On Android 13+ phones with "Active apps" (stock Android, Realme UI 6.0), Stop shuts Unrot off without the 30 s friction of [0004](0004-friction-before-weakening.md).
  - It is advertised in quick settings the whole time blocking is on, with no confirmation, whereas Force stop is buried in Settings and asks to confirm.
  - Unrot stays off until it's opened, and the kill card appears only then, with at most the 12-hourly watchdog notice before that.
  - On Realme UI 6.0, opening Unrot brings the service back. On stock Android that's untested.
  - Accepted by the owner: it's a deliberate act, and the accidental "Clear all" hole is the bigger everyday problem.
- **A notification for everyone.** On Android 8–12 a foreground notification can't be swiped away, only hidden by turning off its channel (not yet observed on a device). Existing testers onboarded under "nothing else gets a notification", so the CHANGELOG tells them.
- **A hidden notification is untested.** Whether "Clear all" still spares Unrot when its notification is swiped away, its channel is off, or the permission is denied is untested (FEAT-0001 A16g). If it doesn't, onboarding must tell users to keep the notification visible.
- **Store policy.** Release builds now declare `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`.
  - A Google Play upload would first need the special-use declaration and its video, per the ship-build runbook.
  - If Unrot is distributed through F-Droid (a distribution decision that would supersede [0005](0005-closed-source.md), still to be written), that needs no declaration.
- **Updating from an `-fgs` build.** The version name loses its `-fgs` suffix. The versionCode is unchanged, so `adb install -r` still installs over it.
