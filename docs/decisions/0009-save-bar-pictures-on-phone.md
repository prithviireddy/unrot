# 0009. Save the bar's tab pictures on the phone; no timed refresh
Status: Accepted
Date: 2026-11-23

## Context
The redraw in [0008](0008-redraw-bottom-bar-sampled-colour.md) builds its 4-tab bar from screenshots of the app's real tabs.
- **How many pictures:** each tab has two looks, selected and not selected, so Instagram needs 8 pictures per theme.
- **Where they lived:** in memory only, and they also expired after 10 minutes.
- **The cost on Android 11–13:** every new picture means briefly hiding the redraw to take a screenshot. That is the 5-icon flash. It came back after every Unrot restart, and again every 10 minutes.

The owner asked for the pictures to be taken once and reused.

## Decision
- **No timed refresh.** Crops no longer expire after 10 minutes. A picture is replaced only when its key changes. The key covers the tab, its selected state, its descendants' ids and texts (for example the DM unread count), its size, the app's appearance (dark or light), and the app's version.
- **Saved on the phone.**
  - **Where and how:** crops are saved as PNGs in Unrot's private storage (`files/bar-cache/`), with an index.
  - **When they're loaded:** when the service starts.
  - **Size limit:** the same 4 MB cap as the in-memory cache, oldest dropped first.
  - **What they include:** they can contain the user's profile picture and DM unread count.
  - **Privacy:** they are never sent anywhere, never added to Inspector dumps, and excluded from backups (`allowBackup=false`).
- **When they are deleted:**
  - the app's version changes (that app's pictures)
  - the rules version changes (all)
  - "Simple patch" is turned on (all)
  - the user taps "Delete saved bar pictures" in Unrot (all)
- **Disclosure v3.** The disclosure says Unrot keeps small pictures of the bar's tabs on the phone. Saving to storage happens only once v3 is accepted; until then pictures stay in memory only, as in v2.

## Alternatives considered
- **Memory only, with a 10-minute refresh.** This was the previous behaviour. The 5-icon flash came back after every restart and every 10 minutes.
- **Memory only, no refresh.** The flash still came back after every restart.
- **Drawing our own icon copies.** This copies the apps' artwork, and can't show the profile picture or the unread count.

## Consequences
- After the first time each look has been seen, there are no screenshot hides on Android 11–13, even after a reboot.
- **A stale profile picture** can show until the app's version changes or the user deletes the pictures.
- **More stored on the phone:** small images of the profile picture and DM count. The disclosure and Play's Data safety answers must say so (stored on device, not collected).
