package com.dungeonvibes.ui.scenes.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Attack-Hold-Release mini sliders share the full sliders' squared scale and steps. */
class AhrControlTest {

    @Test
    fun miniSliderValues_landOnTheirOwnPositions() {
        val max = FxRanges.ENVELOPE_MAX
        for (ms in listOf(0, 100, 500, 1_000, 1_500, max)) {
            assertEquals(ms, positionToTime(timeToPosition(ms, 0, max), 0, max))
        }
    }
}
