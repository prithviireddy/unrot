# Plan: bottom-bar redraw with sampled colour (decision 0008)
Status: SHIP (round 2, 2026-11-22). Round-2 majors N1–N6 and minors folded in below; N1 needs the owner's choice.
Date: 2026-11-22
Implements: [decision 0008](../decisions/0008-redraw-bottom-bar-sampled-colour.md). FEAT-0001 rows A1, A5, A11, A12, and A6 and A7 re-run.

> **Superseded in part (plan 0012, 2026-11-24).** Not to be followed where these differ from the code:
> - the colour-only mode and the `BAR_REBUILD` build flag (removed), and the Phase-0 `WindowCapture` switch (API 34+ is always WINDOW);
> - the Phase 0 spike tooling (`app/src/debug`, `DebugHooks`, removed);
> - the 10-minute crop refresh and the "nothing saved" wording, now [decision 0009](../decisions/0009-save-bar-pictures-on-phone.md);
> - the `minSdk` 29 step (`minSdk` stays 26, so Android 8–9 get the patch).
> The sections below stay as the record of the reviews.

## Goal and hard rules
Inside Instagram and YouTube, the bottom bar looks native, with the Reels/Shorts tab gone.

Three rules hold over everything below:
1. **Navigation is never blocked.** If anything is uncertain, the real bar shows with only the hidden tab patched. Outside the hidden tab's rectangle, nothing of ours takes touches.
2. **No false positives.** Our full-bar window never sits over anything except the app's own unobstructed tab row.
3. **The disclosure matches the code.** No screenshot is taken before the user has accepted a disclosure that describes it.

## Scope
**In:**
- Android 10–17 (minSdk 29).
- Instagram and YouTube bars.
- Rules schema 2.
- Colour sampling, the crop cache, tap forwarding with verification, fallbacks, and the disclosure v2.
- A "Simple patch" setting, a pure `BarController` with tests, and the doc and runbook updates.

**Out:**
- The targetSdk/compileSdk/AGP upgrade. It is a separate change, with its own commit and review, because it isn't part of decision 0008.
- iOS.
- Any change to the hard block, the DM exception, caught-up or friction.

## Facts confirmed by review (SDK sources, android-36.1)
- **Display screenshots include our overlays** (`takeScreenshot`, API 30). That's why `takeScreenshotOfWindow` exists. So on 11–13 we must hide our full-bar window to capture.
- **The rate limit is 333 ms.** `takeScreenshotOfWindow` (API 34) returns pixels in window coordinates, and `FLAG_SECURE` gives an error.
- **Cropping a HARDWARE bitmap reads back the whole bitmap and returns another HARDWARE bitmap.** `getPixel` on a HARDWARE bitmap throws.
- **`ACTION_CLICK` returns true for any clickable view,** whatever the listener does. Success has to be checked by its effect.
- **`SimpleOnGestureListener` turns on double-tap handling,** which swallows the second tap.
- **`isVisibleToUser` ignores views drawn on top of the node.**

## Phase 0: device spikes (gates)
These are debug builds only. `canTakeScreenshot` comes from a **debug-only** resource override, so it can't reach a tester build before the disclosure v2 ships.

**Devices:**
- the Realme (Android 13)
- an **API 34+ emulator or device**, which is a **gate**
- an Android 11 or 12 emulator for S2

The results are written into this file before Phase 1.

| # | Question | How |
|---|---|---|
| S1 | Real bar and tab nodes for Instagram 456 and YouTube 21.45: ids (unique within the bar?), descriptions, bounds, `selected`, clickable level, drawing order. Also: does the container include the area under the navigation bar? Is the bar opaque, does it have a divider, does it hide on scroll? Is it present under sheets? | Inspector dumps for each tab, in light and dark, and in **gesture and 3-button navigation**. Dumps of covered screens: Instagram comments sheet, share sheet, account switcher, story viewer, camera, DM thread; YouTube watch page and miniplayer; a picture-in-picture window, a chat bubble, the shade half-pulled |
| S2 | Confirm our overlay appears in display screenshots on 11, 12 and 13 | Magenta test window, check the pixel |
| S3 | Does `ACTION_CLICK` on each tab node (or which ancestor) switch tabs, and does `selected` follow? Long-click on Profile? Re-click on the selected Home tab? On both API 33 and 34+ | Debug buttons, watching `selected` in a dump |
| S4 | The coordinate mapping: display screenshot vs node bounds (on 13); `takeScreenshotOfWindow` vs window bounds (on 34+), including split screen and letterboxing | Compare crops |
| S5 | What the 11–13 capture looks like: hide the full-bar window, capture, show it again | **Slow-motion video from a second phone** (not a screen recording) |

