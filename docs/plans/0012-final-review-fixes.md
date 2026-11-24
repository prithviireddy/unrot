# Plan: fixes from the final Fable review (round 1 NO-SHIP)
Status: SHIP (plan gate, round 2, 2026-11-24). "Round-1 answers" and "Round-2 nits" at the end win over the text above.
Date: 2026-11-24
Scope: the uncommitted bar-redraw and fast-block change set (since d2cc6cc). The keep-alive work (plan 0011) is separate and comes after this.

## Blockers
- **B1: container-miss patch on a profile's own "Reels" grid tab** (`rules/Bar.kt` `containerMiss`, `assets/rules.json` ig_bar `hide`).
  - `containerMiss` only uses `hide` conds that name a `viewId`. A desc-only cond no longer qualifies (this also covers YouTube `desc: Shorts`).
  - Rules stay at v3. The rule doesn't change; the code becomes stricter.
  - Test: `ig_profile.json` with `tab_bar` removed gives no layout. A YouTube-like fixture with a desc-only Shorts node in the lower quarter gives no patch.
- **B2: taps swallowed on a bar the probe redrew.**
  1. `BlockerService.onBarTap`: resolve the package as `lastEvalPkg ?: bar.rebuiltSpot()?.pkg`, and set `lastTargetChangeAt` only after `bar.onTap` returns a command.
  2. `BarController`: `RebuiltMemo` keeps the layout signature (`sig`). `onBarBack` restores `sig`/`sigSince` when they're null, so `onTap` works straight after a probe redraw.
  3. JVM test: `invalidate(keepRebuilt)` → `onBarBack` → `onTap` returns a command.

## Major
- **M1:** the settle re-read after a block gets its own Runnable (`settleRead`), so it never holds back the shared throttle timer.
- **M2: full-read block decisions are checked against screen changes.**
  - In `applyRead`, skip the block decision (not the DM stamp) when `capturedAtMs < lastTargetChangeAt`, and schedule a fresh read.
  - Before Back or the caught-up tap, re-check that the active window is still a target window of the same package (window list), else drop it.
  - `tapById` returns null (no Back/Home fallback) when there's no active window.
- **M3: display-path crops** (API 30–33, single shot, now saved for good).
  - A DISPLAY result is a failure when the active window is unknown.
  - The request carries the read's `capturedAtMs`. A result is stale when `lastTargetChangeAt` is later than that.
  - Single-shot crops are committed only when the between-tab background samples are uniform and match the known bar colour (within the loose tolerance) when one is known.
  - Tests in the controller for the colour-match rule.
- **M4: disclosure texts.**
  - `OnboardingScreen` "It never reads other apps…" becomes "It never keeps or analyses content from other apps, and never stores or sends your messages, passwords or other screen content."
  - `strings.xml` `service_description` gets "and presses Back when you open them" back.
- **M5: DM exception after a quiet chat.**
  - While the last probe or read saw a DM marker, keep content-change probes at the urgent rate, so the chat is seen again when the viewer opens.
  - A device check is noted.

## Stale code (owner asked for cleanup)
- **Delete, with their tests:**
  - **The colour-only mode:** `Env.colourOnly`, `BAR_REBUILD` build flag and all branches. Simple patch and rules `mode: patch` are the fallbacks.
  - **Unreachable debounce:** the controller's 1-evaluation patch debounce (`misses`, `prevBox`, `ruleColors`, `colourOf`), and dead `BlockerService` lines 917–919.
  - **Inputs the shell never varies:**
    - `Env.targetInFront` / `inFront`, which the shell always passes as true;
    - `WindowCapture` / `Env.api34`, always WINDOW;
    - `counted`, which duplicates `wantCapture`.
  - **Redundant checks and unused bits:** the `sl` checks (which clears both compiler warnings), `val hold`, and the unused params `onTapRender(nowMs)`, `onTapResult(nowMs)`, `allCommitted(now)`.
  - **Dead or write-only fields in `BlockerService`:** `blockQueued`, `lastMarkers` (pass `emptySet()`), `BLOCK_EVENT_TYPES`, the `pane` flag, `rootOf`'s flags param, the duplicate `sinceStateEvent`, the orphan `ProbeOutcome` KDoc, and `@Volatile` on main-only fields.
  - **Unused stored data:** `BarStore.Entry.appVersion`.
  - **Phase 0 spike tooling:** `app/src/debug/**` (SpikeTools, SpikeInit, debug manifest), `service/DebugHooks.kt` and its three call sites, plus the spike-only bits in `Inspector.list` (txt/png) and `Intents.share`. This also clears 9 lint errors. The Inspector DUMP button stays.
