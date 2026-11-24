# Changelog

## 1.0.0 (first release, 2026-11-24)
- **Blocks Instagram Reels and YouTube Shorts.** The bottom bar is redrawn without the Reels or Shorts tab. A reel opened from anywhere else closes within about a second.
- **One reel from a DM still plays.** Swiping to the next one is blocked.
- **"You're all caught up" ends the Instagram feed.** Tap Home to go back to the top.
- **Turning blocking off takes a 30-second wait.** Turning it on is instant.
- **A small, silent "Unrot is blocking" notification** while blocking is on. It helps stop your phone from closing Unrot, for example with Recents › Clear all on Realme ([0012](docs/decisions/0012-keep-alive-on-by-default.md)).
- **"Active apps" (Android 13 and later):** Unrot is listed under "Active apps" at the bottom of quick settings. Its Stop button turns Unrot off until you open the app again.
- **If your phone stops Unrot,** the app shows "Unrot was stopped" next time, with a guide for your phone.
- **Everything stays on your phone.** The tab pictures for the redrawn bar are saved privately on the phone, and nothing is sent anywhere.
- **If you tried an earlier test build,** its setup said "Nothing else gets a notification." That's no longer true: the notification above is part of every build.
