# Later

Ideas that are shelved on purpose. Each one notes why it's waiting and what would bring it back.
To pick one up, run it through `sharpen-feature`, then write a FEAT. Delete it from here once it has one.

## Bigger directions
| Idea | Why it's waiting | Bring it back when |
|---|---|---|
| **Web wrapper** ("Unrot Feed"): instagram.com and m.youtube.com in our own WebView, with CSS/JS truly hiding Reels, Shorts, ads and suggested posts. A MutationObserver re-applies the hiding, and /reels/ and /shorts/ URLs are blocked | A second product. Mobile web Instagram is worse, and users would log in inside our app. See [0001](decisions/0001-android-first-accessibility-overlay.md) | Friend testers find the patch or the Back-press jarring, or want ads and suggested posts gone |
| **iOS:** Screen Time shields (FamilyControls, ManagedSettings, a ShieldConfiguration extension), DeviceActivity for schedules and unlocks, a WKWebView with the shared hiding script, and a "post mode" that unlocks the native app for about 10 minutes | Needs the FamilyControls distribution entitlement, $99 a year, and carries App Store risk (guidelines 4.2 and 5.2) | The Android test convinces |
| **Desktop:** a Manifest V3 Chrome/Firefox extension that redirects `/shorts/<id>` to `/watch?v=<id>` and hides the Reels and Shorts sections | A small audience for v0.1 | iOS or the web wrapper exists, because it shares the hiding script |
| **One shared hiding script** for the web wrapper, the Safari extension and the Chrome extension | No consumer yet | The first of the three above starts |

## Blocking modes
| Idea | Why it's waiting | Bring it back when |
|---|---|---|
| **Soft nudge** mode ("You've watched 12 today. Leave or watch anyway, 3 passes left") | A weak test of whether people want this. See [0002](decisions/0002-cover-tab-icon-plus-hard-block.md) | Testers say the hard block is too harsh |
| **A wait that grows:** the friction doubles each time blocking is turned off on the same day | 30 seconds first, then see. See [0004](decisions/0004-friction-before-weakening.md) | Testers routinely wait out the 30 seconds |
| **Schedules:** blocking only during set hours, or a "sleep mode" that locks the apps at night | Not part of the core claim | Testers ask for it |
| **Daily allowance:** N reels or N minutes a day, then a hard block | Needs reliable counting of reels watched | The friend test shows people want "some, not none" |
| **"Time saved" stats** | Hard to estimate honestly | The block counter is trusted and there's a fair estimation method |

## More blocking targets
| Idea | Why it's waiting | Bring it back when |
|---|---|---|
| **Shorts shelf in the YouTube home feed** | Patches drift as the feed scrolls. v0.1 blocks the player instead. See FEAT-0001 non-goals | We find a stable signal, or testers say the shelf itself is the pull |
| **Instagram suggested posts and ads inside the feed** (beyond "caught up") | Can't remove items from a native feed, and covering them drifts | The web wrapper is built |
| **TikTok** | The whole app is short-form, so this is app blocking rather than feature blocking | Testers ask for it |
| **Facebook Reels, Snapchat Spotlight** | Need new rules and the Inspector work for each app | The Instagram and YouTube rules are stable |

## Product
| Idea | Why it's waiting | Bring it back when |
|---|---|---|
| **Monetization** (paid tier, donations) | Free first. Running costs are about $0. See [0006](decisions/0006-no-backend-remote-json-rules.md) | The app has real users and costs appear |

## Predict the return from a DM chat's back arrow (bar redraw)
- **Idea:** when the user taps the ← arrow in a chat (a `TYPE_VIEW_CLICKED` from the chat window), draw the redrawn bar at once, before the chat slides away. This would remove the last 2–4 frames of the real 5-icon bar on ← returns.
- **Doesn't help:** the system back gesture, which accessibility services don't receive.
- **Parked:** 2026-11-24, when the owner moved on to faster reel blocking.
- **Brings it back:** if the remaining frames still bother the owner in everyday use.
