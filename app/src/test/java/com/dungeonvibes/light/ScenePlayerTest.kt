package com.dungeonvibes.light

import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.FxEntity
import com.dungeonvibes.data.db.FxTransition
import com.dungeonvibes.data.db.PulseSettings
import com.dungeonvibes.data.fx.FxItem
import com.dungeonvibes.data.fx.FxRepository
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.scene.EffectLayer
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.fake.FakeLightAdapter
import com.dungeonvibes.light.fake.FakeLightAdapter.Command
import com.dungeonvibes.light.fx.realTimeEffectRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 5 checklist, automated: applying a library scene fades every slot and turns unused ones off. */
class ScenePlayerTest : DatabaseTest() {

    // Real time (runBlocking), like the other database tests; see SceneEditorViewModelTest.
    private val lightScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun stopLights() {
        lightScope.cancel()
    }

    private fun player(scenes: SceneRepository, fake: FakeLightAdapter): ScenePlayer {
        val controller = LightController(setOf(fake), lightScope)
        return ScenePlayer(scenes, controller, realTimeEffectRunner(controller, lightScope))
    }

    /* Effects (FX3) */

    private class EffectFixture(
        val player: ScenePlayer,
        val fake: FakeLightAdapter,
        val scenes: SceneRepository,
        val ceiling: Long,
        val serial: String,
    ) {
        fun frames() = fake.commands.value.filterIsInstance<Command.SetState>().filter { it.serial == serial && !it.reliable }
    }

