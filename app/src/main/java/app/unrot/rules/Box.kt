package app.unrot.rules

/** Integer screen rectangle (left, top, right, bottom). Pure Kotlin so it runs in JVM tests. */
data class Box(val l: Int, val t: Int, val r: Int, val b: Int) {
    val width get() = r - l
    val height get() = b - t

    /** Strict overlap: rectangles that only share an edge do not intersect. */
    fun intersects(o: Box) = l < o.r && o.l < r && t < o.b && o.t < b

    fun contains(o: Box) = l <= o.l && t <= o.t && r >= o.r && b >= o.b

    fun union(o: Box) = Box(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))
}
