package com.dungeonvibes.light.fx

import com.dungeonvibes.data.scene.flashes
import com.dungeonvibes.data.scene.brightnessShift
import com.dungeonvibes.data.scene.withFlashes
import com.dungeonvibes.data.scene.withBrightnessShift
import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FlickerSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.FxPrimitive
import com.dungeonvibes.data.db.PulseSettings
import com.dungeonvibes.data.db.SpinFxSettings
import com.dungeonvibes.data.db.StrobeSettings
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.data.scene.EffectLayer
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.light.BulbAddress
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.LightState
import com.dungeonvibes.light.MonotonicClock
import com.dungeonvibes.light.NetworkStatus
import com.dungeonvibes.light.fake.FakeLightAdapter
import com.dungeonvibes.light.fake.FakeLightAdapter.Command
import kotlin.random.Random
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Virtual time throughout: the runner's clock and delays both follow the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
class EffectRunnerTest {

    private val serial1 = FakeLightAdapter.serialFor(1)
    private val serial2 = FakeLightAdapter.serialFor(2)
    private val bulb1 = BulbAddress(FakeLightAdapter.PROTOCOL_ID, serial1, "0.0.0.1")
    private val bulb2 = BulbAddress(FakeLightAdapter.PROTOCOL_ID, serial2, "0.0.0.2")
    private val base = LightState(250f, 0.9f, 0.08f, null)

    private class Fixture(val fake: FakeLightAdapter, val runner: EffectRunner, val online: MutableStateFlow<Boolean>)

    private fun TestScope.fixture(): Fixture {
        val fake = FakeLightAdapter()
        val online = MutableStateFlow(true)
        val network = object : NetworkStatus {
            override val isOnLocalNetwork = online
        }
        val runner = EffectRunner(
            LightController(setOf(fake), backgroundScope),
            network,
            MonotonicClock { testScheduler.currentTime },
            backgroundScope,
        )
        return Fixture(fake, runner, online)
    }

    private val breathing = GroupEffects(layers = listOf(EffectLayer.brightness(BrightnessShiftSettings(
            enabled = true,
            levelMin = 0.1f,
            levelMax = 0.9f,
            speedMinMs = 1_000,
            speedMaxMs = 1_000,
        ))))

    private val lightning = GroupEffects(layers = listOf(EffectLayer.flashes(FlashSettings(
            enabled = true,
            color = FxColor.white(6_500),
            peak = 1f,
            attackMs = 30,
            holdMs = 60,
            releaseMs = 250,
            gapMinMs = 1_000,
            gapMaxMs = 1_000,
        ))))

    private fun group(effects: GroupEffects, vararg bulbs: BulbAddress) = EffectGroup(1, base, effects, bulbs.toList())

    private fun Fixture.frames(serial: String) =
        fake.commands.value.filterIsInstance<Command.SetState>().filter { it.serial == serial && !it.reliable }

