package com.dungeonvibes.ui.scenes.editor

import com.dungeonvibes.ui.common.snapMs
import com.dungeonvibes.ui.common.snapPercent
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** Slider steps (owner rule, Oct 23, 2026): tenths under 1 s, half seconds above, 5 % for percents. */
class SliderStepsTest {

    @Test
    fun times_snapToTenthsUnderASecond_andHalvesAbove() {
        assertEquals(0, snapMs(40f))
        assertEquals(100, snapMs(60f))
        assertEquals(300, snapMs(330f))
        assertEquals(1_000, snapMs(980f))
        assertEquals(1_500, snapMs(1_400f))
        assertEquals(12_500, snapMs(12_600f))
        // The slider's own minimum still holds.
        assertEquals(200, positionToTime(0f, 200, 10_000))
        assertEquals(10_000, positionToTime(1f, 200, 10_000))
    }

    @Test
    fun percents_snapTo5PercentSteps() {
        assertEquals(0.35f, snapPercent(0.36f), 0.0001f)
        assertEquals(0.4f, snapPercent(0.38f), 0.0001f)
        assertEquals(1f, snapPercent(0.99f), 0.0001f)
        assertEquals(0f, snapPercent(0.02f), 0.0001f)
    }

    @Test
    fun durations_alwaysShowOneDecimal() {
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals("0.3 s", formatDuration(300))
            assertEquals("1.0 s", formatDuration(1_000))
            assertEquals("1.5 s", formatDuration(1_500))
            assertEquals("12.5 s", formatDuration(12_500))
            assertEquals("2 min 0.0 s", formatDuration(120_000))
            assertEquals("2 min 30.5 s", formatDuration(150_500))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