- **Merge duplicates:**
  - the active-window lookup, behind one helper;
  - "windows above, not ours", behind one helper;
  - `clickableFrom` and `nearestClickable`;
  - one 4 px slop constant;
  - view-id expansion, via `Target.exactViewIds`;
  - a single nav-insets read per evaluation;
  - one `recycle` helper.
- **Keep:**
  - the `kind: cover` path, as a remote-rules tool;
  - trace-only bookkeeping;
  - `Detection.isBlocked`.
- **Comments and KDoc:** update every item the review lists:
  - BarController L75, L506, L828/expiry wording;
  - the BlockerService class KDoc, L112/907, L147, L188, L417, L478–483, L621/685/741, L1152, L1498, L1514, L1540, L1923, and the "patch" wording;
  - NodeSnap L19–20 and L150, Policy L30–31, BarCapture L23 and L115, BarStore L26, Overlays L20, Settings L35 and L85;
  - the HomeScreen L132 card title, which becomes "Unrot now redraws the bar".
- **Tests and fixtures:**
  - rename `yt_watch.json` to `yt_shorts_camera.json`;
  - add a "no block rule matches any fixture" test;
  - add a Shorts-camera test.

## Minor
- `onTrimMemory`: trim crops only for `>= TRIM_MEMORY_RUNNING_LOW` and not `UI_HIDDEN`, using the exact levels.
- Receivers are registered `RECEIVER_NOT_EXPORTED`, except where system broadcasts need EXPORTED; check each one.
- `dump()`: the window list and `rootOf` before the package check, and `Detection.run` gets the nav bar and truncated flag.
- `fadeEdges`: cache the gradients per size.
- Probe "back" with a stale `lastReadIme` also schedules a read.

## Docs
- **README** "How it works": the redraw, screenshots and saved pictures (0008, 0009), the probes and the fast block, and Simple patch. Remove "not yet verified on a device".
- **FEAT-0001:**
  - status line and scope: redraw, Simple patch, saved pictures, fast block;
  - remove the stale risk;
  - decisions through 0011;
  - test count.
- **Decision 0008** (untracked, so it can still be amended): minSdk 26 kept (Android 8–9 get the patch), and a note that the 10-minute refresh and "nothing saved" are superseded by 0009.
- **Plan 0008:** a note at the top listing the superseded sections.
- **Runbooks:**
  - `publish-rules-update`: `mode: patch` as the first response; remote rules aren't live yet.
  - `ship-build-to-testers`: the Play screenshot and accessibility declaration; the stale "tabs are covered" line fixed.

## Verify
- `./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`, with no compiler warnings in the bar code.
- `:app:lintDebug`: no NewApi errors left from spike code. `BarCapture` gets a `@RequiresApi` / `SDK_INT` guard annotation.
- **Device checks** carried to the owner: B2, B1, A3 after a quiet chat, caught-up while switching, and Android 11–13 capture while swiping home.

## Round-1 answers (these win over the sections above)
### Blockers
1. **Disclosure (M4):** `OnboardingScreen` gets: "It never keeps or analyses content from other apps. Apart from the bar's tab pictures described above, it never stores screen content, and it never sends your messages, passwords or any screen content anywhere." No disclosure version bump.
2. **View-id expansion:** a single helper `fullViewId(id, pkg)` (prefix `pkg:id/` when there's no ':'), used by both `Target.exactViewIds` and `startProbe` (which reads the BAR target's `bar` cond). `startProbe` keeps reading the `bar` cond; it does **not** use `exactViewIds`. JVM test: the bundled bar targets resolve to `com.instagram.android:id/tab_bar` and `com.google.android.youtube:id/bottom_bar_container`.

