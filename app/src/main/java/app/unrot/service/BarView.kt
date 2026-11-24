package app.unrot.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Shader
import android.os.Bundle
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import app.unrot.bar.Divider
import app.unrot.bar.RenderState
import app.unrot.bar.Slot
import app.unrot.bar.slotIndexAt
import app.unrot.rules.Box
import kotlin.math.roundToInt

/**
 * The redrawn bar: a fill in the sampled colour, each kept tab's crop in its slot, and the divider line (if any) on top.
 * Taps and TalkBack clicks are reported as slot indices. Every touch on the view is consumed, so the window never
 * passes touches through to the app.
 *
 * When a slot's crop changes while the bar is shown (the selection moved, a badge changed), the old crop fades out and
 * the new one in over [FADE_MS]. The old bitmap is held only until the fade ends. Bitmaps are never recycled here: the
 * controller's cache still owns them.
 *
 * Coordinates are local to the window, whose origin is the top-left of the tabs box.
 */
class BarView(context: Context, private val onSlot: (index: Int, longPress: Boolean) -> Unit) : View(context) {
    /**
     * False while the bar is provisional (see BlockerService.showProvisional): the window still takes the touches over the
     * bar, so they never fall through to the app's own tabs, but nothing is forwarded and TalkBack actions are ignored.
     */
    var touchable = true
    private var origin = Box(0, 0, 0, 0)
    private var slots: List<Slot> = emptyList()
    private var crops: List<Bitmap> = emptyList()
    private var fill = Color.BLACK
    /** The fade gradients of the last crop size and fill (see [fadeEdgesFor]). */
    private var fadeCache: Pair<Triple<Int, Int, Int>, List<Pair<Shader, RectF>>>? = null
    private var divider: Divider? = null
    private val paint = Paint()
    private val bitmapPaint = Paint()

    /** Old crops of the slots being faded out (null where a slot does not fade), and the fade's progress 0..1. */
    private var fromCrops: List<Bitmap?> = emptyList()
    private var fadeT = 1f
    private var fader: ValueAnimator? = null

    private val gestures = GestureDetector(context, object : GestureDetector.OnGestureListener {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onShowPress(e: MotionEvent) {}
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            slotAt(e.x)?.let { onSlot(it, false) }
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean = false
        override fun onLongPress(e: MotionEvent) {
            slotAt(e.x)?.let { onSlot(it, true) }
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean = false
    }).apply { setOnDoubleTapListener(null) } // no double-tap handling: it would swallow the second tap

    /** TalkBack: one virtual view per slot, with the tab's description, clickable and long-clickable. */
    private val helper = object : ExploreByTouchHelper(this@BarView) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = slotAt(x) ?: Int.MIN_VALUE

        override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
            slots.indices.forEach { virtualViewIds.add(it) }
        }

