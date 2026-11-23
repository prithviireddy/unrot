package app.unrot.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import app.unrot.bar.RenderState
import app.unrot.rules.Box

/**
 * Draws the bar's patch and the redrawn bar, as two TYPE_ACCESSIBILITY_OVERLAY windows. Both are added once and then
 * kept: switching between states only changes visibility and touchability (no window is removed or re-added).
 *
 * - [patchView] covers the hidden tab. It is added first, so it stays below the bar.
 * - [barView] covers the whole tabs box. It is added on the first Rebuilt, after the patch.
 *
 * An invisible window is also FLAG_NOT_TOUCHABLE. Both are removed only for Hidden ([remove]).
 *
 * No-flash rules:
 * - Patch -> Rebuilt: the bar goes VISIBLE and touchable; after its first frame the patch goes INVISIBLE.
 * - Rebuilt -> Patch (including a hide capture): the patch goes VISIBLE and touchable first; after its first frame the
 *   bar goes INVISIBLE, and after one more patch frame and a vsync the shell is told [onSettled], so a display capture
 *   never sees the bar on screen.
 */
class BarOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)

    /** Called on the main thread once the patch has drawn and the bar is INVISIBLE (a display capture may run then). */
    var onSettled: (() -> Unit)? = null

    /** A tap or TalkBack click on the redrawn bar: the slot index and whether it was a long press. */
    var onSlotTap: ((Int, Boolean) -> Unit)? = null

    private enum class Mode { NONE, PATCH, BAR }

    private var mode = Mode.NONE
    /** Bumped on each mode change, so frame callbacks from an earlier mode do nothing. */
    private var token = 0

    private var patchView: View? = null
    private var patchPlaced: Box? = null   // window coordinates
    private var patchScreen: Box? = null   // screen coordinates
    private var patchColour = 0
    private var patchVisible = false
    private var patchCommitted = false

    private var barView: BarView? = null
    private var barAdded = false
    private var barVisible = false
    private var barPlaced: Box? = null     // window coordinates
    private var barScreen: Box? = null     // screen coordinates

    /** Something of ours is on screen or added: the shell's ticker and polling run while this is true. */
    val isShown: Boolean get() = patchView != null || barAdded

    /** Screen rectangles of the windows currently visible, so the window checks can recognise our own overlays. */
    val shownRects: List<Box> get() = listOfNotNull(patchScreen.takeIf { patchVisible }, barScreen.takeIf { barVisible })

    /** True when nothing of ours is over the bar any more: the bar is INVISIBLE and the patch (if added) has settled. */
    fun hideSettled(): Boolean = !barVisible && (patchView == null || patchCommitted)

    /**
     * Shows, moves or hides the windows for [state]. [navLeftInset] is the left navigation-bar inset, which is subtracted
     * from x because the windows are laid out without FLAG_LAYOUT_NO_LIMITS.
     * [touchable] false: a Rebuilt bar is drawn but takes no touches (provisional, not yet confirmed), and the patch is
     * never shown beside it. Patch states ignore it.
     */
    @Suppress("UNCHECKED_CAST")
    fun render(state: RenderState, navLeftInset: Int, touchable: Boolean = true) {
        when (state) {
            RenderState.Hidden -> remove()
            is RenderState.Patch -> showPatch(state.box, state.color, navLeftInset)
            is RenderState.Rebuilt<*> -> showBar(state as RenderState.Rebuilt<Bitmap>, navLeftInset, touchable)
        }
    }

    /** Removes both windows. */
    fun remove() {
        token++
        mode = Mode.NONE
        barView?.let { v ->
            v.cancelFade()
            if (barAdded) runCatching { wm.removeView(v) }
        }
        patchView?.let { v -> runCatching { wm.removeView(v) } }
        barView = null
        barAdded = false
        barVisible = false
        barPlaced = null
        barScreen = null
        patchView = null
        patchPlaced = null
        patchScreen = null
        patchVisible = false
        patchCommitted = false
    }

    /** Adds the patch window (touchable, swallows taps on the hidden tab). It is added before the bar, so it stays below. */
    private fun addPatchView(target: Box, colour: Int): Boolean {
        val v = View(service).apply {
            setBackgroundColor(colour)
            isClickable = true
            setOnClickListener { }
        }
        if (!runCatching { wm.addView(v, layoutFor(target, touchable = true)) }.isSuccess) return false
        patchView = v
        patchPlaced = target
        patchColour = colour
        patchVisible = false
        return true
    }

    private fun showPatch(box: Box, colour: Int, inset: Int) {
        if (box.width <= 0 || box.height <= 0) { remove(); return }
        if (mode != Mode.PATCH) { token++; mode = Mode.PATCH }
        val target = Box(box.l - inset, box.t, box.l - inset + box.width, box.t + box.height)
        val existing = patchView
        if (existing == null) {
            if (!addPatchView(target, colour)) return
        } else {
            if (patchPlaced != target) {
                relayout(existing, target, touchable = patchVisible)
                patchPlaced = target
            }
            if (patchColour != colour) {
                existing.setBackgroundColor(colour)
                patchColour = colour
            }
        }
        patchScreen = box
        // Settle whenever the bar may still be up (a Patch that arrives during the bar's first frame cancels its settle).
        val needSettle = !patchVisible || barVisible
        if (!patchVisible) {
            patchVisible = true
            patchCommitted = false
            setPatchShown(true)
        }
        if (needSettle) settlePatch() // after its first frame the patch can take over from the bar
    }

    private fun showBar(r: RenderState.Rebuilt<Bitmap>, inset: Int, touchable: Boolean) {
        val tb = r.tabsBox
        if (tb.width <= 0 || tb.height <= 0) { remove(); return }
        if (mode != Mode.BAR) { token++; mode = Mode.BAR }
        val target = Box(tb.l - inset, tb.t, tb.l - inset + tb.width, tb.t + tb.height)
        val wasVisible = barVisible
        val v = barView ?: BarView(service) { i, long -> onSlotTap?.invoke(i, long) }.also { barView = it }
        v.touchable = touchable
        v.show(r, animate = wasVisible)
        barScreen = tb
        // The patch window is always added before the bar, so the bar is above it, and it stays INVISIBLE until needed.
        if (patchView == null) {
            val pb = r.patchBox?.let { Box(it.l - inset, it.t, it.l - inset + it.width, it.t + it.height) } ?: target
            if (addPatchView(pb, 0)) setPatchShown(false)
        }
        // The bar window always takes the touches over its box (a provisional bar swallows them, see BarView.touchable):
        // a touch that falls through would reach the app's own tabs under it.
        if (!barAdded) {
            v.visibility = View.VISIBLE
            if (!runCatching { wm.addView(v, layoutFor(target, touchable = true)) }.isSuccess) {
                barView = null
                return
            }
            barAdded = true
            barPlaced = target
        } else if (barPlaced != target) {
            relayout(v, target, touchable = true)
            barPlaced = target
        }
        if (!wasVisible) {
            barVisible = true
            v.visibility = View.VISIBLE
            relayout(v, barPlaced ?: target, touchable = true)
        }
        if (patchVisible) {
            // A provisional bar never sits beside the patch: the patch goes at once (it is not shown in that case anyway).
            if (!touchable) {
                setPatchShown(false)
            } else {
                // The patch goes only once the bar has drawn its first frame, so the hidden tab never flashes.
                val t = token
                onFrameCommit(v) {
                    if (t == token && mode == Mode.BAR && patchVisible) {
                        setPatchShown(false)
                        patchCommitted = false
                    }
                }
            }
        }
    }

    /**
     * After the patch draws: the bar goes INVISIBLE, then one more patch frame and a vsync must pass before [onSettled],
     * so the INVISIBLE state has reached WindowManager when a display capture starts.
     */
    private fun settlePatch() {
        val v = patchView ?: return
        val t = token
        patchCommitted = false // not settled until this sequence finishes, even on a re-settle
        onFrameCommit(v) {
            if (t != token || mode != Mode.PATCH || patchView !== v) return@onFrameCommit
            hideBarWindow()
            onFrameCommit(v) {
                Choreographer.getInstance().postFrameCallback {
                    if (t == token && mode == Mode.PATCH && patchView === v) {
                        patchCommitted = true
                        onSettled?.invoke()
                    }
                }
            }
        }
    }

    /** Makes the patch visible and touchable, or INVISIBLE and not touchable. */
    private fun setPatchShown(shown: Boolean) {
        val v = patchView ?: return
        val box = patchPlaced ?: return
        patchVisible = shown
        v.visibility = if (shown) View.VISIBLE else View.INVISIBLE
        relayout(v, box, touchable = shown)
    }

    /** Makes the bar INVISIBLE and not touchable (no-op when it is not shown). */
    private fun hideBarWindow() {
        val v = barView ?: return
        v.cancelFade()
        if (!barVisible) return
        barVisible = false
        v.visibility = View.INVISIBLE
        barPlaced?.let { relayout(v, it, touchable = false) }
    }

    private fun relayout(v: View, box: Box, touchable: Boolean) {
        runCatching { wm.updateViewLayout(v, layoutFor(box, touchable)) }
    }

    /** Runs [action] after the next frame that [v] commits. Forces a draw first, so the callback does fire. */
    private fun onFrameCommit(v: View, action: () -> Unit) {
        v.invalidate()
        if (Build.VERSION.SDK_INT >= 29) v.viewTreeObserver.registerFrameCommitCallback(action)
        else v.postOnAnimation(action)
    }

    private fun layoutFor(r: Box, touchable: Boolean): WindowManager.LayoutParams = WindowManager.LayoutParams(
        r.width, r.height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START or Gravity.DISPLAY_CLIP_VERTICAL or Gravity.DISPLAY_CLIP_HORIZONTAL
        x = r.l
        y = r.t
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}