**Gates, per app:**
- If S1 and S3 fail, that app ships with `mode: patch`.
- If S4 on 34+ fails, 34+ uses the display API with the hide, like 11–13.
- If S5 is visibly bad, 11–13 use patch, as decision 0008 says.

## Phase 1: build

### Step A: disclosure v2 and settings (lands with or before the first capture code)
- **Onboarding `Disclosure`:** add *"While Instagram or YouTube is open, Unrot may take a screenshot of the screen. It keeps only the bottom bar, in memory, to redraw it without the Reels/Shorts tab, and discards the rest at once. Nothing is saved or sent."* Reword "never reads other apps" to *"never reads or keeps content from other apps"*.
- **`service_description`:** reworded to match.
- **`Settings.disclosureVersion`:** `0` means v1 accepted, `2` means v2.
  - Existing users who haven't accepted v2 see a one-time card on the home screen with the new text and an Accept button.
  - **No screenshot is ever taken unless `disclosureVersion >= 2`.** Until then: patch in the rule colour.
- **New setting "Simple patch"** (a normal toggle, no countdown, since it doesn't weaken blocking). It forces the patch for both apps. It's visible to testers, which gives them a live escape hatch while remote rules aren't hosted yet.
- **Release config:** `canTakeScreenshot="true"` moves into the release accessibility config in this step.

### Step B: rules schema 2 (`rules/Rules.kt`, `rules/NodeSnap.kt`, `assets/rules.json`)
- **Schema:** `"schema": 2`, `SUPPORTED_SCHEMA = 2`. Schema-1 files still parse.
- **`NodeSnap`:** gains `drawingOrder` and `windowId` (root only). The Inspector dump records them, plus the bar's layout and which sanity check failed. No pixels, ever.
- **New target `kind: "bar"`**, one per app:
  ```json
  { "id": "ig_bar", "kind": "bar", "feature": "reels",
    "mode": "rebuild",                 // "rebuild" | "patch"; missing or unknown means "patch"
    "bar":  [Cond],                    // the container, matched by a stable view id
    "tabsContainer": [Cond],           // optional: if the tabs aren't the container's direct children
    "hide": [Cond],                    // the tab to remove (the tab node or a descendant)
    "minTabs": 4, "maxTabs": 6,        // count INCLUDING the hidden tab
    "coverLight": "#FFFFFF", "coverDark": "#0C1014" }
  ```
- **Tabs:** the visible children of the tab container with a non-zero size, sorted by `left`.
- **Tab identity:** use the view id only if it's unique within the bar, otherwise the description, otherwise the index. Rectangles are compared with a ±4 px tolerance.
- **Output:** `Detection.bar` is a `BarLayout?`. It has:
  - `tabsRect`: the union of the tab bounds, **not** the container bounds
  - the tabs
  - the hidden tab's rectangle (`patchRect`)
  - the mode
  - the colours
  - `rejectReason`
- **Pure sanity checks.** If any fails, the mode becomes patch:
  - `tabsRect` top ≥ 75% of the screen height and height ≤ 12%
  - tab count within `[minTabs, maxTabs]`, with exactly one hidden tab
  - tab widths within ±25% of their median, and the tabs inside the container
  - **Navigation bar:** `tabsRect` must not intersect the navigation-bar inset rectangle, which is passed in from the service (B3). Clamping happens in the service; if the clamped rectangle loses more than 10% of its height, use patch.
  - **Occlusion:** no node that is drawn after the bar may intersect `tabsRect`. That means a later sibling of the bar, or of any of its ancestors, by `drawingOrder` then index (B4). If one does, the **bar is treated as absent** for the full-bar window. The patch is still allowed if `patchRect` itself isn't covered.
- **Container miss (M7):** if `bar` doesn't match but `hide` matches a node that's tab-sized (≤ 50% width, ≤ 20% height) in the bottom 25% of the screen, emit a patch for it. This keeps today's coverage.
- **Rules content:** the old `kind: cover` tab targets are removed, since the bar target replaces them. `version` goes to 2. The rules come from the S1 dumps. **Snapshot budget:** if the 4000-node budget runs out before the bar is reached, the service also snapshots the bar subtree directly with `findAccessibilityNodeInfosByViewId`, and logs that the budget ran out.

### Step C: `bar/BarController.kt` (pure Kotlin, no Android types; it holds all the logic)
**Inputs:**
- `onEvaluate(layout, env, nowMs)`, where env holds `api`, `imeVisible`, `featureOn`, `simplePatch`, `disclosureV2`, `targetInFront`, `windowAboveBar`, `navInsets`, `appVersion` and `rulesVersion`
- `onCaptureResult(gen, crops, bgSamples)`
- `onTapResult`

**Output:** a `RenderState`, one of:
- `Hidden`
- `Patch(patchRect, colour)`
- `Rebuilt(tabsRect, slots, colour)`

Plus `CaptureRequest?` and `TapCommand?`.

1. **The state comes only from `onEvaluate` (M2).** Async results update the cache and ask for an evaluation. They never set the state themselves.
2. **Mode resolution.** The result is Patch if any of these is true:
   - API 29
   - the rule says patch
   - `simplePatch`
   - no disclosure v2
   - `failed[(pkg, versionCode)]`
   - the layout was rejected
   - `windowAboveBar`
   - any kept tab without a valid crop
   - API ≥ 34 before S4 has passed (a build flag)

   Otherwise Rebuilt.
3. **Generations (M1, M2).**
   - Each capture request carries `gen` plus the layout signature: the keys and bounds of every tab.
   - A result is accepted only if the latest evaluation has the same signature and `gen` is still current.
   - `hide()`, a package change, a configuration change, screen-off and teardown all bump `gen`.
4. **Capture scheduling (M1).**
   - Requests are spaced at least 350 ms apart, coalesced to the latest one.
   - Capture only after the layout signature has been stable for 400 ms, so the pill and icon animations have finished and the profile picture has loaded.
   - A crop is cached only after **two consecutive captures agree**, within a small per-pixel tolerance.
   - On error: back off 0.35 s, then 2 s, then 10 s, then 30 s while still in Patch. Never a retry storm.
5. **The cache (M1, M4).**
   - The key is `(pkg, versionCode, nightBits, tabIndex, tabId, selected, desc, tabW, tabH)`.
   - It's an LRU capped at **4 MB** by bytes.
   - All crops for a package are **expired every time the app comes to the front**. They're rebuilt from Patch, where capturing needs no hide.
   - It's cleared when the rules version changes, when the app version changes, and in `onTrimMemory`.
   - On 11–13, a key with no crop while in Rebuilt means **drop to Patch first, then capture** (Patch needs no hide on 11–13; see Step D). The swap is a visible change from 4 slots to 5, which S5 judges.
   - Description-driven changes, such as a new DM badge, are captured while in Patch too.
6. **Colour (M9).**
   - The background samples are taken at points generated from the gaps between tab rects, for any tab count, plus 4 px in from both edges, skipping anything inside `patchRect`.
   - The colour is the median per channel.
   - The samples must agree within ΔE about 6; if they don't (gradient, blur, divider), the result is `bgUniform=false` and the mode is Patch with the rule colour.
   - The sample is rejected if any crop in the same capture is invalid (near-uniform, or blacked out because a window is secure).
   - The colour is cached per `(pkg, nightBits)` and **saved to DataStore**, which isn't sensitive, so it's correct right away after a restart. Until there is a first sample, the rule colour is used (decision 0008 has been clarified in the same sense).
7. **Tap verification (B2).**
   - A tap on a slot produces `TapCommand(tabKey, kind)`, but only if `targetInFront` is true and the latest layout is current. Otherwise the tap is dropped without being counted as a failure.
   - For a click on a tab that isn't selected, the expectation is that within 700 ms an evaluation shows that tab `selected`. For tabs that never become selected (YouTube's Create, found in S1 and marked in the rules with `"selects": false`), the expectation is that the active window or screen signature changes.
   - If the expectation isn't met, or `performAction` returns false, or the node isn't found: set `failed[(pkg, versionCode)]` and drop to Patch at once. One log line, with no descriptions.
   - Long-click and re-clicking the selected Home tab have no effect that can be checked. They're forwarded without verification, because the clicks on other tabs already verify the bar.
   - `failed` is cleared when the rules version or the app version changes.

