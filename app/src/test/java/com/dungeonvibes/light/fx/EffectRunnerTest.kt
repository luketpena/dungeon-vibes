package com.dungeonvibes.light.fx

import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FlickerSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.FxPrimitive
import com.dungeonvibes.data.db.PulseSettings
import com.dungeonvibes.data.db.Spread
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

    private val breathing = GroupEffects(
        brightnessShift = BrightnessShiftSettings(
            enabled = true,
            levelMin = 0.1f,
            levelMax = 0.9f,
            speedMinMs = 1_000,
            speedMaxMs = 1_000,
        ),
    )

    private val lightning = GroupEffects(
        flashes = FlashSettings(
            enabled = true,
            color = FxColor.white(6_500),
            peak = 1f,
            attackMs = 30,
            holdMs = 60,
            releaseMs = 250,
            gapMinMs = 1_000,
            gapMaxMs = 1_000,
        ),
    )

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
    fun burstPulses_landExactlyOnTheirEdges() = runTest {
        val rolling = lightning.copy(
            flashes = lightning.flashes.copy(pulsesMin = 3, pulsesMax = 3, spacingMinMs = 70, spacingMaxMs = 70),
        )
        val f = fixture()
        f.runner.start(listOf(group(rolling, bulb1)), startFadeMs = 0, random = Random(1))
        // Pulses start at 1000, 1410 and 1820 (340 ms long, 70 ms apart).
        val strikes = mutableListOf<Long>()
        var seen = 0
        repeat(2_000) {
            advanceTimeBy(1)
            val now = f.frames(serial1).count { it.state.brightness > 0.99f }
            if (now > seen) strikes.add(testScheduler.currentTime)
            seen = now
        }
        assertEquals(listOf(1_001L, 1_411L, 1_821L), strikes)
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

        val faster = breathing.copy(brightnessShift = breathing.brightnessShift.copy(speedMinMs = 500, speedMaxMs = 500))
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
        val rare = lightning.copy(flashes = lightning.flashes.copy(gapMinMs = 60_000, gapMaxMs = 60_000))
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
}
