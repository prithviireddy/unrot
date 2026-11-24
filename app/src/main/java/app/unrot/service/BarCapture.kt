package app.unrot.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import app.unrot.bar.CaptureKind
import app.unrot.bar.CaptureRequest
import app.unrot.bar.CaptureResult
import app.unrot.bar.cropValid
import app.unrot.bar.median
import app.unrot.bar.nearUniform
import java.util.concurrent.ExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Takes the screenshot the bar colour and crops are sampled from. The frame is kept in memory only: it is freed as soon as the
 * bar's pixels are read. Nothing is written to disk or logged here (saved crops are BarStore's).
 *
 * WINDOW (API 34+) captures only the app's window, so our own overlays are not in the picture. DISPLAY captures the whole
 * screen (API 30+), which includes our overlays; it is used below API 34, where WINDOW is not available.
 */
class BarCapture(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "unrot-bar-capture").apply { isDaemon = true }
    }
    /**
     * Buffers handed to the executor and not yet started. Whoever removes a buffer from this set owns it and closes it:
     * the task when it starts, or [shutdown] for tasks that never ran. So each buffer is closed exactly once.
     */
    private val queued: MutableSet<HardwareBuffer> = ConcurrentHashMap.newKeySet()

    /**
     * Starts one capture. [onResult] or [onFail] is later called on the main thread, exactly once, unless the call
     * itself throws (then [onFail] is called at once). [windowId] is the accessibility window id of the app.
     */
    fun run(
        req: CaptureRequest, windowId: Int, screenW: Int, screenH: Int,
        onResult: (CaptureResult<Bitmap>) -> Unit, onFail: () -> Unit,
    ) {
        try {
            val isWindow = req.kind == CaptureKind.WINDOW
            if (isWindow && Build.VERSION.SDK_INT < 34) return fail(onFail)
            if (!isWindow && Build.VERSION.SDK_INT < 30) return fail(onFail)

            // Pixel offset of the capture's origin in screen coordinates.
            val (ox, oy) = if (isWindow) {
                val wb = windowBounds(windowId) ?: return fail(onFail)
                wb.left to wb.top
            } else 0 to 0

            val cb = object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val cs = result.colorSpace
                    queued.add(buffer)
                    try {
                        executor.execute { process(req, buffer, cs, ox, oy, screenW, screenH, onResult, onFail) }
                    } catch (t: Throwable) {
                        if (queued.remove(buffer)) runCatching { buffer.close() }
                        fail(onFail)
                    }
                }

                override fun onFailure(errorCode: Int) = fail(onFail)
            }

            if (isWindow) {
                @Suppress("NewApi")
                service.takeScreenshotOfWindow(windowId, service.mainExecutor, cb)
            } else {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, cb)
            }
        } catch (t: Throwable) {
            fail(onFail)
        }
    }

    fun shutdown() {
        executor.shutdownNow()
        // Tasks that never started still own their buffers: close them here.
        for (b in queued.toList()) if (queued.remove(b)) runCatching { b.close() }
    }

    private fun fail(onFail: () -> Unit) {
        main.post { onFail() }
    }

    private fun windowBounds(windowId: Int): Rect? = runCatching {
        service.windows.firstOrNull { it.id == windowId }?.let { w -> Rect().also { w.getBoundsInScreen(it) } }
    }.getOrNull()

    /** Background thread: decodes on [executor] and posts the outcome to the main thread. */
    private fun process(
        req: CaptureRequest, buffer: HardwareBuffer, cs: ColorSpace?, ox: Int, oy: Int, screenW: Int, screenH: Int,
        onResult: (CaptureResult<Bitmap>) -> Unit, onFail: () -> Unit,
    ) {
        if (!queued.remove(buffer)) return // shutdown() took it
        val r = try {
            runCatching { decode(req, buffer, cs, ox, oy, screenW, screenH) }.getOrNull() // Throwable, including OOM
        } finally {
            runCatching { buffer.close() }
        }
        if (r != null) main.post { onResult(r) } else fail(onFail)
    }

    /**
     * Reads the bar's pixels out of the frame. Null when the frame can't be used. Recycles the bitmaps made here, except the
     * full frame when a crop is that same bitmap (then the crop keeps it). The caller closes [buffer] (see [process]).
     */
    private fun decode(
        req: CaptureRequest, buffer: HardwareBuffer, cs: ColorSpace?, ox: Int, oy: Int, screenW: Int, screenH: Int,
    ): CaptureResult<Bitmap>? {
        var hw: Bitmap? = null
        var full: Bitmap? = null
        var keepFull = false
        try {
            // decode is only reached on API 30+ (see run); the SDK check is the guard that wrapHardwareBuffer (API 29) needs.
            val wrapped = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Bitmap.wrapHardwareBuffer(buffer, cs) else null)
                ?: return null
            hw = wrapped
            val f = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            full = f
            val fw = f.width
            val fh = f.height
            // A display shot must be the whole screen: anything else is not a clean shot of the bar.
            if (req.kind != CaptureKind.WINDOW && (fw != screenW || fh != screenH)) return null

            val crops = LinkedHashMap<String, Bitmap>()
            var valid = true
            for ((identity, box) in req.tabs) {
                val l = box.l - ox
                val t = box.t - oy
                val w = box.width
                val h = box.height
                if (w <= 0 || h <= 0 || l < 0 || t < 0 || l + w > fw || t + h > fh) return null
                val crop = Bitmap.createBitmap(f, l, t, w, h)
                if (crop === f) keepFull = true
                crops[identity] = crop
                val px = IntArray(w * h)
                crop.getPixels(px, 0, w, 0, 0, w, h)
                if (!cropValid(px)) valid = false
            }

            // Display shots: our flat patch must be flat in the shot. Icon pixels there mean the bar was on screen.
            val pb = req.patchBox
            if (req.kind != CaptureKind.WINDOW && pb != null && valid) {
                val pl = pb.l - ox
                val pt = pb.t - oy
                if (pb.width > 0 && pb.height > 0 && pl >= 0 && pt >= 0 && pl + pb.width <= fw && pt + pb.height <= fh) {
                    val px = IntArray(pb.width * pb.height)
                    f.getPixels(px, 0, pb.width, pl, pt, pb.width, pb.height)
                    if (!nearUniform(px)) valid = false
                }
            }

            fun pixel(p: Pair<Int, Int>): Int? {
                val x = p.first - ox
                val y = p.second - oy
                return if (x in 0 until fw && y in 0 until fh) f.getPixel(x, y) else null
            }
            val bg = req.samples.map { pixel(it) ?: return null }
            val top = req.topRows.map { row -> row.map { pixel(it) ?: return null } }

            return CaptureResult(req.gen, req.sig, crops, valid, bg, top)
        } finally {
            if (!keepFull) full?.recycle()
            hw?.recycle()
        }
    }

    companion object {
        /** Median-of-border background for [bg] used by the two-capture comparison. */
        fun borderBackground(px: IntArray, w: Int, h: Int): Int {
            val border = ArrayList<Int>(2 * (w + h))
            for (x in 0 until w) { border += px[x]; border += px[(h - 1) * w + x] }
            for (y in 0 until h) { border += px[y * w]; border += px[y * w + w - 1] }
            return median(border)
        }
    }
}
