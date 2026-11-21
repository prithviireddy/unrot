# 0002. Cover the tab icon, plus a hard block
Status: Accepted
Date: 2026-11-18

## Context
Given [0001](0001-android-first-accessibility-overlay.md), we can't edit Instagram's or YouTube's interface. SocialLite's marketing screenshot showing Reels "removed" comes from their own wrapped Instagram, not the native app. We needed to choose what the user experiences when short-form content comes up.

## Decision
- **Cover the entry point.** An opaque patch is drawn over the Reels tab icon (Instagram) and the Shorts tab icon (YouTube), and it swallows taps.
  - Its colour follows the phone's light or dark theme, and the colours can be set per rule.
  - It's hidden while the keyboard is open or when the target app isn't in front. While a patch is showing, the service re-checks every 500 ms.
- **Hard block everywhere else.** If a reel viewer, the Shorts player or a Reels/Shorts tab is detected, the app presses Back and shows a 1.5-second "Reels blocked · N today" message that can't be tapped. If Back lands on a blocked screen three times within a few seconds, it presses Home instead.

## Alternatives considered
- **Soft nudge** ("You've watched 12 today. Leave or watch anyway"). It's easy to tap through, so it's a weak test of whether people want this. It may come back as an optional mode alongside daily allowances.
- **Hybrid** (hard block on tabs, nudge elsewhere). More complex, with no clear benefit for v0.1.

## Consequences
- In practice, the tab icon looks gone, as in SocialLite's screenshot, and the user keeps the real app.
- Colour matching can be off if the user picks a theme in Instagram that differs from the phone's.
- Patches depend on the tab's view id and position, which can break after app updates.
