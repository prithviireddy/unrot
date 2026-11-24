package app.unrot.bar

import kotlin.math.abs

/** Colour helpers on ARGB Ints. Pure Kotlin so they run in JVM unit tests. */

/** A thin band across the top of the bar (YouTube's 1 px line). [rows] is how many sampled rows it covers. */
data class Divider(val color: Int, val rows: Int)

private fun channel(c: Int, shift: Int): Int = (c shr shift) and 0xFF

private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

private fun medianOf(values: List<Int>): Int {
    val s = values.sorted()
    val m = s.size
    return if (m % 2 == 1) s[m / 2] else (s[m / 2 - 1] + s[m / 2]) / 2
}

/** Per-channel median; the result is always fully opaque. */
fun median(colors: List<Int>): Int {
    require(colors.isNotEmpty()) { "no colours to take a median of" }
    return argb(
        medianOf(colors.map { channel(it, 16) }),
        medianOf(colors.map { channel(it, 8) }),
        medianOf(colors.map { channel(it, 0) }),
    )
}

/** True when a colour is within [tol] of [ref] on every channel. */
fun near(a: Int, ref: Int, tol: Int): Boolean =
    abs(channel(a, 16) - channel(ref, 16)) <= tol &&
        abs(channel(a, 8) - channel(ref, 8)) <= tol &&
        abs(channel(a, 0) - channel(ref, 0)) <= tol

/** Every colour is within [tol] per channel of the median. Empty input is not uniform. */
fun uniform(colors: List<Int>, tol: Int = 12): Boolean {
    if (colors.isEmpty()) return false
    val m = median(colors)
    return colors.all { near(it, m, tol) }
}

/**
 * Whether a single-shot display crop agrees with the bar's colour. Its background samples must be uniform (tolerance 12),
 * and, when the appearance they show already has a sampled colour [known] (taken before this result), within 24 of it on
 * every channel. Empty samples never agree.
 */
fun cropColourAgrees(bg: List<Int>, known: Int?): Boolean {
    if (!uniform(bg)) return false
    return known == null || near(median(bg), known, 24)
}

/**
 * True when fewer than [minOff] of the pixels differ from the median by more than [tol]. Used to reject blank or
 * blacked-out crops, so the caller treats `true` as "invalid". Empty input counts as blank.
 */
fun nearUniform(pixels: IntArray, tol: Int = 12, minOff: Double = 0.01): Boolean {
    if (pixels.isEmpty()) return true
    val m = median(pixels.asList())
    val off = pixels.count { !near(it, m, tol) }
    return off < minOff * pixels.size
}

/**
 * [topRows] holds the sampled colours across rows 0..2 of the bar. The divider is the leading run (up to 3 rows) of
 * rows that are uniform and differ from [bg] by more than [tol]. Null when row 0 is not such a row.
 */
fun detectDivider(topRows: List<List<Int>>, bg: Int, tol: Int = 12): Divider? {
    var rows = 0
    var color = 0
    for (k in 0 until minOf(3, topRows.size)) {
        val row = topRows[k]
        if (!uniform(row, tol)) break
        val m = median(row)
        if (near(m, bg, tol)) break
        if (rows == 0) color = m
        rows++
    }
    return if (rows == 0) null else Divider(color, rows)
}