### Step D: Android shell (`service/BarOverlay.kt`, `service/BarCapture.kt`)
**Two windows (B1):**
- **`patchWindow`** covers exactly `patchRect`. It's touchable and swallows taps (today's behaviour). It's the only one of our windows in Patch.
- **`barWindow`** covers exactly the clamped `tabsRect`. It's touchable and exists **only in Rebuilt**. It draws the crops, each centred in its slot with an explicit destination rectangle and never scaled, over a fill in the sampled colour.

**Capturing on 11–13 while in Rebuilt:**
- Set `barWindow` to INVISIBLE, which also removes it from touch. `patchWindow` shows instead.
- Wait for `ViewTreeObserver.registerFrameCommitCallback` plus one vsync, then capture.
- The next evaluation makes `barWindow` visible again.

**Navigation-bar clamp (B3):**
- The navigation-bar insets come from `WindowManager.currentWindowMetrics.windowInsets` (`navigationBars()`), plus any `TYPE_SYSTEM` window in `windows` whose title or bounds identify the navigation bar.
- Our windows are placed with absolute screen coordinates, **without `FLAG_LAYOUT_NO_LIMITS`** for the bar windows, and are clamped to the display minus those insets.

**`windowAboveBar` (B4):** true if any window in `windows`, other than ours, sits above the target app's window and intersects `tabsRect`. Examples: picture-in-picture, a bubble, the shade, a system dialog. In that case there are no captures and no Rebuilt.

