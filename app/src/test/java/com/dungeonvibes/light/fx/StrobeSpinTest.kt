package com.dungeonvibes.light.fx

import com.dungeonvibes.data.db.ColorOrder
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.SpinFxSettings
import com.dungeonvibes.data.db.SpinSettings
import com.dungeonvibes.data.db.StrobeSettings
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Strobe and Spin (FX and scene layer), and ping-pong palettes (owner requests, Oct 23, 2026). */
class StrobeSpinTest {

    private fun base() = Hsbk().apply {
        hue = 240f
        saturation = 1f
        brightness = 0.2f
        kelvin = 3_500f
    }

    @Test
    fun strobe_alternatesEvenlyWithHardCuts_forItsDuration() {
        val shot = StrobeShot(StrobeSettings(color = FxColor.color(0f), level = 0.8f, flashesPerSecond = 2f, durationMs = 1_000))
        assertEquals(1_000L, shot.end)

        val on = base()
        assertEquals(0, shot.apply(100, on))
        assertEquals(0.8f, on.brightness, 0.001f)
        assertEquals(0f, on.hue, 0.001f)

        val off = base()
        shot.apply(300, off)
        assertEquals(0f, off.brightness, 0.001f)

        assertEquals(250L, shot.nextEdge(100))
        assertNull(shot.apply(1_000, base()))
    }

    @Test
    fun strobe_isCappedAtFourFlashesASecond() {
        val shot = StrobeShot(StrobeSettings(flashesPerSecond = 20f))
        // 4 a second: 125 ms on, 125 ms off.
        assertEquals(125L, shot.nextEdge(0))
    }

    @Test
    fun spinShot_eachBulbTakesItsTurnEveryLap() {
        val settings = SpinFxSettings(color = FxColor.color(270f), level = 1f, attackMs = 0, holdMs = 100, releaseMs = 0, stepMs = 200, laps = 2)
        val second = SpinShot(settings, index = 1, count = 3)
        // Turns at 200 and 800 (a lap is 3 × 200 ms).
        assertEquals(900L, second.end)

        val before = base()
        second.apply(100, before)
        assertEquals(0.2f, before.brightness, 0.001f)

        val turn = base()
        second.apply(250, turn)
        assertEquals(1f, turn.brightness, 0.001f)
        assertEquals(270f, turn.hue, 0.5f)

        val between = base()
        second.apply(500, between)
        assertEquals(240f, between.hue, 0.5f)

        val secondLap = base()
        second.apply(850, secondLap)
        assertEquals(270f, secondLap.hue, 0.5f)
    }

    @Test
    fun spinLayer_stepsThroughTheBulbs_withThePauseAfterEachLap() {
        val settings = SpinSettings(enabled = true, attackMs = 0, holdMs = 50, releaseMs = 0, stepMs = 100, lapGapMs = 200)
        val third = SpinTimeline(settings, index = 2, bulbCount = 3)
        // A lap: 3 × 100 ms + a 200 ms pause = 500 ms; the third bulb's turn is 200–250.
        assertEquals(1f, third.levelAt(220), 0.001f)
        assertEquals(0f, third.levelAt(260), 0.001f)
        assertEquals(1f, third.levelAt(720), 0.001f)
        assertEquals(250L, third.segmentEnd(220))
    }

    @Test
    fun spinLayer_aLongReleaseTrailsIntoTheNextLap() {
        val settings = SpinSettings(enabled = true, attackMs = 0, holdMs = 0, releaseMs = 400, stepMs = 100)
        val first = SpinTimeline(settings, index = 0, bulbCount = 2)
        // Lap 200 ms, release 400: 100 ms into the second lap's turn, the first turn is still fading too.
        assertEquals(0.75f, first.levelAt(100), 0.001f)
        assertEquals(0.75f, first.levelAt(300), 0.001f)
    }

    @Test
    fun pingPong_walksThePaletteUpThenBack() {
        val palette = Array(3) { ColorPoint(it * 100f, 1f, 3_500f) }
        val settings = ColorShiftSettings(enabled = true, order = ColorOrder.PING_PONG, holdMinMs = 100, holdMaxMs = 100, blendMinMs = 100, blendMaxMs = 100)
        val timeline = ColorShiftTimeline(settings, palette, Random(1), desync = false)
        val out = Hsbk()
        val seen = ArrayList<Float>()
        // Sample each step's hold: 0, 1, 2, 1, 0, 1.
        for (step in 0 until 6) {
            timeline.sample(step * 200L + 50, out)
            seen.add(out.hue)
        }
        assertEquals(listOf(0f, 100f, 200f, 100f, 0f, 100f), seen)
    }

    @Test
    fun spin_reverse_goesLastToFirst() {
        val settings = SpinFxSettings(attackMs = 0, holdMs = 100, releaseMs = 0, stepMs = 200, laps = 1, reverse = true)
        // Of 3 targets, the last turns first (0–100) and the first turns last (400–500).
        val last = base()
        SpinShot(settings, index = 2, count = 3).apply(50, last)
        assertEquals(1f, last.brightness, 0.001f)
        assertEquals(500L, SpinShot(settings, index = 0, count = 3).end)

        val layer = SpinSettings(enabled = true, attackMs = 0, holdMs = 50, releaseMs = 0, stepMs = 100, reverse = true)
        assertEquals(1f, SpinTimeline(layer, index = 2, bulbCount = 3).levelAt(20), 0.001f)
        assertEquals(1f, SpinTimeline(layer, index = 0, bulbCount = 3).levelAt(220), 0.001f)
        assertEquals(0f, SpinTimeline(layer, index = 0, bulbCount = 3).levelAt(20), 0.001f)
    }
}