    @Test
    fun start_turnsBulbsOnToTheFirstFrame_thenAnimatesAtMostFiveTimesASecond() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(breathing, bulb1, bulb2)), startFadeMs = 500, random = Random(1))
        runCurrent()

        // Instant color, then a 500 ms power fade (the bulbs' power was unknown).
        val startup = f.fake.commands.value.filter { it.serial == serial1 }
        assertEquals(Command.SetPower(serial1, true, 500, reliable = true), startup.last())
        assertTrue(f.runner.isRunning.value)

        advanceTimeBy(1_000)
        f.fake.clearLog()
        advanceTimeBy(2_000)
        val frames = f.frames(serial1)
        assertTrue("${frames.size} frames in 2 s", frames.size in 6..10)
        // Each frame overlaps the next, so a late packet never leaves the bulb parked.
        assertTrue(frames.all { it.fadeMs == EffectTiming.FRAME_TRANSITION_MS.toInt() })
        assertEquals(frames.size, f.frames(serial2).size)
    }

    @Test
    fun aBulbInTwoLitGroups_keepsItsOwnersLook_andFlashesWithTheOther() = runTest {
        val f = fixture()
        val ownerBase = LightState(30f, 1f, 0.3f, null)
        // Bulb 1 is won by a still group; the storm group (which owns bulb 2) only flashes it.
        val owner = EffectGroup(1, ownerBase, GroupEffects(), listOf(bulb1))
        val storm = EffectGroup(2, base, lightning, listOf(bulb1, bulb2), flashOnly = setOf(bulb1))
        f.runner.start(listOf(owner, storm), startFadeMs = 0, random = Random(1))
        advanceTimeBy(1_600)

        val frames = f.frames(serial1)
        assertTrue("the storm's flash reaches bulb 1", frames.any { it.state.brightness > 0.99f })
        // Between flashes bulb 1 shows its owner's look, not the storm group's base.
        assertEquals(30f, frames.last().state.hue, 0.5f)
        assertEquals(0.3f, frames.last().state.brightness, 0.0001f)
        // Bulb 2 belongs to the storm group.
        assertEquals(0.08f, f.frames(serial2).last().state.brightness, 0.0001f)
    }

    @Test
    fun flashes_areSentOnTheirEdges_soAShortAttackStaysCrisp() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(lightning, bulb1)), startFadeMs = 0, random = Random(1))
        advanceTimeBy(1_600)

        val frames = f.frames(serial1)
        val strike = frames.first { it.state.brightness > 0.99f }
        assertEquals(30, strike.fadeMs) // the whole attack, sent the moment it starts
        assertEquals(6_500, strike.state.kelvin)
        // After the release the bulb is back on the dim base.
        assertEquals(0.08f, frames.last().state.brightness, 0.0001f)
    }

    @Test
    fun flickerPulses_landExactlyOnTheirEdges() = runTest {
        val rolling = lightning.withFlashes(lightning.flashes.withFlicker(true))
        val f = fixture()
        f.runner.start(listOf(group(rolling, bulb1)), startFadeMs = 0, random = Random(1))
        // Each pulse snaps on (fade 0) the moment it starts; the frame before it is the dim 0.08
        // base (or a release heading there), so a pulse shows as a jump above 0.1.
        val starts = mutableListOf<Long>()
        val tops = mutableListOf<Float>()
        var seen = 0
        var previous = 0f
        repeat(2_000) {
            advanceTimeBy(1)
            val frames = f.frames(serial1)
            for (i in seen until frames.size) {
                val frame = frames[i]
                val brightness = frame.state.brightness
                if (brightness > 0.1f && previous <= 0.1f) {
                    assertEquals(0, frame.fadeMs)
                    starts.add(testScheduler.currentTime)
                    tops.add(brightness)
                }
                previous = brightness
            }
            seen = frames.size
        }
        // 2 or 3 pulses from 1000 ms; all but the last are 100 ms long (40 hold, 60 release) and
        // the next starts 5–10 ms after. One of them is the full strike.
        assertTrue("$starts", starts.size in 2..3)
        assertEquals(1_001L, starts[0])
        for (i in 1 until starts.size) assertTrue("$starts", starts[i] - starts[i - 1] in 105L..110L)
        assertEquals("$tops", 1, tops.count { it > 0.99f })
        assertTrue("$tops", tops.last() > 0.99f) // the strike comes last
    }

    @Test
    fun update_withOnlyANewBase_keepsTheRunGoing_andShowsTheBaseAtOnce() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(lightning, bulb1)), startFadeMs = 0, random = Random(1))
        advanceTimeBy(300)
        f.fake.clearLog()

        val brighter = base.copy(brightness = 0.3f)
        f.runner.update(listOf(EffectGroup(1, brighter, lightning, listOf(bulb1))), startFadeMs = 0)
        advanceTimeBy(5)

        // No restart (nothing reliable), just a frame with the new base.
        assertTrue(f.fake.commands.value.none { it is Command.SetState && it.reliable })
        assertEquals(0.3f, f.frames(serial1).single().state.brightness, 0.0001f)
        // The strike still lands where the original timeline put it (1000 ms in).
        advanceTimeBy(1_000)
        assertTrue(f.frames(serial1).any { it.state.brightness > 0.99f })
    }

    @Test
    fun update_withChangedEffects_restartsJustThatGroup() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(breathing, bulb1), group(lightning, bulb2).copy(slotId = 2)), startFadeMs = 0, random = Random(1))
        advanceTimeBy(500)
        f.fake.clearLog()

        val faster = breathing.withBrightnessShift(breathing.brightnessShift.copy(speedMinMs = 500, speedMaxMs = 500))
        f.runner.update(
            listOf(group(faster, bulb1), group(lightning, bulb2).copy(slotId = 2)),
            startFadeMs = 0,
        )
        advanceTimeBy(5)

        val reliable = f.fake.commands.value.filter { it is Command.SetState && it.reliable }
        assertEquals(listOf(serial1), reliable.map { it.serial })
    }

    @Test
    fun update_withNothingLeft_stopsWithoutSettling() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(breathing, bulb1)), startFadeMs = 0, random = Random(1))
        advanceTimeBy(500)
        f.runner.update(listOf(group(GroupEffects(), bulb1)), startFadeMs = 0)
        runCurrent()
        f.fake.clearLog()
        advanceTimeBy(1_000)
        assertFalse(f.runner.isRunning.value)
        assertTrue(f.fake.commands.value.isEmpty())
    }

    @Test
    fun triggerFlash_strikesImmediately() = runTest {
        val f = fixture()
        val rare = lightning.withFlashes(lightning.flashes.copy(gapMinMs = 60_000, gapMaxMs = 60_000))
        f.runner.start(listOf(group(rare, bulb1)), startFadeMs = 0, random = Random(1))
        advanceTimeBy(500)
        f.fake.clearLog()

        f.runner.triggerFlash(slotId = 1)
        advanceTimeBy(5)
        val strike = f.frames(serial1).single()
        assertEquals(1f, strike.state.brightness, 0.0001f)
        assertEquals(30, strike.fadeMs)
    }

    @Test
    fun stopIfCurrent_ignoresARunThatWasReplaced() = runTest {
        val f = fixture()
        val first = f.runner.start(listOf(group(breathing, bulb1)), startFadeMs = 0, random = Random(1))
        f.runner.start(listOf(group(breathing, bulb2)), startFadeMs = 0, random = Random(1))
        f.runner.stopIfCurrent(first)
        assertTrue(f.runner.isRunning.value)
    }

    @Test
    fun startDelay_holdsEverythingBack_andStopDuringItSendsNothing() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(breathing, bulb1)), startFadeMs = 0, random = Random(1), startDelayMs = 1_000)
        advanceTimeBy(900)
        assertTrue(f.fake.commands.value.isEmpty())
        assertTrue(f.runner.isRunning.value)

        f.runner.stop(settleFadeMs = -1)
        advanceTimeBy(2_000)
        assertTrue(f.fake.commands.value.isEmpty())
    }

    @Test
    fun stop_fadesBulbsBackToTheBase_andSendsNothingAfter() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(breathing, bulb1)), startFadeMs = 0, random = Random(1))
        advanceTimeBy(1_000)

        f.runner.stop(settleFadeMs = 500)
        advanceTimeBy(100)
        assertFalse(f.runner.isRunning.value)
        assertEquals(Command.SetState(serial1, base, 500, reliable = true), f.fake.commands.value.last())

        f.fake.clearLog()
        advanceTimeBy(2_000)
        assertTrue(f.fake.commands.value.isEmpty())
    }

    @Test
    fun offWifi_pausesSending_andResumesByItself() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(breathing, bulb1)), startFadeMs = 0, random = Random(1))
        advanceTimeBy(1_000)

        f.online.value = false
        runCurrent()
        f.fake.clearLog()
        advanceTimeBy(2_000)
        assertTrue(f.fake.commands.value.isEmpty())
        assertTrue(f.runner.isRunning.value)

        f.online.value = true
        advanceTimeBy(300)
        assertTrue(f.frames(serial1).isNotEmpty())
    }

    @Test
    fun groupsWithoutEffectsOrBulbs_areSkipped() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(GroupEffects(), bulb1), group(breathing)), startFadeMs = 0)
        advanceTimeBy(1_000)
        assertFalse(f.runner.isRunning.value)
        assertTrue(f.fake.commands.value.isEmpty())
    }

    @Test
    fun startingAgain_replacesTheCurrentRun() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(breathing, bulb1)), startFadeMs = 0, random = Random(1))
        advanceTimeBy(500)
        f.runner.start(listOf(group(breathing, bulb2)), startFadeMs = 0, random = Random(1))
        runCurrent()
        f.fake.clearLog()
        advanceTimeBy(2_000)
        assertTrue(f.frames(serial1).isEmpty())
        assertTrue(f.frames(serial2).isNotEmpty())
    }

    /* FX */

    private val heal = PulseSettings(
        color = FxColor.color(120f, 1f),
        attackMs = 100,
        holdMs = 200,
        releaseMs = 400,
    )

    private fun fxFire(
        vararg targets: FxTarget,
        spread: Spread = Spread.TOGETHER,
        onRelease: (suspend (Int) -> Unit)? = null,
    ) = FxFire(FxPrimitive.PULSE, heal, FlickerSettings(), spread, 500, targets.toList(), onRelease)

    private fun lit(address: BulbAddress) = FxTarget(1, address, base, wasOff = false)

    @Test
    fun fire_overAStaticScene_playsAndReturnsToTheBase_thenEnds() = runTest {
        val f = fixture()
        f.runner.fire(fxFire(lit(bulb1)), Random(1))
        runCurrent()
        assertTrue(f.runner.isRunning.value)
        advanceTimeBy(200)
        val peak = f.frames(serial1).last()
        assertEquals(1f, peak.state.brightness, 0.001f)
        assertEquals(120f, peak.state.hue, 0.5f)

        advanceTimeBy(1_000)
        // Back on the scene, and the FX-only run is over.
        assertEquals(base.brightness, f.frames(serial1).last().state.brightness, 0.001f)
        assertEquals(250f, f.frames(serial1).last().state.hue, 0.5f)
        assertFalse(f.runner.isRunning.value)
        f.fake.clearLog()
        advanceTimeBy(2_000)
        assertTrue(f.fake.commands.value.isEmpty())
    }

    @Test
    fun fire_overRunningEffects_playsOnTop_andTheEffectsCarryOn() = runTest {
        val f = fixture()
        f.runner.start(listOf(group(breathing, bulb1)), startFadeMs = 0, random = Random(1))
        advanceTimeBy(500)
        f.runner.fire(fxFire(lit(bulb1)), Random(2))
        advanceTimeBy(200)
        assertEquals(120f, f.frames(serial1).last().state.hue, 0.5f)
        advanceTimeBy(1_000)
        f.fake.clearLog()
        advanceTimeBy(1_000)
        // The breathing is back on its own color, still running.
        val frames = f.frames(serial1)
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { kotlin.math.abs(it.state.hue - 250f) < 0.5f })
        assertTrue(f.runner.isRunning.value)
    }

    @Test
    fun fire_onABulbItsSceneHasOff_powersItForTheFx_andOffAfter() = runTest {
        val f = fixture()
        val dark = FxTarget(1, bulb1, base.copy(brightness = 0f), wasOff = true)
        f.runner.fire(fxFire(dark), Random(1))
        runCurrent()
        assertTrue(Command.SetPower(serial1, true, 0, reliable = true) in f.fake.commands.value)
        advanceTimeBy(1_200)
        assertEquals(Command.SetPower(serial1, false, 0, reliable = true), f.fake.commands.value.filterIsInstance<Command.SetPower>().last())
        assertFalse(f.runner.isRunning.value)
    }

    @Test
    fun fire_wave_startsEachBulbItsOffsetLater() = runTest {
        val f = fixture()
        f.runner.fire(fxFire(lit(bulb1), lit(bulb2), spread = Spread.WAVE), Random(1))
        advanceTimeBy(200)
        assertEquals(120f, f.frames(serial1).last().state.hue, 0.5f)
        assertTrue(f.frames(serial2).none { it.state.brightness > 0.5f })
        advanceTimeBy(500)
        assertEquals(120f, f.frames(serial2).last().state.hue, 0.5f)
    }

    @Test
    fun fire_transition_runsAsTheFinalReleaseStarts_withItsLength() = runTest {
        val f = fixture()
        val calls = mutableListOf<Pair<Long, Int>>()
        f.runner.fire(fxFire(lit(bulb1)) { ms -> calls.add(testScheduler.currentTime to ms) }, Random(1))
        advanceTimeBy(1_500)
        // Attack 100 + hold 200: the release (400 ms) starts at 300.
        assertEquals(listOf(300L to 400), calls)
    }

    @Test
    fun stop_duringAnFx_settlesItsBulbsBackOnTheirScene() = runTest {
        val f = fixture()
        val dark = FxTarget(1, bulb2, base.copy(brightness = 0f), wasOff = true)
        f.runner.fire(fxFire(lit(bulb1), dark), Random(1))
        advanceTimeBy(150)
        f.fake.clearLog()
        f.runner.stop(settleFadeMs = 500)
        runCurrent()
        val commands = f.fake.commands.value
        assertTrue(Command.SetPower(serial2, false, 500, reliable = true) in commands)
        assertTrue(commands.any { it is Command.SetState && it.serial == serial1 && kotlin.math.abs(it.state.brightness - base.brightness) < 0.001f })
    }

    @Test
    fun fire_whileASceneFadesIn_playsAtOnce_andTheSceneEffectsStartAfter() = runTest {
        val f = fixture()
        // A scene with effects waits out its 2 s fade before animating.
        f.runner.start(listOf(group(breathing, bulb1)), startFadeMs = 0, random = Random(1), startDelayMs = 2_000)
        advanceTimeBy(500)
        f.runner.fire(fxFire(lit(bulb1)), Random(2))
        advanceTimeBy(200)
        // Playing now, not after the fade.
        val peak = f.frames(serial1).last()
        assertEquals(120f, peak.state.hue, 0.5f)
        assertEquals(1f, peak.state.brightness, 0.001f)

        advanceTimeBy(2_000)
        f.fake.clearLog()
        advanceTimeBy(1_000)
        // The breathing took over on its own color.
        val frames = f.frames(serial1)
        assertTrue(frames.isNotEmpty())
        assertTrue(frames.all { kotlin.math.abs(it.state.hue - 250f) < 0.5f })
        assertTrue(f.runner.isRunning.value)
    }

    @Test
    fun fire_whileASceneFadesIn_itsTransitionRunsOnce() = runTest {
        val f = fixture()
        val calls = mutableListOf<Int>()
        f.runner.start(listOf(group(breathing, bulb1)), startFadeMs = 0, random = Random(1), startDelayMs = 400)
        f.runner.fire(fxFire(lit(bulb1)) { ms -> calls.add(ms) }, Random(2))
        advanceTimeBy(2_000)
        assertEquals(listOf(400), calls)
    }

    @Test
    fun fire_offWifi_anFxOnlyRunStillEnds() = runTest {
        val f = fixture()
        f.online.value = false
        f.runner.fire(fxFire(lit(bulb1)), Random(1))
        runCurrent()
        assertTrue(f.runner.isRunning.value)
        advanceTimeBy(1_500)
        assertFalse(f.runner.isRunning.value)
    }

    @Test
    fun fire_strobe_flashesOnAndOffForItsDuration() = runTest {
        val f = fixture()
        val strobe = StrobeSettings(color = FxColor.white(6_500), level = 1f, flashesPerSecond = 2f, durationMs = 1_000)
        f.runner.fire(
            FxFire(FxPrimitive.STROBE, heal, FlickerSettings(), Spread.TOGETHER, 500, listOf(lit(bulb1)), strobe = strobe),
            Random(1),
        )
        advanceTimeBy(1_500)
        val levels = f.frames(serial1).map { it.state.brightness }
        // On, off, on, off (250 ms each), then back to the scene.
        assertTrue(levels.count { it > 0.99f } >= 2)
        assertTrue(levels.count { it < 0.01f } >= 2)
        assertEquals(base.brightness, levels.last(), 0.001f)
        assertFalse(f.runner.isRunning.value)
    }

    @Test
    fun fire_spin_lightsEachBulbInTurn() = runTest {
        val f = fixture()
        val spin = SpinFxSettings(color = FxColor.color(270f), level = 1f, attackMs = 0, holdMs = 250, releaseMs = 0, stepMs = 300, laps = 1)
        f.runner.fire(
            FxFire(FxPrimitive.SPIN, heal, FlickerSettings(), Spread.TOGETHER, 500, listOf(lit(bulb1), lit(bulb2)), spin = spin),
            Random(1),
        )
        advanceTimeBy(150)
        assertEquals(270f, f.frames(serial1).last().state.hue, 0.5f)
        assertTrue(f.frames(serial2).none { kotlin.math.abs(it.state.hue - 270f) < 0.5f })
        advanceTimeBy(300)
        assertEquals(270f, f.frames(serial2).last().state.hue, 0.5f)
        assertEquals(250f, f.frames(serial1).last().state.hue, 0.5f)
    }
}
