package app.unrot.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings.Secure
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import app.unrot.data.Settings
import app.unrot.inspector.Inspector
import app.unrot.policy.Decision
import app.unrot.policy.Policy
import app.unrot.rules.BlockAction
import app.unrot.rules.Detection
import app.unrot.rules.NodeSnap
import app.unrot.rules.RulesRepository
import app.unrot.rules.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Watches Instagram and YouTube (the package filter lives in res/xml/accessibility_service_config.xml).
 * Pipeline per screen: snapshot tree → Detection (rules) → Policy → Action (Back / tap / covers).
 */
class BlockerService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = Policy()
    private lateinit var overlays: Overlays

    @Volatile private var settings = Settings()
    private var evalScheduled = false
    private var lastEvalPkg: String? = null

    // Action bookkeeping
    private var lastActionAt = 0L
    private var lastCountedAt = 0L
    private var backsInRow = 0

    private val versionCache = HashMap<String, Pair<Long, String?>>()

    private val evalRunnable = Runnable { evalScheduled = false; evaluate() }
    private val pollRunnable = object : Runnable {
        override fun run() {
            // While patches are on screen we keep checking, since leaving the app sends us no event.
            if (overlays.hasCovers) { evaluate(); main.postDelayed(this, POLL_MS) }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = true
        overlays = Overlays(this)
        RulesRepository.load(this)
        Settings.flow(this).onEach { s ->
            settings = s
            overlays.setInspectorButton(s.devUnlocked && s.inspectorEnabled, ::dump)
            scheduleEval(0)
        }.launchIn(scope)
        Log.i(TAG, "Service connected, rules v${RulesRepository.current.version} (${RulesRepository.source})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in TARGET_PACKAGES) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val id = event.source?.let { src -> src.viewIdResourceName.also { recycle(src) } }
                handle(policy.onScroll(id))
                scheduleEval(THROTTLE_MS)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleEval(STATE_DELAY_MS)
            else -> scheduleEval(THROTTLE_MS)
        }
    }

    /** Throttle (not debounce): content-changed events never stop while a video plays. */
    private fun scheduleEval(delay: Long) {
        if (evalScheduled && delay > 0) return
        main.removeCallbacks(evalRunnable)
        evalScheduled = true
        main.postDelayed(evalRunnable, delay)
    }

    private fun evaluate() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg !in TARGET_PACKAGES) {
            root?.let(::recycle)
            if (lastEvalPkg != null) { overlays.clearCovers(); lastEvalPkg = null }
            return
        }
        lastEvalPkg = pkg
        val (w, h) = screenSize()
        val snap = try { NodeSnap.capture(root) } finally { recycle(root) }
        val rules = RulesRepository.current.forApp(pkg!!, versionOf(pkg).first)
        val det = Detection.run(rules, snap, w, h)
        val s = settings

        // Covers: only when the feature is on and the keyboard isn't up (patches would float over it).
        val covers = if (imeVisible()) emptyList() else det.covers.filter { s.featureEnabled(it.feature) }
        overlays.setCovers(covers)
        if (covers.isNotEmpty()) { main.removeCallbacks(pollRunnable); main.postDelayed(pollRunnable, POLL_MS) }

        handle(policy.onScreen(det, s))
    }

    private fun handle(d: Decision) {
        if (d !is Decision.Block) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_DEBOUNCE_MS) return
        backsInRow = if (now - lastActionAt < ESCALATE_WINDOW_MS) backsInRow + 1 else 1
        lastActionAt = now
        act(d.target)

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
    }

    private fun act(t: Target) {
        if (t.action == BlockAction.TAP && t.tapViewId != null && tapById(t.tapViewId)) return
        if (backsInRow >= 3) {
            // Back keeps landing on a blocked screen (e.g. Reels is the app's start tab): leave the app.
            performGlobalAction(GLOBAL_ACTION_HOME)
            backsInRow = 0
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun tapById(viewId: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId)
        val ok = nodes.firstOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        nodes.forEach(::recycle); recycle(root)
        return ok
    }

    private fun dump() {
        val root = rootInActiveWindow
        val pkg = root?.packageName?.toString()
        if (root == null || pkg == null) { overlays.toast("Nothing to dump"); return }
        val (w, h) = screenSize()
        val snap = try { NodeSnap.capture(root) } finally { recycle(root) }
        val (vc, vn) = versionOf(pkg)
        val det = Detection.run(RulesRepository.current.forApp(pkg, vc), snap, w, h)
        scope.launch(Dispatchers.IO) {
            val f = runCatching {
                Inspector.write(this@BlockerService, pkg, vc, vn, w, h, snap, det, RulesRepository.current.version)
            }
            launch(Dispatchers.Main) {
                overlays.toast(f.fold({ "Saved ${it.name}" }, { "Dump failed: ${it.message}" }), 2_500)
            }
        }
    }

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

    @Suppress("DEPRECATION")
    private fun recycle(n: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < 33) runCatching { n.recycle() }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        running = false
        main.removeCallbacksAndMessages(null)
        if (::overlays.isInitialized) overlays.removeAll()
        scope.cancel()
    }

    companion object {
        private const val TAG = "UnrotService"
        val TARGET_PACKAGES = setOf("com.instagram.android", "com.google.android.youtube")
        private const val THROTTLE_MS = 150L
        private const val STATE_DELAY_MS = 60L
        private const val POLL_MS = 500L
        private const val ACTION_DEBOUNCE_MS = 700L
        private const val ESCALATE_WINDOW_MS = 2_500L
        private const val COUNT_GAP_MS = 3_000L

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
