# 0010. The provisional bar after an in-app return ignores taps until it is confirmed
Status: Accepted
Date: 2026-11-24

## Context
Returning from a DM chat or a story opened from a profile, Instagram slides its main window back in, which takes about 0.3 s. Waiting until the real bar has settled showed Instagram's real 5-icon bar for that time.

Drawing Unrot's bar early (provisional) covers the slide. But until the real bar is confirmed at its place, a tap can't be forwarded safely: the real tabs don't line up with Unrot's 4 slots, so a tap could reach Reels.

## Decision
- **When:** only on a return from another Instagram or YouTube window. The bar's quick check must have seen the real bar appearing in its usual band, with no other window over it.
- **What's drawn:** Unrot draws its 4-tab bar at once, from cached pictures, for at most 600 ms.
- **Taps:** while the bar is provisional, it ignores taps and accessibility actions. Nothing is forwarded, and nothing falls through to Instagram.
- **Confirmation:** the bar becomes normal and tappable once the real bar is seen at its place. If it isn't confirmed within 600 ms, or the user leaves, it is removed.
- **Not after Home or Recents:** a return from outside the app draws nothing provisional, and waits 450 ms for the app-open animation.

## Alternatives considered
- **Don't draw early.** Taps always work, but the real 5-icon bar shows for about 0.3 s on every return.
- **Draw early and let taps through.** Taps land on misaligned real tabs, including Reels.

## Consequences
- **Hard rules affected:** this relaxes plan 0008's hard rules 1 and 2 for at most 600 ms after an in-app return. Unrot's bar may sit over the outgoing screen during the slide, and a tap there is ignored.
- **For the user:** an occasional double tap is needed right after coming back from a chat.