### Major
- **A. M2, re-checking the window:**
  - **Where:** done in `handle()`, before any bookkeeping (toast, counter, settle, `backsInRow`).
  - **The check:** the **focused** window (`isFocused`; our overlays are `FLAG_NOT_FOCUSABLE`) must be a target window of the decision's package and, when known, the decision's window id.
  - **What carries the window:** decisions carry (pkg, windowId) from `applyRead` (`i.pkg`, `i.rootWindowId`), from the fast probe (`blockProbe` returns `active.id`), and from `onScroll` (the event's package and window).
  - **On a drop:** schedule a fresh read and do nothing else.
  - **Helper:** a pure `actVerdict(focused, targetWindows, pkg, windowId)` with a JVM test.
  - **KDoc:** notes one main-thread `getWindows()` call per action.
- **B. M3, the colour-match rule:**
  - **Compare against:** `colors[colourKey(pk, isDarkColour(median(bg)))]` as it was *before* this result. If that appearance has no colour yet, check uniformity only (tolerance 12). Otherwise uniform (12) **and** within 24 of the known colour.
  - **Gate the crop commit only:** `sampleColour` and the `awaitingThemeSample` reset still run.
  - **A rejected crop set backs off** like a disagreement.
  - **Tests:** `appearanceChangeNeverShowsOnScreenCropsOfTheOtherAppearance` and `nightModeToggleOnApi33…` stay unchanged as guards. New controller tests cover a mismatched colour (no commit + backoff, colour still sampled) and a theme flip (recovers).
  - **Service side:** a DISPLAY result with an unknown active window fails, with a debug trace. Staleness is judged against the request's read time.
  - **Helper:** a pure `displayResultVerdict(activeKnown, readAt, lastTargetChangeAt)` with a test.
- **C. M5 replaced:**
  - **The flag:** a main-only flag `lastReadSawDm`, set from the last applied full read's markers ("dm"), cleared by a read without "dm" and by `EvalSkip`.
  - **How it's used:** while it's set, every Instagram accessibility event calls `policy.dmSeen(now)`.
  - **Cost:** no extra probing and no extra IPC.
  - **Helper:** a pure `dmFlagAfter(prevFlag, readMarkers | skip)` with a test.
  - **Order:** this goes in *before* `lastMarkers` is deleted. `lastMarkers` is then removed.
- **D. Tests for the service rules:** pure helpers with JVM tests:
  - `blockReadMayDecide(capturedAt, lastTargetChangeAt, lastActionAt)`;
  - `actVerdict`;
  - `displayResultVerdict`;
  - `dmFlagAfter`.

  The rest is listed as phone checks.

### Minor
1. **M1:** both settle reschedules (in `handle()` and `applyRead`'s settle branch) use the `settleRead` Runnable; the pending one is removed before posting.
2. **B2, timer:** `onBarBack` restores `sigSince` to the redraw time.
3. **B2, package:** `TapCommand` carries the bar's package, and `onBarTap` uses it.
4. **B1:** "names a viewId" means the exact `viewId` field only. The `ig_profile` test is written first and must fail before the fix.
5. **M2 starvation bound:** if 3 consecutive full reads were skipped as stale and showed the same block target, the 3rd decides. Plus a debug counter.
6. **M3:** the unknown-active-window failure is traced in debug builds.
7. **Receivers:** the screen receiver (SCREEN_ON/OFF, USER_PRESENT) stays EXPORTED, since these are protected broadcasts. The package receiver may stay as it is. No change.
8. **Cleanup cautions:**
   - Keep `@Volatile` on `settings`, `ownRectsSnap`, `liveTabsSpec` and `running`.
   - Keep `colourOnlyHide`/`colourWanted`, a different concept from the colour-only mode.
   - Fix the comment on capture counting.
   - The merged clickable helper never recycles the kept live node.
   - The `tapExecutor` body goes in try/finally, so `tapsInFlight` always drops back.
9. **Spike files:** "Delete all" in the developer card wipes the whole `files/dumps` folder, including old spike files.
10. **`fadeEdges` cache:** keyed by (w, h, fill), drawn through `canvas.translate`.
11. **Caught-up fallback:** when the Home-tab tap fails, show a toast ("You're all caught up") instead of Back or Home (decision 0007).
12. **Order of work:**
    1. Failing tests for B1, B2 and M3, plus the pure helpers.
    2. The behaviour fixes (B1, B2, M1–M4, new M5).
    3. The behaviour-neutral cleanup in small steps, with the suite green after each.
    4. Delete the spike code, then lint.
    5. Docs.
13. **Docs:**
    - FEAT acceptance rows for the new device checks.
    - A12 says "Android 8–10".
    - The `rules.json` `_note` is updated (a comment only, no version bump).

## Round-2 nits (folded in)
1. **Disclosure:** the sentence becomes "…Apart from the bar's tab pictures described above (and screen dumps you save yourself in developer mode), it never stores screen content…".
2. **`tapById`:** uses the same focused-window verdict as `handle()`.
3. **Caught-up:** a TAP action never presses Back or Home on any path, including when the executor rejects it. No duplicate toast.
4. **Starvation bound:** the stale-read streak is passed into `blockReadMayDecide(…, streak)` and reset on a fresh read, a different target, or a decision. JVM test.
5. **Probe id:** a pure `Target.probeViewId(pkg)` is used by `startProbe`, and the test covers it.
6. **Developer card:** "Delete all" shows whenever `files/dumps` isn't empty.
7. **Docs:**
   - **Plan, accepted residual:** M2 doesn't catch changes inside the same window that send no window-state event.
   - **Phone checks:** add a first-ever light/dark flip to the "swiping home" check.
   - **FEAT A3:** "the chat counts as on screen until a read shows another screen".
   - **Onboarding:** remove the duplicate "never" bullet.
