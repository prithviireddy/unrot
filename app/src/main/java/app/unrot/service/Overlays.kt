package app.unrot.service

import android.accessibilityservice.AccessibilityService
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import app.unrot.rules.Box
import app.unrot.rules.Cover

/**
 * The cover patches, the toast and the Inspector button we draw over other apps (the bar is drawn by BarOverlay). They use
 * TYPE_ACCESSIBILITY_OVERLAY, which needs no SYSTEM_ALERT_WINDOW permission because it belongs to the accessibility service.
 */
class Overlays(private val service: AccessibilityService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private val coverViews = HashMap<Box, View>() // key = screen rect
    private var toastView: View? = null
    private var inspectorButton: View? = null
    private val hideToast = Runnable { toastView?.let { removeSafely(it) }; toastView = null }

    private fun baseParams(w: Int, h: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private val isDark: Boolean
        get() = (service.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Shows exactly [covers] (adding, moving or removing patches as needed). Tapping a patch does nothing. */
    fun setCovers(covers: List<Cover>) {
        val wanted = covers.associateBy { Box(it.left, it.top, it.right, it.bottom) }
        (coverViews.keys - wanted.keys).forEach { k -> coverViews.remove(k)?.let(::removeSafely) }
        val dark = isDark
        for ((k, c) in wanted) {
            if (k in coverViews) continue
            val v = View(service).apply {
                setBackgroundColor(if (dark) c.dark else c.light)
                isClickable = true // swallow taps on the hidden icon
                setOnClickListener { }
            }
            val lp = baseParams(c.right - c.left, c.bottom - c.top, touchable = true).apply { x = c.left; y = c.top }
            if (addSafely(v, lp)) coverViews[k] = v
        }
    }

    fun clearCovers() = setCovers(emptyList())

    val hasCovers get() = coverViews.isNotEmpty()

    /** Screen rectangles of every window this class is showing (patches, and the toast and DUMP button once laid out). */
    fun ownRects(): List<Box> = coverViews.keys.toList() + listOfNotNull(rectOf(toastView), rectOf(inspectorButton))

    private fun rectOf(v: View?): Box? {
        if (v == null || !v.isLaidOut) return null
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return Box(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
    }

    /** Brief, non-touchable message near the top of the screen. */
    fun toast(message: String, durationMs: Long = 1_500) {
        main.removeCallbacks(hideToast)
        toastView?.let(::removeSafely)
        val tv = TextView(service).apply {
            text = message
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val pad = dp(14)
            setPadding(pad * 2, pad, pad * 2, pad)
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(0xE6202124.toInt()) }
        }
        val lp = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = false)
            .apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(96) }
        if (addSafely(tv, lp)) toastView = tv
        main.postDelayed(hideToast, durationMs)
    }

    fun setInspectorButton(visible: Boolean, onTap: () -> Unit) {
        if (!visible) { inspectorButton?.let(::removeSafely); inspectorButton = null; return }
        if (inspectorButton != null) return
        val tv = TextView(service).apply {
            text = "DUMP"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xCCD84315.toInt()) }
            setOnClickListener { onTap() }
        }
        val lp = baseParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true)
            .apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        if (addSafely(tv, lp)) inspectorButton = tv
    }

    fun removeAll() {
        clearCovers()
        main.removeCallbacks(hideToast)
        toastView?.let(::removeSafely); toastView = null
        inspectorButton?.let(::removeSafely); inspectorButton = null
    }

    private fun addSafely(v: View, lp: WindowManager.LayoutParams) = runCatching { wm.addView(v, lp) }.isSuccess
    private fun removeSafely(v: View) { runCatching { wm.removeView(v) } }
    private fun dp(v: Int) = (v * service.resources.displayMetrics.density).toInt()
}
