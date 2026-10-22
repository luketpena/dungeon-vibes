package com.dungeonvibes.light

import com.dungeonvibes.data.db.BulbApplyTarget
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.light.fake.FakeLightAdapter
import com.dungeonvibes.light.fake.FakeLightAdapter.Command
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LightControllerTest {

    private val serial1 = FakeLightAdapter.serialFor(1)
    private val serial2 = FakeLightAdapter.serialFor(2)
    private val bulb1 = BulbAddress(FakeLightAdapter.PROTOCOL_ID, serial1, "0.0.0.1")

    /** The controller's long-lived scope is the test's background scope, cancelled at the end. */
    private fun TestScope.controllerWith(fake: LightAdapter): LightController =
        LightController(setOf(fake), backgroundScope)

    private fun target(bulbId: Long, serial: String, isOn: Boolean, hue: Float = 0f) = BulbApplyTarget(
        bulbId = bulbId,
        protocolId = FakeLightAdapter.PROTOCOL_ID,
        serial = serial,
        lastKnownIp = "0.0.0.$bulbId",
        slotId = 1,
        isOn = isOn,
        mode = if (isOn) LightMode.COLOR else null,
        hue = if (isOn) hue else null,
        saturation = if (isOn) 1f else null,
        kelvin = if (isOn) 2700 else null,
        brightness = if (isOn) 0.5f else null,
    )

    /* Applying a scene */

    @Test
    fun apply_turnsOnWithInstantColorThenPowerFade_andTurnsOthersOff() = runTest {
        val fake = FakeLightAdapter()
        val controller = controllerWith(fake)

        val result = controller.applyScene(
            listOf(target(1, serial1, isOn = true, hue = 30f), target(2, serial2, isOn = false)),
            fadeMs = 800,
        ).await()

        assertTrue(result.failedBulbIds.isEmpty())
        val bulb1Commands = fake.commands.value.filter { it.serial == serial1 }
        assertEquals(
            listOf(
                Command.SetState(serial1, LightState(30f, 1f, 0.5f, null), fadeMs = 0, reliable = true),
                Command.SetPower(serial1, on = true, fadeMs = 800, reliable = true),
            ),
            bulb1Commands,
        )
        assertEquals(
            listOf(Command.SetPower(serial2, on = false, fadeMs = 800, reliable = true)),
            fake.commands.value.filter { it.serial == serial2 },
        )
    }

    @Test
    fun apply_toABulbKnownToBeOn_fadesTheColorDirectly() = runTest {
        val fake = FakeLightAdapter()
        val controller = controllerWith(fake)
        controller.applyScene(listOf(target(1, serial1, isOn = true, hue = 30f)), fadeMs = 0).await()
        fake.clearLog()

        controller.applyScene(listOf(target(1, serial1, isOn = true, hue = 200f)), fadeMs = 1_000).await()

        assertEquals(
            listOf(Command.SetState(serial1, LightState(200f, 1f, 0.5f, null), fadeMs = 1_000, reliable = true)),
            fake.commands.value,
        )
    }

    @Test
    fun apply_whiteModeTarget_sendsKelvin() = runTest {
        val fake = FakeLightAdapter()
        val controller = controllerWith(fake)
        val white = target(1, serial1, isOn = true).copy(mode = LightMode.WHITE, kelvin = 4000)

        controller.applyScene(listOf(white), fadeMs = 0).await()

        val state = (fake.commands.value.first() as Command.SetState).state
        assertEquals(4000, state.kelvin)
    }

    @Test
    fun apply_offlineBulb_doesNotStopTheOthers_andIsReported() = runTest {
        val fake = FakeLightAdapter()
        fake.offlineSerials += serial2
        val controller = controllerWith(fake)

        val result = controller.applyScene(
            listOf(target(1, serial1, isOn = true), target(2, serial2, isOn = true)),
            fadeMs = 500,
        ).await()

        assertEquals(listOf(2L), result.failedBulbIds)
        assertEquals(setOf(1L), result.failedSlotIds) // both test targets are in slot 1
        assertEquals(true, fake.bulb(serial1)!!.isOn)
    }

    @Test
    fun rapidApplies_onlyTheLastSceneIsApplied() = runTest {
        val fake = FakeLightAdapter(commandDelayMs = 100)
        val controller = controllerWith(fake)

        val first = controller.applyScene(listOf(target(1, serial1, isOn = true, hue = 0f)), fadeMs = 500)
        runCurrent()
        val second = controller.applyScene(listOf(target(1, serial1, isOn = true, hue = 240f)), fadeMs = 500)
        val result = second.await()

        assertTrue(first.isCancelled)
        try {
            first.await()
            fail("The first apply should have been cancelled")
        } catch (expected: CancellationException) {
        }
        assertTrue(result.failedBulbIds.isEmpty())
        val sentHues = fake.commands.value.filterIsInstance<Command.SetState>().map { it.state.hue }
        assertEquals(listOf(240f), sentHues)
        assertEquals(240f, fake.bulb(serial1)!!.state.hue)
    }

    @Test
    fun emptyScene_succeedsWithoutCommands() = runTest {
        val fake = FakeLightAdapter()

        val result = controllerWith(fake).applyScene(emptyList(), fadeMs = 0).await()

        assertTrue(result.failedBulbIds.isEmpty())
        assertTrue(fake.commands.value.isEmpty())
    }

    /* Live preview */

    @Test
    fun preview_sendsOnlyTheNewestValue_fireAndForget() = runTest {
        val fake = FakeLightAdapter()
        val controller = controllerWith(fake)

        for (i in 0 until 20) {
            controller.preview(bulb1, LightState(i * 10f, 1f, 1f, null))
        }
        // The preview sender lives in backgroundScope, which advanceUntilIdle() skips; moving the
        // virtual clock forward runs it.
        advanceTimeBy(500)

        val states = fake.commands.value.filterIsInstance<Command.SetState>()
        assertEquals(1, states.size)
        assertEquals(190f, states.single().state.hue)
        assertFalse(states.single().reliable)
        // Bulb wasn't known to be on: instant color, then a 100 ms power-up.
        assertEquals(
            Command.SetPower(serial1, on = true, fadeMs = LightController.PREVIEW_FADE_MS, reliable = false),
            fake.commands.value.last(),
        )
    }

    @Test
    fun preview_sendsAtMostTenUpdatesPerSecond_withA100msTransition() = runTest {
        val fake = FakeLightAdapter()
        val controller = controllerWith(fake)
        controller.preview(bulb1, LightState(0f, 1f, 1f, null))
        advanceTimeBy(200)
        fake.clearLog()

        // A slider dragged for one second, reporting every 20 ms (50 changes).
        for (i in 1..50) {
            controller.preview(bulb1, LightState(i.toFloat(), 1f, 1f, null))
            delay(20)
        }
        advanceTimeBy(500)

        val states = fake.commands.value.filterIsInstance<Command.SetState>()
        assertTrue("sent ${states.size}", states.size in 9..11)
        assertTrue(states.all { it.fadeMs == LightController.PREVIEW_FADE_MS && !it.reliable })
        // The final position is always delivered.
        assertEquals(50f, states.last().state.hue)
    }

    @Test
    fun preview_null_turnsTheBulbOff() = runTest {
        val fake = FakeLightAdapter()
        val controller = controllerWith(fake)

        controller.preview(bulb1, null)
        advanceTimeBy(500)

        assertEquals(
            listOf(Command.SetPower(serial1, on = false, fadeMs = LightController.PREVIEW_FADE_MS, reliable = false)),
            fake.commands.value,
        )
    }

    /* Routing and availability */

    @Test
    fun disabledAdapter_isSkippedByDiscovery_andCommandsFail() = runTest {
        val fake = FakeLightAdapter(isEnabled = { false })
        val controller = controllerWith(fake)

        assertTrue(controller.discover(1_000).toList().isEmpty())
        assertTrue(controller.turnOn(bulb1, LightState(0f, 1f, 1f, null), 0).isFailure)
    }

    @Test
    fun unknownProtocol_fails() = runTest {
        val controller = controllerWith(FakeLightAdapter())

        val result = controller.turnOff(BulbAddress("hue_bridge", "x", "1.2.3.4"), 0)

        assertTrue(result.isFailure)
    }

    @Test
    fun discovery_teachesPowerState_soTheNextTurnOnFadesColorDirectly() = runTest {
        val fake = FakeLightAdapter()
        val controller = controllerWith(fake)
        controller.turnOn(bulb1, LightState(0f, 1f, 1f, null), 0)
        // A fresh controller only knows what discovery tells it.
        val fresh = controllerWith(fake)
        fresh.discover(1_000).toList()
        fake.clearLog()

        fresh.turnOn(bulb1, LightState(90f, 1f, 1f, null), 700)

        assertEquals(
            listOf(Command.SetState(serial1, LightState(90f, 1f, 1f, null), fadeMs = 700, reliable = true)),
            fake.commands.value,
        )
    }

    /* Mirror (what the screen follows) */

    @Test
    fun everyCommand_isMirroredWithItsFade() = runTest {
        val mirror = LightMirror()
        val controller = LightController(setOf(FakeLightAdapter()), backgroundScope, mirror)
        val sent = mirror.observe(LightMirror.keyOf(FakeLightAdapter.PROTOCOL_ID, serial1))
        assertNull(sent.value)

        val red = LightState(0f, 1f, 0.5f, null)
        controller.turnOn(bulb1, red, fadeMs = 800)
        assertEquals(red, sent.value!!.color)
        assertEquals(800, sent.value!!.fadeMs)

        val blue = LightState(240f, 1f, 0.7f, null)
        controller.sendEffectFrame(bulb1, blue, transitionMs = 300)
        assertEquals(blue, sent.value!!.color)
        assertEquals(300, sent.value!!.fadeMs)

        controller.turnOff(bulb1, fadeMs = 500)
        assertNull(sent.value!!.color)
        assertEquals(500, sent.value!!.fadeMs)
    }
}
