# 0003. DM exception: one reel, blocked on swipe
Status: Accepted
Date: 2026-11-17

## Context
Friends send each other reels in DMs. Blocking those cuts off a social use people value. Allowing them freely turns the DM screen into a way back into the endless reels feed. This is meant to set Unrot apart from competitors.

## Decision
- If the user was in an Instagram DM thread within the last **5 seconds** before a DM-eligible reel viewer opens, that first reel is allowed.
- The first swipe to another reel ends the pass and blocks. A swipe is a scroll event from the viewer's pager, and scroll events in the first 1.2 seconds are ignored while the pager settles.
- It has its own toggle, on by default. Turning it **on** loosens blocking, so it sits behind the friction countdown ([0004](0004-friction-before-weakening.md)).

## Alternatives considered
- **Allow everything opened from DMs.** Simpler, but it leaks into endless scrolling.
- **No exception.** The strictest option, but friends' reels get blocked and people are more likely to uninstall.

## Consequences
- It depends on detecting the DM thread (a marker rule) and the viewer's pager id. Both need tuning with Inspector dumps.
- Other scroll events inside the viewer, such as comments, might count as a swipe. Fix this through `swipeViewIds` in the rules if it happens.
