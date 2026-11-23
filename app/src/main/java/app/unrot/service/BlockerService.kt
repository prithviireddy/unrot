package app.unrot.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks2
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings.Secure
import android.text.TextUtils
import android.util.Log
import android.view.Choreographer
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import app.unrot.BuildConfig
import app.unrot.bar.BarController
import app.unrot.bar.BarProbe
import app.unrot.bar.ProbeOutcome
import app.unrot.bar.ProbeWin
import app.unrot.bar.CaptureKind
import app.unrot.bar.CaptureRequest
import app.unrot.bar.Env
import app.unrot.bar.Output
import app.unrot.bar.RenderState
import app.unrot.bar.TapCommand
import app.unrot.bar.foregroundSame
import app.unrot.bar.readMayDrawBar
import app.unrot.data.Settings
import app.unrot.inspector.Inspector
import app.unrot.policy.Decision
import app.unrot.policy.Policy
import app.unrot.rules.BarLayout
import app.unrot.rules.BarMode
import app.unrot.rules.BarTab
import app.unrot.rules.BlockAction
import app.unrot.rules.Box
import app.unrot.rules.Detection
import app.unrot.rules.KeptNode
import app.unrot.rules.NodeSnap
import app.unrot.rules.RulesRepository
import app.unrot.rules.Target
import app.unrot.rules.TargetKind
import app.unrot.rules.isSettledFullWidth
import app.unrot.rules.recycleNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Watches Instagram and YouTube (the package filter lives in res/xml/accessibility_service_config.xml).
 * Pipeline per screen: snapshot tree → Detection (rules) → Policy → Action (Back / tap / covers / bar redraw or patch).
 *
 * Threads: the target app's accessibility tree is read off the main thread only (the eval thread, the block and bar-back probe
 * threads, the tap thread). The window list is read on main as metadata only (once per action, capture result and probe
 * refresh), never a tree of a window that is not a target app's. The debug dump is the one tree read on main. Main only applies
 * the results: the overlays, the bar controller, the policy and the settings. A read is applied as it was taken (see [EvalRead]).
 */
