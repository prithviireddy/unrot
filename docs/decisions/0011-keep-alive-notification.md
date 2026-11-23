# 0011. A quiet always-on notification and an auto-launch guide, so phone cleaners don't stop blocking
Status: Accepted
Date: 2026-11-24

## Context
On the owner's Realme (Android 13), Realme's "Clear all" in Recents killed Unrot's process twice during one test session (logged as `OplusClearSystemService … o-kill … with oneclick`). Blocking stopped until Android restarted the accessibility service, 20–40 s later, so reels played in the meantime.

Locking Unrot in Recents prevents this, but leaves Unrot's card permanently in Recents, which the owner considers bad UI.

## Decision
- **The notification.** While blocking is on, the accessibility service runs as a foreground service. It shows a low-importance, silent notification titled "Unrot is blocking Reels and Shorts", which opens Unrot when tapped.
  - On Android 13+ the user can swipe it away, or turn off its channel, and blocking keeps running.
  - On Android 14+ the service declares `foregroundServiceType="specialUse"` with a stated reason for the Play declaration.
- **The guide.** On Oppo, Realme and OnePlus, onboarding and the Home screen guide the user to allow "Auto launch" and "Background activity" for Unrot, with a button to App info. Locking in Recents is mentioned only as an optional extra.
- **Detecting a kill.** If the service was stopped by the system (no clean shutdown) while blocking was on, the next start shows a Home card and, when notifications are allowed, a notification pointing to the guide.

## Alternatives considered
- **Lock in Recents.** It is reliable, but Unrot's card stays visible in Recents.
- **Hide Unrot from Recents.** The cleaner still kills it.
- **The guide only.** Kills still happen; Unrot just comes back sooner.

## Consequences
- **A visible notification.** It is optional for the user to keep: it can be swiped away or its channel turned off.
- **Play policy.** A foreground-service permission and a special-use declaration must be justified in the Play listing.
- **Not proven.** It isn't confirmed that Realme's cleaner spares a foreground service. That gets checked on the owner's phone with "Clear all".
