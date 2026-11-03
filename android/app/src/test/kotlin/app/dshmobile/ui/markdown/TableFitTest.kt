package app.dshmobile.ui.markdown

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TableFitTest {
    @Test fun aTableThatFitsIsLeftAlone() {
        val w = intArrayOf(100, 150)
        shrinkToFit(w, fit = 400, min = 96)
        assertArrayEquals(intArrayOf(100, 150), w)
    }

    @Test fun theWidestColumnsGiveWidthBackUntilTheTableFits() {
        // The device case: two long-text columns at the 240 dp cap in a ~340 px bubble.
        val w = intArrayOf(240, 240)
        shrinkToFit(w, fit = 340, min = 96)
        assertEquals(340, w.sum())
        assertArrayEquals(intArrayOf(170, 170), w)
    }

    @Test fun aNarrowColumnKeepsItsWidthWhileTheWideOneShrinks() {
        val w = intArrayOf(60, 240)
        shrinkToFit(w, fit = 200, min = 96)
        assertArrayEquals(intArrayOf(60, 140), w)
    }

    @Test fun columnsNeverGoBelowTheMinimumSoAVeryWideTableScrolls() {
        val w = intArrayOf(240, 240, 240, 240, 240)
        shrinkToFit(w, fit = 340, min = 96)
        assertArrayEquals(intArrayOf(96, 96, 96, 96, 96), w)
    }
}
