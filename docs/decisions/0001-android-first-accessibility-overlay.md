# 0001. Android first, using an accessibility overlay
Status: Accepted
Date: 2026-11-21

## Context
Unrot should block Instagram Reels and YouTube Shorts, the way SocialLite does. Android apps can't change other apps, so the question is how to get between the user and the short-form parts of Instagram and YouTube while staying on Google Play and keeping the real apps.

## Decision
- Ship on Android first. iOS and desktop come later, and only if the idea proves itself with testers.
- Unrot is a **separate app**. It never modifies, repackages or injects code into Instagram or YouTube.
- It works only through an `AccessibilityService` limited to `com.instagram.android` and `com.google.android.youtube`. The service:
  - reads the screen's accessibility tree
  - acts like a user (Back, Home, tapping a view)
  - draws `TYPE_ACCESSIBILITY_OVERLAY` windows on top
- If the user uninstalls Unrot or turns the service off, the target apps behave exactly as before.

## Alternatives considered
- **Web wrapper** (instagram.com in our own WebView with CSS hiding, like SocialLite's in-app mode). It can truly remove elements, ads and suggested posts. But mobile web Instagram is worse (DMs, posting), users log in inside our app, and it's a second product to maintain. Deferred until after the friend test. It shares a hiding script with the future iOS app.
- **Modded Instagram APK.** Breaks Instagram's terms, can't go on Play, risks account bans and is a security risk. Rejected.
- **Root hooks** (LSPosed/Xposed). Need a rooted phone, so the audience is tiny and it can't go on Play. Rejected.

## Consequences
- Reels can't be truly removed, only covered and blocked (see [0002](0002-cover-tab-icon-plus-hard-block.md)). A reel may flash for a moment before the block.
- Detection depends on Instagram's and YouTube's view ids, which change with app updates. That's handled by remote rules and the Inspector (see [0006](0006-no-backend-remote-json-rules.md)).
- Play review of accessibility apps needs the accessibility declaration form, a prominent disclosure and a demo video. Collecting no data makes the case easier.
- Users can always turn the service off. We rely on friction and honest stats, not Device Admin tricks.
