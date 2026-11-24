package app.unrot

import app.unrot.bar.cropValid
import app.unrot.bar.foregroundSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarPixelsTest {
    private val w = 40
    private val h = 40
    private val bg = 0xFF101010.toInt()
    private val icon = 0xFFF0F0F0.toInt()

    /** A bar-coloured crop with a 10x10 icon whose top-left corner is at (x0, 5). */
    private fun crop(x0: Int, bgColour: Int = bg, iconColour: Int = icon): IntArray =
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            if (x in x0 until x0 + 10 && y in 5 until 15) iconColour else bgColour
        }

    @Test fun identicalCropsAreSame() {
        assertTrue(foregroundSame(crop(5), crop(5), w, h, bg))
    }

    @Test fun shiftedIconIsNotSame() {
        // A 3 px shift changes 60 of 1600 pixels (3.75%), which is above the 2% limit.
        assertFalse(foregroundSame(crop(5), crop(8), w, h, bg))
    }

    @Test fun blurredBackgroundChangeWithSameIconIsSame() {
        // The background moves by 8 per channel, which is inside the 24 foreground tolerance.
        val blurred = 0xFF181818.toInt()
        assertTrue(foregroundSame(crop(5), crop(5, bgColour = blurred), w, h, bg))
    }

    @Test fun changedIconColourIsNotSame() {
        assertFalse(foregroundSame(crop(5), crop(5, iconColour = 0xFF404040.toInt()), w, h, bg))
    }

    @Test fun mismatchedSizesAreNotSame() {
        assertFalse(foregroundSame(crop(5), IntArray(10), w, h, bg))
    }

    @Test fun blankCropIsInvalid() {
        assertFalse(cropValid(IntArray(w * h) { 0xFF000000.toInt() }))
    }

    @Test fun cropWithIconIsValid() {
        assertTrue(cropValid(crop(5)))
    }
}
