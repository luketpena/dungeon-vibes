package com.dungeonvibes.light.fx

import com.dungeonvibes.data.db.FlickerSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.PulseSettings
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FxShotsTest {

    private fun base() = Hsbk().apply {
        hue = 240f
        saturation = 1f
        brightness = 0.2f
        kelvin = 3_500f
    }

    private val pulse = PulseSettings(
        color = FxColor.color(0f, 1f),
        peakMin = 1f,
        peak = 1f,
        attackMs = 100,
        holdMs = 200,
        releaseMs = 400,
    )

    @Test
    fun pulse_holdsAtThePeak_andIsGoneAfterItsRelease() {
        val shot = PulseShot(pulse, Random(1))
        assertEquals(700L, shot.end)
        assertEquals(300L, shot.releaseStart)
        assertEquals(400, shot.releaseMs)

        val out = base()
        shot.apply(150, out)
        assertEquals(1f, out.brightness, 0.001f)
        assertEquals(0f, out.hue, 0.5f)

        val after = base()
        assertNull(shot.apply(700, after))
        assertEquals(0.2f, after.brightness, 0.001f)
        assertEquals(240f, after.hue, 0.001f)
    }

    @Test
    fun pulse_onlyMovesTheChosenChannels() {
        val brightnessOnly = PulseShot(pulse.copy(affectsColor = false, affectsSaturation = false), Random(1))
        val out = base()
        brightnessOnly.apply(150, out)
        assertEquals(1f, out.brightness, 0.001f)
        assertEquals(240f, out.hue, 0.001f)

        val colorOnly = PulseShot(pulse.copy(affectsBrightness = false), Random(1))
        val tinted = base()
        colorOnly.apply(150, tinted)
        assertEquals(0.2f, tinted.brightness, 0.001f)
        assertEquals(0f, tinted.hue, 0.5f)
    }

    @Test
    fun pulse_attackAndReleaseFadeTowardsWhereTheyAreHeaded() {
        val shot = PulseShot(pulse, Random(1))
        // The attack is sent once, fading over all 100 ms of it.
        assertEquals(100, shot.apply(0, base()))
        // Mid-release: aimed one frame ahead.
        assertEquals(EffectTiming.FRAME_MS.toInt(), shot.apply(400, base()))
    }

    @Test
    fun pulse_neverDimsALightAlreadyBrighterThanItsPeak() {
        val shot = PulseShot(pulse.copy(peakMin = 0.5f, peak = 0.5f), Random(1))
        val bright = base().apply { brightness = 0.9f }
        shot.apply(150, bright)
        assertEquals(0.9f, bright.brightness, 0.001f)
        // Its color still lands.
        assertEquals(0f, bright.hue, 0.5f)

        val dim = base()
        shot.apply(150, dim)
        assertEquals(0.5f, dim.brightness, 0.001f)
    }

    @Test
    fun pulseRelease_isCurved_stayingNearThePeakLonger() {
        val shot = PulseShot(pulse, Random(1))
        // At the release's start (300) it aims 200 ms in, halfway through: 75 % of the way up
        // rather than the 50 % of a straight fade.
        val out = base()
        shot.apply(300, out)
        assertEquals(0.2f + 0.8f * 0.75f, out.brightness, 0.001f)
    }

    @Test
    fun pulseBurst_reachesTheTopOnce_andStaysInRange() {
        val burst = PulseShot(pulse.copy(peakMin = 0.3f, pulsesMin = 3, pulsesMax = 3, spacingMinMs = 100, spacingMaxMs = 100), Random(7))
        // Three 700 ms pulses, 100 ms apart.
        assertEquals(2_300L, burst.end)
        val peaks = listOf(150L, 950L, 1_750L).map { t -> base().also { burst.apply(t, it) }.brightness }
        assertTrue(peaks.any { it > 0.999f })
        assertTrue(peaks.all { it in 0.3f..1f })
    }

    @Test
    fun flicker_dipsWithinItsDepth_noFasterThanTenTimesASecond_andEnds() {
        val settings = FlickerSettings(durationMs = 5_000, depth = 0.8f, intervalMinMs = 20, intervalMaxMs = 150)
        val shot = FlickerShot(settings, Random(3))
        assertEquals(5_000L, shot.end)
        assertEquals(0, shot.releaseMs)

        var t = 0L
        var dips = 0
        while (t < shot.end) {
            val next = shot.nextEdge(t)
            assertTrue("steps at least 100 ms apart", next - t >= FlickerShot.MIN_INTERVAL_MS || next == shot.end)
            val out = Hsbk().apply { brightness = 1f }
            assertEquals(0, shot.apply(t, out)) // hard cuts
            // Either full light, or a dip of 40–100 % of the depth.
            assertTrue(out.brightness == 1f || out.brightness in 0.2f..0.68f)
            if (out.brightness < 1f) dips++
            t = next
        }
        assertTrue(dips > 5)
        assertNull(shot.apply(5_000, Hsbk()))
    }
}
