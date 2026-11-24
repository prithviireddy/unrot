package app.unrot

import app.unrot.bar.slotIndexAt
import app.unrot.rules.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Hit-testing a tap into a slot index, for slots tiling tabsBox the way layoutSlots does. */
class BarSlotsTest {
    private val tb = Box(0, 2208, 1080, 2352)

    @Test fun xOutsideTheBarGivesNull() {
        assertNull(slotIndexAt(tb, 4, -1))
        assertNull(slotIndexAt(tb, 4, 1080))
        assertNull(slotIndexAt(tb, 0, 10))
    }

    @Test fun fourSlotsSplitAtTheirBoundaries() {
        assertEquals(0, slotIndexAt(tb, 4, 0))
        assertEquals(0, slotIndexAt(tb, 4, 269))
        assertEquals(1, slotIndexAt(tb, 4, 270))
        assertEquals(2, slotIndexAt(tb, 4, 540))
        assertEquals(3, slotIndexAt(tb, 4, 810))
        assertEquals(3, slotIndexAt(tb, 4, 1079))
    }

    @Test fun oddWidthsMatchTheIntegerBoundaries() {
        // 5 slots over 1081 px: starts at 0, 216, 432, 648, 864 (integer maths, as layoutSlots).
        val odd = Box(0, 2208, 1081, 2352)
        for (x in 0 until 1081) {
            val expected = (0 until 5).last { i -> i * 1081 / 5 <= x }
            assertEquals("x=$x", expected, slotIndexAt(odd, 5, x))
        }
    }

    @Test fun everyPixelMapsToExactlyOneSlotForSixTabs() {
        val wide = Box(0, 2208, 1080, 2352)
        var prev = -1
        for (x in 0 until 1080) {
            val i = slotIndexAt(wide, 6, x)!!
            org.junit.Assert.assertTrue("jumped from $prev to $i at $x", i == prev || i == prev + 1)
            prev = i
        }
        assertEquals(5, prev)
    }
}
