# 0007. Stop the Instagram feed at "You're all caught up"
Status: Accepted
Date: 2026-11-21

## Context
SocialLite doesn't show an endless feed: once you're caught up on posts from people you follow, it stops. Native Instagram shows a "You're all caught up" marker and then continues with endless "Suggested posts". We can't remove those posts ([0001](0001-android-first-accessibility-overlay.md)), but we can detect the marker.

## Decision
- When the marker or the "Suggested posts" header reaches the upper 40% of the screen, Unrot taps Instagram's Home tab, which scrolls the feed back to the top, and shows "You're all caught up ✓".
- It has its own toggle, on by default. Turning it off goes through the friction countdown ([0004](0004-friction-before-weakening.md)).

## Alternatives considered
- **A full-screen "caught up" wall.** More intrusive, and it covers the feed the user may still want to scroll back through.
- **Pressing Back.** Would leave Instagram's home screen, which is too harsh.

## Consequences
- It depends on Instagram still showing the marker. Some accounts or versions may mix suggested posts into the feed, and those can't be handled.
- First built into v0.1 on 2026-11-18 from the owner's observation about SocialLite, and accepted by the owner on 2026-11-21.
