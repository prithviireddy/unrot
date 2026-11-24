package app.unrot.bar

import kotlin.math.abs

/**
 * Pixel helpers for comparing bar crops. Pure Kotlin so they run in JVM unit tests.
 * Crops are ARGB ints, row-major, [w] x [h].
 */

private fun ch(c: Int, shift: Int): Int = (c shr shift) and 0xFF

/** True when any colour channel of [a] differs from [b] by more than [tol]. */
private fun differs(a: Int, b: Int, tol: Int): Boolean =
    abs(ch(a, 16) - ch(b, 16)) > tol || abs(ch(a, 8) - ch(b, 8)) > tol || abs(ch(a, 0) - ch(b, 0)) > tol

/**
 * Compares two crops by their foreground only (the icon and label), so a change in a blurred background behind a
 * translucent bar does not count. A pixel is foreground when it differs from [bg] by more than [fgTol]. The crops
 * are the same when the share of pixels that differ is at most [maxDiffFrac]. A pixel differs when its foreground
 * flag differs between the two crops, or when it is foreground in both and the colours differ by more than [fgTol].
 * Mismatched sizes and empty crops are never the same.
 */
fun foregroundSame(
    a: IntArray, b: IntArray, w: Int, h: Int, bg: Int,
    fgTol: Int = 24, maxDiffFrac: Double = 0.02,
): Boolean {
    val n = w * h
    if (n <= 0 || a.size != n || b.size != n) return false
    var diff = 0
    for (i in 0 until n) {
        val fa = differs(a[i], bg, fgTol)
        val fb = differs(b[i], bg, fgTol)
        if (fa != fb || (fa && fb && differs(a[i], b[i], fgTol))) diff++
    }
    return diff <= maxDiffFrac * n
}

/** A crop is worth keeping only if it is not (nearly) one colour, such as a blank or blacked-out window. */
fun cropValid(pixels: IntArray): Boolean = !nearUniform(pixels)