**Leaving the app quickly (M3):**
- While either window is shown, a 100 ms ticker checks only `rootInActiveWindow?.packageName`, with no snapshot.
- The windows are hidden immediately if it isn't a target package.
- They're also hidden on `onConfigurationChanged` (rotation, theme) and on `ACTION_SCREEN_OFF`.
- They're hidden while the bar's bounds changed in the last 300 ms (animating), and also when the bar is missing for 2 or more evaluations in a row.

**Bitmaps (M4):** all on a single background executor:
- `wrapHardwareBuffer`; if it returns null, the capture fails.
- `copy(ARGB_8888)` of the full frame, then bounds-checked crops for each tab rectangle and the sample points.
- Recycle the full copy; `HardwareBuffer.close()` goes in `finally` on every path, including results that are thrown away.
- On 34+, the crop offsets are the node bounds minus the window's screen bounds (`AccessibilityWindowInfo.getBoundsInScreen`), with the window id from `NodeSnap.windowId`.
- Every exception is caught, so the service never crashes from this path.

**Tap forwarding (M6, minor items):**
- `GestureDetector` with a plain **`OnGestureListener`** (not `SimpleOnGestureListener`), plus `setOnDoubleTapListener(null)`.
- `onSingleTapUp` sends a click and `onLongPress` sends a long-click.
- Nodes are found by tab identity (unique id, then description, then rectangle with tolerance).
- `performAction` runs on a background thread, because it's a blocking binder call. The result goes back to the controller.

**TalkBack:**
- `ExploreByTouchHelper` (adds `androidx.customview`) gives one virtual view per slot, with the real tab's description, forwarding click and long-click.
- Our overlay becoming the active window must not count as "left the app": the in-front check ignores our own windows.

**Removal:** both windows go in every case where covers go today, plus the cases above. `removeAll()` and teardown also bump `gen` and cancel the ticker, the frame callbacks, the executor tasks and any pending taps.

### Step E: service wiring (`BlockerService.kt`)
- **`evaluate()`:** snapshot → `Detection` → `BarController.onEvaluate(det.bar, env)` → apply the `RenderState` to `BarOverlay`, and hand any capture request to `BarCapture`.
- **Polling:** the existing 500 ms poll also runs while a bar window is shown. The 100 ms package ticker covers fast exits.
- **Version cache:** `versionCache` is cleared on `ACTION_PACKAGE_REPLACED` for the target packages (a registered receiver) and each time the app comes to the front.

### Step F: tests (JVM)
- **Schema 2:**
  - parsing, with `mode` missing or unknown defaulting to patch
  - `hide` matching zero or two tabs gives patch
  - tab identity when ids aren't unique
- **`BarLayout`:**
  - `tabsRect` is the union of the tabs
  - the navigation-bar intersection is rejected
  - occlusion by a later sibling at each ancestor level is rejected
  - the container miss gives a patch
  - each sanity check
  - layout for 4, 5 and 6 tabs and odd pixel widths
- **`BarController`:**
  - the state machine for each mode-resolution input
  - generation drops for stale results
  - stability waiting and the two-capture agreement
  - 350 ms spacing and coalescing, and backoff
  - tap verification (success, timeout, `selects: false`, not in front means no failure)
  - the `failed` flag is scoped to and reset by the app version and the rules version
  - expiry on foreground
