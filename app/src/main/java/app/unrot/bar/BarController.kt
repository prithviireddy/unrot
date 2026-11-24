package app.unrot.bar

import app.unrot.rules.BarLayout
import app.unrot.rules.BarMode
import app.unrot.rules.BarTab
import app.unrot.rules.Box

enum class CaptureKind { DISPLAY_NO_HIDE, DISPLAY_WITH_HIDE, WINDOW }

data class Env(
    val api: Int, val nowMs: Long, val night: Boolean, val featureOn: Boolean, val imeVisible: Boolean,
    val simplePatch: Boolean, val disclosureV2: Boolean,
    val appVersion: Long, val rulesVersion: Int,
    /** The window the read was taken from (the bar's window): remembered with a Rebuilt layout for the bar-back probe. */
    val windowId: Int = -1,
    /**
     * A window that is not the target app's (picture-in-picture of another app, a bubble, the shade) sits over the bar.
     * Patch only: the Rebuilt layout is forgotten (see [onEvaluate]).
     */
    val foreignWindowAbove: Boolean = false,
    /**
     * Another window of the target app sits over the bar (a DM or story opening over the feed). The bar is hidden, and its
     * Rebuilt layout is kept for the bar-back probe, so it can come back from the cache when that window closes.
     */
    val targetWindowAbove: Boolean = false,
)

sealed interface RenderState {
    object Hidden : RenderState
    data class Patch(val box: Box, val color: Int) : RenderState
    data class Rebuilt<C>(
        val tabsBox: Box, val slots: List<Slot>, val crops: List<C>, val color: Int, val divider: Divider?, val patchBox: Box?,
    ) : RenderState
}

data class CaptureRequest(
    val gen: Int, val sig: String, val kind: CaptureKind, val tabs: List<Pair<String, Box>>,
    val samples: List<Pair<Int, Int>>, val topRows: List<List<Pair<Int, Int>>>,
    /** Where our flat patch sits, if any: a display shot must show it flat (or it is not a clean shot of the bar). */
    val patchBox: Box? = null,
    /** When the screen was read that the capture is for (the read's start, elapsed ms): a result is stale if the app changed since. */
    val readAtMs: Long = 0L,
)

data class CaptureResult<C>(
    val gen: Int, val sig: String, val crops: Map<String, C>, val cropsValid: Boolean,
    val bgSamples: List<Int>, val topRowSamples: List<List<Int>>,
)

data class TapCommand(
    val identity: String, val viewId: String?, val desc: String?, val box: Box, val longPress: Boolean, val pkg: String,
)

data class Output(val state: RenderState, val capture: CaptureRequest?, val evalAtMs: Long?)

/** True when a colour's relative luminance (0.2126R + 0.7152G + 0.0722B over 255) is below one half: the app's appearance is dark. */
fun isDarkColour(c: Int): Boolean {
    val r = (c shr 16) and 0xFF
    val g = (c shr 8) and 0xFF
    val b = c and 0xFF
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0 < 0.5
}

/**
 * Whether a read may change the bar. A read taken before the target app last changed screen (or a tap was forwarded) is
 * stale: it may describe a sheet or screen that is gone, so it leaves the bar as it is. A read with the keyboard up
 * always may, since that hides the bar, which is the safe direction.
 */
fun readMayDrawBar(capturedAtMs: Long, lastTargetChangeMs: Long, ime: Boolean, settleUntilMs: Long = 0L): Boolean =
    ime || (capturedAtMs >= lastTargetChangeMs && capturedAtMs >= settleUntilMs)

/**
 * Decides what the bar overlay shows. Pure Kotlin: the Android shell calls [onEvaluate] on every evaluation and feeds
 * capture results back. The render state comes from [onEvaluate], [onTapRender] and [onBarBack]; the async callbacks only
 * update internal state and return whether an evaluation should be requested.
 *
 * Colours and crops are keyed by the app's appearance (dark or light), which is read from the sampled bar colour and
 * can differ from the phone's own dark mode. [same] is the crop comparator used for two-capture agreement, which is
 * only needed in WINDOW mode (API 34+). Display captures (API 30-33) commit each crop from one
 * accepted result, since the bar has to be hidden for them anyway and every extra shot costs a hide.
 *
 * [trace] receives short debug lines (state changes, capture starts, results). It never gets descriptions or pixels.
 */