    /** One group (Ceiling, bulb 1) and the scene's [effects] on it, over a dim blue base. */
    private suspend fun effectFixture(): EffectFixture {
        val fake = FakeLightAdapter()
        val scenes = SceneRepository(db, time)
        val slots = LightSlotRepository(db)
        val bulbs = BulbRepository(db, time)
        val ceiling = slots.createSlot("Ceiling")
        val serial = FakeLightAdapter.serialFor(1)
        slots.assignBulb(bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, serial, "0.0.0.1", "One"), ceiling)
        return EffectFixture(player(scenes, fake), fake, scenes, ceiling, serial)
    }

    private suspend fun EffectFixture.scene(name: String, effects: GroupEffects?): Long {
        val id = scenes.createLibraryScene(name)
        scenes.updateSceneDetails(id, name, fadeMs = 200)
        scenes.saveSceneLight(SceneLightEntity(id, ceiling, true, LightMode.COLOR, 240f, 1f, 2700, 0.3f))
        if (effects != null) scenes.saveGroupEffects(id, ceiling, effects)
        return id
    }

    private val breathing = GroupEffects(layers = listOf(EffectLayer.brightness(BrightnessShiftSettings(enabled = true, levelMin = 0.1f, levelMax = 0.9f, speedMinMs = 600, speedMaxMs = 600))))

    @Test
    fun play_sceneWithEffects_fadesToItsBase_thenRunsThem(): Unit = runBlocking {
        val f = effectFixture()
        val id = f.scene("Storm", breathing)

        f.player.play(id)

        // The scene's own fade comes first, reliably, to its base.
        val first = f.fake.commands.value.first { it.serial == f.serial } as Command.SetState
        assertTrue(first.reliable)
        assertEquals(0.3f, first.state.brightness)
        // Effects wait out the 200 ms scene fade, ease in over 300 ms, then animate.
        delay(1_600)
        assertTrue(f.player.effectsRunning.value)
        assertTrue("${f.frames().size} frames", f.frames().size >= 3)
    }

    @Test
    fun play_multiColorGroup_fadesInOnItsFirstPaletteColor(): Unit = runBlocking {
        val f = effectFixture()
        val multi = GroupEffects(
            colorShift = ColorShiftSettings(enabled = true),
            palette = listOf(FxColor.white(2_200).copy(brightness = 0.5f), FxColor.color(30f)),
        )
        val id = f.scene("Campfire", multi)

        f.player.play(id)

        val first = f.fake.commands.value.first { it.serial == f.serial } as Command.SetState
        assertEquals(2_200, first.state.kelvin)
        assertEquals(0.5f, first.state.brightness) // the first color's own brightness
    }

    @Test
    fun play_anotherScene_stopsTheEffects(): Unit = runBlocking {
        val f = effectFixture()
        val storm = f.scene("Storm", breathing)
        val calm = f.scene("Calm", null)
        f.player.play(storm)
        delay(800)

        f.player.play(calm)
        assertFalse(f.player.effectsRunning.value)
        f.fake.clearLog()
        delay(800)
        assertTrue(f.frames().isEmpty())
    }

    @Test
    fun play_allLights_runsTheSharedEffectsOnEveryBulb(): Unit = runBlocking {
        val f = effectFixture()
        val room = LightSlotRepository(db).ensureRoomSlot()
        val id = f.scene("Campfire", null)
        f.scenes.saveSceneLight(SceneLightEntity(id, room, true, LightMode.COLOR, 20f, 1f, 2700, 0.6f))
        f.scenes.saveGroupEffects(id, room, breathing)
        f.scenes.setAllLightsOn(id, true)

        f.player.play(id)

        // The shared look, not the ceiling's own blue, is what fades in...
        val first = f.fake.commands.value.first { it.serial == f.serial } as Command.SetState
        assertEquals(20f, first.state.hue)
        // ...and then the shared effects run.
        delay(1_600)
        assertTrue(f.player.effectsRunning.value)
        assertTrue(f.frames().size >= 3)
    }

    @Test
    fun stopEffects_settlesOnTheScenesBase(): Unit = runBlocking {
        val f = effectFixture()
        val id = f.scene("Storm", breathing)
        f.player.play(id)
        delay(800)

        f.player.stopEffects()
        delay(700)

        assertFalse(f.player.effectsRunning.value)
        val last = f.fake.commands.value.last { it.serial == f.serial } as Command.SetState
        assertTrue(last.reliable)
        assertEquals(0.3f, last.state.brightness)
    }

    @Test
    fun play_fadesLitSlots_turnsOtherSlotsOff_andLeavesUnassignedBulbsAlone(): Unit = runBlocking {
        val fake = FakeLightAdapter()
        val scenes = SceneRepository(db, time)
        val slots = LightSlotRepository(db)
        val bulbs = BulbRepository(db, time)
        val player = player(scenes, fake)

        val ceiling = slots.createSlot("Ceiling")
        val lamp = slots.createSlot("Lamp")
        val (s1, s2, s3) = (1..3).map { FakeLightAdapter.serialFor(it) }
        slots.assignBulb(bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, s1, "0.0.0.1", "One"), ceiling)
        slots.assignBulb(bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, s2, "0.0.0.2", "Two"), lamp)
        bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, s3, "0.0.0.3", "Loose") // in no slot

        val scene = scenes.createLibraryScene("Crypt")
        scenes.updateSceneDetails(scene, "Crypt", fadeMs = 2_000)
        scenes.saveSceneLight(SceneLightEntity(scene, ceiling, true, LightMode.COLOR, 220f, 1f, 2700, 0.4f))
        // Lamp has no settings in this scene, so it must turn off.

        val result = player.play(scene)!!

        assertTrue(result.failedBulbIds.isEmpty())
        assertEquals(
            listOf(
                Command.SetState(s1, LightState(220f, 1f, 0.4f, null), fadeMs = 0, reliable = true),
                Command.SetPower(s1, on = true, fadeMs = 2_000, reliable = true),
            ),
            fake.commands.value.filter { it.serial == s1 },
        )
        assertEquals(
            listOf(Command.SetPower(s2, on = false, fadeMs = 2_000, reliable = true)),
            fake.commands.value.filter { it.serial == s2 },
        )
        assertTrue(fake.commands.value.none { it.serial == s3 })
    }

    @Test
    fun play_marksTheSceneActive_andRecordsSlotsWhoseBulbsFailed(): Unit = runBlocking {
        val fake = FakeLightAdapter()
        val scenes = SceneRepository(db, time)
        val slots = LightSlotRepository(db)
        val bulbs = BulbRepository(db, time)
        val player = player(scenes, fake)
        val ceiling = slots.createSlot("Ceiling")
        val lamp = slots.createSlot("Lamp")
        slots.assignBulb(bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(1), "0.0.0.1", "One"), ceiling)
        slots.assignBulb(bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(2), "0.0.0.2", "Two"), lamp)
        fake.offlineSerials += FakeLightAdapter.serialFor(2)
        val scene = scenes.createLibraryScene("Storm")
        scenes.saveSceneLight(SceneLightEntity(scene, ceiling, true, LightMode.WHITE, 0f, 0f, 6500, 1f))
        scenes.saveSceneLight(SceneLightEntity(scene, lamp, true, LightMode.WHITE, 0f, 0f, 6500, 1f))

        player.play(scene)

        val active = player.active.value!!
        assertEquals(scene, active.sceneId)
        assertEquals(setOf(lamp), active.failedSlotIds)
        // The working bulb still got the scene.
        assertEquals(true, fake.bulb(FakeLightAdapter.serialFor(1))!!.isOn)
    }

    @Test
    fun play_newSceneReplacesTheActiveOne_andClearsOldFailures(): Unit = runBlocking {
        val fake = FakeLightAdapter()
        val scenes = SceneRepository(db, time)
        val slots = LightSlotRepository(db)
        val bulbs = BulbRepository(db, time)
        val player = player(scenes, fake)
        val ceiling = slots.createSlot("Ceiling")
        slots.assignBulb(bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(1), "0.0.0.1", "One"), ceiling)
        val first = scenes.createLibraryScene("First")
        val second = scenes.createLibraryScene("Second")
        fake.offlineSerials += FakeLightAdapter.serialFor(1)
        player.play(first)
        assertEquals(setOf(ceiling), player.active.value!!.failedSlotIds)

        fake.offlineSerials.clear()
        player.play(second)

        assertEquals(second, player.active.value!!.sceneId)
        assertTrue(player.active.value!!.failedSlotIds.isEmpty())
    }

    @Test
    fun play_missingScene_returnsNull(): Unit = runBlocking {
        val player = player(SceneRepository(db, time), FakeLightAdapter())

        assertNull(player.play(999))
    }

    /* FX (FX4a) */

    private fun fxItem(transition: FxTransition = FxTransition.RETURN, sceneId: Long? = null) = FxItem(
        FxEntity(
            id = 1,
            name = "Heal",
            sortOrder = 0,
            transition = transition,
            transitionSceneId = sceneId,
            pulse = PulseSettings(color = FxColor.color(120f), attackMs = 50, holdMs = 100, releaseMs = 300),
            createdAt = 0,
            updatedAt = 0,
        ),
    )

    @Test
    fun fireFx_return_playsOverTheActiveScene_andLeavesItActive(): Unit = runBlocking {
        val f = effectFixture()
        val calm = f.scene("Calm", null)
        f.player.play(calm)
        f.fake.clearLog()

        assertTrue(f.player.fireFx(fxItem(), adventureId = null))
        delay(1_000)

        val frames = f.frames()
        assertTrue(frames.any { kotlin.math.abs(it.state.hue - 120f) < 0.5f && it.state.brightness > 0.99f })
        // Back on the scene's dim blue.
        assertEquals(240f, frames.last().state.hue, 0.5f)
        assertEquals(0.3f, frames.last().state.brightness, 0.001f)
        assertEquals(calm, f.player.active.value?.sceneId)
        assertFalse(f.player.effectsRunning.value)
    }

    @Test
    fun fireFx_lightsOff_fadesEveryBulbOutOverTheRelease(): Unit = runBlocking {
        val f = effectFixture()
        f.player.play(f.scene("Calm", null))

        f.player.fireFx(fxItem(FxTransition.LIGHTS_OFF), adventureId = null)
        delay(1_000)

        assertTrue(Command.SetPower(f.serial, false, 300, reliable = true) in f.fake.commands.value)
        assertNull(f.player.active.value)
    }

    @Test
    fun fireFx_intoALibrarySceneTheAdventureLacks_marksItFromFx(): Unit = runBlocking {
        val f = effectFixture()
        val dawn = f.scene("Dawn", null)
        val adventure = AdventureRepository(db, time).create("Crypt")

        f.player.fireFx(fxItem(FxTransition.SCENE, dawn), adventureId = adventure)
        delay(1_000)

        val active = f.player.active.value!!
        assertEquals(dawn, active.sceneId)
        assertTrue(active.fromFx)
    }

    @Test
    fun fireFx_intoALibrarySceneTheAdventureHas_playsItsCopy(): Unit = runBlocking {
        val f = effectFixture()
        val dawn = f.scene("Dawn", null)
        val adventure = AdventureRepository(db, time).create("Crypt")
        val copy = f.scenes.addLibraryScenesToAdventure(adventure, listOf(dawn)).single()

        f.player.fireFx(fxItem(FxTransition.SCENE, dawn), adventureId = adventure)
        delay(1_000)

        val active = f.player.active.value!!
        assertEquals(copy, active.sceneId)
        assertFalse(active.fromFx)
    }

    /* Lights menu: on/off and default lighting */

    @Test
    fun turnOff_thenOn_bringsBackTheSceneThatWasShowing(): Unit = runBlocking {
        val f = effectFixture()
        val calm = f.scene("Calm", null)
        f.player.play(calm)

        f.player.turnOffLights()
        assertTrue(Command.SetPower(f.serial, false, ScenePlayer.TURN_OFF_FADE_MS, reliable = true) in f.fake.commands.value)
        assertTrue(f.player.room.value.lightsOff)
        assertNull(f.player.active.value)

        f.player.turnOnLights()
        assertEquals(calm, f.player.active.value?.sceneId)
        assertEquals(RoomLights(), f.player.room.value)
    }

    @Test
    fun turnOn_withNothingShowingBefore_showsTheDefaultLighting(): Unit = runBlocking {
        val f = effectFixture()
        f.player.turnOffLights()

        f.player.turnOnLights()

        val defaultId = f.scenes.ensureDefaultScene()
        assertEquals(defaultId, f.player.active.value?.sceneId)
        assertEquals(RoomLights(lightsOff = false, defaultOn = true), f.player.room.value)
        // All lights on, in the default warm white.
        val sent = f.fake.commands.value.filterIsInstance<Command.SetState>().last { it.serial == f.serial }
        assertEquals(2_700, sent.state.kelvin)
    }

    @Test
    fun defaultLighting_offGoesBackToTheScene(): Unit = runBlocking {
        val f = effectFixture()
        val calm = f.scene("Calm", null)
        f.player.play(calm)

        f.player.setDefaultLighting(true)
        assertTrue(f.player.room.value.defaultOn)
        assertEquals(f.scenes.ensureDefaultScene(), f.player.active.value?.sceneId)

        f.player.setDefaultLighting(false)
        assertFalse(f.player.room.value.defaultOn)
        assertEquals(calm, f.player.active.value?.sceneId)
    }

    @Test
    fun defaultLighting_survivesOffAndOn_andStillGoesBackToTheScene(): Unit = runBlocking {
        val f = effectFixture()
        val calm = f.scene("Calm", null)
        f.player.play(calm)
        f.player.setDefaultLighting(true)

        f.player.turnOffLights()
        // On/off comes first: the default can't be switched while the lights are off.
        f.player.setDefaultLighting(false)
        assertTrue(f.player.room.value.lightsOff)

        f.player.turnOnLights()
        assertTrue(f.player.room.value.defaultOn)
        f.player.setDefaultLighting(false)
        assertEquals(calm, f.player.active.value?.sceneId)
    }

    @Test
    fun defaultLighting_offWithNoSceneBefore_turnsTheLightsOff(): Unit = runBlocking {
        val f = effectFixture()
        f.player.setDefaultLighting(true)

        f.player.setDefaultLighting(false)

        assertEquals(RoomLights(lightsOff = true, defaultOn = false), f.player.room.value)
        assertNull(f.player.active.value)
    }

    @Test
    fun playingAScene_switchesTheDefaultOff_andTurnsTheLightsBackOn(): Unit = runBlocking {
        val f = effectFixture()
        val calm = f.scene("Calm", null)
        f.player.setDefaultLighting(true)
        f.player.play(calm)
        assertEquals(RoomLights(), f.player.room.value)

        f.player.turnOffLights()
        f.player.play(calm)
        assertEquals(RoomLights(), f.player.room.value)
    }

    /* Launch state and reachability (owner requests, Oct 23, 2026) */

    private suspend fun twoBulbs(slots: LightSlotRepository, bulbs: BulbRepository): Pair<Long, Long> {
        val ceiling = slots.createSlot("Ceiling")
        val one = bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(1), "0.0.0.1", "One")
        val two = bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(2), "0.0.0.2", "Two")
        slots.assignBulb(one, ceiling)
        slots.assignBulb(two, ceiling)
        return one to two
    }

    @Test
    fun syncWithBulbs_everyAssignedBulbOff_startsTheMenuOff_oneOnLeavesItOn(): Unit = runBlocking {
        val scenes = SceneRepository(db, time)
        val (one, two) = twoBulbs(LightSlotRepository(db), BulbRepository(db, time))

        val lit = player(scenes, FakeLightAdapter())
        lit.syncWithBulbs(mapOf(one to false, two to true))
        assertFalse(lit.room.value.lightsOff)

        val dark = player(scenes, FakeLightAdapter())
        dark.syncWithBulbs(mapOf(one to false, two to false))
        assertTrue(dark.room.value.lightsOff)
    }

    @Test
    fun syncWithBulbs_afterTheLightsWereUsed_changesNothing(): Unit = runBlocking {
        val scenes = SceneRepository(db, time)
        val (one, two) = twoBulbs(LightSlotRepository(db), BulbRepository(db, time))
        val player = player(scenes, FakeLightAdapter())
        player.turnOffLights()
        player.turnOnLights()

        player.syncWithBulbs(mapOf(one to false, two to false))

        assertFalse(player.room.value.lightsOff)
    }

    @Test
    fun play_tellsReachabilityWhichBulbsAnswered(): Unit = runBlocking {
        val scenes = SceneRepository(db, time)
        val slots = LightSlotRepository(db)
        val (one, two) = twoBulbs(slots, BulbRepository(db, time))
        val fake = FakeLightAdapter()
        fake.offlineSerials += FakeLightAdapter.serialFor(2)
        val controller = LightController(setOf(fake), lightScope)
        val reachability = BulbReachability()
        val player = ScenePlayer(scenes, controller, realTimeEffectRunner(controller, lightScope), reachability)
        val ceiling = slots.observeSlots().first().single().id
        val scene = scenes.createLibraryScene("Storm")
        scenes.saveSceneLight(SceneLightEntity(scene, ceiling, true, LightMode.WHITE, 0f, 0f, 6500, 1f))

        player.play(scene)

        assertFalse(reachability.state.value.isUnreachable(one))
        assertTrue(reachability.state.value.isUnreachable(two))
    }

    /* Intro FX (FX4b, owner request Oct 23, 2026) */

    @Test
    fun play_withAnIntroFx_playsItFirst_thenLandsInTheSceneOverItsRelease(): Unit = runBlocking {
        val fake = FakeLightAdapter()
        val scenes = SceneRepository(db, time)
        val slots = LightSlotRepository(db)
        val ceiling = slots.createSlot("Ceiling")
        val serial = FakeLightAdapter.serialFor(1)
        slots.assignBulb(BulbRepository(db, time).recordSeen(FakeLightAdapter.PROTOCOL_ID, serial, "0.0.0.1", "One"), ceiling)
        val fx = FxRepository(db, time)
        val heal = fx.create("Heal")
        val item = fx.get(heal)!!
        fx.save(item.copy(fx = item.fx.copy(pulse = PulseSettings(color = FxColor.color(120f), attackMs = 50, holdMs = 100, releaseMs = 300))))
        val scene = scenes.createLibraryScene("Dawn")
        scenes.updateSceneDetails(scene, "Dawn", fadeMs = 5_000)
        scenes.saveSceneLight(SceneLightEntity(scene, ceiling, true, LightMode.COLOR, 240f, 1f, 2700, 0.3f))
        scenes.setIntroFx(scene, heal)
        val controller = LightController(setOf(fake), lightScope)
        val player = ScenePlayer(scenes, controller, realTimeEffectRunner(controller, lightScope), BulbReachability(), fx)

        player.play(scene)
        // Marked active at once, while the FX plays.
        assertEquals(scene, player.active.value?.sceneId)
        delay(800)

        val commands = fake.commands.value.filter { it.serial == serial }
        assertTrue(commands.any { it is Command.SetState && !it.reliable && kotlin.math.abs(it.state.hue - 120f) < 0.5f })
        // The scene lands over the FX's 300 ms release, not its own 5 s fade.
        val landing = commands.filterIsInstance<Command.SetState>().last { it.reliable }
        assertEquals(240f, landing.state.hue, 0.5f)
        assertEquals(300, landing.fadeMs)
    }

    @Test
    fun play_withoutAFxLibrary_orIntro_appliesAsUsual(): Unit = runBlocking {
        val f = effectFixture()
        val calm = f.scene("Calm", null)
        assertTrue(f.player.play(calm) != null)
    }
}
