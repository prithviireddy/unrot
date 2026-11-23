package app.unrot

import app.unrot.bar.Divider
import app.unrot.bar.cropColourAgrees
import app.unrot.bar.detectDivider
import app.unrot.bar.median
import app.unrot.bar.nearUniform
import app.unrot.bar.uniform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarColorTest {
    private val yBar = 0xFF2B2B2B.toInt()
    private val yBg = 0xFF0F0F0F.toInt()

    @Test fun medianIsPerChannelAndOpaque() {
        val m = median(listOf(0xFF102030.toInt(), 0xFF203040.toInt(), 0xFF304050.toInt()))
        assertEquals(0xFF203040.toInt(), m)
        assertEquals(0xFF, (m ushr 24) and 0xFF)
    }

    @Test fun uniformUsesToleranceAroundMedian() {
        assertTrue(uniform(listOf(0xFF000000.toInt(), 0xFF0C0C0C.toInt(), 0xFF000000.toInt())))
        assertFalse(uniform(listOf(0xFF000000.toInt(), 0xFF0D0D0D.toInt(), 0xFF0D0D0D.toInt(), 0xFF000000.toInt(), 0xFF000000.toInt())))
        assertFalse(uniform(emptyList()))
    }

    @Test fun nearUniformRejectsBlankAndAcceptsDetailedCrops() {
        assertTrue(nearUniform(IntArray(1000) { yBg }))
        val oneOff = IntArray(1000) { yBg }.also { it[0] = 0xFFFFFFFF.toInt() }
        assertTrue(nearUniform(oneOff))
        val detailed = IntArray(1000) { yBg }.also { for (i in 0 until 50) it[i] = 0xFFFFFFFF.toInt() }
        assertFalse(nearUniform(detailed))
    }

    @Test fun youtubeLikeDividerIsDetected() {
        val rows = listOf(List(10) { yBar }, List(10) { yBg }, List(10) { yBg })
        assertEquals(Divider(yBar, 1), detectDivider(rows, yBg))
    }

    @Test fun dividerNeedsRowZeroToDifferFromBackground() {
        assertNull(detectDivider(listOf(List(10) { yBg }, List(10) { yBar }), yBg))
    }

    @Test fun dividerStopsAtNonUniformRow() {
        val rows = listOf(List(10) { yBar }, List(10) { yBar }, listOf(yBar, yBg, yBar, yBg))
        assertEquals(Divider(yBar, 2), detectDivider(rows, yBg))
    }

    /** A display shot's patch area must be flat: our patch is one colour, so an icon drawn there fails the check. */
    @Test fun patchAreaMustBeFlatInDisplayShots() {
        val flat = IntArray(216 * 144) { yBar }
        assertTrue(nearUniform(flat))
        val iconOver = IntArray(216 * 144) { yBar }
        for (i in 0 until 216 * 144 step 12) iconOver[i] = 0xFFFFFFFF.toInt() // about 8% of pixels differ
        assertFalse(nearUniform(iconOver))
    }
    @Test fun displayCropAgreesOnlyWhenUniformAndNearAKnownColour() {
        val dark = 0xFF0C1014.toInt()
        val near = 0xFF1A2024.toInt() // 14 per channel off: within 24
        val far = 0xFF404040.toInt()  // 52 per channel off: not within 24
        assertTrue(cropColourAgrees(List(3) { dark }, null)) // no colour sampled yet for that appearance: uniform is enough
        assertTrue(cropColourAgrees(List(3) { dark }, dark))
        assertTrue(cropColourAgrees(List(3) { near }, dark))
        assertFalse(cropColourAgrees(List(3) { far }, dark))
        assertFalse(cropColourAgrees(listOf(dark, far, dark), null)) // not uniform
        assertFalse(cropColourAgrees(emptyList(), null))
    }
}
