package com.dungeonvibes.light.fx

import com.dungeonvibes.data.db.BrightnessMode
import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.ColorOrder
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.light.LightState
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectEngineTest {

    private companion object {
        /** Longer than Flicker spacing, shorter than any gap between flashes in these tests. */
        const val GAP = 100L
    }

    private val red = LightState(0f, 1f, 0.2f, null)

    private fun run(effects: GroupEffects, bulbs: Int = 1, seed: Int = 7, base: LightState = red) =
        GroupEffectRun(Hsbk.of(base), effects, bulbs, Random(seed))

    private fun GroupEffectRun.at(bulb: Int, t: Long): Hsbk {
        val out = Hsbk()
        flashKey(bulb, t)
        sample(bulb, t, out)
        return out
    }

    @Test
    fun restingState_isTheFirstPaletteColorForMultiColor_elseTheBase() {
        val base = LightState(30f, 1f, 0.6f, null)
        assertEquals(base, restingState(base, null))
        assertEquals(base, restingState(base, flashes()))
        val multi = colorShift(ColorOrder.IN_SEQUENCE).copy(
            palette = listOf(FxColor.white(2_200).copy(brightness = 0.4f), FxColor.color(200f)),
        )
        // At the first color's own brightness.
        assertEquals(LightState(0f, 0f, 0.4f, 2_200), restingState(base, multi))
    }

    /* Color math */

    @Test
    fun blendHue_takesTheShortWayRound() {
        assertEquals(0f, blendHue(350f, 10f, 0.5f), 0.001f)
        assertEquals(355f, blendHue(10f, 340f, 0.5f), 0.001f)
    }

    @Test
    fun blendFromWhiteToColor_keepsTheColorsHue_andMovesSaturationAndWhitePoint() {
        val out = Hsbk()
        blendColor(0f, 0f, 2_700f, 200f, 1f, 3_500f, 0.5f, out)
        assertEquals(200f, out.hue, 0.001f)
        assertEquals(0.5f, out.saturation, 0.001f)
        assertEquals(3_100f, out.kelvin, 0.001f)
    }

    @Test
    fun toLightState_sendsWhitesInWhiteMode_andColorsWithTheirWhitePoint() {
        val white = Hsbk().apply { saturation = 0f; brightness = 0.5f; kelvin = 2_700f }.toLightState()
        assertEquals(2_700, white.kelvin)
        val tinted = Hsbk().apply { hue = 30f; saturation = 0.3f; brightness = 0.5f; kelvin = 3_000f }.toLightState()
        assertNull(tinted.kelvin)
        assertEquals(3_000, tinted.colorKelvin)
    }

    /* Color shift */

    private fun colorShift(order: ColorOrder, spread: Spread = Spread.TOGETHER) = GroupEffects(
        colorShift = ColorShiftSettings(
            enabled = true,
            spread = spread,
            order = order,
            blendMinMs = 100,
            blendMaxMs = 100,
            holdMinMs = 100,
            holdMaxMs = 100,
        ),
        palette = listOf(FxColor.color(0f), FxColor.color(120f), FxColor.color(240f)),
    )

    @Test
    fun colorShift_inSequence_holdsThenBlendsThroughThePaletteInOrder_andLoops() {
        val run = run(colorShift(ColorOrder.IN_SEQUENCE))
        assertEquals(0f, run.at(0, 50).hue, 0.01f)
        assertEquals(60f, run.at(0, 150).hue, 0.01f)
        assertEquals(120f, run.at(0, 250).hue, 0.01f)
        assertEquals(240f, run.at(0, 450).hue, 0.01f)
        assertEquals(0f, run.at(0, 650).hue, 0.01f)
    }

    @Test
    fun colorShift_holdsEachColorsBrightness_andBlendsBetweenThem() {
        val dimToBright = colorShift(ColorOrder.IN_SEQUENCE).copy(
            palette = listOf(FxColor.color(0f, brightness = 0.2f), FxColor.color(120f, brightness = 1f)),
        )
        val run = run(dimToBright)
        assertEquals(0.2f, run.at(0, 50).brightness, 0.0001f)
        assertEquals(0.6f, run.at(0, 150).brightness, 0.0001f)
        assertEquals(1f, run.at(0, 250).brightness, 0.0001f)
    }

    @Test
    fun colorShift_random_neverPicksTheColorItIsOn() {
        val effects = colorShift(ColorOrder.RANDOM).let {
            it.copy(colorShift = it.colorShift.copy(blendMinMs = 0, blendMaxMs = 0))
        }
        val run = run(effects)
        var previous = run.at(0, 50).hue
        for (k in 1..300) {
            val hue = run.at(0, 50L + k * 100).hue
            assertNotEquals("step $k", previous, hue, 0.01f)
            previous = hue
        }
    }

    @Test
    fun colorShift_needsTwoColors() {
        val one = colorShift(ColorOrder.IN_SEQUENCE).copy(palette = listOf(FxColor.color(90f)))
        assertEquals(0f, run(one).at(0, 150).hue, 0.01f) // the base, untouched
    }

    /* Brightness shift */

    @Test
    fun smooth_startsLow_peaksMidCycle_andStaysInRange() {
        val run = run(
            GroupEffects(
                brightnessShift = BrightnessShiftSettings(
                    enabled = true,
                    levelMin = 0.2f,
                    levelMax = 0.8f,
                    speedMinMs = 1_000,
                    speedMaxMs = 1_000,
                ),
            ),
        )
        assertEquals(0.2f, run.at(0, 0).brightness, 0.001f)
        assertEquals(0.5f, run.at(0, 250).brightness, 0.001f)
        assertEquals(0.8f, run.at(0, 500).brightness, 0.001f)
        for (t in 500L..5_000L step 37) {
            val b = run.at(0, t).brightness
            assertTrue("$b at $t", b in 0.1999f..0.8001f)
        }
    }

    @Test
    fun flicker_jumpsBetweenRandomLevelsInsideTheRange() {
        val run = run(
            GroupEffects(
                brightnessShift = BrightnessShiftSettings(
                    enabled = true,
                    mode = BrightnessMode.FLICKER,
                    levelMin = 0.3f,
                    levelMax = 0.6f,
                    speedMinMs = 100,
                    speedMaxMs = 100,
                ),
            ),
        )
        val seen = HashSet<Float>()
        for (k in 0 until 100) {
            val b = run.at(0, k * 100L + 50).brightness
            assertTrue(b in 0.3f..0.6f)
            seen.add(b)
        }
        assertTrue("only ${seen.size} levels", seen.size > 20)
    }

    @Test
    fun flicker_glidesBetweenLevels_withoutParking() {
        val run = run(
            GroupEffects(
                brightnessShift = BrightnessShiftSettings(
                    enabled = true,
                    mode = BrightnessMode.FLICKER,
                    levelMin = 0f,
                    levelMax = 1f,
                    speedMinMs = 400,
                    speedMaxMs = 400,
                ),
            ),
        )
        var previous = run.at(0, 0).brightness
        var still = 0
        for (t in 10L..20_000L step 10) {
            val b = run.at(0, t).brightness
            // Continuous: no jumps bigger than one 10 ms slice of a full-range 400 ms glide.
            assertTrue("jump at $t", kotlin.math.abs(b - previous) <= 0.0251f)
            if (b == previous) still++
            previous = b
        }
        assertTrue("parked $still times", still < 20)
    }

    @Test
    fun lookAhead_samplesShiftsAhead_butNotFlashes() {
        val effects = GroupEffects(
            brightnessShift = BrightnessShiftSettings(
                enabled = true,
                levelMin = 0.2f,
                levelMax = 0.8f,
                speedMinMs = 1_000,
                speedMaxMs = 1_000,
            ),
        )
        val out = Hsbk()
        val transition = run(effects).sample(0, 200, out, lookAheadMs = 300)
        assertEquals(300, transition)
        assertEquals(0.8f, out.brightness, 0.001f) // the value at 500 ms
    }

    /* Flashes */

    private fun flashes(flicker: Boolean = false, spread: Spread = Spread.TOGETHER) = GroupEffects(
        flashes = FlashSettings(
            enabled = true,
            spread = spread,
            color = FxColor.white(6_500),
            peak = 1f,
            attackMs = 100,
            holdMs = 100,
            releaseMs = 200,
            gapMinMs = 1_000,
            gapMaxMs = 1_000,
        ).withFlicker(flicker),
    )

    @Test
    fun envelope_risesOverAttack_holds_andFallsOverRelease() {
        val timeline = FlashTimeline(flashes().flashes, Random(1))
        timeline.advance(1_000)
        assertEquals(0f, timeline.levelAt(999), 0.0001f)
        assertEquals(0.5f, timeline.levelAt(1_050), 0.0001f)
        assertEquals(1f, timeline.levelAt(1_150), 0.0001f)
        assertEquals(0.5f, timeline.levelAt(1_300), 0.0001f)
        assertEquals(0f, timeline.levelAt(1_400), 0.0001f)
    }

    @Test
    fun flash_targetsTheEndOfItsAttack_withAMatchingTransition() {
        val run = run(flashes())
        val out = Hsbk()
        run.flashKey(0, 1_050)
        val transition = run.sample(0, 1_050, out)
        assertEquals(50, transition)
        // At the end of the attack the flash fully wins: its white and its peak.
        assertEquals(1f, out.brightness, 0.0001f)
        assertEquals(0f, out.saturation, 0.0001f)
        assertEquals(6_500f, out.kelvin, 0.01f)
    }

    @Test
    fun flash_releaseHandsBackToTheLayersUnderneath() {
        val run = run(flashes())
        val out = Hsbk()
        run.flashKey(0, 1_250)
        assertEquals(150, run.sample(0, 1_250, out))
        assertEquals(0.2f, out.brightness, 0.0001f) // the base, by the end of the release
        assertEquals(1f, out.saturation, 0.0001f)
        assertEquals(0.2f, run.at(0, 1_500).brightness, 0.0001f)
    }

    @Test
    fun flashRelease_bringsTheSceneColorBackFasterThanItsBrightness() {
        // A white flash over a red base (saturation 1, brightness 0.2), with a 1 s release from
        // 1200 ms. At the release's start it aims 200 ms in: 80 % of the flash left.
        val slow = flashes().let { it.copy(flashes = it.flashes.copy(releaseMs = 1_000)) }
        val out = run(slow).at(0, 1_200)
        assertEquals(0.2f + 0.8f * 0.8f, out.brightness, 0.0001f)
        // Color: 0.8² = 64 % of the flash left, so the red is already 36 % back (straight: 20 %).
        assertEquals(0.36f, out.saturation, 0.0001f)
    }

    @Test
    fun flash_takesPrecedenceOverBrightnessShift() {
        val effects = flashes().copy(
            brightnessShift = BrightnessShiftSettings(enabled = true, levelMin = 0f, levelMax = 0.1f),
        )
        assertEquals(1f, run(effects).at(0, 1_150).brightness, 0.0001f)
    }

    @Test
    fun singleOrFlicker_isStoredInThePulseCounts() {
        val single = FlashSettings()
        assertTrue(single.flicker.not())
        val flicker = single.withFlicker(true)
        assertTrue(flicker.flicker)
        assertEquals(2, flicker.pulsesMin)
        assertEquals(3, flicker.pulsesMax)
        assertEquals(single, flicker.withFlicker(false))
        // Older rows with any multi-pulse burst read as Flicker.
        assertTrue(single.copy(pulsesMin = 1, pulsesMax = 5).flicker)
    }

    /** One pulse of a timeline: when it starts and ends, and how strong it is. */
    private class Pulse(val start: Long, var end: Long, val strength: Float, val peak: Float)

    /** Every pulse a timeline plays up to [until], stepping 1 ms. */
    private fun pulsesOf(timeline: FlashTimeline, until: Long): List<Pulse> {
        val pulses = ArrayList<Pulse>()
        var current: Pulse? = null
        for (t in 0L..until) {
            timeline.advance(t)
            val lit = timeline.segment(t) != FlashTimeline.IDLE
            if (lit && current == null) {
                current = Pulse(t, t, timeline.strength, timeline.peak).also { pulses.add(it) }
            } else if (!lit && current != null) {
                current.end = t
                current = null
            }
        }
        return pulses
    }

    /** Pulses split into bursts: anything less than [GAP] ms apart is the same burst. */
    private fun burstsOf(pulses: List<Pulse>): List<List<Pulse>> {
        val bursts = ArrayList<MutableList<Pulse>>()
        for (pulse in pulses) {
            val last = bursts.lastOrNull()
            if (last != null && pulse.start - last.last().end < GAP) last.add(pulse) else bursts.add(mutableListOf(pulse))
        }
        return bursts
    }

    @Test
    fun single_playsOnePulsePerFlash() {
        val bursts = burstsOf(pulsesOf(FlashTimeline(flashes().flashes, Random(3)), 20_000))
        assertTrue(bursts.size > 5)
        assertTrue(bursts.all { it.size == 1 && it[0].strength == 1f })
    }

    @Test
    fun flicker_playsTwoOrThreePulses_5To10MsApart_thenWaitsTheGap() {
        // A full burst is the last one if it ends before the window does.
        val bursts = burstsOf(pulsesOf(FlashTimeline(flashes(flicker = true).flashes, Random(3)), 60_000)).dropLast(1)
        assertTrue(bursts.size > 20)
        for (burst in bursts) {
            assertTrue("${burst.size}", burst.size in 2..3)
            for (i in 1 until burst.size) {
                val spacing = burst[i].start - burst[i - 1].end
                assertTrue("$spacing", spacing in 5L..10L)
            }
        }
        assertTrue("both sizes should occur", bursts.any { it.size == 2 } && bursts.any { it.size == 3 })
        // Every pulse snaps on and holds 40 ms; all but the last die away in 60 ms, the last over
        // the layer's 200 ms Release.
        for (burst in bursts) {
            for (i in 0 until burst.size - 1) assertEquals(100L, burst[i].end - burst[i].start)
            assertEquals(240L, burst.last().end - burst.last().start)
        }
        for (i in 1 until bursts.size) assertEquals(1_000L, bursts[i][0].start - bursts[i - 1].last().end)
    }

    @Test
    fun flicker_oneFullPulsePerBurst_echoesAtMostAFifthAsStrong() {
        val settings = flashes(flicker = true).flashes.copy(peakMin = 0.3f, peak = 1f)
        val bursts = burstsOf(pulsesOf(FlashTimeline(settings, Random(3)), 60_000)).dropLast(1)
        val echoes = HashSet<Float>()
        for (burst in bursts) {
            assertEquals(1, burst.count { it.strength == 1f })
            // The strike always ends the burst.
            assertEquals(1f, burst.last().strength)
            // Every pulse of a burst shares the top of the peak range.
            assertTrue(burst.all { it.peak == 1f })
            for (pulse in burst) if (pulse.strength < 1f) {
                assertTrue("${pulse.strength}", pulse.strength in 0.1f..0.2f)
                echoes.add(pulse.strength)
            }
        }
        assertTrue("echoes should vary", echoes.size > 10)
    }

    @Test
    fun flicker_ignoresAttackAndHold_andSnapsOn() {
        val slow = flashes(flicker = true).let { it.copy(flashes = it.flashes.copy(attackMs = 2_000, holdMs = 2_000)) }
        val run = run(slow)
        val out = Hsbk()
        // The first pulse starts at 1000: no attack, so it's straight into its hold, sent instantly.
        run.flashKey(0, 1_000)
        assertEquals(0, run.sample(0, 1_000, out))
        assertTrue(out.brightness > 0.2f)
        // Its hold ends 40 ms later whatever Hold says.
        run.flashKey(0, 1_041)
        val transition = run.sample(0, 1_041, out)
        assertTrue("$transition", transition in 1..200)
    }

    @Test
    fun flicker_echoesLiftTheBaseOnlyALittle() {
        // Base brightness 0.2, peak 1: the main pulse reaches 1, an echo at most 0.2 + 0.8 × 0.2.
        val run = run(flashes(flicker = true), seed = 4)
        var strikes = 0
        var wasLit = false
        var echoTop = 0f
        for (t in 0L..2_500L) {
            val brightness = run.at(0, t).brightness
            val lit = brightness > 0.9f
            if (lit && !wasLit) strikes++
            wasLit = lit
            if (brightness < 0.9f && brightness > echoTop) echoTop = brightness
        }
        assertEquals(1, strikes)
        assertTrue("$echoTop", echoTop > 0.21f && echoTop <= 0.36f + 0.0001f)
    }

    @Test
    fun peakRange_singleFlashes_varyInsideTheRange() {
        val settings = flashes().flashes.copy(peakMin = 0.4f, peak = 0.6f)
        val timeline = FlashTimeline(settings, Random(5))
        val peaks = HashSet<Float>()
        var lastSerial = -1L
        for (t in 0L..60_000L step 5) {
            timeline.advance(t)
            if (timeline.segment(t) == FlashTimeline.HOLD && timeline.serial != lastSerial) {
                assertTrue(timeline.peak in 0.4f..0.6f)
                peaks.add(timeline.peak)
                lastSerial = timeline.serial
            }
        }
        assertTrue(peaks.size > 10)
    }

    @Test
    fun triggerFlash_startsABurstNow_andWaveBulbsFollowByTheirOffset() {
        val rare = flashes(spread = Spread.WAVE).let {
            it.copy(flashes = it.flashes.copy(gapMinMs = 60_000, gapMaxMs = 60_000, waveOffsetMs = 300))
        }
        val run = run(rare, bulbs = 2)
        run.flashKey(0, 500)
        run.flashKey(1, 500)
        run.triggerFlash(500)

        // Bulb 0 peaks after its 100 ms attack; bulb 1 only starts 300 ms later.
        assertEquals(1f, run.at(0, 650).brightness, 0.0001f)
        assertEquals(0.2f, run.at(1, 650).brightness, 0.0001f)
        assertEquals(1f, run.at(1, 950).brightness, 0.0001f)
    }

    @Test
    fun triggerFlash_duringAFlash_doesNothing() {
        val run = run(flashes())
        val before = run.flashKey(0, 1_150)
        run.triggerFlash(1_150)
        assertEquals(before, run.flashKey(0, 1_150))
    }

    @Test
    fun setBase_movesTheBaseWithoutRestarting() {
        val run = run(flashes())
        run.setBase(Hsbk.of(LightState(120f, 1f, 0.5f, null)))
        assertEquals(120f, run.at(0, 500).hue, 0.001f)
        assertEquals(0.5f, run.at(0, 500).brightness, 0.001f)
        assertEquals(1f, run.at(0, 1_150).brightness, 0.0001f) // the first flash is where it was
    }

    @Test
    fun flashKey_changesAtEachSegment() {
        val run = run(flashes())
        val keys = listOf(500L, 1_050L, 1_150L, 1_250L, 1_450L).map { run.flashKey(0, it) }
        assertEquals(5, keys.toSet().size)
        assertEquals(-1L, run(colorShift(ColorOrder.IN_SEQUENCE)).flashKey(0, 0))
    }

    /* Spread */

    private fun breathing(spread: Spread) = GroupEffects(
        brightnessShift = BrightnessShiftSettings(
            enabled = true,
            spread = spread,
            waveOffsetMs = 300,
            levelMin = 0f,
            levelMax = 1f,
            speedMinMs = 1_000,
            speedMaxMs = 3_000,
        ),
    )

    @Test
    fun together_everyBulbMatches() {
        val run = run(breathing(Spread.TOGETHER), bulbs = 3)
        for (t in 0L..10_000L step 50) {
            val first = run.at(0, t).brightness
            assertEquals(first, run.at(1, t).brightness, 0f)
            assertEquals(first, run.at(2, t).brightness, 0f)
        }
    }

    @Test
    fun independent_bulbsDiffer() {
        val run = run(breathing(Spread.INDEPENDENT), bulbs = 2)
        var differences = 0
        for (t in 0L..10_000L step 50) {
            if (kotlin.math.abs(run.at(0, t).brightness - run.at(1, t).brightness) > 0.05f) differences++
        }
        assertTrue("only $differences", differences > 50)
    }

    @Test
    fun wave_eachBulbFollowsThePreviousOneByTheOffset() {
        val run = run(breathing(Spread.WAVE), bulbs = 2)
        val first = HashMap<Long, Float>()
        for (t in 0L..10_000L step 50) {
            first[t] = run.at(0, t).brightness
            if (t >= 300) assertEquals("at $t", first.getValue(t - 300), run.at(1, t).brightness, 0.00001f)
        }
    }

    @Test
    fun spreadIsPerLayer() {
        // Flashes together, brightness independent: every bulb flashes at once over different levels.
        val effects = breathing(Spread.INDEPENDENT).copy(flashes = flashes().flashes)
        val run = run(effects, bulbs = 2)
        assertEquals(run.flashKey(0, 1_150), run.flashKey(1, 1_150))
        assertEquals(1f, run.at(1, 1_150).brightness, 0.0001f)
    }
}