class BarController<C>(
    private val sizeOf: (C) -> Int,
    private val same: (C, C) -> Boolean,
    private val maxBytes: Int = 4 * 1024 * 1024,
    private val trace: ((String) -> Unit)? = null,
) {
    private companion object {
        const val STABLE_MS = 400L
        const val GAP_MS = 350L
        /** A sampled colour is re-sampled after this long. Crops never expire: they are replaced only when their key changes. */
        const val COLOUR_STALE_MS = 600_000L
        const val TAP_DEADLINE_MS = 700L
        const val MAX_ATTEMPTS = 6
        const val STRIKES_TO_FAIL = 2
        const val NON_UNIFORM_RUN = 3
        const val LOOSE_TOL = 24
        /** The bar counts as moving for this long after its tabs box (or the patch box) changed: YouTube slides it away. */
        const val MOVE_MS = 300L
        val BACKOFF_MS = longArrayOf(350L, 2_000L, 10_000L, 30_000L)
        /** Debug trace: the reasons for a Patch are logged at most this often. */
        const val PATCH_WHY_MS = 1_000L
    }

    private class Entry<T>(val crop: T, val bytes: Int)

    /** A tap whose effect must show within [TAP_DEADLINE_MS]: a tab becoming selected, or (selectWait false) a layout change. */
    private class Expect(val identity: String, val selectWait: Boolean, val sigAtTap: String, val deadline: Long)

    // Environment and failure bookkeeping
    private var gen = 0
    /** The current generation. [invalidate] bumps it; the shell cancels pending work whose generation is stale. */
    val generation: Int get() = gen
    private var pkg: String? = null
    private var night = false
    private var appVersion = 0L
    private var winMode = false
    private var rulesVersion: Int? = null
    private val appVersions = HashMap<String, Long>()
    private val failed = HashSet<Pair<String, Long>>()
    private var strikes = 0
    private var pendingTap: Expect? = null

    // Crops and colours. Colour keys are "$pkg|$dark", where dark is the app's appearance.
    private val cache = LinkedHashMap<String, Entry<C>>(16, 0.75f, true)
    private var cacheBytes = 0
    /** Called on main when a crop is committed (from a capture, not from [preload]). Keys are stable across runs. */
    var onCommit: ((key: String, crop: C) -> Unit)? = null
    /** Called on main when crops leave the cache: evicted, dropped with a package or version, or cleared. */
    var onDrop: ((keys: Collection<String>) -> Unit)? = null
    private val pending = HashMap<String, C>()
    private val colors = HashMap<String, Int>()
    /** The app's appearance per package, from its sampled bar colour. Missing means "the phone's night mode". */
    private val appearance = HashMap<String, Boolean>()
    /** Colours sampled since the app last came to the front, with when. Restored or older colours get re-sampled. */
    private val colourAt = HashMap<String, Long>()
    /** The phone's night mode when the app's appearance was sampled, per package (persisted with the appearance). */
    private val sampledNight = HashMap<String, Boolean>()
    /** The night mode restored with an appearance, per package; checked once, on first sight after start. */
    private val restoredNight = HashMap<String, Boolean>()
    private val nightChecked = HashSet<String>()
    private val dividers = HashMap<String, Divider?>()
    private val nonUniformRuns = HashMap<String, MutableList<List<Int>>>()

    // What is on screen
    private var lastState: RenderState = RenderState.Hidden
    private var lastKept: List<BarTab> = emptyList()
    private var lastShown: Map<String, C>? = null
    /** The cache key of each crop on screen (identity → key), so a reused crop's selection can be checked. */
    private var lastShownKeys: Map<String, String> = emptyMap()
    private var nextShownKeys: Map<String, String> = emptyMap()
    /** True when the bar was Rebuilt just before the current hide capture started (see [onEvaluate]). */
    private var rebuiltBeforeHide = false
    /** The appearance the on-screen crops were drawn for. Crops of the other appearance are never shown on top of it. */
    private var shownAppearance: Boolean? = null
    private var seenLayout = false
    /** The phone's night mode at the last evaluation; a change makes the app's appearance unknown until a new sample. */
    private var lastNight: Boolean? = null
    /** Set on a night-mode change: Rebuilt waits for a colour sampled in the new appearance. */
    private var awaitingThemeSample = false
    /**
     * The last layout that was drawn Rebuilt: its geometry, the cache keys of its crops (in tab order) and its tabs. Kept
     * across no-bar reads so [onBarBack] can redraw it from the cache. Cleared by [invalidate].
     */
    private class RebuiltMemo(
        val pkg: String, val appearance: Boolean, val keys: List<String>, val kept: List<BarTab>, val rebuilt: RenderState.Rebuilt<*>,
        val container: Box, val windowId: Int, val sig: String,
    )
    /**
     * Where the last Rebuilt layout of an app was: its package, the window it was read from, its bar container box and its
     * tabs box.
     */
    class RebuiltSpot(val pkg: String, val windowId: Int, val container: Box, val tabsBox: Box)
    private var rebuiltMemo: RebuiltMemo? = null
    /**
     * The memo of an app that was left (see [invalidate]): kept per package so the bar can come back from the cache when
     * the app is re-entered. Cleared by the same events as [rebuiltMemo] (appearance or night change, app version, rules,
     * simple patch, cleared crops, an occluded or patch-only layout, a failed app version).
     */
    private val reentry = HashMap<String, RebuiltMemo>()
    /** True while the state is Patch or Hidden only because the bar is moving (see [tabsChangedAt]): a redraw may follow. */
    private var movingHold = false
    private var lastPatchWhyAt = 0L
    private var lastTabsBox: Box? = null
    private var tabsChangedAt: Long? = null
    private var lastPatchBox: Box? = null
    private var patchChangedAt: Long? = null

    // Layout signature and capture scheduling
    private var sig: String? = null
    private var sigSince = 0L
    /** Cache keys for the kept tabs under each appearance (false = light, true = dark), by tab identity. */
    private var keysByApp: Map<Boolean, Map<String, String>> = emptyMap()
    private var keyList: List<String> = emptyList()
    private var attempts = 0
    private var backoffIdx = 0
    private var retryAt: Long? = null
    private var lastStart: Long? = null
    private var inFlight: CaptureRequest? = null

    fun onEvaluate(pkg: String, layout: BarLayout?, env: Env): Output {
        val now = env.nowMs
        if (this.pkg != null && this.pkg != pkg) {
            invalidate(keepRebuilt = true) // the app is left: its Rebuilt layout is kept for re-entry
            pendingTap = null
            strikes = 0
            seenLayout = false
        }
        this.pkg = pkg
        val nightPrev = lastNight
        lastNight = env.night
        if (nightPrev != null && nightPrev != env.night) {
            // The phone's night mode changed: the appearance is unknown until a new sample, so the bar shows Patch and
            // re-samples (no hide needed while Patch is on screen) before any cached crop of the new appearance is shown.
            appearance.clear()
            forget(pkg)
            awaitingThemeSample = true
            sig = null
            pending.clear()
            attempts = 0
            backoffIdx = 0
            retryAt = null
        }
        night = env.night
        appVersion = env.appVersion
        winMode = env.api >= 34
        // Simple patch: no Rebuilt layout is redrawn from memory while it is on.
        if (env.simplePatch) forget(pkg)

        if (rulesVersion != null && rulesVersion != env.rulesVersion) {
            invalidate()
            dropped(cache.keys.toList())
            cache.clear()
            cacheBytes = 0
            failed.clear()
            strikes = 0
            pendingTap = null
        }
        rulesVersion = env.rulesVersion

        val prevVersion = appVersions.put(pkg, env.appVersion)
        if (prevVersion != null && prevVersion != env.appVersion) {
            invalidate()
            dropPkg(pkg)
            strikes = 0
            pendingTap = null
        } else if (prevVersion == null) {
            // First sight since start: preloaded crops of another version of this app are never used.
            dropOtherVersions(pkg, env.appVersion)
        }
        if (nightChecked.add(pkg)) {
            // Restored after a restart: if the phone's night mode is not the one the appearance was sampled in, the
            // restored appearance and its crops are of the other theme. Patch in the rule colour until a new sample.
            val saved = restoredNight.remove(pkg)
            if (saved != null && saved != env.night) {
                appearance.remove(pkg)
                awaitingThemeSample = true
                sig = null
                pending.clear()
                attempts = 0
                backoffIdx = 0
                retryAt = null
                trace?.invoke("restored appearance is from the other night mode: awaiting a sample")
            }
        }

        // The window facts are part of the signature: a capture in flight from before a change (an occluder, a patch-only
        // evaluation) must not be committed as the bar of the new state.
        val newSig = layout?.let { sigOf(it, pkg) + "|above=${env.foreignWindowAbove}" }
        val e = pendingTap
        if (e != null) {
            val met = if (e.selectWait) layout?.tabs?.any { it.identity == e.identity && it.selected } == true
            else layout == null || newSig != e.sigAtTap
            if (met) {
                pendingTap = null
                strikes = 0
                trace?.invoke("tap met")
            } else if (now > e.deadline) {
                // Judged by when the screen was read (env.nowMs): only a read taken after the deadline that still
                // shows the old state counts. Without a bar in that read there is nothing to judge.
                pendingTap = null
                if (layout != null) {
                    addStrike()
                    trace?.invoke("tap timed out strikes=$strikes")
                } else {
                    trace?.invoke("tap expired without bar")
                }
            }
        }

        // No bar in this read: hidden. (The service passes featureOn = false without a layout, so the bar never shows on one.)
        if (layout == null) {
            if (lastState is RenderState.Rebuilt<*>) trace?.invoke("drop why=noBar(gate=${gateOpen(env)})")
            return out(RenderState.Hidden, null, null, emptyList(), null)
        }

        if (!gateOpen(env)) {
            forget(pkg)
            return out(RenderState.Hidden, null, null, emptyList(), null)
        }
        if (env.targetWindowAbove) {
            // Another window of the target app is over the bar: hidden, and no redraw over it. The Rebuilt layout, its memo
            // and its crops are kept (as when the app is left), so the bar-back probe can redraw it once that window closes.
            // A capture in flight is dropped: the screen it was taken of is not the one that is shown now.
            inFlight = null
            sig = null
            pending.clear()
            return out(RenderState.Hidden, null, null, emptyList(), null)
        }

        val s = newSig!!
        val ap = appearanceOf(pkg)
        val ck = colourKey(pkg, ap)
        val sampled = colors[ck]
        val colour = sampled ?: if (ap) layout.dark else layout.light
        val sl = layoutSlots(layout)
        val keptTabs = layout.tabs.filter { !it.hidden }.sortedBy { it.box.l }
        if (s != sig) {
            sig = s
            sigSince = now
            attempts = 0
            backoffIdx = 0
            retryAt = null
            pending.clear()
        }
        // Keys of the real layout: what a capture result is committed under.
        keysByApp = mapOf(false to keysFor(pkg, env, keptTabs, false), true to keysFor(pkg, env, keptTabs, true))
        val keyFor = keysByApp.getValue(ap)
        keyList = keptTabs.map { keyFor.getValue(it.identity) }
        // Movement: a bar that slides (YouTube opening a video) is not redrawn until it has been still for MOVE_MS.
        if (seenLayout && lastTabsBox != layout.tabsBox) tabsChangedAt = now
        if (seenLayout && lastPatchBox != layout.patchBox) patchChangedAt = now
        seenLayout = true
        lastTabsBox = layout.tabsBox
        lastPatchBox = layout.patchBox
        val tabsMoving = tabsChangedAt?.let { now - it < MOVE_MS } == true
        val patchMoving = patchChangedAt?.let { now - it < MOVE_MS } == true

        val mayCapture = env.api >= 30 && env.disclosureV2 && !env.simplePatch && !env.foreignWindowAbove &&
            layout.mode == BarMode.REBUILD && sl != null
        val patchOnly = layout.mode == BarMode.PATCH || env.api < 30 || env.simplePatch || !env.disclosureV2 ||
            (pkg to env.appVersion) in failed || env.foreignWindowAbove ||
            sl == null

        // Crops to show: the predicted ones while a tap's selection is expected, else the real keys.
        val lookup = predictedKeys(pkg, env.appVersion, keptTabs, ap) ?: keyList
        val hideWasInFlight = inFlight?.kind == CaptureKind.DISPLAY_WITH_HIDE
        // A key with no crop may use the crop on screen while Rebuilt is shown, or while the hide capture that started
        // from Rebuilt is in flight (key change only, or WINDOW mode).
        // Never across an appearance change: the on-screen crops are of the other appearance.
        val apChanged = shownAppearance?.let { it != ap } == true
        val fallbackOk = (lastState is RenderState.Rebuilt<*> || (hideWasInFlight && rebuiltBeforeHide)) &&
            lastShown != null && attempts < MAX_ATTEMPTS && !apChanged
        var allCached = true
        var complete = true
        var missing = 0
        val crops = ArrayList<C>(keptTabs.size)
        val usedKeys = HashMap<String, String>()
        for (i in keptTabs.indices) {
            val tab = keptTabs[i]
            val entry = cache[lookup[i]]
            if (entry != null) {
                crops.add(entry.crop)
                usedKeys[tab.identity] = lookup[i]
                continue
            }
            allCached = false
            missing++
            // Reuse the crop on screen only if it shows the same selection: reusing "Home selected" after Home was
            // deselected would show two highlighted tabs at once.
            val shownKey = lastShownKeys[tab.identity]
            val sameSel = shownKey != null && selOf(shownKey) == selOf(lookup[i])
            val fb = if (fallbackOk && sameSel) lastShown?.get(tab.identity) else null
            if (fb != null) { crops.add(fb); usedKeys[tab.identity] = shownKey!!; continue }
            // Badge fallback: the same tab in the same selection and appearance, only its badge (or another descendant
            // text) differs, e.g. the DM unread count. Show that picture until the new one is captured, instead of the
            // patch. Never across a selection or appearance change.
            val near = if (attempts < MAX_ATTEMPTS) nearestKey(lookup[i]) else null
            val nearEntry = near?.let { cache[it] }
            if (nearEntry != null) { crops.add(nearEntry.crop); usedKeys[tab.identity] = near } else complete = false
        }

        // Crops and colour are wanted separately: a stale colour alone triggers a capture without dropping Rebuilt.
        val colourKnown = sampled != null
        val colourStale = colourAt[ck]?.let { now - it > COLOUR_STALE_MS } ?: true
        val cropsWanted = !allCached
        val coloursWanted = !colourKnown || colourStale || awaitingThemeSample
        val last = lastStart
        // Rebuilt would be shown now (before any hide). On the display path a colour-only sample would need a hide while
        // Rebuilt is up, so it waits for the next crop capture; the colour refresh is not worth a hide.
        val rebuiltCandidate = !patchOnly && complete && colourKnown && !tabsMoving && !awaitingThemeSample
        val colourOnlyHide = !winMode && !cropsWanted && coloursWanted && rebuiltCandidate
        val colourWanted = coloursWanted && !colourOnlyHide
        val wantCapture = cropsWanted || colourWanted
        var capture: CaptureRequest? = null
        var evalAt: Long? = null
        // Every capture wanted (missing crops, or a missing or stale colour) counts towards the attempt cap, so the cap applies.
        if (mayCapture && inFlight == null && wantCapture && attempts < MAX_ATTEMPTS) {
            val earliest = maxOf(sigSince + STABLE_MS, last?.let { it + GAP_MS } ?: 0L, retryAt ?: 0L)
            if (now >= earliest) {
                val kind = when {
                    winMode -> CaptureKind.WINDOW
                    // Rebuilt is on screen, or will be drawn by this evaluation: the bar must be hidden for the shot.
                    lastState is RenderState.Rebuilt<*> || rebuiltCandidate -> CaptureKind.DISPLAY_WITH_HIDE
                    else -> CaptureKind.DISPLAY_NO_HIDE
                }
                val req = CaptureRequest(
                    gen, s, kind, keptTabs.map { it.identity to it.box }, samplePoints(layout),
                    (0..2).map { topRowPoints(layout, it) }, layout.patchBox, env.nowMs,
                )
                capture = req
                inFlight = req
                lastStart = now
                if (kind == CaptureKind.DISPLAY_WITH_HIDE) rebuiltBeforeHide = lastState is RenderState.Rebuilt<*>
                attempts++
                trace?.invoke("capture kind=$kind missing=$missing colourStale=$colourStale")
            } else {
                evalAt = earliest
            }
        }

        // Decided after the capture: a hide-capture that just started means the bar must not be Rebuilt.
        val hideInFlight = inFlight?.kind == CaptureKind.DISPLAY_WITH_HIDE
        val rebuilt = !patchOnly && complete && colourKnown && !hideInFlight && !tabsMoving && !awaitingThemeSample
        // Moving: a bar that is sliding is drawn as Patch or Hidden for now. Its memo is kept, so the redraw can come from the
        // cache once the bar is still (see canRebuild). Any other layout that is not drawn Rebuilt forgets the memo, except
        // while a hide capture for the redraw is in flight: that hide is a step of the same redraw.
        val movingOnly = tabsMoving && !patchOnly && complete && colourKnown && !hideInFlight && !awaitingThemeSample
        fun whyNotRebuilt(): String = buildList {
            if (patchOnly) add("patchOnly(mode=${layout.mode},reject=${layout.rejectReason},above=${env.foreignWindowAbove},failed=${(pkg to env.appVersion) in failed})")
            if (!complete) add("missing=$missing")
            if (!colourKnown) add("noColour")
            if (hideInFlight) add("hide")
            if (tabsMoving) add("moving${if (patchMoving) "+patch" else ""}")
            if (awaitingThemeSample) add("theme")
            if (sl == null) add("noSlots")
        }.joinToString(",").ifEmpty { "none" }
        val state: RenderState = when {
            rebuilt -> RenderState.Rebuilt(layout.tabsBox, sl!!, crops.toList(), colour, dividers[ck], layout.patchBox)
            tabsMoving && patchMoving -> RenderState.Hidden
            layout.patchBox != null -> RenderState.Patch(layout.patchBox, colour)
            else -> RenderState.Hidden
        }
        if (state !is RenderState.Rebuilt<*> && !hideInFlight && !movingOnly) forget(pkg)
        if (state is RenderState.Rebuilt<*>) {
            shownAppearance = ap
            nextShownKeys = usedKeys
            rebuiltMemo = RebuiltMemo(pkg, ap, lookup.toList(), keptTabs, state, layout.container, env.windowId, s)
            reentry.remove(pkg)
        }
        else if (hideInFlight && rebuiltBeforeHide) nextShownKeys = lastShownKeys
        else if (trace != null && lastState is RenderState.Rebuilt<*>) trace.invoke("drop why=${whyNotRebuilt()}")
        else if (trace != null && state is RenderState.Patch && now - lastPatchWhyAt >= PATCH_WHY_MS) {
            lastPatchWhyAt = now
            trace.invoke("patch why=${whyNotRebuilt()}")
        }
        return when {
            state is RenderState.Rebuilt<*> -> out(state, capture, evalAt, keptTabs, keptTabs.zip(crops).associate { it.first.identity to it.second })
            // The hide in flight keeps the on-screen crops, so the next evaluation after its result needs no fallback.
            hideInFlight && rebuiltBeforeHide && lastShown != null -> out(state, capture, evalAt, lastKept, lastShown)
            else -> out(state, capture, evalAt, emptyList(), null)
        }.also { movingHold = movingOnly }
    }

    /**
     * Async result of a capture. Returns true when an evaluation should be requested. The sample sets the app's
     * appearance first; the crops are then committed under the key for that appearance, so a dark crop is never kept
     * under a light bar's key.
     *
     * Display captures (not WINDOW) commit from this single result: the request was only made after the layout had been
     * stable for [STABLE_MS], and a result for another signature is dropped above. A single shot's crops are committed only
     * when its colour agrees with the bar's (see [cropColourAgrees]); a rejected set backs off like a disagreement. WINDOW
     * captures need two agreeing results, as the two shots of an app window are free: no hide is needed for them.
     */
    fun onCaptureResult(r: CaptureResult<C>, nowMs: Long): Boolean {
        if (r.gen != gen) {
            trace?.invoke("result stale gen")
            return false
        }
        val kind = inFlight?.kind
        inFlight = null
        if (r.sig != sig) {
            trace?.invoke("result stale sig")
            return false
        }
        val pk = pkg ?: return false
        val single = kind != null && kind != CaptureKind.WINDOW
        var disagree = false
        var rejected = false
        if (!r.cropsValid) {
            nonUniformRuns.remove(colourKey(pk, appearanceOf(pk)))
            trace?.invoke("result invalid kind=$kind")
        } else {
            // A single shot's crops are checked against the colour its samples show, as it was before this result: uniform,
            // and near the known colour when there is one. The colour is sampled either way.
            val knownBefore = if (r.bgSamples.isEmpty()) null else colors[colourKey(pk, isDarkColour(median(r.bgSamples)))]
            val agrees = !single || cropColourAgrees(r.bgSamples, knownBefore)
            sampleColour(pk, r.bgSamples, r.topRowSamples, nowMs)
            if (awaitingThemeSample && colourAt[colourKey(pk, appearanceOf(pk))] == nowMs) awaitingThemeSample = false
            // A rejected single shot keeps none of its crops.
            val keys = keysByApp[appearanceOf(pk)].orEmpty()
            var committed = 0
            if (!agrees) trace?.invoke("result rejected: colour kind=$kind")
            rejected = !agrees
            for ((ident, crop) in if (rejected) emptyMap() else r.crops) {
                val key = keys[ident] ?: continue
                if (single) {
                    pending.remove(key)
                    commit(key, crop)
                    committed++
                    continue
                }
                val p = pending[key]
                if (p == null) {
                    pending[key] = crop
                } else if (same(p, crop)) {
                    pending.remove(key)
                    commit(key, crop)
                    committed++
                } else {
                    disagree = true
                    pending[key] = crop
                }
            }
            trace?.invoke(
                when {
                    disagree -> "result pending disagree kind=$kind"
                    else -> "result accepted kind=$kind committed=$committed"
                },
            )
        }
        if (!r.cropsValid || disagree || rejected) backoff(nowMs)
        else if (allCommitted(pk)) {
            backoffIdx = 0
            retryAt = null
        }
        return true
    }

    /** The shell's capture failed. Backs off when the failed capture belongs to the current signature. */
    fun onCaptureFailed(gen: Int, nowMs: Long) {
        if (gen != this.gen) return
        val req = inFlight
        inFlight = null
        trace?.invoke("capture failed")
        if (req != null && req.sig != sig) return
        backoff(nowMs)
    }

    /**
     * Called on a tap. Returns the command to forward, or null when the last output was not Rebuilt or the target is not in
     * front. For a tab that is not selected yet, the selection is predicted from the cache on the next evaluation (see
     * [predictedKeys]); the shell should evaluate right after this call.
     */
    fun onTap(slotIndex: Int, longPress: Boolean, nowMs: Long): TapCommand? {
        if (lastState !is RenderState.Rebuilt<*>) return null
        val pkg = pkg ?: return null
        val tab = lastKept.getOrNull(slotIndex) ?: return null
        val sigNow = sig ?: return null
        // "Already selected" is judged by what our bar shows, not by the last read: right after a tap, reads lag by up to a
        // few seconds, and a second tap (DMs, then Home) must still move the highlight at once.
        val shownSel = shownSelectedIdentity()
        val selectedNow = if (shownSel != null) shownSel == tab.identity else tab.selected
        pendingTap = when {
            longPress -> null
            !tab.selects -> Expect(tab.identity, false, sigNow, nowMs + TAP_DEADLINE_MS)
            !selectedNow -> Expect(tab.identity, true, sigNow, nowMs + TAP_DEADLINE_MS)
            // Already selected: a second tap on that tab keeps a pending expectation for the same tab.
            else -> pendingTap?.takeIf { it.identity == tab.identity }
        }
        trace?.invoke("tap slot=$slotIndex select=${pendingTap?.selectWait}")
        return TapCommand(tab.identity, tab.viewId, tab.desc, tab.box, longPress, pkg)
    }

    /**
     * Re-renders the bar at once after a tap, from the cache only, with no tree read: the Rebuilt on screen with the tapped
     * tab's predicted selection, when every predicted crop is still cached. Null otherwise (the tap then shows at the
     * next evaluation, as before). Returns no capture.
     */
    @Suppress("UNCHECKED_CAST")
    fun onTapRender(): Output? {
        val st = lastState as? RenderState.Rebuilt<C> ?: return null
        val pk = pkg ?: return null
        if (lastKept.size != st.slots.size) return null
        val keys = predictedKeys(pk, appVersion, lastKept, appearanceOf(pk)) ?: return null
        val crops = ArrayList<C>(keys.size)
        val shown = HashMap<String, C>()
        for (i in keys.indices) {
            val crop = cache[keys[i]]?.crop ?: return null
            crops.add(crop)
            shown[lastKept[i].identity] = crop
        }
        val next = RenderState.Rebuilt(st.tabsBox, st.slots, crops, st.color, st.divider, st.patchBox)
        nextShownKeys = lastKept.indices.associate { lastKept[it].identity to keys[it] }
        return out(next, null, null, lastKept, shown)
    }

    /** The memo of [pk]: the last Rebuilt layout, or the one kept for an app that was left. */
    private fun memoFor(pk: String): RebuiltMemo? = rebuiltMemo?.takeIf { it.pkg == pk } ?: reentry[pk]

    /** Where the bar-back probe looks for the bar: the package, window and container of the remembered Rebuilt layout. */
    fun rebuiltSpot(): RebuiltSpot? {
        val pk = pkg ?: return null
        val m = memoFor(pk) ?: return null
        return RebuiltSpot(m.pkg, m.windowId, m.container, m.rebuilt.tabsBox)
    }

    /**
     * True while the state is Patch or Hidden only because the bar is moving (see [onEvaluate]). Such a state may be redrawn
     * from the memo too, so the probe runs then as it does for a no-bar Hidden state.
     */
    val holdingForMove: Boolean get() = movingHold

    /**
     * True when [onBarBack] would redraw the remembered Rebuilt layout now: the bar is Hidden (or only moving, see
     * [holdingForMove]), the app is in front, the appearance is the one that layout was drawn for, no colour is awaited,
     * and every crop of it is still cached.
     */
    fun canRebuild(): Boolean {
        val pk = pkg ?: return false
        val m = memoFor(pk) ?: return false
        return m.keys.isNotEmpty() && !awaitingThemeSample && (lastState is RenderState.Hidden || movingHold) &&
            inFlight == null && (pk to appVersion) !in failed && m.appearance == appearanceOf(pk) &&
            m.keys.all { cache.containsKey(it) }
    }

    /**
     * Redraws the remembered Rebuilt layout from the cache, after the bar was hidden by a read with no bar layout, or while
     * it is only moving (see [canRebuild]). Returns the Rebuilt output to draw, or null when [canRebuild] is false. Judges
     * no tap and starts no capture: pendingTap and the in-flight capture are left as they are. Pure state: the shell
     * renders the result.
     */
    fun onBarBack(nowMs: Long): Output? {
        if (!canRebuild()) return null
        val pk = pkg ?: return null
        val m = memoFor(pk) ?: return null
        val crops = ArrayList<C>(m.keys.size)
        val shown = HashMap<String, C>()
        val keysShown = HashMap<String, String>()
        for (i in m.keys.indices) {
            val crop = cache[m.keys[i]]?.crop ?: return null
            crops.add(crop)
            shown[m.kept[i].identity] = crop
            keysShown[m.kept[i].identity] = m.keys[i]
        }
        val st = m.rebuilt
        val colour = colors[colourKey(pk, m.appearance)] ?: st.color
        val next = RenderState.Rebuilt(st.tabsBox, st.slots, crops.toList(), colour, st.divider, st.patchBox)
        shownAppearance = m.appearance
        nextShownKeys = keysShown
        // invalidate cleared the layout signature: the redrawn layout is the remembered one again, so a tap is judged on it.
        if (sig == null) { sig = m.sig; sigSince = nowMs }
        rebuiltMemo = m // the redrawn layout is the current one now
        reentry.remove(pk)
        // The remembered box is the bar's box now: the confirming read at it is no change, so it does not start a move hold.
        lastTabsBox = st.tabsBox
        lastPatchBox = st.patchBox
        tabsChangedAt = null
        patchChangedAt = null
        trace?.invoke("bar back from cache slots=${st.slots.size} at=$nowMs")
        return out(next, null, null, m.kept, shown)
    }

    /** Result of performAction on a forwarded tap. A failure counts as a strike; success is verified by the next evaluation. */
    fun onTapResult(ok: Boolean) {
        if (ok) return
        pendingTap = null
        addStrike()
    }

    /**
     * Drops the in-flight capture, pending crops and the on-screen state. Bumps the generation.
     * [keepRebuilt]: the app is being left (not rotated, not a rules or version change): the remembered Rebuilt layout is
     * kept per package, so the bar can come back from the cache when the app is entered again. Otherwise every kept
     * layout is forgotten.
     */
    fun invalidate(keepRebuilt: Boolean = false) {
        gen++
        inFlight = null
        pending.clear()
        nonUniformRuns.clear()
        lastState = RenderState.Hidden
        lastKept = emptyList()
        lastShown = null
        lastShownKeys = emptyMap()
        rebuiltBeforeHide = false
        shownAppearance = null
        val m = rebuiltMemo
        if (!keepRebuilt) reentry.clear() else if (m != null) reentry[m.pkg] = m
        rebuiltMemo = null
        movingHold = false
        pendingTap = null
        attempts = 0
        colourAt.clear() // next entry re-samples
        sig = null       // and waits for the screen to settle first
    }

    /** Drops every crop (and reports them to [onDrop]). Colours and dividers are kept. */
    fun clearCrops() {
        reentry.clear()
        dropped(cache.keys.toList())
        trimCrops()
    }

    /** onTrimMemory: drops the crops from memory only. Saved copies are kept, so [onDrop] is not called. */
    fun trimCrops() {
        cache.clear()
        cacheBytes = 0
        pending.clear()
        lastShown = null
        lastShownKeys = emptyMap()
    }

    /**
     * Seeds the crop cache with pictures restored from storage, before the first evaluation. They count as committed and
     * fresh, so a Rebuilt bar needs no capture. Does not call [onCommit]. Entries beyond the byte cap are evicted (and
     * reported to [onDrop]), oldest first.
     */
    fun preload(entries: List<Pair<String, C>>) {
        val evicted = ArrayList<String>()
        for ((key, crop) in entries) {
            // Already in memory: nothing to add. A version the app no longer has (when known): not used, and dropped.
            val known = appVersions[key.substringBefore('|')]
            if (known != null && known != versionOf(key)) { evicted += key; continue }
            if (cache.containsKey(key)) continue
            evicted += insert(key, crop)
        }
        dropped(evicted)
    }

    /**
     * Colours with their appearance, for DataStore: "$pkg|true" (dark) or "$pkg|false", "$pkg|appearance" as 1 or 0, and
     * "$pkg|night" as 1 or 0 (the phone's night mode when the appearance was sampled).
     */
    fun colorsSnapshot(): Map<String, Int> = HashMap(colors).also { m ->
        appearance.forEach { (p, dark) -> m["$p|appearance"] = if (dark) 1 else 0 }
        sampledNight.forEach { (p, night) -> m["$p|night"] = if (night) 1 else 0 }
    }

    fun restoreColors(m: Map<String, Int>) {
        m.forEach { (k, v) ->
            when {
                // An appearance saved without the phone's night mode (older builds) can't be checked: treat it as unknown.
                k.endsWith("|appearance") ->
                    if (m.containsKey(k.removeSuffix("|appearance") + "|night")) appearance.putIfAbsent(k.removeSuffix("|appearance"), v == 1)
                k.endsWith("|night") -> restoredNight.putIfAbsent(k.removeSuffix("|night"), v == 1)
                else -> colors.putIfAbsent(k, v)
            }
        }
    }

    /**
     * Forgets the remembered Rebuilt layout of [pk]. The shell calls it for a stale (not applied) read of that app that shows
     * the bar covered by another window, or occluded in its tree: the safe direction, so the bar is not redrawn from memory.
     */
    fun forgetMemo(pk: String) {
        forget(pk)
    }

    /** Forgets the remembered Rebuilt layout of [pk], the current one and the one kept for re-entry. */
    private fun forget(pk: String) {
        rebuiltMemo = null
        reentry.remove(pk)
    }

    private fun gateOpen(env: Env): Boolean = env.featureOn && !env.imeVisible

    private fun appearanceOf(pk: String): Boolean = appearance[pk] ?: night

    private fun colourKey(pk: String, dark: Boolean): String = "$pk|$dark"

    /**
     * Records the new state and what the shell may draw from it: [kept] and [shown] are the tabs and crops on screen
     * (empty and null unless the state is Rebuilt, or a hide capture keeps the previous ones).
     */
    private fun out(
        state: RenderState, capture: CaptureRequest?, evalAt: Long?, kept: List<BarTab>, shown: Map<String, C>?,
    ): Output {
        if (stateName(state) != stateName(lastState)) trace?.invoke("state ${stateName(lastState)}->${stateName(state)}")
        movingHold = false // set by onEvaluate after this, for a moving layout only
        lastState = state
        lastKept = kept
        lastShown = shown
        lastShownKeys = if (shown == null) emptyMap() else nextShownKeys
        nextShownKeys = emptyMap()
        return Output(state, capture, evalAt)
    }

    private fun stateName(s: RenderState): String = when (s) {
        RenderState.Hidden -> "hidden"
        is RenderState.Patch -> "patch"
        is RenderState.Rebuilt<*> -> "rebuilt"
    }

    /**
     * Cache keys of the kept tabs as they would be once the tab being tapped is selected: only its own selected field
     * (and the previously selected tab's) changes. Null when no selection is expected, the target is already selected,
     * or any predicted crop is missing, so the tap then behaves as it always did.
     */
    private fun predictedKeys(pk: String, appVersion: Long, keptTabs: List<BarTab>, ap: Boolean): List<String>? {
        val target = pendingTap?.takeIf { it.selectWait }?.identity ?: return null
        if (keptTabs.none { it.identity == target }) return null
        val keys = keptTabs.map { keyOf(pk, appVersion, ap, it.copy(selected = it.identity == target)) }
        val cached = keys.all { k -> cache.containsKey(k) }
        return if (cached) keys else null
    }

    /** The layout signature: package, and each tab's identity, state and box. Not the appearance, so a colour flip keeps it. */
    private fun sigOf(layout: BarLayout, pk: String): String =
        layout.tabs.sortedBy { it.box.l }.joinToString("#") {
            "$pk|${it.identity}|${it.signature}|${it.selected}@${it.hidden}${it.box}"
        } + "|mode=${layout.mode}|reject=${layout.rejectReason}"

    private fun keysFor(pk: String, env: Env, tabs: List<BarTab>, dark: Boolean): Map<String, String> =
        tabs.associate { it.identity to keyOf(pk, env.appVersion, dark, it) }

    /**
     * A cached key for the same tab as [key] (package, app version, appearance, index, identity, selection and size all
     * equal) that differs only in the tab signature (badge or other descendant text). The most recently used one, or null.
     */
    private fun nearestKey(key: String): String? {
        val parts = key.split('|')
        if (parts.size < 8) return null
        val prefix = parts.take(5).joinToString("|") + "|"
        val suffix = "|" + parts.takeLast(2).joinToString("|")
        return cache.keys.lastOrNull { it != key && it.startsWith(prefix) && it.endsWith(suffix) }
    }

    /** The tab whose "selected" picture our bar shows now, or null when unknown. */
    private fun shownSelectedIdentity(): String? =
        lastShownKeys.entries.firstOrNull { selOf(it.value) == "true" }?.key

    private fun selOf(key: String): String = key.substringAfterLast("|sel=").substringBefore('|')

    /** The app version in a key "pkg|version|dark|index|identity|signature|sel=..|WxH"; 0 when unreadable. */
    private fun versionOf(key: String): Long = key.split('|').getOrNull(1)?.toLongOrNull() ?: 0L

    /** Selection is its own field, so a predicted selection is a key change of exactly that field. */
    private fun keyOf(pk: String, appVersion: Long, dark: Boolean, t: BarTab): String =
        "$pk|$appVersion|$dark|${t.index}|${t.identity}|${t.signature}|sel=${t.selected}|${t.box.width}x${t.box.height}"

    private fun commit(key: String, crop: C) {
        dropped(insert(key, crop))
        onCommit?.invoke(key, crop)
    }

    /** Puts a crop in the LRU and returns the keys evicted to stay within the byte cap. */
    private fun insert(key: String, crop: C): List<String> {
        cache.remove(key)?.let { cacheBytes -= it.bytes }
        val bytes = sizeOf(crop)
        cache[key] = Entry(crop, bytes)
        cacheBytes += bytes
        val evicted = ArrayList<String>()
        while (cacheBytes > maxBytes && cache.isNotEmpty()) {
            val eldest = cache.keys.first()
            cache.remove(eldest)?.let { cacheBytes -= it.bytes }
            evicted += eldest
        }
        return evicted
    }

    private fun dropped(keys: Collection<String>) {
        if (keys.isNotEmpty()) onDrop?.invoke(keys.toList())
    }

    /** Takes a colour from the sample when it is uniform, or from three loosely uniform results in a row. */
    private fun sampleColour(pk: String, bg: List<Int>, topRows: List<List<Int>>, now: Long) {
        if (bg.isEmpty()) return
        if (uniform(bg)) {
            commitColour(pk, median(bg), topRows, now)
            nonUniformRuns.remove(colourKey(pk, appearanceOf(pk)))
            return
        }
        // Translucent bar: three non-uniform results in a row may still commit their median if loosely uniform.
        // Once a colour exists, a non-uniform result neither removes it nor forces Patch.
        val ck = colourKey(pk, appearanceOf(pk))
        if (colors.containsKey(ck)) {
            // Confirmed: the sample is not uniform, but the colour is known, so it is not sampled again until stale.
            colourAt[ck] = now
            return
        }
        val run = nonUniformRuns.getOrPut(ck) { ArrayList() }
        run.add(bg)
        if (run.size > NON_UNIFORM_RUN) run.removeAt(0)
        if (run.size == NON_UNIFORM_RUN) {
            val all = run.flatten()
            if (uniform(all, LOOSE_TOL)) {
                commitColour(pk, median(all), topRows, now)
                nonUniformRuns.remove(ck)
            }
        }
    }

    /** Stores the colour under the appearance it shows. Changing appearance drops the package's pending crops. */
    private fun commitColour(pk: String, colour: Int, topRows: List<List<Int>>, now: Long) {
        val dark = isDarkColour(colour)
        if (dark != appearanceOf(pk)) {
            pending.keys.removeAll { it.startsWith("$pk|") }
            forget(pk)
        }
        appearance[pk] = dark
        val ck = colourKey(pk, dark)
        colors[ck] = colour
        colourAt[ck] = now
        sampledNight[pk] = night
        dividers[ck] = detectDivider(topRows, colour)
    }

    private fun allCommitted(pk: String): Boolean {
        val ap = appearanceOf(pk)
        if (!colors.containsKey(colourKey(pk, ap))) return false
        val keys = keysByApp[ap]?.values ?: return false
        return keys.all { k -> cache.containsKey(k) }
    }

    /** Crops of [p] under any other app version than [version] leave the cache (keys are "pkg|version|...").  */
    private fun dropOtherVersions(p: String, version: Long) {
        val prefix = "$p|"
        val own = "$p|$version|"
        val stale = cache.keys.filter { it.startsWith(prefix) && !it.startsWith(own) }
        if (stale.isEmpty()) return
        dropped(stale)
        stale.forEach { k -> cache.remove(k)?.let { cacheBytes -= it.bytes } }
    }

    private fun backoff(now: Long) {
        retryAt = now + BACKOFF_MS[minOf(backoffIdx, BACKOFF_MS.lastIndex)]
        backoffIdx = minOf(backoffIdx + 1, BACKOFF_MS.lastIndex)
    }

    private fun addStrike() {
        strikes++
        if (strikes >= STRIKES_TO_FAIL) {
            pkg?.let { failed.add(it to appVersion); reentry.remove(it) }
            strikes = 0
        }
    }

    /** A new app version: drops its crops, pending crops, failed flag, sampled colours and appearance. */
    private fun dropPkg(p: String) {
        val prefix = "$p|"
        dropped(cache.keys.filter { it.startsWith(prefix) })
        cache.keys.filter { it.startsWith(prefix) }.forEach { k -> cache.remove(k)?.let { cacheBytes -= it.bytes } }
        pending.keys.removeAll { it.startsWith(prefix) }
        failed.removeAll { it.first == p }
        reentry.remove(p)
        colors.keys.removeAll { it.startsWith(prefix) }
        colourAt.keys.removeAll { it.startsWith(prefix) }
        dividers.keys.removeAll { it.startsWith(prefix) }
        nonUniformRuns.keys.removeAll { it.startsWith(prefix) }
        appearance.remove(p)
        sampledNight.remove(p)
        restoredNight.remove(p)
    }
}