- **Colour:** the per-channel median, rejection of non-uniform samples, skipping inside `patchRect`, and rejection when a crop is invalid.
- **Fixtures built from the real S1 dumps** (personal data removed). Each of these must give **no** Rebuilt:
  - Instagram: comments sheet, share sheet, story viewer, camera mode selector, DM composer
  - YouTube: watch page action row, miniplayer
- **Existing tests:** the 8 current tests are still green, with fixtures updated.
- **Device only:** captures, the swap's appearance, real taps, TalkBack, and everything listed under "Still to check on a phone".

### Step G: docs
- **README:** update "How it works".
- **`publish-rules-update`:** add `mode: patch` as the first response. Note that a newer *bundled* version beats a remote patch, and that **the remote switch isn't live until rules hosting exists** (`RULES_URL` is empty today). Until then, "Simple patch" is the escape hatch.
- **`roll-back-rules`:** add the same note.
- **FEAT-0001:** update the risks and the "still to check on a phone" list.
- **Decision 0008:** a one-line clarification of when the sampled colour first appears.
- **Play notes in `ship-build-to-testers`:**
  - update the accessibility declaration to mention screenshots
  - recheck redrawing another app's UI against the Device and Network Abuse policy before any Play upload
  - Data safety is unchanged

## Order of work
1. Phase 0 spikes (debug-only screenshot capability). Results go into this file.
2. Step B plus its tests: schema, layout, occlusion, navigation bar, container miss.
3. Step C plus its tests: the controller.
4. Step A (disclosure v2, the "Simple patch" setting, release `canTakeScreenshot`), together with Step D in **Patch mode only** and sampled colour. Install it. Check A1's colour, A12, and that every real tab still responds (the B1 check). **`review-change` on this diff.** This is the shippable midpoint.
5. Step D Rebuilt and tap forwarding, then Step E. Install it. Check A1, A11, A6, A7, and the list below, for Instagram. **`review-change` on this diff.**
6. YouTube rules from S1. Check A5.
7. Step G docs. A final `review-change` before installing for testers.

## Still to check on a phone (no test covers these)
- S1–S5.
- 3-button navigation.
- Instagram sheets and the story viewer; YouTube's watch page and miniplayer.
- Picture-in-picture, a chat bubble, and the shade pulled over the bar.
- Home or Recents, then a dock tap within 0.5 s.
- The keyboard opening during a tab switch.
- YouTube rotation.
- The system dark-mode toggle, and Instagram's in-app theme.
- An Instagram update while the service is running.
- TalkBack; large display and font sizes; locking the screen with Instagram open.
- Re-run A6 and A7.

## Risks and rollback
| Risk | Mitigation |
|---|---|
| A bar redesign breaks the redraw | Sanity checks; tap verification sets `failed` for that app version; "Simple patch" setting; remote `mode: patch` once hosting exists; hard block stays |
| The 4-to-5-slot jump during an 11–13 capture is visible | Captures only after the layout is stable, plus the cache, plus expiry only on entering the app; S5 gate; patch on ≤13 if it's bad |
| Stale crops | Expired on every app entry; generation and signature checks; agreement between two captures |
| A screenshot catches other content | No captures with any window above the bar; only bar crops kept, in memory; full frame freed at once; disclosure v2 says so |
| Policy | Disclosure v2 is required before any capture; declaration updated; policy check before upload |

**Rollback:** turn on "Simple patch" on the phone, or set `mode: patch` remotely once hosting exists. Worst case, a release with `rebuild` disabled by a build flag.

## Round 1 findings
| Finding | Where it's addressed |
|---|---|
| B1: the patch window blocked the bar | Step D: two windows, only `patchRect` touchable in Patch |
| B2: silent tap failures, no escape hatch | C7 tap verification; Step A "Simple patch"; Step G says remote rules aren't live |
| B3: navigation bar covered | B: `tabsRect` = union of tabs plus the navigation check; D: clamp, no `FLAG_LAYOUT_NO_LIMITS`; S1 in 3-button navigation |
| B4: bar covered by sheets or windows | B: occlusion by drawing order; D: `windowAboveBar`; S1 and fixtures |
| B5: disclosure inaccurate and late | Step A reworded, gated on `disclosureVersion`, landing with the first capture code |
| M1: cache poisoning and staleness | C3–C5: generations, stability, two-capture agreement, expiry on entry, version refresh |
| M2: async callbacks deciding visibility | C1, C3 |
| M3: leaving the app slowly, sticky `failed` | D: 100 ms ticker, config and screen-off; C7: scoped `failed` |
| M4: bitmap pipeline | D: copy then crop on an executor, `finally` close, 4 MB byte cap |
| M5: the 14+ path untested | Phase 0 gate on 34+; window offsets; patch on 34+ until S4 passes |
| M6: double tap | D: `OnGestureListener` with no double-tap listener |
| M7: container miss | B: patch for the hidden tab |
| M8: non-unique ids | B: tab identity order, tolerance, key includes index and size |
| M9: flat-bar and sample validation | C6; S1 records opacity and divider |
| M10: tests | Step C as a pure controller; Step F |
| Minor items | Frame-commit timing, slow-motion S5, colour persistence, night bits only, rotation and animation hiding, off-main `performAction`, backoff, debug-only capability, TalkBack details, SDK upgrade split out, extra `review-change` rounds, no description logging |