class BlockerService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = Policy()
    private lateinit var overlays: Overlays
    private lateinit var barOverlay: BarOverlay
    private lateinit var barCapture: BarCapture
    private lateinit var bar: BarController<Bitmap>
    /** The bar's tab pictures saved on the phone (see [BarStore]). Created on connect; main only. */
    private var store: BarStore? = null
    private var storeLoadRequested = false
    /** Bumped when the saved pictures are deleted, so a load asked for before is not applied after. */
    private var pictureGen = 0
    private var simplePatchSeen: Boolean? = null
    private var resetSeen: Long? = null
    /** Main only: the app version and rules version last applied, to drop pictures that no longer belong. */
    private val versionSeen = HashMap<String, Long>()
    private var rulesSeen: Int? = null

    @Volatile private var settings = Settings()
    private var evalScheduled = false
    private var lastEvalPkg: String? = null

    // Off-main evaluation (main side). At most one read is in flight; a request made meanwhile runs once more after it.
    private var evalThread: HandlerThread? = null
    private var evalHandler: Handler? = null
    private var collecting = false
    private var collectAgain = false
    /** Bumped on main whenever the bar's state is forgotten; a read taken before a bump is dropped, not applied. */
    private var stateEpoch = 0
    /** Eval thread only: the package the last read was for, so its version is re-read on entry. */
    private var collectPkg: String? = null
    /** Screen boxes of our own windows, published from main and read by the eval, tick and probe threads, and main (see [publishOwnRects]). */
    @Volatile private var ownRectsSnap: List<Box> = emptyList()
    /** Main only: the screen facts of the last applied evaluation, for the instant render after a tap. */
    private var frame: Frame? = null
    private var tickInFlight = false
    /** The ticker's own thread, for the window-list reading (see [requestTick]). */
    private var tickThread: HandlerThread? = null
    private var tickHandler: Handler? = null
    /** Elapsed ms of the latest window-state change of a target app, or of a forwarded tap. Main only (see [applyRead]). */
    private var lastTargetChangeAt = 0L
    /** Elapsed ms of the latest accessibility event from a target app. Main only (see [reapplyLastRead]). */
    private var lastTargetEventAt = 0L
    /**
     * Window ids that Instagram or YouTube sent events from, with the package: a read only fetches the tree of one of these,
     * and the probe only of the window of its own app.
     */
    private val targetWindows = ConcurrentHashMap<Int, String>()
    /** Main only: the keyboard was up in the last applied read. */
    private var lastReadIme = false
    /**
     * Main only: the last applied read found the bar's layout under another window of the target app, so the bar is hidden
     * for it (see [BarController.onEvaluate]). The probe runs for that window to close.
     */
    private var targetAboveHidden = false
    /** Main only: elapsed ms when the last read with no bar layout started hiding the bar, 0 when it did not. */
    private var noBarSince = 0L
    /**
     * Provisional bar (see [showProvisional]): elapsed ms when the untouchable redraw was shown, 0 when none, and its layout.
     * Main only. Ended by the probe's confirmation, by a read, by a stop, or by [PROVISIONAL_MS] (see [dropProvisional]).
     */
    private var provisionalAt = 0L
    private var provisionalState: RenderState.Rebuilt<*>? = null
    private val provisionalExpire = Runnable { dropProvisional("timeout") }
    /** Bar-back probe (see [updateProbe]). Main only, except [probeHandler]'s thread, which runs [probeBarBack]. */
    private var probeTarget: ProbeTarget? = null
    /**
     * The reason of the probe's last not-back outcome: logged on change only, and "otherTargetWindow" decides whether a return
     * kicks the probe as returnDriven (see [kickProbe]).
     */
    private var probeReason: String? = null
    private var probeInFlight = false
    private var probeThread: HandlerThread? = null
    private var probeHandler: Handler? = null
    private val probeRunnable = Runnable { probeTick() }

    // Block probe (fast path, see requestBlockProbe). Main only, except blockHandler and the probe thread's own reads.
    private var blockThread: HandlerThread? = null
    private var blockHandler: Handler? = null
    /** The package of the latest target event: the probe reads that app's window. */
    private var blockPkg: String? = null
    private var blockInFlight = false
    /** An event came while a probe ran: one follow-up probe after it. */
    private var blockAgain = false
    private var blockStartedAt = 0L
    /** Elapsed ms of the first window-state event of the current screen (for the trace), and of the latest one. */
    private var stateEventAt = 0L
    private var lastStateEventAt = 0L
    /** Elapsed ms until which the block probe runs at the fast cadence (after a window-state, pane or scroll event). */
    private var blockUrgentUntil = 0L
    /** Probes in a row that found no block, since the last window-state or scroll event. */
    private var blockMisses = 0
    /** A hit waiting for the next probe to agree (same target, same bounds), with the elapsed ms it was seen. */
    private var pendingHit: BlockHit? = null
    private var pendingAt = 0L
    /**
     * Main only: the last applied full read saw a DM marker (see [dmFlagAfter]). While it is set, every Instagram event keeps
     * the policy's DM context fresh, so a reel opened from the chat is judged against a recent sighting.
     */
    private var lastReadSawDm = false
    /** Main: the 1 s check that clears [lastReadSawDm] once the user has left the chat (see [checkDmFlag]). */
    private val dmFlagCheck = Runnable { checkDmFlag() }
    private val blockRunnable = Runnable { startBlockProbe() }
    /** The settle re-read after a block (see [scheduleSettleRead]): its own timer, so it never holds back the shared throttle. */
    private val settleRead = Runnable { requestCollect() }
    /** Starvation bound (see [blockReadMayDecide]): the target of the last stale reads skipped in a row, and how many. Main only. */
    private var staleTarget: String? = null
    private var staleStreak = 0

    // Bar state
    private var coloursRestored = false
    private var savedColours: Map<String, Int> = emptyMap()
    private var lastColourSave = 0L
    private var lastRule: Pair<Int, Int>? = null
    private var barRootWindowId = -1
    private var tickerRunning = false
    private var mismatches = 0
    private var unknowns = 0
    private var receiversOn = false
    /** Main. The foreground notification: the features' last value, whether it is on, and the permission last seen (decision 0011). */
    private var fgsWanted = false
    private var fgsRunning = false
    private var notifGranted: Boolean? = null
    /** Main. The blocking value last written to the service record, and the elapsed ms of the last heartbeat. */
    private var blockingWritten: Boolean? = null
    private var lastHeartbeatAt = 0L
    /** What the overlay was last told to show. Drives the probe (see [probeAlive]) and goes into the Inspector dump. */
    private var renderName = "hidden"
    private var renderSlots = 0
    /** A display capture that must wait until the bar is hidden (see [tryStartHide]). */
    private class PendingHide(val req: CaptureRequest, val windowId: Int)
    private var pendingHide: PendingHide? = null
    /** When the current hide started (elapsed ms), for the debug trace; 0 when none. */
    private var hideQueuedAt = 0L
    /**
     * The tab nodes of the bar as of the last evaluation, by tab identity: live nodes kept from the snapshot (see
     * [NodeSnap.captureFull]), so a tap needs no fresh tree walk. [liveTabsSpec] is the tab list they were kept for.
     */
    private var liveTabs: Map<String, KeptNode> = emptyMap()
    @Volatile private var liveTabsSpec: List<BarTab> = emptyList()
    /** Taps still running on [tapExecutor]: kept nodes they use are only recycled once they finish. */
    private var tapsInFlight = 0
    private val deferredRelease = ArrayList<KeptNode>()
    private val tapExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "unrot-bar-tap").apply { isDaemon = true } }
    /** Gives up on a queued hide capture that never settles, so the controller backs off instead of waiting forever. */
    private val hideTimeout = Runnable {
        pendingHide?.let { p ->
            pendingHide = null
            bar.onCaptureFailed(p.req.gen, SystemClock.elapsedRealtime())
            scheduleEval(0)
        }
    }
    /** Re-evaluates shortly after a forwarded tap, so the controller can see whether the tab became selected. */
    private val tapWake = Runnable { scheduleEval(0) }

    // Action bookkeeping
    private var lastActionAt = 0L
    private var lastCountedAt = 0L
    private var backsInRow = 0

    private val versionCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    private val evalRunnable = Runnable { evalScheduled = false; requestCollect() }
    private val pollRunnable = Runnable {
        // While covers or the bar are on screen we keep checking, since leaving the app sends us no event.
        if (overlays.hasCovers || barShown) requestCollect()
    }
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!barShown) { tickerRunning = false; return }
            requestTick()
            main.postDelayed(this, TICK_MS)
        }
    }
    private val saveColoursRunnable = Runnable { saveColours() }
    /** Controller wake-ups get their own timer: a far-off one must never hold back event-driven evaluation. */
    private val barWake = Runnable { scheduleEval(0) }

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // A reboot or a power-off is a clean stop: no kill card after it (see ServiceState).
                Intent.ACTION_SHUTDOWN -> ServiceState.markClean(this@BlockerService)
                Intent.ACTION_SCREEN_OFF -> {
                    ServiceState.heartbeat(this@BlockerService, System.currentTimeMillis())
                    lastHeartbeatAt = SystemClock.elapsedRealtime()
                    hideBar(keepRebuilt = true) // nothing to show until the screen is back
                }
                else -> scheduleEval(STATE_DELAY_MS) // screen on / unlocked
            }
        }
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            if (pkg in TARGET_PACKAGES) versionCache.remove(pkg)
        }
    }

    private val barShown: Boolean get() = ::barOverlay.isInitialized && barOverlay.isShown

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        overlays = Overlays(this)
        barOverlay = BarOverlay(this)
        barCapture = BarCapture(this)
        bar = BarController(
            sizeOf = { it.allocationByteCount },
            same = { a, b -> sameCrop(a, b) },
            trace = if (BuildConfig.DEBUG) { m: String -> Log.i(TAG_BAR, m) } else null,
        )
        // Pictures are saved only once disclosure v3 is accepted, and never in Simple patch (see applyPictureSettings).
        bar.onCommit = { key, crop -> if (settings.disclosureVersion >= 3 && !settings.simplePatch) store?.save(key, crop) }
        bar.onDrop = { keys -> store?.delete(keys) }
        barOverlay.onSlotTap = { slot, longPress -> onBarTap(slot, longPress) }
        barOverlay.onSettled = { tryStartHide() }
        val thread = HandlerThread("unrot-eval").also { it.start() }
        evalThread = thread
        evalHandler = Handler(thread.looper)
        val tick = HandlerThread("unrot-tick").also { it.start() }
        tickThread = tick
        tickHandler = Handler(tick.looper)
        RulesRepository.load(this)
        store = BarStore(this, RulesRepository.current.version)
        registerReceivers()
        // One read-decide-write: a kill of the previous run becomes a card and, when allowed, a notification (decision 0011).
        // Failures here must not stop blocking, so they are logged and the service goes on.
        val nowWall = System.currentTimeMillis()
        lastHeartbeatAt = SystemClock.elapsedRealtime()
        runCatching {
            when (ServiceState.connect(this, nowWall)) {
                KillVerdict.None -> Unit
                is KillVerdict.Killed -> Notices.postKill(this, nowWall, crashed = false)
                is KillVerdict.Crashed -> Notices.postKill(this, nowWall, crashed = true)
            }
        }.onFailure { Log.w(TAG, "kill check not run: ${it.javaClass.simpleName}") }
        // This run is up, so a watchdog notice from before is out of date.
        Notices.cancelWatchdog(this)
        Settings.flow(this).onEach { s ->
            settings = s
            if (!coloursRestored) {
                bar.restoreColors(s.barColors)
                savedColours = s.barColors
                coloursRestored = true
            }
            applyPictureSettings(s)
            keepAlive(s)
            overlays.setInspectorButton(s.devUnlocked && s.inspectorEnabled, ::dump)
            scheduleEval(0)
        }.launchIn(scope)
        Log.i(TAG, "Service connected, rules v${RulesRepository.current.version} (${RulesRepository.source})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TARGET_PACKAGES) return
        val now = SystemClock.elapsedRealtime()
        lastTargetEventAt = now
        if (now - lastHeartbeatAt >= HEARTBEAT_MS) {
            lastHeartbeatAt = now
            ServiceState.heartbeat(this, System.currentTimeMillis())
        }
        // While the last read saw a DM chat, Instagram events keep the policy's DM context fresh (see lastReadSawDm).
        if (lastReadSawDm && pkg == INSTAGRAM_PKG) policy.dmSeen(wallOf(now))
        if (event.windowId >= 0) {
            if (targetWindows.size > 64) targetWindows.clear()
            targetWindows[event.windowId] = pkg
        }
        // The service is configured for window-state, content-changed and scroll events only (accessibility_service_config.xml).
        // The pane change types exist from API 28 (below it no event carries them).
        val pane = Build.VERSION.SDK_INT >= 28 && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            (event.contentChangeTypes and (AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED)) != 0
        // Only a horizontal scroll (a pager or tab slide) counts as urgent; a vertical feed scroll does not (API 28+ only).
        val horizontal = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            Build.VERSION.SDK_INT >= 28 && event.scrollDeltaX != 0
        requestBlockProbe(pkg, event.eventType, now, pane, horizontal)
        if (BuildConfig.DEBUG && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && event.contentChangeTypes and
                    (AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_APPEARED or AccessibilityEvent.CONTENT_CHANGE_TYPE_PANE_DISAPPEARED) != 0))
        ) {
            // Class names and change types only: no text, no descriptions.
            dbg("event type=${event.eventType} change=${event.contentChangeTypes} cls=${event.className} win=${event.windowId} bar=$barShown")
        }
        // The probed window changed its state (the app came back, or a sheet over the bar closed): probe now. Nothing is
        // drawn from the event itself: a provisional bar only comes from a probe's result (see onProbeResult).
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (probeTarget?.windowId == event.windowId) {
                // Back from another window of the app (a DM, a story closed) is a return: only that kick arms a provisional.
                kickProbe(returnDriven = probeReason == "otherTargetWindow")
            } else if (probeTarget == null && !barShown && !lastReadIme &&
                bar.rebuiltSpot()?.let { it.pkg == pkg && it.windowId == event.windowId } == true && bar.canRebuild()
            ) {
                // Back in the window of the remembered bar (after Home, Recents, or another Instagram screen): probe now,
                // without waiting for a full read (which is slow right after the app comes back). From outside the app the
                // probe accepts nothing that started before the app's first frames have settled (see REENTRY_SETTLE_MS), and
                // full reads do not draw the bar inside that time either.
                val outside = lastEvalPkg == null
                probeOutside = outside
                probeSettleUntil = if (outside) now + REENTRY_SETTLE_MS else 0L
                if (outside) reentrySettleUntil = now + REENTRY_SETTLE_MS
                probeArmed = true
                probeSpent = false
                reentryProbe = true
                updateProbe()
                kickProbe(returnDriven = true)
            }
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // event.source is an IPC into the app: read only in a DM pass, the one place the scroll's view id matters.
                val id = if (policy.dmPassActive) event.source?.let { src -> src.viewIdResourceName.also { recycleNode(src) } } else null
                handle(policy.onScroll(id), pkg, event.windowId.takeIf { it >= 0 })
                scheduleEval(THROTTLE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastTargetChangeAt = now
                scheduleEval(STATE_DELAY_MS)
            }
            else -> scheduleEval(THROTTLE_MS)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        onBarInvalidate(keepRebuilt = false) // rotated: the remembered layout's geometry is no longer the screen's
    }

    @Suppress("DEPRECATION") // TRIM_MEMORY_RUNNING_LOW: still the level that matters on our minSdk
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Memory only: the saved pictures stay on the phone and are not deleted.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN &&
            ::bar.isInitialized
        ) {
            bar.trimCrops()
            storeLoadRequested = false // the saved pictures may be loaded again, on the next read (see applyRead)
        }
    }

    /**
     * Main. The foreground notification follows the features (see [fgsTransition]), and the blocking record follows
     * [blockingOn]: on the first emission and whenever it changes. Nothing else in the settings touches either.
     */
    private fun keepAlive(s: Settings) {
        val blocking = blockingOn(s.onboarded, s.instagramEnabled, s.youtubeEnabled)
        if (blockingWritten != blocking) {
            blockingWritten = blocking
            ServiceState.setBlocking(this, blocking)
        }
        val wanted = foregroundWanted(s.instagramEnabled, s.youtubeEnabled)
        val action = fgsTransition(fgsWanted, wanted)
        fgsWanted = wanted
        if (!BuildConfig.KEEP_ALIVE_FGS) return
        when (action) {
            FgsAction.START -> startKeepAlive()
            FgsAction.STOP -> stopKeepAlive()
            FgsAction.NONE -> Unit
        }
        // The permission can be granted after the start: post the notification once more, while it is on.
        val granted = Notices.allowed(this)
        if (granted && notifGranted == false && fgsRunning) startKeepAlive()
        notifGranted = granted
    }

    /** Main. Starts the foreground notification; a failure is logged and blocking goes on without it. */
    private fun startKeepAlive() {
        runCatching { Notices.startForeground(this); fgsRunning = true }
            .onFailure { Log.w(TAG, "foreground notification not started: ${it.javaClass.simpleName}") }
    }

    /** Main. Removes the foreground notification (never throws). */
    private fun stopKeepAlive() {
        if (!fgsRunning) return
        fgsRunning = false
        runCatching { Notices.stopForeground(this) }
    }

    /** Throttle (not debounce): content-changed events never stop while a video plays. */
    private fun scheduleEval(delay: Long) {
        if (evalScheduled && delay > 0) return
        main.removeCallbacks(evalRunnable)
        evalScheduled = true
        main.postDelayed(evalRunnable, delay)
    }

    /**
     * Main. A read after the block settle time (see [handle], [applyRead]), on its own timer: the pending one, if any, is
     * replaced, and the shared throttle timer ([scheduleEval]) is never held back by it.
     */
    private fun scheduleSettleRead(delay: Long) {
        main.removeCallbacks(settleRead)
        main.postDelayed(settleRead, delay.coerceAtLeast(1L))
    }

    /**
     * Asks for an evaluation. Main: starts a read on the eval thread unless one is in flight, in which case exactly one
     * more read runs after it (requests coalesce).
     */
    private fun requestCollect() {
        if (!running) return
        if (collecting) { collectAgain = true; return }
        val handler = evalHandler ?: return
        val epoch = stateEpoch
        collecting = true
        val posted = handler.post {
            val input = try {
                collect(epoch)
            } catch (t: Throwable) {
                Log.w(TAG, "evaluation read failed: ${t.javaClass.simpleName}")
                collectPkg = null
                EvalSkip(epoch, ownApp = false) // conservative: hide the bar, as for a screen we do not know
            }
            main.post { onCollected(input) }
        }
        if (!posted) collecting = false
    }

    /** Main: a read finished. Applied unless the bar's state was forgotten since it was taken, or the service is gone. */
    private fun onCollected(input: EvalInput) {
        collecting = false
        val again = collectAgain
        collectAgain = false
        if (!running) { release(input); return }
        if (input.epoch != stateEpoch) { release(input); requestCollect(); return }
        applyInput(input)
        if (again) requestCollect()
    }

    /**
     * Eval thread. Reads the target app's tree and the window list, and returns everything [applyInput] needs. Touches no view,
     * bar, policy or settings state. [EvalRead.capturedAtMs] is when the read started: the screen as it was then.
     */
    private fun collect(epoch: Int): EvalInput {
        val t0 = SystemClock.elapsedRealtime()
        // Window list first (metadata only): never fetch the tree of a window that Instagram or YouTube didn't send events
        // from, so another app in front (e.g. under YouTube's picture-in-picture) is never read.
        val active = activeWindowInfo()
        if (active == null || targetWindows[active.id] == null) {
            val own = active != null && isOwnWindow(active, ownRectsSnap)
            collectPkg = null
            return EvalSkip(epoch, ownApp = own)
        }
        // API 33+: the checked window's root, with its descendants prefetched (see NodeSnap).
        // The root of the window checked above, not the focused window's (another IPC, which can be another app's or the shade).
        val root = if (Build.VERSION.SDK_INT >= 33) active.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
        else rootOf(active)
        val pkg = root?.packageName?.toString()
        // Our own overlay became the active window (TalkBack focus on the bar): keep the current state and hide nothing.
        if (root != null && pkg == packageName) { recycleNode(root); collectPkg = null; return EvalSkip(epoch, ownApp = true) }
        // The window's package is the one that sent its events: a window of another target app is never read as this one.
        if (pkg != targetWindows[active.id]) { root?.let(::recycleNode); collectPkg = null; return EvalSkip(epoch, ownApp = false) }
        if (root == null || pkg == null || pkg !in TARGET_PACKAGES || !screenUsable()) {
            root?.let(::recycleNode)
            collectPkg = null // the next read of a target app re-reads its version
            return EvalSkip(epoch, ownApp = false)
        }
        if (pkg != collectPkg) versionCache.remove(pkg) // a newly entered app: re-read its version
        collectPkg = pkg
        val rootWindowId = root.windowId
        val (w, h) = screenSize()
        val spec = liveTabsSpec
        val keep: ((Box, String?, String?) -> Boolean)? = if (spec.isEmpty()) null else { b, vid, desc ->
            spec.any { it.box == b && it.viewId == vid && it.desc == desc }
        }
        val cap = try { NodeSnap.captureFull(root, keep) } finally { recycleNode(root) }
        val (vc, _) = versionOf(pkg)
        val rules = RulesRepository.current.forApp(pkg, vc)
        val nav = navInsets(w, h)
        val det = Detection.run(rules, cap.root, w, h, nav.bar, cap.truncated)
        val wf = windowFacts(rootWindowId, det.bar, pkg)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val t1 = SystemClock.elapsedRealtime()
        return EvalRead(
            epoch = epoch,
            capturedAtMs = t0,
            collectMs = t1 - t0,
            pkg = pkg,
            rootWindowId = rootWindowId,
            versionCode = vc,
            rulesVersion = RulesRepository.current.version,
            det = det,
            kept = cap.kept,
            ime = imeVisible(),
            foreignAbove = wf.foreignAbove,
            targetAbove = wf.targetAbove,
            frame = Frame(night, nav.left, wf.boxes),
            nodes = cap.nodes,
        )
    }

    /**
     * Main. Before a probe-driven draw, the windows above the bar's window are taken from the live window list (metadata
     * only) instead of the last full read, which may still list a chat window that has since closed.
     */
    private fun refreshFrameForeign(windowId: Int) {
        val f = frame ?: return
        val own = ownRectsSnap
        val boxes = runCatching {
            val all = windows
            val app = all.firstOrNull { it.id == windowId } ?: return
            windowsAbove(all, app, own).map { boxOf(it) }
        }.getOrNull() ?: return
        frame = Frame(f.night, f.navLeft, boxes)
    }

    /** The windows of [all] above [app] (higher layer) that are not ours: the ones that can sit over the bar. */
    private fun windowsAbove(all: List<AccessibilityWindowInfo>, app: AccessibilityWindowInfo, own: List<Box>): List<AccessibilityWindowInfo> =
        all.filter { it.id != app.id && it.layer > app.layer && !isOwnWindow(it, own) }

    /**
     * Eval thread. The windows above the app other than ours (picture-in-picture, bubbles, the shade, and the target app's own
     * windows) as screen boxes, and whether one of them overlaps the bar's tabs box: [WindowFacts.foreignAbove] for a window
     * that is not one of the target app's (see [targetWindows]), [WindowFacts.targetAbove] for one that is. Unknown window
     * list: the bar counts as covered by a foreign window.
     */
    private fun windowFacts(rootWindowId: Int, layout: BarLayout?, pkg: String): WindowFacts {
        val own = ownRectsSnap
        return runCatching {
            val all = windows
            val app = all.firstOrNull { it.id == rootWindowId }
            val above = if (app == null) emptyList() else windowsAbove(all, app, own)
            val boxes = above.map { boxOf(it) }
            when {
                layout == null -> WindowFacts(foreignAbove = false, targetAbove = false, boxes = boxes)
                app == null -> WindowFacts(foreignAbove = true, targetAbove = false, boxes = boxes)
                else -> {
                    val over = above.filter { win -> boxOf(win).intersects(layout.tabsBox) }
                    // Only a window of the bar's own app is a target window here: another target app's (YouTube over
                    // Instagram) is a foreign one.
                    WindowFacts(
                        foreignAbove = over.any { targetWindows[it.id] != pkg },
                        targetAbove = over.any { targetWindows[it.id] == pkg },
                        boxes = boxes,
                    )
                }
            }
        }.getOrElse { WindowFacts(foreignAbove = layout != null, targetAbove = false, boxes = emptyList()) }
    }

    /**
     * Main. Applies one read: covers, the bar controller, the render, captures, the ticker, the poll, colour saves and the
     * policy. The controller's clock is the read's start time, so tap deadlines are judged by when the screen was read.
     */
    private fun applyInput(input: EvalInput) {
        when (input) {
            is EvalSkip -> {
                setDmFlag(dmFlagAfter(null))
                if (input.ownApp) return
                // Not a target app, or the screen is not usable: nothing of ours stays on screen.
                if (lastEvalPkg != null) { overlays.clearCovers(); lastEvalPkg = null }
                hideBar(keepRebuilt = true) // no reschedule here: a read would otherwise loop outside the target apps
            }
            is EvalRead -> applyRead(input, settings)
        }
    }

    /**
     * Main. Keeps the saved pictures in step with the settings. Simple patch going on, or the user's "Delete saved bar
     * pictures", deletes them and clears the crops. Pictures are loaded once disclosure v3 is accepted and Simple patch is off.
     */
    private fun applyPictureSettings(s: Settings) {
        val st = store ?: return
        val simpleBefore = simplePatchSeen
        simplePatchSeen = s.simplePatch
        val resetBefore = resetSeen
        resetSeen = s.barCacheResetAt
        val simpleTurnedOn = s.simplePatch && simpleBefore != true
        val resetByUser = resetBefore != null && resetBefore != s.barCacheResetAt
        if (simpleTurnedOn || resetByUser) {
            pictureGen++
            st.deleteAll()
            if (::bar.isInitialized) bar.clearCrops()
        }
        if (!storeLoadRequested && s.disclosureVersion >= 3 && !s.simplePatch) {
            storeLoadRequested = true
            val gen = pictureGen
            st.loadAll { list ->
                if (running && gen == pictureGen && !settings.simplePatch && ::bar.isInitialized) bar.preload(list)
            }
        }
    }

    private fun applyRead(i: EvalRead, s: Settings) {
        dbg("collect nodes=${i.nodes} ms=${i.collectMs} apply lag=${SystemClock.elapsedRealtime() - i.capturedAtMs}ms")
        lastEvalPkg = i.pkg
        lastReadIme = i.ime
        // Re-armed by onTrimMemory: load the saved pictures again (only once the real settings are known: defaults would
        // look like a fresh "Delete" and wipe them).
        if (!storeLoadRequested && coloursRestored) applyPictureSettings(s)
        // A new app version drops that app's saved pictures; a new rules version drops them all.
        val seenVersion = versionSeen.put(i.pkg, i.versionCode)
        if (seenVersion != null && seenVersion != i.versionCode) store?.deletePkg(i.pkg)
        val seenRules = rulesSeen
        rulesSeen = i.rulesVersion
        if (seenRules != null && seenRules != i.rulesVersion) {
            pictureGen++
            store?.resetAll(i.rulesVersion)
        }
        val det = i.det
        // Covers (kind cover): only when the feature is on and the keyboard isn't up (patches would float over it).
        overlays.setCovers(if (i.ime) emptyList() else det.covers.filter { s.featureEnabled(it.feature) })

        // The bar waits for the saved colours/appearance: deciding before them could trust a stale theme for the run.
        if (coloursRestored && readMayDrawBar(i.capturedAtMs, lastTargetChangeAt, i.ime, reentrySettleUntil)) {
            applyReadBar(i, s)
        } else {
            // Taken before the target's last screen change or forwarded tap: the bar keeps what it shows, and a fresh read follows.
            dbg("stale read: bar kept")
            // A stale read that sees the bar covered by another app's window, or occluded in its tree, still forgets the
            // remembered layout (the safe direction). A window of the same app, or a mid-slide geometry, keeps it.
            if (i.foreignAbove || i.det.bar?.rejectReason == "occluded") bar.forgetMemo(i.pkg)
            releaseKept(i.kept)
            scheduleEval(0)
        }
        setDmFlag(dmFlagAfter(det.markers))
        // The block decision of this read (see blockReadMayDecide): a read taken before the last Back, or within its settle time,
        // decides nothing; so does a read taken before the target's last screen change, unless the same target was already
        // skipped as stale twice in a row. A skipped read's DM marker still counts. The next read or probe decides.
        val blockId = det.blocks.firstOrNull()?.id
        val streak = if (blockId != null && blockId == staleTarget) staleStreak else 0
        if (blockId == null) { staleTarget = null; staleStreak = 0 }
        if (blockReadMayDecide(i.capturedAtMs, lastTargetChangeAt, lastActionAt, streak)) {
            // A read taken before the last screen change decides only through the starvation bound: traced (debug builds).
            if (i.capturedAtMs < lastTargetChangeAt) dbgBlock("block read decided on the stale bound, streak=$streak")
            staleTarget = null
            staleStreak = 0
            val d = policy.onScreen(det, s)
            if (d is Decision.Block) {
                dbgBlock("block read target=${d.target.id} readMs=${i.collectMs} sinceEvent=${sinceStateEvent()}")
            }
            handle(d, i.pkg, i.rootWindowId)
        } else {
            if ("dm" in det.markers) policy.dmSeen(wallOf(i.capturedAtMs))
            if (i.capturedAtMs < lastActionAt + BLOCK_SETTLE_MS) {
                // The read after the settle time rechecks a Back that did not land (see handle).
                scheduleSettleRead((lastActionAt + BLOCK_SETTLE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
                dbgBlock("block read stale: not decided")
            } else {
                // Taken before the target's last screen change: a fresh read follows, and the skip counts towards the bound.
                if (blockId != null) { staleTarget = blockId; staleStreak = streak + 1 }
                dbgBlock("block read stale: not decided, screen changed")
                scheduleEval(0)
            }
        }
        updateProbe()
    }

    /**
     * Main. Sets [lastReadSawDm]. The flag going up starts the 1 s check ([checkDmFlag]); a flag that is already up keeps the
     * check it has.
     */
    private fun setDmFlag(v: Boolean) {
        if (v && !lastReadSawDm) {
            lastReadSawDm = true
            main.removeCallbacks(dmFlagCheck)
            main.postDelayed(dmFlagCheck, DM_FLAG_CHECK_MS)
        } else {
            lastReadSawDm = v
        }
    }

    /**
     * Main. The 1 s check while [lastReadSawDm] is set: reads the window list only (metadata, never a tree) on the ticker thread,
     * and clears the flag when the active window is no longer an Instagram window (see [dmFlagStillValid]). It reschedules itself
     * while the flag stays set. A post the ticker refuses is retried by the same timer.
     */
    private fun checkDmFlag() {
        if (!running || !lastReadSawDm) return
        val posted = tickHandler?.post {
            // One of our own windows (the bar, under TalkBack focus) is not a sign that the user left the chat.
            val active = activeWindowInfo()?.takeUnless { isOwnWindow(it, ownRectsSnap) }?.id
            main.post {
                if (running && lastReadSawDm && !dmFlagStillValid(active, targetWindows)) lastReadSawDm = false
                if (running && lastReadSawDm) main.postDelayed(dmFlagCheck, DM_FLAG_CHECK_MS)
            }
        } ?: false
        if (!posted) main.postDelayed(dmFlagCheck, DM_FLAG_CHECK_MS)
    }

    /** Main. Elapsed ms since the first window-state event of the current screen, or -1 if none is known. */
    private fun sinceStateEvent(): Long = if (stateEventAt == 0L) -1L else SystemClock.elapsedRealtime() - stateEventAt

    /**
     * Main. A target event asks for a block probe of its app, when that app's feature is on. One probe is in flight at a time,
     * starts are at least [BLOCK_MIN_GAP_MS] apart, and events that come meanwhile give one follow-up probe. Content-change
     * events are the idle cut-off: after [BLOCK_MAX_MISSES] probes in a row found no block, they start no probe until a
     * window-state or scroll event.
     */
    private fun requestBlockProbe(pkg: String, eventType: Int, now: Long, pane: Boolean, horizontal: Boolean) {
        if (!running) return
        val feature = when (pkg) {
            INSTAGRAM_PKG -> "reels"
            "com.google.android.youtube" -> "shorts"
            else -> return
        }
        if (!settings.featureEnabled(feature)) return
        // Window-state and horizontal scroll events reset the miss count; the other content changes wait while it is high.
        val resets = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || horizontal
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (now - lastStateEventAt > STATE_EPISODE_GAP_MS) stateEventAt = now
            lastStateEventAt = now
        }
        if (resets) blockMisses = 0
        else if (blockMisses >= BLOCK_MAX_MISSES && !pane) return // idle: a content change starts no probe until the next window or scroll event
        if (resets || pane) blockUrgentUntil = now + BLOCK_URGENT_MS
        blockPkg = pkg
        if (blockInFlight) blockAgain = true else scheduleBlockProbe()
    }

    /**
     * Main. Runs the next block probe once the gap since the last start has passed: [BLOCK_MIN_GAP_MS] within the urgent
     * window, [BLOCK_IDLE_GAP_MS] otherwise.
     */
    private fun scheduleBlockProbe() {
        // A probe already queued is re-timed from the same start: an urgent event brings it forward.
        main.removeCallbacks(blockRunnable)
        val now = SystemClock.elapsedRealtime()
        val gap = if (now < blockUrgentUntil) BLOCK_MIN_GAP_MS else BLOCK_IDLE_GAP_MS
        val wait = gap - (now - blockStartedAt)
        main.postDelayed(blockRunnable, wait.coerceAtLeast(0L))
    }

    /** Main. Starts a probe on the block thread (its own [HandlerThread], created on first use). */
    private fun startBlockProbe() {
        val pkg = blockPkg ?: return
        if (!running || blockInFlight) return
        if (blockThread == null) {
            val th = HandlerThread("unrot-block").also { it.start() }
            blockThread = th
            blockHandler = Handler(th.looper)
        }
        val h = blockHandler ?: return
        val started = SystemClock.elapsedRealtime()
        blockStartedAt = started
        blockInFlight = true
        blockAgain = false
        val nearState = lastStateEventAt != 0L && started - lastStateEventAt < DM_NEAR_STATE_MS
        val wallStart = System.currentTimeMillis()
        val posted = h.post {
            val probe = runCatching { blockProbe(pkg, nearState) }.getOrNull()
            val ms = SystemClock.elapsedRealtime() - started
            main.post { onBlockProbeDone(probe, pkg, started, wallStart, ms) }
        }
        if (!posted) blockInFlight = false
    }

    /**
     * Block probe thread. Decides from the window list first, as [collect] does: the active window must be one a target app sent
     * events from, and of [pkg]. Only then is its root read, and only the view ids of the reel and Shorts block targets are
     * looked up (no tree walk). Returns the first block target whose exact-id conds match (null when none), the DM marker
     * seen, and the active window's id (the decision's window, see [handle]).
     */
    private fun blockProbe(pkg: String, nearState: Boolean): BlockProbe? {
        if (!screenUsable()) return null
        val active = activeWindowInfo() ?: return null
        if (targetWindows[active.id] != pkg) return null
        val (vc, _) = versionOf(pkg)
        val rules = RulesRepository.current.forApp(pkg, vc) ?: return null
        val targets = rules.targets.filter { t ->
            t.kind == TargetKind.BLOCK && settings.featureEnabled(t.feature) && t.fastViewIds(pkg).isNotEmpty()
        }
        val dmTargets = rules.targets.filter { it.kind == TargetKind.MARKER && it.feature == "dm" && it.all.isEmpty() && it.none.isEmpty() }
        if (targets.isEmpty() && dmTargets.isEmpty()) return null
        val root = rootOf(active) ?: return null
        try {
            if (root.packageName?.toString() != pkg) return null
            val (w, h) = screenSize()
            val found = HashMap<String, List<NodeSnap>>()
            fun snapsOf(t: Target) = t.fastViewIds(pkg).flatMap { id -> found.getOrPut(id) { lookupById(root, id) } }
            var hit: BlockHit? = null
            val primary = targets.firstOrNull { t -> t.matchesFast(snapsOf(t), w, h) }
            if (primary != null) {
                val key = snapsOf(primary).filter { isSettledFullWidth(it, w) }.joinToString(";") { "${it.left},${it.top},${it.right},${it.bottom}" }
                // A DM-eligible viewer hit could be granted the DM pass alone. The other reel and Shorts blocks (the Reels tab,
                // whose selected tab is narrower than full width) are judged by id too, so they still block with it.
                val extra = if (primary.dmEligible) {
                    targets.filter { it !== primary && !it.dmEligible && it.matchesById(snapsOf(it), w, h) }
                } else emptyList()
                hit = BlockHit(primary, key, extra)
            }
            // The DM context is looked up near a window change, and whenever a DM-eligible reel is hit: the chat's marker
            // must not wait for a full read (1-3 s) before a reel opened from it is judged.
            var dm = false
            if (settings.dmException && (nearState || hit?.target?.dmEligible == true) && dmTargets.isNotEmpty()) {
                dm = dmTargets.any { t ->
                    val snaps = t.exactViewIds(pkg).flatMap { id -> found.getOrPut(id) { lookupById(root, id) } }
                    t.matchesById(snaps, w, h)
                }
            }
            return BlockProbe(hit, dm, active.id)
        } finally {
            recycleNode(root)
        }
    }

    /** Block probe thread. Snapshots the first [MAX_FAST_NODES] nodes with [id] (no children) and recycles the rest. */
    private fun lookupById(root: AccessibilityNodeInfo, id: String): List<NodeSnap> {
        val nodes = root.findAccessibilityNodeInfosByViewId(id) ?: return emptyList()
        return try {
            nodes.take(MAX_FAST_NODES).map { NodeSnap.single(it) }
        } finally {
            nodes.forEach(::recycleNode)
        }
    }

    /**
     * Main. A block probe finished. A hit is handled as a full read's block would be (same policy, two-probe agreement,
     * action verdict and counter), unless the probe started before the last Back: that Back already left the screen it saw.
     */
    private fun onBlockProbeDone(probe: BlockProbe?, pkg: String, started: Long, wallStart: Long, ms: Long) {
        blockInFlight = false
        if (!running) return
        if (probe?.dmSeen == true) policy.dmSeen(wallStart)
        val hit = probe?.hit
        val now = SystemClock.elapsedRealtime()
        if (hit == null) {
            blockMisses++
            pendingHit = null
        } else {
            blockMisses = 0
            val p = pendingHit
            val agreed = p != null && p.target.id == hit.target.id && p.key == hit.key && now - pendingAt <= PENDING_MS
            pendingHit = null
            if (started >= lastActionAt + BLOCK_SETTLE_MS) {
                if (agreed) {
                    // The markers are not used by a fast detection (see Policy.onScreen): none are passed.
                    val d = policy.onScreen(Detection(listOf(hit.target) + hit.extra, emptySet(), emptyList()), settings, fast = true)
                    if (d is Decision.Block) {
                        dbgBlock("block fast target=${hit.target.id} probe=$ms sinceEvent=${sinceStateEvent()}")
                    }
                    handle(d, pkg, probe?.windowId)
                } else {
                    // The first sighting: the next probe must find the same target at the same bounds before it acts.
                    pendingHit = hit
                    pendingAt = now
                    blockAgain = true
                }
            }
        }
        if (blockAgain) {
            blockAgain = false
            scheduleBlockProbe()
        }
    }

    /** Wall-clock ms of an elapsed-clock instant, for the policy's clock. */
    private fun wallOf(elapsedAt: Long): Long = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedAt)

    private fun dbgBlock(msg: String) {
        if (BuildConfig.DEBUG) Log.i(TAG_BLOCK, msg)
    }

    /** Main. The bar part of a read that may draw: the controller, the render, captures, the ticker and the poll. */
    private fun applyReadBar(i: EvalRead, s: Settings) {
        barRootWindowId = i.rootWindowId
        frame = i.frame
        val det = i.det
        val layout = det.bar
        layout?.let { lastRule = it.light to it.dark }
        releaseKept(liveTabs.values.toList())
        liveTabs = mapKept(i.kept, layout)
        liveTabsSpec = layout?.tabs.orEmpty()
        val env = Env(
            api = Build.VERSION.SDK_INT,
            nowMs = i.capturedAtMs,
            night = i.frame.night,
            featureOn = layout?.let { s.featureEnabled(it.feature) } ?: false,
            imeVisible = i.ime,
            simplePatch = s.simplePatch,
            disclosureV2 = s.disclosureVersion >= 2,
            appVersion = i.versionCode,
            rulesVersion = i.rulesVersion,
            windowId = i.rootWindowId,
            foreignWindowAbove = i.foreignAbove,
            targetWindowAbove = i.targetAbove,
        )
        lastBarRead = LastBarRead(i.pkg, layout, env, i.rootWindowId, i.frame.navLeft, stateEpoch)
        val bo = bar.onEvaluate(i.pkg, layout, env)
        // Another window of the target app over the bar: the controller hides it and keeps its layout; the probe waits for it.
        targetAboveHidden = layout != null && i.targetAbove
        // A new no-bar episode (the bar was not hidden by a no-bar read before) may probe again, even after an expired one.
        if (layout == null && noBarSince == 0L) probeSpent = false
        noBarSince = if (layout != null) 0L else noBarSince.takeIf { it != 0L } ?: i.capturedAtMs
        // A fresh read settles a provisional bar: a Rebuilt layout confirms it (touchable from here on), anything else drops it.
        // A read taken mid-slide (the bar is only moving, so Patch or Hidden for now) neither draws over the provisional bar nor
        // drops it: the bar is still where it was shown, and the probe confirms it.
        val keepProvisional = provisionalAt != 0L && bo.state !is RenderState.Rebuilt<*> && bar.holdingForMove
        if (provisionalAt != 0L && !keepProvisional) {
            if (bo.state is RenderState.Rebuilt<*>) {
                dbg("provisional confirmed ms=${SystemClock.elapsedRealtime() - provisionalAt} src=read")
                clearProvisional()
            } else {
                dropProvisional("read")
            }
        }
        applyBar(bo, i.rootWindowId, i.frame.navLeft, render = !keepProvisional)
        saveColoursIfChanged()
        updateTicker()
        if (overlays.hasCovers || barShown) {
            main.removeCallbacks(pollRunnable)
            main.postDelayed(pollRunnable, POLL_MS)
        }
    }

    /** The bar part of the last applied read, so a capture result can redraw at once instead of waiting for a new read. */
    private class LastBarRead(
        val pkg: String, val layout: BarLayout?, val env: Env, val rootWindowId: Int, val navLeft: Int, val epoch: Int,
    )
    private var lastBarRead: LastBarRead? = null

    private fun applyBar(out: Output, rootWindowId: Int, navLeft: Int, render: Boolean = true) {
        if (render) renderBar(out.state, navLeft)
        out.capture?.let { c ->
            // Any display shot waits until nothing of ours is over the bar (a Patch-only evaluation may still leave it up).
            if (c.kind == CaptureKind.WINDOW || barOverlay.hideSettled()) startCapture(c, rootWindowId)
            else queueHide(c, rootWindowId)
        }
        main.removeCallbacks(barWake)
        out.evalAtMs?.let { main.postDelayed(barWake, (it - SystemClock.elapsedRealtime()).coerceAtLeast(0)) }
    }

    /**
     * After a capture result: re-applies the last read (same snapshot time, so tap deadlines are judged fairly) so the new
     * crops show at once. Only for a fresh read (≤ [REAPPLY_MAX_AGE_MS]); older reads wait for the next one, since a sheet
     * may have opened over the bar meanwhile.
     */
    private fun reapplyLastRead() {
        val r = lastBarRead ?: return
        if (r.epoch != stateEpoch || SystemClock.elapsedRealtime() - r.env.nowMs > REAPPLY_MAX_AGE_MS) return
        // Only when the screen is still the one that was read: no keyboard, no sheet over the bar, and no target event since.
        val layout = r.layout ?: return
        val wf = windowFacts(r.rootWindowId, layout, r.pkg)
        if (imeVisible() || wf.foreignAbove || wf.targetAbove) return
        if (lastTargetChangeAt > r.env.nowMs || lastTargetEventAt > r.env.nowMs) return
        applyBar(bar.onEvaluate(r.pkg, layout, r.env), r.rootWindowId, r.navLeft)
        updateTicker()
    }

    /**
     * Draws [state] as the bar, or hides it when another window sits over what would be drawn. Uses the screen facts of the
     * last read ([frame]). Main thread.
     */
    private fun renderBar(state: RenderState, navLeft: Int, touchable: Boolean = provisionalAt == 0L) {
        val f = frame ?: return
        // Simple patch uses the rule colour instead of the sampled one.
        val st = displayState(state, settings, f.night)
        // Never draw over another app's window (picture-in-picture, a bubble) that sits over what would be drawn: the
        // patch's box, or the whole tabs box for a redrawn bar.
        val drawn = when (st) {
            is RenderState.Patch -> st.box
            is RenderState.Rebuilt<*> -> st.tabsBox
            RenderState.Hidden -> null
        }
        val blocked = drawn != null && f.foreign.any { it.intersects(drawn) }
        val render = if (blocked) RenderState.Hidden else st
        if (blocked && st is RenderState.Rebuilt<*>) dbg("drop why=foreignWindow")
        renderName = when (render) {
            is RenderState.Patch -> "patch"
            is RenderState.Rebuilt<*> -> "rebuilt"
            RenderState.Hidden -> "hidden"
        }
        renderSlots = (render as? RenderState.Rebuilt<*>)?.slots?.size ?: 0
        barOverlay.render(render, navLeft, touchable)
        if (renderName == "rebuilt" && hideQueuedAt != 0L) {
            dbg("hide shown +${sinceHide()}ms")
            hideQueuedAt = 0L
        }
        publishOwnRects()
    }

    /** Main. Publishes our window rectangles for the eval, tick and probe threads' window checks. Call after anything of ours changes. */
    private fun publishOwnRects() {
        ownRectsSnap = ownRects()
    }

    /** What the overlay shows. Simple patch uses the rule colour instead of the sampled one. */
    private fun displayState(st: RenderState, s: Settings, night: Boolean): RenderState {
        val rule = lastRule
        return if (st is RenderState.Patch && s.simplePatch && rule != null) {
            RenderState.Patch(st.box, if (night) rule.second else rule.first)
        } else st
    }

    private fun startCapture(req: CaptureRequest, windowId: Int) {
        val (sw, sh) = screenSize()
        barCapture.run(
            req, windowId, sw, sh,
            onResult = { r ->
                if (req.kind == CaptureKind.DISPLAY_WITH_HIDE) dbg("hide capture result +${sinceHide()}ms")
                val now = SystemClock.elapsedRealtime()
                // The user may have left while the shot was taken: then the pixels may not be the app's bar. An unknown
                // active window, or one of ours (TalkBack focus on the bar), keeps the result. A keyboard that came up
                // meanwhile drops it: the shot may show the keyboard's sheet, and the request must still be settled.
                val active = activeWindowInfo()
                // A display shot must come with a known active window (else it may not be the bar's); a window shot needs none.
                val verdict = displayResultVerdict(active != null || req.kind == CaptureKind.WINDOW, req.readAtMs, lastTargetChangeAt)
                when {
                    imeVisible() -> { bar.onCaptureFailed(req.gen, now); scheduleEval(0) }
                    verdict == DisplayVerdict.UNKNOWN_WINDOW -> {
                        dbg("capture dropped: active window unknown")
                        bar.onCaptureFailed(req.gen, now); scheduleEval(0)
                    }
                    // The app changed screen after the read the shot was requested with (a sheet or story opening): the pixels
                    // may not be the bar, and would be saved. Treat it as failed; a later capture retries.
                    verdict == DisplayVerdict.STALE -> { dbg("capture dropped: screen changed"); bar.onCaptureFailed(req.gen, now); scheduleEval(0) }
                    active != null && active.id != windowId && !isOwnWindow(active, ownRects()) -> onBarInvalidate(keepRebuilt = true)
                    bar.onCaptureResult(r, now) -> { reapplyLastRead(); scheduleEval(0) }
                }
            },
            onFail = { bar.onCaptureFailed(req.gen, SystemClock.elapsedRealtime()); scheduleEval(0) },
        )
    }

    /**
     * A display capture (API 30-33) must not show the redrawn bar. It is queued until the bar is hidden and the patch has
     * drawn, then started one vsync later by [tryStartHide].
     */
    private fun queueHide(req: CaptureRequest, windowId: Int) {
        pendingHide = PendingHide(req, windowId)
        hideQueuedAt = SystemClock.elapsedRealtime()
        dbg("hide queued")
        main.removeCallbacks(hideTimeout)
        main.postDelayed(hideTimeout, HIDE_TIMEOUT_MS)
        tryStartHide()
    }

    private fun tryStartHide() {
        val p = pendingHide ?: return
        if (!barOverlay.hideSettled()) return
        pendingHide = null
        main.removeCallbacks(hideTimeout)
        dbg("hide settled +${sinceHide()}ms")
        Choreographer.getInstance().postFrameCallback {
            if (!running) return@postFrameCallback
            // Re-checked here: the bar's window must still be the active one (or ours) and no keyboard may be up.
            val active = activeWindowInfo()
            val windowOk = active != null && (active.id == p.windowId || isOwnWindow(active, ownRects()))
            if (bar.generation != p.req.gen || !barOverlay.hideSettled() || !windowOk || imeVisible()) {
                bar.onCaptureFailed(p.req.gen, SystemClock.elapsedRealtime())
                scheduleEval(0)
            } else {
                dbg("hide capture start +${sinceHide()}ms")
                startCapture(p.req, p.windowId)
            }
        }
    }

    /**
     * A tap on a slot of the redrawn bar. The tapped tab is drawn selected at once from the cache (no tree read), the
     * next evaluation predicts the same from the cache too, and the tap is forwarded on a background thread: first on the
     * tab node kept from the last evaluation, and only when that fails, a fresh lookup from the window's root. Verified by
     * later evaluations.
     */
    private fun onBarTap(slot: Int, longPress: Boolean) {
        val t0 = SystemClock.elapsedRealtime()
        val cmd = bar.onTap(slot, longPress, t0) ?: return
        // Reads taken before this tap do not change the bar (see applyRead). Set only once the controller took the tap.
        lastTargetChangeAt = t0
        bar.onTapRender()?.let { renderBar(it.state, frame?.navLeft ?: 0) }
        scheduleEval(0)
        // The package the bar was drawn for (the controller's own), so a tap works right after a redraw from the cache.
        val pkg = cmd.pkg
        val windowId = barRootWindowId
        val live = liveTabs[cmd.identity]?.node
        tapsInFlight++
        val queued = runCatching {
            tapExecutor.execute {
                var result: Pair<Boolean?, String> = false to "error"
                try {
                    result = performBarTap(cmd, pkg, windowId, live)
                } catch (t: Throwable) {
                    // Metadata only: no tab name, no id, no text. The tap is reported as failed, never as dropped.
                    Log.w(TAG_BAR, "bar tap threw: ${t.javaClass.simpleName}")
                    result = false to "error"
                } finally {
                    // Always posted, so tapsInFlight drops back even when the tap threw.
                    val (ok, path) = result
                    val ms = SystemClock.elapsedRealtime() - t0
                    main.post {
                        tapsInFlight--
                        if (tapsInFlight == 0) {
                            deferredRelease.forEach { recycleNode(it.node) }
                            deferredRelease.clear()
                        }
                        dbg("tap slot=$slot id=${cmd.identity.hashCode()} path=$path ms=$ms ok=$ok")
                        if (ok != null) {
                            bar.onTapResult(ok)
                            scheduleEval(0)
                            main.postDelayed(tapWake, TAP_WAKE_1_MS)
                            main.postDelayed(tapWake, TAP_WAKE_2_MS)
                        }
                    }
                }
            }
        }.isSuccess
        if (!queued) tapsInFlight--
    }

    /**
     * Background thread. Returns the result (null: dropped, the window is no longer the bar's) and the path taken.
     * Fast path: the kept node, if it is still where the tab was drawn. Slow path: a fresh lookup from the root.
     */
    private fun performBarTap(cmd: TapCommand, pkg: String, windowId: Int, live: AccessibilityNodeInfo?): Pair<Boolean?, String> {
        val action = if (cmd.longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        if (live != null && live.windowId == windowId && near(live, cmd.box) && activeWindowInfo()?.id == windowId) {
            val target = clickableFrom(live, keepStart = true)
            if (target != null) {
                val ok = target.performAction(action)
                if (target !== live) recycleNode(target)
                if (ok) return true to "fast"
            }
        }
        // The root of the bar's own window, not of whatever is focused now (see collect).
        val win = activeWindowInfo()?.takeIf { it.id == windowId } ?: return null to "slow"
        val root = rootOf(win) ?: return null to "slow"
        return try {
            if (root.packageName?.toString() != pkg || root.windowId != windowId) return null to "slow"
            val node = findTapNode(root, cmd) ?: return false to "slow"
            val target = clickableFrom(node, keepStart = false) ?: return false to "slow"
            val ok = target.performAction(action)
            recycleNode(target)
            ok to "slow"
        } finally {
            recycleNode(root)
        }
    }

    /**
     * The node itself if clickable, else its nearest clickable ancestor (within [MAX_ANCESTORS]), or null. The ancestors walked
     * past are recycled. [start] is recycled too unless [keepStart]: the kept live tab node is never recycled here. The node
     * returned is the caller's to recycle.
     */
    private fun clickableFrom(start: AccessibilityNodeInfo, keepStart: Boolean): AccessibilityNodeInfo? {
        var cur = start
        var depth = 0
        while (true) {
            if (cur.isClickable) return cur
            if (depth++ >= MAX_ANCESTORS) break
            val parent = cur.parent ?: break
            if (cur !== start || !keepStart) recycleNode(cur)
            cur = parent
        }
        if (cur !== start || !keepStart) recycleNode(cur)
        return null
    }

    /** Keeps the kept nodes that match a tab of [layout] by identity; the rest are released. Main thread. */
    private fun mapKept(kept: List<KeptNode>, layout: BarLayout?): Map<String, KeptNode> {
        val tabs = layout?.tabs.orEmpty().filter { !it.hidden }
        val out = HashMap<String, KeptNode>()
        for (k in kept) {
            val t = tabs.firstOrNull { it.box == k.bounds && it.viewId == k.viewId && it.desc == k.desc }
            if (t != null && !out.containsKey(t.identity)) out[t.identity] = k else releaseKept(listOf(k))
        }
        return out
    }

    /** Recycles kept nodes, or once the taps using them have finished (API 33+ recycle does nothing anyway). */
    private fun releaseKept(nodes: List<KeptNode>) {
        if (nodes.isEmpty()) return
        if (tapsInFlight > 0) deferredRelease.addAll(nodes) else nodes.forEach { recycleNode(it.node) }
    }

    /** Forgets the bar's kept tab nodes. Used whenever the bar is hidden, so nothing is kept for another screen. */
    private fun releaseLive() {
        releaseKept(liveTabs.values.toList())
        liveTabs = emptyMap()
        liveTabsSpec = emptyList()
    }

    /** Recycles the nodes of a read that will not be applied. */
    private fun release(input: EvalInput) {
        if (input is EvalRead) input.kept.forEach { recycleNode(it.node) }
    }

    private fun sinceHide(): Long = if (hideQueuedAt == 0L) 0L else SystemClock.elapsedRealtime() - hideQueuedAt

    /** Debug-only trace for the bar (tag UnrotBar). Never called with descriptions or pixels. */
    private fun dbg(msg: String) {
        if (BuildConfig.DEBUG) Log.i(TAG_BAR, msg) // Log.d is dropped on Realme UI
    }

    /** The node for a tab: by view id (within 4 px of the tab), then by description and box, then by box alone. */
    private fun findTapNode(root: AccessibilityNodeInfo, cmd: TapCommand): AccessibilityNodeInfo? {
        cmd.viewId?.let { id ->
            var found: AccessibilityNodeInfo? = null
            for (n in root.findAccessibilityNodeInfosByViewId(id)) {
                if (found == null && near(n, cmd.box)) found = n else recycleNode(n)
            }
            if (found != null) return found
        }
        return walkFor(root, cmd, requireDesc = true, depth = 0) ?: walkFor(root, cmd, requireDesc = false, depth = 0)
    }

    private fun walkFor(node: AccessibilityNodeInfo, cmd: TapCommand, requireDesc: Boolean, depth: Int): AccessibilityNodeInfo? {
        if (depth > MAX_WALK_DEPTH) return null
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val descOk = !requireDesc || (cmd.desc != null && c.contentDescription?.toString() == cmd.desc)
            if (descOk && near(c, cmd.box)) return c
            val deeper = walkFor(c, cmd, requireDesc, depth + 1)
            if (deeper != null) { recycleNode(c); return deeper }
            recycleNode(c)
        }
        return null
    }

    private fun near(n: AccessibilityNodeInfo, box: Box): Boolean {
        val r = Rect()
        n.getBoundsInScreen(r)
        return abs(r.left - box.l) <= BarProbe.SLOP_PX && abs(r.top - box.t) <= BarProbe.SLOP_PX &&
            abs(r.right - box.r) <= BarProbe.SLOP_PX && abs(r.bottom - box.b) <= BarProbe.SLOP_PX
    }

    /**
     * Hides the bar and forgets its state, for a window change (the app is left, or another window takes the front), a
     * rotation, or a ticker mismatch. [keepRebuilt]: the app is being left, so its Rebuilt layout is kept for the bar-back
     * probe (see [hideBar]).
     */
    private fun onBarInvalidate(keepRebuilt: Boolean) {
        if (!::bar.isInitialized) return
        hideBar(keepRebuilt)
        scheduleEval(STATE_DELAY_MS)
    }

    /**
     * Hides the bar, and forgets its state. [keepRebuilt] keeps the last Rebuilt layout per app (for leaving the app, the
     * keyboard and a ticker mismatch), so the bar can come back from the cache on re-entry; a rotation or teardown does not.
     */
    private fun hideBar(keepRebuilt: Boolean) {
        if (!::bar.isInitialized) return
        if (provisionalAt != 0L) dropProvisional("stop")
        // The starvation bound counts stale reads of the screen being left: a new screen starts it again.
        staleTarget = null
        staleStreak = 0
        targetAboveHidden = false
        if (BuildConfig.DEBUG && barShown) {
            val st = Throwable().stackTrace
            dbg("hideBar from=${st.getOrNull(1)?.methodName}<${st.getOrNull(2)?.methodName}")
        }
        stopProbe("hide")
        stateEpoch++ // reads taken before this are not applied
        bar.invalidate(keepRebuilt)
        pendingHide = null
        main.removeCallbacks(hideTimeout)
        barOverlay.remove()
        releaseLive()
        updateTicker()
        publishOwnRects()
    }

    /**
     * Main. The probe runs while the bar is hidden by the last read (a read with no bar layout), or only moving (see
     * [BarController.holdingForMove]), and [BarController.canRebuild] says the remembered Rebuilt layout can be drawn again
     * from cache. Not after a drop by occlusion or a patch-only evaluation.
     */
    private fun updateProbe() {
        if (!probeAlive()) { stopProbe("state"); return }
        // The probe follows the window of the remembered Rebuilt layout: another window or app stops it.
        val spot = bar.rebuiltSpot()
        probeTarget?.let { t -> if (spot == null || t.pkg != spot.pkg || t.windowId != spot.windowId) stopProbe("window") }
        // Not started while the keyboard is up; a probe that is running keeps going through it (see probeBarBack).
        // An expired probe is not restarted by reads: only a kick by a return or a new no-bar episode starts one again.
        if (probeTarget == null && !lastReadIme && !probeSpent) startProbe()
    }

    /** Main. The bar is in a state the probe is for, and the last read's app is the one that layout belongs to. */
    private fun probeAlive(): Boolean {
        val r = lastBarRead
        val hidden = (r != null && r.layout == null && renderName == "hidden" && lastEvalPkg == r.pkg) || bar.holdingForMove ||
            (targetAboveHidden && renderName == "hidden")
        // A provisional bar is shown already, so canRebuild is false then: the probe runs for its confirmation.
        val provisional = provisionalAt != 0L
        return running && (hidden || provisional || (reentryProbe && !barShown)) && (provisional || bar.canRebuild())
    }

    /**
     * Main. Shows the remembered Rebuilt layout at once, UNTOUCHABLE, when the window of the bar's app comes back (the bar
     * was hidden, and the probe has not confirmed it). Taken from the cache only (see [BarController.onBarBack]), so the
     * bar is the one drawn before. The probe confirms it (see [onProbeResult]); a read, a stop or [PROVISIONAL_MS] drops it.
     */
    private fun showProvisional(trigger: String) {
        if (!running || provisionalAt != 0L || barShown || lastReadIme || frame == null) return
        val t = probeTarget ?: return
        if (!settings.featureEnabled(t.feature) || !screenUsable()) return
        if (!bar.canRebuild()) return
        val now = SystemClock.elapsedRealtime()
        val st = bar.onBarBack(now)?.state as? RenderState.Rebuilt<*> ?: return
        provisionalAt = now
        provisionalState = st
        // Reads started before this describe the screen before the return: they are stale and keep the bar as it is.
        lastTargetChangeAt = now
        bar.rebuiltSpot()?.let { barRootWindowId = it.windowId }
        mismatches = 0
        unknowns = 0
        dbg("provisional show trigger=$trigger")
        renderBar(st, frame?.navLeft ?: 0, touchable = false)
        if (renderName != "rebuilt") {
            // A window of another app sits over the bar: nothing is shown, so the controller goes back to Hidden.
            clearProvisional()
            bar.invalidate(keepRebuilt = true)
            updateTicker()
            return
        }
        updateTicker()
        requestTick()
        main.removeCallbacks(provisionalExpire)
        main.postDelayed(provisionalExpire, PROVISIONAL_MS)
    }

    /** Main. Ends the provisional bar. A read has already given the controller its state, so only the window goes; otherwise the bar is Hidden. */
    private fun dropProvisional(reason: String) {
        if (provisionalAt == 0L) return
        dbg("provisional dropped reason=$reason ms=${SystemClock.elapsedRealtime() - provisionalAt}")
        clearProvisional()
        if (reason == "read") return
        bar.invalidate(keepRebuilt = true) // the layout stays in the re-entry memo, so the probe can still confirm it
        renderBar(RenderState.Hidden, frame?.navLeft ?: 0)
        updateTicker()
        if (reason == "timeout") scheduleEval(0)
    }

    /** Main. Forgets the provisional state, without a trace line or a render. */
    private fun clearProvisional() {
        provisionalAt = 0L
        provisionalState = null
        main.removeCallbacks(provisionalExpire)
    }

    /** A probe started by returning to the remembered bar's window (see onAccessibilityEvent); cleared when it stops. */
    private var reentryProbe = false
    /** Main only: for the next probe started, whether it began from outside the app, until when its results settle, and
     *  whether it was started by a return (so it may show a provisional bar, once). */
    private var probeOutside = false
    private var probeSettleUntil = 0L
    private var probeArmed = false
    /** Main only: a probe ran out its episode cap; none starts again until a kick by a return or a new no-bar episode. */
    private var probeSpent = false
    /** Main only: until when a read may not draw the bar after a return from outside the app (absolute elapsed ms). */
    private var reentrySettleUntil = 0L

    /**
     * Main. Starts the probe for the window of the remembered Rebuilt layout. Off when the rules have no view id in the bar
     * condition, or the feature is off. The bar's view id is the bar cond's viewId, with the package prefix when it has none.
     */
    private fun startProbe() {
        // The one-shot inputs of the next probe are taken here, so an early return does not leave them for a later probe.
        val outside = probeOutside
        val settle = probeSettleUntil
        val armed = probeArmed
        probeOutside = false
        probeSettleUntil = 0L
        probeArmed = false
        val spot = bar.rebuiltSpot() ?: return
        val t = RulesRepository.current.forApp(spot.pkg, versionOf(spot.pkg).first)
            ?.targets?.firstOrNull { it.kind == TargetKind.BAR } ?: return
        if (!settings.featureEnabled(t.feature)) return
        val viewId = t.probeViewId(spot.pkg) ?: return
        if (probeThread == null) {
            val th = HandlerThread("unrot-probe").also { it.start() }
            probeThread = th
            probeHandler = Handler(th.looper)
        }
        probeInFlight = false
        probeReason = null
        val now = SystemClock.elapsedRealtime()
        val hiddenSince = noBarSince.takeIf { it != 0L } ?: now
        probeTarget = ProbeTarget(
            spot.pkg, spot.windowId, viewId, spot.container, spot.tabsBox, t.feature, hiddenSince, now,
            outsideReentry = outside, settleUntil = settle, armed = armed,
        )
        dbg("probe start")
        main.removeCallbacks(probeRunnable)
        main.post(probeRunnable)
    }

    /** Main. Stops the probe and quits its thread. A result still on its way is ignored (see [onProbeResult]). */
    private fun stopProbe(reason: String? = null) {
        if (probeTarget != null && reason != null) dbg("probe stop reason=$reason")
        probeTarget = null
        probeInFlight = false
        probeReason = null
        reentryProbe = false
        main.removeCallbacks(probeRunnable)
        probeHandler?.removeCallbacksAndMessages(null)
        probeThread?.quitSafely()
        probeThread = null
        probeHandler = null
    }

    /** Main. A window-state change of the probed window: the probe runs now rather than at its next tick, fast again. */
    private fun kickProbe(returnDriven: Boolean = false) {
        val t = probeTarget ?: return
        t.fastFrom = SystemClock.elapsedRealtime()
        // A kick re-arms the probe's one provisional bar only when it comes from a return (see BarProbe.provisionalFromProbe).
        t.armed = returnDriven
        if (returnDriven) probeSpent = false
        dbg("probe kick")
        main.removeCallbacks(probeRunnable)
        main.post(probeRunnable)
    }

    /** Main. The probe's cadence (see [BarProbe.intervalMs]): one probe read on the probe thread, at most one in flight. */
    private fun probeTick() {
        val t = probeTarget ?: return
        val now = SystemClock.elapsedRealtime()
        // Stops with the episode: a feature switched off, a state no longer probed, no event from the target for a minute,
        // or its cap (BarProbe.EPISODE_MS, from this probe's own start or last kick). A capped probe is spent: reads do not
        // start it again until a return or a new no-bar episode.
        val idle = now - lastTargetEventAt > PROBE_IDLE_MS
        val expired = BarProbe.episodeOver(now, t.fastFrom)
        if (!probeAlive() || !settings.featureEnabled(t.feature) || idle || expired) {
            if (expired) probeSpent = true
            stopProbe(if (idle) "idle" else if (expired) "episode" else if (!probeAlive()) "state" else "feature")
            return
        }
        val handler = probeHandler ?: return
        if (!probeInFlight) {
            probeInFlight = true
            val started = now
            val posted = handler.post {
                val (outcome, reason) = runCatching { probeBarBack(t) }.getOrDefault(ProbeOutcome.NOT_BACK to "error")
                val ms = SystemClock.elapsedRealtime() - started
                main.post { onProbeResult(t, outcome, reason, started, ms) }
            }
            if (!posted) probeInFlight = false
        }
        main.removeCallbacks(probeRunnable)
        main.postDelayed(probeRunnable, BarProbe.intervalMs(now - t.fastFrom))
    }

    /**
     * Probe thread. Decides from the window list (metadata only) first, and reads a root only for the probed window, and only
     * when a target-app event came from that window (see [targetWindows]):
     * - another window of Instagram or YouTube is active (a story, a DM thread, a profile sheet): not back, keep probing;
     * - one of our own windows is active: not back, keep probing;
     * - anything else (another app, the launcher, system UI): stop;
     * - the probed window is active: with the keyboard up, not back and no read; else its root. BACK when a visible node with
     *   the bar's view id is at the container box (within [BarProbe.SLOP_PX]). APPEARING when such a node is visible but not
     *   there yet: its top and bottom match the tabs box's band, or it meets the container (the bar slides in).
     * Returns the outcome and, when it is not back, the reason for the debug trace.
     */
    private fun probeBarBack(t: ProbeTarget): Pair<ProbeOutcome, String> {
        val list = runCatching { windows }.getOrNull() ?: return ProbeOutcome.NOT_BACK to "noWindows"
        val own = ownRectsSnap
        val wins = list.map { w ->
            ProbeWin(
                id = w.id, layer = w.layer, active = w.isActive, ime = w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD,
                own = isOwnWindow(w, own), targetPkg = targetWindows[w.id], box = boxOf(w),
            )
        }
        // The window list decides first (metadata only): no node of a window that is not the probed app's is read.
        BarProbe.windowVerdict(wins, t.windowId, t.pkg, t.tabs)?.let { return it }
        val active = activeWindowOf(list) ?: return ProbeOutcome.NOT_BACK to "noActive"
        // The root of exactly the window checked above (rootOf), so a window change in between can't make us read another app.
        val root = rootOf(active) ?: return ProbeOutcome.NOT_BACK to "noRoot"
        return try {
            if (root.packageName?.toString() != t.pkg || root.windowId != t.windowId) return ProbeOutcome.NOT_BACK to "otherRoot"
            val visible = ArrayList<Box>()
            for (n in root.findAccessibilityNodeInfosByViewId(t.viewId)) {
                if (n.isVisibleToUser) visible += nodeBox(n)
                recycleNode(n)
            }
            BarProbe.nodeVerdict(visible, t.container, t.tabs)
        } finally {
            recycleNode(root)
        }
    }

    /** The root of [win] (API 33+: its root with no flags; before, the window's own root, one IPC, of exactly that window). */
    private fun rootOf(win: AccessibilityWindowInfo): AccessibilityNodeInfo? =
        if (Build.VERSION.SDK_INT >= 33) win.getRoot(0) else win.root

    /** Screen bounds of a node as a [Box]. */
    private fun nodeBox(n: AccessibilityNodeInfo): Box {
        val r = Rect()
        n.getBoundsInScreen(r)
        return Box(r.left, r.top, r.right, r.bottom)
    }

    /**
     * Main. A probe read finished. When the bar is back, the remembered Rebuilt layout is drawn again from the cache and a
     * full read follows at once to confirm it (that read applies as usual: occlusion or no bar drops the bar). When a bar
     * node is appearing, the layout is shown provisionally (untouchable, see [showProvisional]) while the probe goes on.
     */
    private fun onProbeResult(t: ProbeTarget, outcome0: ProbeOutcome, reason0: String, started: Long, ms: Long) {
        if (probeTarget !== t) return // a result of a probe that was stopped meanwhile
        probeInFlight = false
        if (outcome0 == ProbeOutcome.STOP || !probeAlive() || !settings.featureEnabled(t.feature)) {
            dropProvisional(if (outcome0 == ProbeOutcome.STOP) "stop" else "state")
            stopProbe(if (outcome0 == ProbeOutcome.STOP) reason0 else "state")
            return
        }
        // A read that started before the target's last screen change, or inside the re-entry settle time, is not back; a
        // stale one is probed again at once.
        val acc = BarProbe.acceptResult(outcome0, reason0, started, t.settleUntil, lastTargetChangeAt)
        if (acc.kick) kickProbe(returnDriven = t.armed) // a stale result keeps the probe as it was armed
        val outcome = acc.outcome
        val reason = acc.reason
        if (outcome == ProbeOutcome.APPEARING || outcome == ProbeOutcome.BACK) refreshFrameForeign(t.windowId)
        if (outcome == ProbeOutcome.APPEARING) {
            // The probe saw the bar coming back right after another Instagram window was in front: that is a return, even
            // when Instagram's window-state event (which arms the probe) arrives a moment later.
            val returning = t.armed || probeReason == "otherTargetWindow"
            if (reason != probeReason) {
                probeReason = reason
                dbg("probe appearing")
            }
            val show = BarProbe.provisionalFromProbe(
                outcome, ime = lastReadIme, provisional = provisionalAt != 0L,
                canRebuild = bar.canRebuild(), outsideReentry = t.outsideReentry, armed = returning,
            )
            if (show) {
                t.armed = false // one provisional bar per return (a kick by a return re-arms it)
                showProvisional("appearing")
            }
            return
        }
        if (outcome == ProbeOutcome.NOT_BACK) {
            if (reason != probeReason) {
                probeReason = reason
                dbg("probe not-back reason=$reason")
            }
            return
        }
        // The bar is back, but the last read saw the keyboard up: the bar is not drawn over it, and a fresh read (which
        // refreshes that reading) decides whether it is still up.
        if (lastReadIme) { scheduleEval(0); return }
        val now = SystemClock.elapsedRealtime()
        dbg("probe back ms=${now - t.hiddenSince} probe=${ms}ms")
        stopProbe("back")
        val shownNow = provisionalState
        if (provisionalAt != 0L && shownNow != null) {
            // The provisional bar is confirmed, and from here on it takes touches. The controller is re-synced first: a read
            // skipped while the bar slid (see applyReadBar) may have left it Patch or Hidden. onBarBack puts it back as
            // Rebuilt from the cache at the remembered box, so taps are judged and the next read sees no box move.
            dbg("provisional confirmed ms=${now - provisionalAt} src=probe")
            clearProvisional()
            lastTargetChangeAt = now
            bar.rebuiltSpot()?.let { barRootWindowId = it.windowId }
            mismatches = 0
            unknowns = 0
            val synced = (bar.onBarBack(now)?.state as? RenderState.Rebuilt<*>) ?: shownNow
            renderBar(synced, frame?.navLeft ?: 0, touchable = true)
            updateTicker()
            scheduleEval(0)
            return
        }
        val out = bar.onBarBack(now) ?: run {
            // Back, but the layout is not drawable from cache now: a fresh read decides what is shown.
            scheduleEval(0)
            return
        }
        // Reads started while the bar was away describe the screen before this moment: they are stale and keep the bar as
        // it is. The confirming read below is taken after it, so it applies.
        lastTargetChangeAt = now
        // The ticker compares the active window with the bar's window: that is now the remembered bar's window.
        bar.rebuiltSpot()?.let { barRootWindowId = it.windowId }
        mismatches = 0
        unknowns = 0
        renderBar(out.state, frame?.navLeft ?: 0)
        updateTicker()
        scheduleEval(0)
    }

    /**
     * 100 ms ticker while the bar is shown: a keyboard hides it at once, and another window taking the front hides it on the
     * second mismatched reading in a row (see [applyTick]).
     */
    private fun updateTicker() {
        if (barShown) {
            if (!tickerRunning) {
                tickerRunning = true
                mismatches = 0
                unknowns = 0
                main.removeCallbacks(tickRunnable)
                main.postDelayed(tickRunnable, TICK_MS)
            }
        } else if (tickerRunning) {
            tickerRunning = false
            main.removeCallbacks(tickRunnable)
        }
    }

    /** Ticker, main: asks the ticker thread for the window reading. One reading at a time, so a slow one never piles up. */
    private fun requestTick() {
        val handler = tickHandler ?: return
        if (tickInFlight || !running) return
        tickInFlight = true
        val posted = handler.post {
            val reading = readTick()
            main.post {
                tickInFlight = false
                applyTick(reading)
            }
        }
        if (!posted) tickInFlight = false
    }

    /**
     * Ticker thread (its own, so a slow tree read on the eval thread never delays it): the keyboard and the active window
     * (its id, and whether it is one of ours). Null active: unknown.
     */
    private fun readTick(): TickReading {
        val own = ownRectsSnap
        val active = activeWindowInfo()
        return TickReading(imeVisible(), active?.let { it.id to isOwnWindow(it, own) })
    }

    /**
     * Main. Applies one window reading. One unknown or mismatched reading is kept; the second hides. A keyboard hides at once.
     */
    private fun applyTick(r: TickReading) {
        if (!running || !barShown) return
        // A keyboard coming up hides the bar at once: nothing of ours may sit over the keyboard.
        if (r.ime) {
            hideBar(keepRebuilt = true)
            scheduleEval(STATE_DELAY_MS)
            return
        }
        val active = r.active
        when {
            active == null -> { unknowns++; mismatches = 0 }
            active.first == barRootWindowId || active.second -> { mismatches = 0; unknowns = 0 }
            else -> { mismatches++; unknowns = 0 }
        }
        if (mismatches >= 2 || unknowns >= 2) onBarInvalidate(keepRebuilt = true) // re-evaluate so the bar comes back if we're still in the app
    }

    /** The active window (the one the user is in), from the live window list; null when unknown. One IPC. */
    private fun activeWindowInfo(): AccessibilityWindowInfo? = runCatching { activeWindowOf(windows) }.getOrNull()

    /** The active window of [all] (a window list already read), or null. */
    private fun activeWindowOf(all: List<AccessibilityWindowInfo>): AccessibilityWindowInfo? = all.firstOrNull { it.isActive }

    /** Screen rectangles of our own windows: the toast, DUMP, the patch and the redrawn bar when shown. */
    private fun ownRects(): List<Box> = overlays.ownRects() + barOverlay.shownRects

    private fun boxOf(win: AccessibilityWindowInfo): Box {
        val r = Rect()
        win.getBoundsInScreen(r)
        return Box(r.left, r.top, r.right, r.bottom)
    }

    /** One of our overlays: a TYPE_ACCESSIBILITY_OVERLAY window whose box lies inside one of our rectangles. */
    private fun isOwnWindow(win: AccessibilityWindowInfo, own: List<Box>): Boolean =
        win.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && own.any { it.contains(boxOf(win)) }

    /** False while the screen is off or the keyguard is up: nothing of ours should be drawn then. */
    private fun screenUsable(): Boolean = runCatching {
        getSystemService(android.os.PowerManager::class.java).isInteractive &&
            !getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
    }.getOrDefault(true)

    /** Colour samples are saved to DataStore at most once every [COLOUR_SAVE_MS]. */
    private fun saveColoursIfChanged() {
        if (bar.colorsSnapshot() == savedColours) return
        val wait = COLOUR_SAVE_MS - (SystemClock.elapsedRealtime() - lastColourSave)
        if (wait > 0) {
            main.removeCallbacks(saveColoursRunnable)
            main.postDelayed(saveColoursRunnable, wait)
        } else {
            saveColours()
        }
    }

    private fun saveColours() {
        if (!::bar.isInitialized) return
        val snap = bar.colorsSnapshot()
        if (snap == savedColours) return
        savedColours = snap
        lastColourSave = SystemClock.elapsedRealtime()
        scope.launch { Settings.setBarColors(this@BlockerService, snap) }
    }

    /** Two captures agree when their foreground (icon and label) matches; the background is ignored. */
    private fun sameCrop(a: Bitmap, b: Bitmap): Boolean {
        val w = a.width
        val h = a.height
        if (w != b.width || h != b.height || w <= 0 || h <= 0) return false
        val pa = IntArray(w * h).also { a.getPixels(it, 0, w, 0, 0, w, h) }
        val pb = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }
        return foregroundSame(pa, pb, w, h, BarCapture.borderBackground(pa, w, h))
    }

    private fun registerReceivers() {
        if (receiversOn) return
        receiversOn = true
        ContextCompat.registerReceiver(
            this, screenOffReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SHUTDOWN)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply { addDataScheme("package") },
            ContextCompat.RECEIVER_EXPORTED,
        )
    }

    private fun unregisterReceivers() {
        if (!receiversOn) return
        receiversOn = false
        runCatching { unregisterReceiver(screenOffReceiver) }
        runCatching { unregisterReceiver(packageReceiver) }
    }

    /**
     * Main. Acts on a block decision of [pkg] taken from window [windowId] (null when unknown). Before anything else (the
     * toast, the counter, the settle time, the Back streak), the focused window must be a target window of [pkg] and of
     * [windowId] (see [actVerdict]); otherwise nothing is done and a fresh read follows. One window-list read per action.
     */
    private fun handle(d: Decision, pkg: String, windowId: Int?) {
        if (d !is Decision.Block) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return
        if (!actVerdict(focusedWindowId(), targetWindows, pkg, windowId)) {
            dbgBlock("block dropped: focus moved")
            scheduleEval(0)
            return
        }
        backsInRow = if (now - lastActionAt < ESCALATE_WINDOW_MS) backsInRow + 1 else 1
        lastActionAt = now
        act(d.target, pkg, windowId)
        // One full read after the settle time rechecks a Back that did not land.
        scheduleSettleRead(BLOCK_SETTLE_MS)

        val counted = now - lastCountedAt > COUNT_GAP_MS
        if (counted) {
            lastCountedAt = now
            scope.launch { Settings.incrementBlocked(this@BlockerService) }
        }
        val n = settings.blockedToday + if (counted) 1 else 0
        overlays.toast(
            when (d.target.feature) {
                "caughtup" -> "You're all caught up ✓"
                "shorts" -> "Shorts blocked · $n today"
                else -> "Reels blocked · $n today"
            },
        )
        publishOwnRects()
    }

    /** The id of the focused window (our overlays are not focusable), or null when none is reported. One window-list read. */
    private fun focusedWindowId(): Int? = runCatching { windows.firstOrNull { it.isFocused }?.id }.getOrNull()

    /**
     * Main. A TAP target is clicked on the tap thread, and never leads to Back or Home, on any path: when the click fails, the
     * block's toast (shown by [handle]) is all there is. Other targets go Back (or Home, see [actBack]).
     */
    private fun act(t: Target, pkg: String, windowId: Int?) {
        if (t.action == BlockAction.TAP) {
            val viewId = t.tapViewId ?: return
            runCatching {
                tapExecutor.execute {
                    val ok = runCatching { tapById(viewId, pkg, windowId) }.getOrNull()
                    dbgBlock("tap by id ok=$ok")
                }
            }
            return
        }
        actBack()
    }

    private fun actBack() {
        if (backsInRow >= 3) {
            // Back keeps landing on a blocked screen (e.g. Reels is the app's start tab): leave the app.
            performGlobalAction(GLOBAL_ACTION_HOME)
            backsInRow = 0
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * Any thread but main: clicks the node with [viewId] in the focused window. Returns true or false for the click, or null
     * when nothing is clicked: no focused window, a window that is not a target window of [pkg] (see [actVerdict]), or a root
     * of another package. The caller then does nothing else (no Back or Home).
     */
    private fun tapById(viewId: String, pkg: String, windowId: Int?): Boolean? {
        val win = runCatching { windows.firstOrNull { it.isFocused } }.getOrNull() ?: return null
        if (!actVerdict(win.id, targetWindows, pkg, windowId)) return null
        val root = rootOf(win) ?: return null
        if (root.packageName?.toString() != pkg) { recycleNode(root); return null }
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        val ok = nodes.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        nodes.forEach(::recycleNode)
        recycleNode(root)
        return ok
    }

    private fun dump() {
        // The window list first, as collect does: the active window must be one a target app sent events from, and its root is
        // read only once the package is checked.
        val active = activeWindowInfo()
        val pkg = active?.let { targetWindows[it.id] }
        val root = if (active != null && pkg != null) rootOf(active) else null
        if (root == null || pkg == null || pkg !in TARGET_PACKAGES || root.packageName?.toString() != pkg) {
            root?.let(::recycleNode)
            overlays.toast("Nothing to dump")
            return
        }
        val (w, h) = screenSize()
        val cap = try { NodeSnap.captureFull(root, null) } finally { recycleNode(root) }
        val (vc, vn) = versionOf(pkg)
        val det = Detection.run(RulesRepository.current.forApp(pkg, vc), cap.root, w, h, navInsets(w, h).bar, cap.truncated)
        val snap = cap.root
        val extra = runCatching { dumpExtra(det.bar) }.getOrNull()
        scope.launch(Dispatchers.IO) {
            val f = runCatching {
                Inspector.write(this@BlockerService, pkg, vc, vn, w, h, snap, det, RulesRepository.current.version, extra)
            }
            launch(Dispatchers.Main) {
                overlays.toast(f.fold({ "Saved ${it.name}" }, { "Dump failed: ${it.message}" }), 2_500)
            }
        }
    }

    /**
     * Window list (ids, types, bounds, no titles or content), nav-bar insets, and the bar's layout (identities and
     * boxes only, no pixels), for the dump's "extra" block.
     */
    private fun dumpExtra(layout: BarLayout?): JSONObject = JSONObject().apply {
        put("windows", JSONArray().apply {
            windows.forEach { win ->
                runCatching {
                    val r = Rect().also { win.getBoundsInScreen(it) }
                    put(JSONObject()
                        .put("id", win.id)
                        .put("type", win.type)
                        .put("layer", win.layer)
                        .put("bounds", "[${r.left},${r.top}][${r.right},${r.bottom}]")
                        .put("active", win.isActive)
                        .put("focused", win.isFocused))
                }
            }
        })
        if (Build.VERSION.SDK_INT >= 30) {
            val ins = getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
                .getInsets(WindowInsets.Type.navigationBars())
            put("navInsets", JSONObject().put("l", ins.left).put("t", ins.top).put("r", ins.right).put("b", ins.bottom))
        }
        layout?.let { put("bar", barJson(it)) }
        put("foreground", fgsRunning)
    }

    private fun barJson(b: BarLayout): JSONObject = JSONObject().apply {
        put("targetId", b.targetId)
        put("mode", if (b.mode == BarMode.REBUILD) "rebuild" else "patch")
        put("render", renderName)
        put("slots", renderSlots)
        b.rejectReason?.let { put("rejectReason", it) }
        put("tabsBox", boxText(b.tabsBox))
        b.patchBox?.let { put("patchBox", boxText(it)) }
        put("tabs", JSONArray().apply {
            b.tabs.forEach { t ->
                put(JSONObject().put("identity", t.identity).put("selected", t.selected).put("hidden", t.hidden))
            }
        })
    }

    private fun boxText(b: Box) = "[${b.l},${b.t}][${b.r},${b.b}]"

    /**
     * The navigation bar, read once per evaluation (API 30+; otherwise none): its inset as a rectangle at the bottom of the
     * screen ([bar], null when there is none), and the left inset (landscape only, subtracted from the patch's x) as [left].
     */
    private class NavInsets(val bar: Box?, val left: Int)

    private fun navInsets(w: Int, h: Int): NavInsets {
        if (Build.VERSION.SDK_INT < 30) return NavInsets(null, 0)
        val ins = getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.navigationBars())
        return NavInsets(if (ins.bottom > 0) Box(ins.left, h - ins.bottom, w - ins.right, h) else null, ins.left)
    }

    /** Ours or other services' windows and the keyboard: any input-method window counts. */
    private fun imeVisible(): Boolean = runCatching {
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(android.view.WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        } else {
            android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        }
    }

    @Suppress("DEPRECATION")
    private fun versionOf(pkg: String): Pair<Long, String?> = versionCache.getOrPut(pkg) {
        runCatching {
            val pi = packageManager.getPackageInfo(pkg, 0)
            (if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()) to pi.versionName
        }.getOrDefault(0L to null)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        // The clean marker is written before the teardown, synchronously: a normal stop is never reported as a kill.
        ServiceState.markClean(this)
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        running = false
        stateEpoch++
        main.removeCallbacksAndMessages(null)
        evalHandler?.removeCallbacksAndMessages(null)
        evalThread?.quitSafely()
        evalHandler = null
        evalThread = null
        tickHandler?.removeCallbacksAndMessages(null)
        tickThread?.quitSafely()
        tickHandler = null
        tickThread = null
        stopProbe()
        blockInFlight = false
        blockAgain = false
        blockHandler?.removeCallbacksAndMessages(null)
        blockThread?.quitSafely()
        blockThread = null
        blockHandler = null
        pendingHide = null
        releaseLive()
        if (::overlays.isInitialized) overlays.removeAll()
        if (::bar.isInitialized) bar.invalidate()
        // Queued writes finish on the store's thread; nothing new is accepted after this.
        store?.shutdown()
        store = null
        clearProvisional()
        if (::barOverlay.isInitialized) barOverlay.remove()
        tickerRunning = false
        unregisterReceivers()
        stopKeepAlive()
        if (::barCapture.isInitialized) barCapture.shutdown()
        tapExecutor.shutdownNow()
        scope.cancel()
    }

    companion object {
        private const val TAG = "UnrotService"
        private const val TAG_BAR = "UnrotBar"
        private const val TAG_BLOCK = "UnrotBlock"
        /** At most one block probe start per this many ms. */
        private const val BLOCK_MIN_GAP_MS = 100L
        /** A window-state event more than this long after the last one starts a new screen episode (for the trace). */
        private const val STATE_EPISODE_GAP_MS = 2_000L
        /** Nodes kept per view id in a block probe (a reel viewer is one or two). */
        private const val MAX_FAST_NODES = 16
        /** Idle cadence of content-change probes (see requestBlockProbe). */
        private const val BLOCK_IDLE_GAP_MS = 500L
        /** After a window-state, pane or scroll event, probes run at BLOCK_MIN_GAP_MS for this long. */
        private const val BLOCK_URGENT_MS = 2_000L
        /** Consecutive probes with no block after which content changes start no probe, until a window-state or scroll event. */
        private const val BLOCK_MAX_MISSES = 10
        /** The period of the check that clears a set DM flag once the user has left the chat (see [checkDmFlag]). */
        private const val DM_FLAG_CHECK_MS = 1_000L
        /** A first sighting of a block waits this long for the next probe to agree. */
        private const val PENDING_MS = 1_000L
        /** A window-state event this recent means the chat may have just opened: the DM context is probed. */
        private const val DM_NEAR_STATE_MS = 2_000L
        private const val REAPPLY_MAX_AGE_MS = 700L
        val TARGET_PACKAGES = setOf(INSTAGRAM_PKG, "com.google.android.youtube")
        private const val THROTTLE_MS = 150L
        private const val STATE_DELAY_MS = 60L
        private const val POLL_MS = 500L
        private const val TICK_MS = 100L
        /** The service record's liveness is refreshed at most this often while a target app sends events (see [ServiceState]). */
        private const val HEARTBEAT_MS = 10L * 60 * 1000
        private const val COLOUR_SAVE_MS = 5_000L
        private const val ACTION_DEBOUNCE_MS = 700L
        private const val ESCALATE_WINDOW_MS = 2_500L
        private const val COUNT_GAP_MS = 3_000L
        private const val HIDE_TIMEOUT_MS = 1_500L
        private const val TAP_WAKE_1_MS = 150L
        private const val TAP_WAKE_2_MS = 750L
        /** The probe stops when the target app has sent no event for this long, or when the episode is this old. */
        private const val PROBE_IDLE_MS = 60_000L
        /** A re-entry from outside the app: the probe accepts no result that started within this long of the return. */
        private const val REENTRY_SETTLE_MS = 450L
        /** A provisional (untouchable) bar that the probe has not confirmed by then is hidden (see [showProvisional]). */
        private const val PROVISIONAL_MS = 600L
        /** How far a tab's box may differ from the one the bar reported, when finding its node (see [findTapNode]). */
        private const val MAX_WALK_DEPTH = 40
        private const val MAX_ANCESTORS = 6

        @Volatile var running = false
            private set

        /** True if the user has switched the service on in Accessibility settings. */
        fun isEnabled(ctx: Context): Boolean {
            val me = ComponentName(ctx, BlockerService::class.java)
            val enabled = Secure.getString(ctx.contentResolver, Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            return splitter.any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/**
 * The bar-back probe's target: the app's package and window of the remembered Rebuilt layout (see BarController.rebuiltSpot),
 * the bar's view id (full), the container and tabs boxes of the last Rebuilt layout, its feature, elapsed ms when the bar was hidden, and [fastFrom]: when
 * the probe started or was last kicked (main only), which sets its fast cadence. [outsideReentry] and [settleUntil]: a return
 * from outside the app, and the time before which its results do not settle (see [BarProbe.acceptResult]).
 */
private class ProbeTarget(
    val pkg: String, val windowId: Int, val viewId: String, val container: Box, val tabs: Box, val feature: String,
    val hiddenSince: Long, var fastFrom: Long, val outsideReentry: Boolean, val settleUntil: Long, var armed: Boolean,
)

/** A block probe's hit: the target, and the bounds of the settled nodes it was decided from (for the two-probe agreement). */
private class BlockHit(val target: Target, val key: String, val extra: List<Target> = emptyList())

/**
 * One block probe's result: the block hit, if any, whether a DM marker was seen, and the window it was read from (see
 * [BlockerService.blockProbe]).
 */
private class BlockProbe(val hit: BlockHit?, val dmSeen: Boolean, val windowId: Int)

/** Screen facts of one read that the render needs on main (see [BlockerService.renderBar]). [foreign]: every window above. */
private class Frame(val night: Boolean, val navLeft: Int, val foreign: List<Box>)

/** The windows above the app, as read by [BlockerService.windowFacts]: over the bar's tabs box, foreign and target app's. */
private class WindowFacts(val foreignAbove: Boolean, val targetAbove: Boolean, val boxes: List<Box>)

/** The window reading of the ticker: the keyboard, and the active window (id, and whether it is one of ours). */
private class TickReading(val ime: Boolean, val active: Pair<Int, Boolean>?)

/** One evaluation's input, read on the eval thread and applied on main. [epoch] is the state epoch when the read started. */
private sealed interface EvalInput {
    val epoch: Int
}

/** Nothing to draw from this read: not a target app, the screen is unusable, or our own overlay is the active window. */
private class EvalSkip(override val epoch: Int, val ownApp: Boolean) : EvalInput

/**
 * A read of a target app's screen. [capturedAtMs] is when the read started; the controller judges tap deadlines by it.
 * [kept] are live tab nodes the read kept (see [NodeSnap.captureFull]); the applier owns them.
 */
private class EvalRead(
    override val epoch: Int,
    val capturedAtMs: Long,
    val collectMs: Long,
    val nodes: Int,
    val pkg: String,
    val rootWindowId: Int,
    val versionCode: Long,
    val rulesVersion: Int,
    val det: Detection,
    val kept: List<KeptNode>,
    val ime: Boolean,
    val foreignAbove: Boolean,
    val targetAbove: Boolean,
    val frame: Frame,
) : EvalInput