        // The helper's virtual-view bounds are set in the parent's space with setBoundsInParent (the only bounds call it reads).
        @Suppress("DEPRECATION")
        override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
            val s = slots.getOrNull(virtualViewId)
            if (s == null) {
                // An id with no slot still needs a description and bounds, or the helper throws.
                node.contentDescription = ""
                node.setBoundsInParent(Rect(0, 0, 0, 0))
                return
            }
            node.contentDescription = s.desc ?: s.identity
            node.isClickable = true
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
            node.setBoundsInParent(local(s.dest))
        }

        override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean {
            if (!touchable || virtualViewId !in slots.indices) return false
            return when (action) {
                AccessibilityNodeInfoCompat.ACTION_CLICK -> { onSlot(virtualViewId, false); true }
                AccessibilityNodeInfoCompat.ACTION_LONG_CLICK -> { onSlot(virtualViewId, true); true }
                else -> false
            }
        }
    }

    init {
        ViewCompat.setAccessibilityDelegate(this, helper)
    }

    /**
     * Shows [r]: its tabs box is this window's area. With [animate], a slot whose crop changed fades from the old crop;
     * without it (the bar was not visible before, e.g. it just appeared) the new crops are drawn at once.
     */
    fun show(r: RenderState.Rebuilt<Bitmap>, animate: Boolean) {
        val prevCrops = crops
        origin = r.tabsBox
        if (slots != r.slots) {
            slots = r.slots
            if (isAttachedToWindow) helper.invalidateRoot()
        }
        crops = r.crops
        fill = r.color
        divider = r.divider
        if (animate && prevCrops.size == crops.size) {
            val froms = prevCrops.indices.map { i -> prevCrops[i].takeIf { it !== crops[i] } }
            if (froms.any { it != null }) startFade(froms)
        }
        invalidate()
    }

    /** Stops any fade and forgets the old crops. Called when the bar is hidden or the window is removed. */
    fun cancelFade() {
        fader?.cancel()
        fader = null
        fromCrops = emptyList()
        fadeT = 1f
    }

    override fun onDetachedFromWindow() {
        cancelFade()
        super.onDetachedFromWindow()
    }

    private fun startFade(froms: List<Bitmap?>) {
        fader?.cancel()
        fromCrops = froms
        fadeT = 0f
        val a = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS)
        a.addUpdateListener {
            fadeT = it.animatedValue as Float
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = finishFade(animation)
            override fun onAnimationCancel(animation: Animator) = finishFade(animation)
        })
        fader = a
        a.start()
    }

    private fun finishFade(animation: Animator) {
        if (fader !== animation) return // a newer fade has replaced this one
        fader = null
        fromCrops = emptyList()
        fadeT = 1f
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Consumed but not acted on while provisional: the touch must not reach the app's tabs under the bar.
        if (touchable) gestures.onTouchEvent(event)
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean =
        !touchable || helper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        helper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        helper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(fill)
        slots.forEachIndexed { i, slot ->
            val from = fromCrops.getOrNull(i)
            val crop = crops.getOrNull(i)
            when {
                crop != null && from != null -> {
                    drawCrop(canvas, slot, from, 1f - fadeT)
                    drawCrop(canvas, slot, crop, fadeT)
                }
                crop != null -> drawCrop(canvas, slot, crop, 1f)
                from != null -> drawCrop(canvas, slot, from, 1f - fadeT)
            }
        }
        // After the crops, so a crop's fade never dashes the divider.
        divider?.let { d ->
            paint.shader = null
            paint.color = d.color
            canvas.drawRect(0f, 0f, width.toFloat(), d.rows.toFloat(), paint)
        }
    }

    /** Draws [crop] in [slot] at [alpha]. Never scaled: a crop of the slot's own size fills it; any other size is centred. */
    private fun drawCrop(canvas: Canvas, slot: Slot, crop: Bitmap, alpha: Float) {
        val dest = local(slot.dest)
        val l = if (crop.width == dest.width()) dest.left else dest.left + (dest.width() - crop.width) / 2
        val t = if (crop.height == dest.height()) dest.top else dest.top + (dest.height() - crop.height) / 2
        val drawn = Rect(l, t, l + crop.width, t + crop.height)
        bitmapPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).roundToInt()
        canvas.drawBitmap(crop, null, drawn, bitmapPaint)
        fadeEdges(canvas, drawn)
    }

    /** Fades the crop's outer [FADE_PX] into the fill, so a crop taken over a translucent bar has no visible edge. */
    private fun fadeEdges(c: Canvas, r: Rect) {
        val f = minOf(FADE_PX, r.width() / 2, r.height() / 2)
        if (f <= 0) return
        val edges = fadeEdgesFor(r.width(), r.height(), f)
        c.save()
        c.translate(r.left.toFloat(), r.top.toFloat())
        for ((shader, rect) in edges) {
            paint.shader = shader
            c.drawRect(rect, paint)
        }
        c.restore()
        paint.shader = null
    }

    /** The edge gradients of a crop of [w] x [h] (in its own coordinates) for the current fill, cached per size and fill. */
    private fun fadeEdgesFor(w: Int, h: Int, f: Int): List<Pair<Shader, RectF>> {
        val key = Triple(w, h, fill)
        fadeCache?.takeIf { it.first == key }?.let { return it.second }
        val solid = fill
        val clear = fill and 0x00FFFFFF
        fun grad(x0: Float, y0: Float, x1: Float, y1: Float): Shader =
            LinearGradient(x0, y0, x1, y1, solid, clear, Shader.TileMode.CLAMP)
        val edges = listOf(
            grad(0f, 0f, f.toFloat(), 0f) to RectF(0f, 0f, f.toFloat(), h.toFloat()),
            grad(w.toFloat(), 0f, (w - f).toFloat(), 0f) to RectF((w - f).toFloat(), 0f, w.toFloat(), h.toFloat()),
            grad(0f, 0f, 0f, f.toFloat()) to RectF(0f, 0f, w.toFloat(), f.toFloat()),
            grad(0f, h.toFloat(), 0f, (h - f).toFloat()) to RectF(0f, (h - f).toFloat(), w.toFloat(), h.toFloat()),
        )
        fadeCache = key to edges
        return edges
    }

    private fun local(b: Box) = Rect(b.l - origin.l, b.t - origin.t, b.r - origin.l, b.b - origin.t)

    /** The slot under local x, or null. */
    private fun slotAt(x: Float): Int? = slotIndexAt(Box(0, 0, width, height), slots.size, x.toInt())

    private companion object {
        const val FADE_PX = 12
        const val FADE_MS = 150L
    }
}