## Round 2 changes (reviewer said SHIP; these are folded in, and win over earlier text where they conflict)
- **N1, re-entering the app (OWNER DECISION, see the "N1 decision" section below):** expiring crops on every entry made the bar jump between 4 and 5 slots for about 1 s each time, on every Android version. Run S5 on the 34+ device too. Decision 0008 is still untracked, so it gets amended before its first commit to match the choice.
- **N2, the swap order on 11–13:**
  - `patchWindow` is shown first and the frame-commit callback is registered on **patchWindow**, which is the window that draws.
  - Then `barWindow` is made INVISIBLE, and the capture happens after the stability wait.
  - `barWindow` is always added after `patchWindow` (if `patchWindow` is recreated, both are re-added).
  - `patchWindow` is never visible while in Rebuilt.
- **N3, the ticker:**
  - The 100 ms ticker reads `windows` (served from the cache), not `rootInActiveWindow`. It hides when the active window's id differs from the bar's `windowId`.
  - One null or unknown reading means keep; the second one means hide.
- **N4, hiding `barWindow`:** it's hidden **on the same evaluation** for any of these: occlusion, `windowAboveBar`, the keyboard, the app not in front, the bar missing. Only `patchWindow` is debounced (2 evaluations).
- **N5, truncated snapshots:** any snapshot that hit the node budget gives no Rebuilt, Patch at most. JVM test.
- **N6, what counts as an occluder:**
  - Count a node only if it, or a descendant inside the bar rectangle, is clickable or has content (text, a description, or an image or text view).
  - The rule may carry an `occluderIgnore` list of conditions.
  - **Positive fixtures:** every main tab (Instagram Home, Search, DMs, Profile; YouTube Home, Subscriptions, You) must give Rebuilt.
  - The Inspector also dumps `windows` (type, layer, bounds), to check `windowAboveBar` on Realme UI.
- **Minors:**
  - **Tap checks:** a newer tap replaces a pending check. `failed` is set after **2 strikes**. A tap result sets `failed` and requests an evaluation, so the controller gets an `onTap` input.
  - **Capture agreement:** two captures that don't agree count as an error for backoff, with at most 6 attempts per signature.
  - **Window placement:**
    - Windows get `Gravity.DISPLAY_CLIP_VERTICAL | DISPLAY_CLIP_HORIZONTAL`.
    - x has the left navigation-bar inset subtracted.
    - The navigation-bar rule is **reject**, not clamp: if the bar rectangle intersects the inset, use Patch.
  - **Spike code** lives in `src/debug` together with the capability.
  - **Disclosure wording:** "never keeps or analyses content from other apps".
  - **Bitmaps:**
    - The wrapped HARDWARE bitmap is recycled as well as the buffer being closed.
    - `OutOfMemoryError` and the `takeScreenshot` call site are wrapped.
    - A crop that `barWindow` may still draw is never recycled. References are dropped and the GC frees it.
  - **Patch fallbacks:** the container-miss patch also respects occlusion and `windowAboveBar`.
  - **Divider check:** one sample row just inside the bar's top edge, to catch a divider line.
  - **"Simple patch"** also stops screenshots and uses the rule colour.

## N1 decision (owner, 2026-11-22): show the redrawn bar straight from the cache when the app comes back to the front
- **All versions:** crops are **not** expired on app entry. If every kept tab's current key has a cached crop, Rebuilt shows immediately.
- **Android 14+:** while the bar is shown, recapture silently with `takeScreenshotOfWindow` (no hide). Replace a crop only after two captures agree.
- **Android 11–13:** a crop expires after **10 minutes**, or when its key changes (`selected` or description). A refresh happens during Patch or as a timed swap (N2). A profile picture or badge that doesn't show up in any node attribute can be up to 10 minutes stale, and the owner accepts that.
- **First time a tab state is seen:** it still shows Patch until it's captured. S5 runs on both 13 and 34+.

