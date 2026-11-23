# 0008. Redraw the bottom bar without the Reels/Shorts tab, in the sampled colour; redraw on Android 11+, patch below
Status: Accepted
Date: 2026-11-22
Amended: 2026-11-24 (plan 0012). `minSdk` stays 26, so Android 8–9 get the patch. The 10-minute refresh and "nothing saved" below are superseded by [0009](0009-save-bar-pictures-on-phone.md).

## Context
On the first phone test (Realme RMX3370, Android 13, Instagram 456), the patch from [0002](0002-cover-tab-icon-plus-hard-block.md) was in the right place but looked broken:
- It was pure black (`#000000` from the rules), while Instagram's dark bar is a dark blue-grey.
- The bar was left with an empty slot between Home and DMs.

An overlay can't move Instagram's icons. It can only draw on top of them.

## Decision
- **Supported versions:** Android 8–17 (`minSdk` 26). The redraw runs on Android 11+ (API 30+); Android 8–10 get the plain patch.
- **Redraw the bar (Instagram and YouTube).** One overlay covers the whole bottom bar and draws the remaining tabs evenly spaced. Instagram ends up with 4 tabs; YouTube's bar is redrawn without Shorts.
  - **Icons are real pixels.** They are cropped from an accessibility screenshot of the app's own tabs, so the selected-tab pill, unread badges and the profile picture are current. No copied artwork.
  - **Taps are forwarded.** A tap clicks the real tab node underneath (`ACTION_CLICK`). Double-tap and long-press are forwarded too, for scroll-to-top and the account switcher.
- **Colour is sampled.**
  - The rule's `coverLight`/`coverDark` is the instant default.
  - On Android 11+ it is replaced by a colour sampled from the bar (while only the Reels/Shorts patch is showing). The sample is saved per app and per theme, so later launches use it immediately.
- **Keeping the crops current:**
  - **Android 14+:** `takeScreenshotOfWindow` captures only the app's window, without our overlay.
  - **Android 11–13:** the full-bar overlay is hidden briefly to capture (the plain patch shows meanwhile). This happens only when a tab state is new (crops no longer expire, see [0009](0009-save-bar-pictures-on-phone.md)). Re-entering the app shows the redrawn bar from cached crops.
- **Fallbacks:**
  - **Android 8–10:** has no service screenshots, so it gets the simple patch from 0002 in the rule colour.
  - **When something fails:** if the bar isn't found, its bounds look wrong or a screenshot fails, it falls back to the simple patch. Navigation must never be blocked.
- **Remote switch.** The rules file gets a cover mode per app (`rebuild` or `patch`), so the redraw can be turned off without a release.
- **Disclosure.** The disclosure adds: *Unrot takes a screenshot of the app's bottom bar only to redraw it; nothing is saved or sent.* The service config gets `canTakeScreenshot`.

## Alternatives considered
- **Patch only, with a fixed colour from the rules.** Cheap, but the empty slot stays, and the colour breaks with themes and redesigns.
- **Our own Unrot icon in the empty slot.** Safe and branded, but it still looks like a modified bar.
- **The redraw on Android 14+ only.** No flash, but the user's own phone (13) and most current phones would see only the patch.
- **Drawing our own copies of the icons.** That copies Instagram's and YouTube's artwork, and the copies would miss selection, badge and profile-picture state.

## Consequences
- **It looks native:** the bar shows no sign of the Reels/Shorts tab.
- **More moving parts:**
  - screenshot rate limits (about 3 per second)
  - a possible one-frame flash on Android 11–13
  - tap forwarding that has to keep up with each redesign of the bar
- **Risk:** a broken redraw can confuse navigation. The fallback, the remote switch and A11 below exist for that.
- **Disclosure:** the screenshot capability widens what the disclosure has to cover. Recheck it against Play's accessibility policy before upload.
- **Revisit:** if the flash is visible to testers on Android 11–13, use the patch there instead.
