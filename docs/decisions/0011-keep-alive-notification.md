# 0011. A quiet always-on notification and an auto-launch guide, so phone cleaners don't stop blocking
Status: Accepted
Date: 2026-11-24
Amended: 2026-11-24 by [0012](0012-keep-alive-on-by-default.md). The notification is now in every build. The two consequences marked below are overtaken.

## Context
On the owner's Realme (Android 13), Realme's "Clear all" in Recents killed Unrot's process twice during one test session (logged as `OplusClearSystemService … o-kill … with oneclick`). Blocking stopped until Android restarted the accessibility service, 20–40 s later, so reels played in the meantime.

Locking Unrot in Recents prevents this, but leaves Unrot's card permanently in Recents, which the owner considers bad UI.

## Decision
- **The notification.** While blocking is on, the accessibility service runs as a foreground service. It shows a low-importance, silent notification titled "Unrot is blocking", which opens Unrot when tapped.
  - On Android 13+ the user can swipe it away, or turn off its channel, and blocking keeps running.
  - On Android 14+ the service declares `foregroundServiceType="specialUse"` with a stated reason for the Play declaration.
- **The guide.** On Oppo, Realme and OnePlus, onboarding and the Home screen guide the user to allow "Auto launch" and "Background activity" for Unrot, with a button to App info. Locking in Recents is mentioned only as an optional extra.
- **Detecting a kill.** If the service was stopped by the system (no clean shutdown) while blocking was on, the next start shows a Home card and, when notifications are allowed, a notification. A stop by the phone points to the guide; a crash of ours says only that Unrot stopped.

## Alternatives considered
- **Lock in Recents.** It is reliable, but Unrot's card stays visible in Recents.
- **Hide Unrot from Recents.** The cleaner still kills it.
- **The guide only.** Kills still happen; Unrot just comes back sooner.

## Consequences
- **A visible notification.** It is optional for the user to keep: it can be swiped away or its channel turned off.
- **Play policy.** A foreground-service permission and a special-use declaration must be justified in the Play listing.
- **Not proven.** It isn't confirmed that Realme's cleaner spares a foreground service. That gets checked on the owner's phone with "Clear all" (FEAT-0001 A16a).
- **Task Manager entry and one-tap Stop.** A foreground service shows in Android 13+'s Task Manager with a Stop button. One tap there stops Unrot without the 30 s friction of decision [0004](0004-friction-before-weakening.md). Accepted for the test build only. Overtaken by [0012](0012-keep-alive-on-by-default.md), which accepts it for every build.
- **The notification comes back after every restart.** Android shows it again each time the service starts, including after a reboot or an update, until the user hides its channel.
- **The release flag stays off.** `KEEP_ALIVE_FGS` is on only for builds made with `-PkeepAliveFgs`. Turning it on for testers or the Play release is a separate change, after the owner has decided on the Task Manager trade-off, and after the Play special-use declaration and its video are ready (see the ship-build runbook). Overtaken by [0012](0012-keep-alive-on-by-default.md): the flag is removed and the notification is in every build.
- **Kill records.** Kills are detected from the previous run's record and the system's exit records (API 30+). The Home card and the stop notice say "by the phone or from the task manager", since the record cannot tell a cleaner from a user's Stop.