## Phase 0 results (Realme RMX3370, Android 13 / API 33, Instagram 456.1.0.56.78, YouTube 21.45.82, gesture navigation, 2026-11-22)
| # | Result |
|---|---|
| S1 Instagram | Container `com.instagram.android:id/tab_bar` (LinearLayout) at `[0,2208][1080,2352]`. 5 direct children with unique ids: `feed_tab` "Home", `clips_tab` "Reels", `direct_tab` "Message", `search_tab` "Search and explore", `profile_tab` "Profile". Each is 216 px wide and clickable, and `selected` follows. Colour #0C1014, uniform, **no divider**. The bar ends 48 px above the screen bottom, which matches the gesture navigation inset (`navInsets.b = 48`). |
| S1 Instagram badge | **The DM unread badge is not in the description** (still "Message"). It's a child `TextView id/notification` with the count as its text ("4"). So the crop key must use a **tab signature**: the tab's own desc and selected state, plus each descendant's id, text and visible flag (replacing desc-only in C5). |
| S1 YouTube | Container `com.google.android.youtube:id/bottom_bar_container` → one wrapper → LinearLayout → tabs. The tabs are the inner row, so `tabsContainer` is needed. 4 tabs, **no view ids**, with unique descriptions: "Home", "Shorts", "Create", "You", each 270 px wide. There's no Subscriptions tab in this account's layout. Removing Shorts leaves 3 tabs (`minTabs` 3 counting the hidden one is too low, so use `minTabs: 4, maxTabs: 6`). Create doesn't select: `"selects": false`. Colour #0F0F0F, plus a **1 px top divider #2B2B2B**. |
| S1 YouTube screens | On the watch page the bar is missing or collapsed (`[0,2352][1080,2352]`, not visible), so it gets hidden. The miniplayer's time bar overlaps the bar's top 27 px but is drawn *before* it, so it doesn't count as an occluder. Home and You: 0 occluders. |
| S1 occlusion | With the drawing-order and N6 content rule, **every main tab gives 0 occluders**. The comments sheet (composer over the bar) and the share sheet (dimmer and recipients grid) **are detected as occluders**. The rule works on real data. |
| S1 windows | Present: the app (type 1), the status bar (type 3), and accessibility overlays (type 4): our patch, DUMP, SPIKE, and possibly other services' overlays (this phone also runs Wispr Flow). Gesture navigation has no navigation-bar window. `windowAboveBar` must **exclude our own overlays by matching their exact bounds**, and count any other type-4 window that intersects the bar. |
| S2 | The display screenshot **includes** our overlay (#FF00FF found) in both apps. So the 11–13 swap is required, as planned. |
| S3 | `ACTION_CLICK` works on the tab nodes themselves in both apps, and `selected` follows within about 50 ms. Long-press on Profile opened the account switcher, and re-clicking Home scrolled the feed to the top (both confirmed by the owner). |
| S4 | The display screenshot is 1080×2400, which equals the screen and the node bounds (the bar edge lands exactly on crop row 40). No scaling or offset is needed on 13. |
| Not done | S5 (needs the Rebuilt build; slow-motion video). 3-button navigation. **An API 34+ device: none available, so 34+ uses Patch until it's verified (M5 gate)**. |

**Changes forced by the results:**
- **C5 tab signature:** a tab's key now includes its descendants (id, text, visible), not just its own desc.
- **YouTube divider:** detect a top band of 1–3 rows that is uniform across the bar and differs from the background. Draw it as a full-width line in `Rebuilt`, and crop the tabs below it.
- **`windowAboveBar`:** excludes our own overlay windows by bounds.

**3-button navigation (same phone, Instagram):** the bar moves to `[0,2124][1080,2268]`. The navigation bar is a type-3 system window at `[0,2268][1080,2400]`, and `navInsets.b = 132`. The bar ends exactly where the navigation bar starts, with no overlap, and our patch followed it correctly. Intersection checks must be **strict**, since edges that only touch don't count, so that the navigation-bar window and the inset don't wrongly reject the bar. Fixture: `ig_profile_3button.json`.

## Phase 0 results on Android 16 (OnePlus CPH2573, API 36, 1440×3168, Instagram 456.1.0.56.78, YouTube 21.46.522, 2026-11-22)
| # | Result |
|---|---|
| S2 | Display screenshot **includes** our overlay. **`takeScreenshotOfWindow` excludes it** in both apps: the window crop shows Instagram's real Reels icon. **M5 gate passed: API 34+ uses `WINDOW`**, with no hide. (Verified on 16. 14 and 15 use the same API; any error falls back to Patch.) |
| S3 | Clicks switch tabs and `selected` follows in both apps. Long-press on Profile returned true. |
| S4 | The window bitmap is 1440×3168 at window `[0,0][1440,3168]`, so window and screen coordinates match here. Both crops line up exactly. |
| Instagram | Same ids and structure. 5 tabs of 288 px at `[0,2944][1440,3112]`. **The bar colour is #17191C on this phone vs #0C1014 on the Realme**, which supports sampling over rule colours. Uniform, no divider. With gesture navigation, `navInsets.b = 0` (handle hidden). |
| YouTube 21.46 | Same structure, 4 tabs of 360 px. With gesture navigation the bar runs to the screen bottom (`[0,3000][1440,3168]`, no inset). With 3-button navigation it's at `[0,2846][1440,3014]`, above a floating navigation window `[249,3014][1190,3168]` (`navInsets.b = 154`), no overlap. **The bar is translucent:** the background is a soft blur of the content behind it, between #0F0F0F and #1D1B1B. It's uniform on some screens and not on others. |

**Changes forced by these results:**
- **C6 colour hysteresis:**
  - The first colour needs uniform samples (tol 12), or 3 non-uniform results in a row that agree within tol 24.
  - After that, a non-uniform result never removes the colour or forces Patch, so the bar doesn't flip between 3 and 4 slots as content scrolls behind it.
- **Step D crop comparison:** `same()` compares only foreground pixels (pixels far from the background colour), because the blurred background behind the icons changes with the content.
- **Step D drawing:** each crop's outer ~12 px fades into the fill colour, so crops taken over a blurred background don't show as faint rectangles.
- **Step D window capture:** the 34+ path uses `WINDOW` by default (`Env.api34 = WINDOW`).

## Midpoint code review (2026-11-22): round 1 NO-SHIP, B1 and M1 fixed; round 2 SHIP
**Follow-ups before testers get a build:**
- **Colour-only mode samples once and keeps it forever.** Re-sample on each app entry, or once the sample is older than about 10 min. Drop the stored colour when the app version changes.
- **Recognising our own windows:** match by containment rather than exact bounds.
- **Late capture results:** if the active window is unknown when a result arrives, keep the result instead of invalidating.
- **The debounced patch:** it must also respect foreign windows above it.
- **TalkBack:** the ticker must ignore our own windows.
- **Fixed on 2026-11-22 after the owner saw a black patch in Instagram's light theme** (phone in dark mode, Instagram set to light in its own settings). Colour-only mode now re-samples on each app entry, after the 400 ms settle wait, and every 10 minutes. Test: `colourOnlyResamplesOnEachAppEntryAndAfterTenMinutes`.
- **Before Rebuilt ships:** crop and colour keys use the *phone's* night mode, but Instagram's in-app theme can differ. Key them by the *app's* appearance instead, from the bar luminance sampled on entry, so cached dark crops never show over a light bar.

## Redraw code review (2026-11-22): rounds 3–4 NO-SHIP (all findings fixed), round 5 SHIP
- **Owner-reported fixes in the same change:**
  - no window churn
  - single-capture commit on API 30–33
  - no hide capture just for colour
  - optimistic selection on our own taps
  - 150 ms crossfade
  - fast tap path using live tab nodes
- **Remaining minors, accepted:** a non-uniform sample confirming the theme for a translucent bar whose app theme is forced opposite to the phone's; a single-shot capture right as the bar reappears; TalkBack taps on the fast path; YouTube Create opening a sheet in the same window counts as a strike; Instagram's in-app theme change on 11–13 stays stale until a tab switch or 10 min.
- **Fixed after round 5:** `patchCommitted` is reset at the start of every settle.

## Before committing or shipping to testers (owner request, 2026-11-23)
- [ ] When all the bar-redraw bug fixes are done, run a final review of the whole change set (`git diff HEAD` plus untracked files) with the **Fable model at max effort**, using the `review-change` skill (at most 3 rounds; a 3rd NO-SHIP goes to the owner).
