package com.dungeonvibes.ui.scenes.editor

import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.MonotonicClock
import com.dungeonvibes.light.NetworkStatus
import com.dungeonvibes.light.ScenePlayer
import com.dungeonvibes.light.fx.EffectRunner
import com.dungeonvibes.light.fake.FakeLightAdapter
import com.dungeonvibes.light.fake.FakeLightAdapter.Command
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs on real time with runBlocking, like the other database tests: Room works on real background
 * threads, which doesn't mix reliably with the virtual clock under Robolectric. The save delay is
 * 0.4 s, so each test waits at most a second or so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SceneEditorViewModelTest : DatabaseTest() {

    private val serial1 = FakeLightAdapter.serialFor(1)
    private val serial2 = FakeLightAdapter.serialFor(2)

    private class Fixture(
        val viewModel: SceneEditorViewModel,
        val player: ScenePlayer,
        val fake: FakeLightAdapter,
        val scenes: SceneRepository,
        val sceneId: Long,
        val ceiling: Long,
        val lamp: Long,
    )

    /**
     * A scene with a lit red Ceiling (bulb 1) and no settings for Lamp (bulb 2). Previews run on an
     * unconfined scope, so the first one for a bulb is sent immediately.
     */
    private fun editorTest(
        sceneIdOverride: (suspend (SceneRepository, AdventureRepository, Long) -> Long)? = null,
        beforeOpen: (suspend (ScenePlayer, SceneRepository) -> Unit)? = null,
        block: suspend (Fixture) -> Unit,
    ): Unit = runBlocking {
        // One thread plays the app's main thread: the ViewModel's state is only ever touched there.
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val lightScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            withContext(main) { runFixture(sceneIdOverride, beforeOpen, lightScope, block) }
        } finally {
            lightScope.cancel()
            Dispatchers.resetMain()
            main.close()
        }
    }

    private suspend fun runFixture(
        sceneIdOverride: (suspend (SceneRepository, AdventureRepository, Long) -> Long)?,
        beforeOpen: (suspend (ScenePlayer, SceneRepository) -> Unit)?,
        lightScope: CoroutineScope,
        block: suspend (Fixture) -> Unit,
    ) {
        run {
            val fake = FakeLightAdapter()
            val scenes = SceneRepository(db, time)
            val adventures = AdventureRepository(db, time)
            val slots = LightSlotRepository(db)
            val bulbs = BulbRepository(db, time)
            val ceiling = slots.createSlot("Ceiling")
            val lamp = slots.createSlot("Lamp")
            slots.assignBulb(bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, serial1, "0.0.0.1", "One"), ceiling)
            slots.assignBulb(bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, serial2, "0.0.0.2", "Two"), lamp)
            val library = scenes.createLibraryScene("Forge")
            scenes.saveSceneLight(SceneLightEntity(library, ceiling, true, LightMode.COLOR, 0f, 1f, 2700, 1f))
            val sceneId = sceneIdOverride?.invoke(scenes, adventures, library) ?: library

            val controller = LightController(setOf(fake), lightScope)
            val network = object : NetworkStatus {
                override val isOnLocalNetwork = MutableStateFlow(true)
            }
            val runner = EffectRunner(controller, network, MonotonicClock { System.nanoTime() / 1_000_000 }, lightScope)
            val player = ScenePlayer(scenes, controller, runner)
            beforeOpen?.invoke(player, scenes)
            val viewModel = SceneEditorViewModel(
                sceneId = sceneId,
                scenes = scenes,
                slots = slots,
                bulbs = bulbs,
                lights = controller,
                effectRunner = runner,
                player = player,
                appScope = lightScope,
            )
            try {
                withTimeout(TIMEOUT_MS) {
                    // Wait until the scene and the slot-to-bulb map are loaded.
                    viewModel.uiState.first { it.isLoaded && it.rows.size == 2 && it.rows.all { row -> row.hasBulbs } }
                    // Opening takes over the bulbs; let that settle and start each test from a clean log
                    // (previews go out at most every 100 ms per bulb).
                    player.active.first { it?.sceneId == sceneId }
                    delay(250)
                    fake.clearLog()
                    block(Fixture(viewModel, player, fake, scenes, sceneId, ceiling, lamp))
                }
            } finally {
                // Stop the ViewModel's database listeners before the test closes the database.
                viewModel.viewModelScope.cancel()
            }
        }
    }

    /** Waits for the database to hold a light for [slotId] matching [predicate]. */
    private suspend fun Fixture.storedLight(slotId: Long, predicate: (SceneLightEntity) -> Boolean): SceneLightEntity =
        scenes.observeSceneLights(sceneId).first { list -> list.any { it.slotId == slotId && predicate(it) } }
            .single { it.slotId == slotId }

    @Test
    fun hueChange_previewsInstantly_andSavesShortlyAfter() = editorTest { f ->
        f.viewModel.setHue(f.ceiling, 120f)

        val preview = f.fake.commands.value.filterIsInstance<Command.SetState>().last()
        assertEquals(serial1, preview.serial)
        assertEquals(120f, preview.state.hue)
        assertFalse(preview.reliable)
        // Lamp's bulb isn't touched by a Ceiling change.
        assertTrue(f.fake.commands.value.none { it.serial == serial2 })

        assertEquals(120f, f.storedLight(f.ceiling) { it.hue == 120f }.hue)
    }

    @Test
    fun changes_areNotWrittenUntilEditingPauses() = editorTest { f ->
        f.viewModel.setBrightness(f.ceiling, 0.3f)
        delay(100)

        // Still the original value: the save waits for a pause.
        assertEquals(1f, f.scenes.getSceneLights(f.sceneId).single().brightness)
        assertEquals(0.3f, f.storedLight(f.ceiling) { it.brightness == 0.3f }.brightness)
    }

    @Test
    fun turningOnASlotWithNoSettings_startsAsWarmWhite2700K() = editorTest { f ->
        f.viewModel.setOn(f.lamp, true)

        val saved = f.storedLight(f.lamp) { it.isOn }
        assertEquals(LightMode.WHITE, saved.mode)
        assertEquals(2700, saved.kelvin)
        val previewed = f.fake.commands.value.filterIsInstance<Command.SetState>().single { it.serial == serial2 }
        assertEquals(2700, previewed.state.kelvin)
        assertEquals(true, f.fake.bulb(serial2)!!.isOn)
    }

    @Test
    fun turningASlotOff_previewsOff_andKeepsItsColor() = editorTest { f ->
        f.viewModel.setOn(f.ceiling, false)

        assertEquals(
            Command.SetPower(serial1, on = false, fadeMs = LightController.PREVIEW_FADE_MS, reliable = false),
            f.fake.commands.value.last { it.serial == serial1 },
        )
        val saved = f.storedLight(f.ceiling) { !it.isOn }
        assertEquals(LightMode.COLOR, saved.mode)
        assertEquals(1f, saved.saturation)
    }

    @Test
    fun withLivePreviewOff_nothingIsSentButChangesStillSave() = editorTest { f ->
        f.viewModel.setLivePreview(false)
        f.viewModel.setBrightness(f.ceiling, 0.25f)

        assertEquals(0.25f, f.storedLight(f.ceiling) { it.brightness == 0.25f }.brightness)
        assertTrue(f.fake.commands.value.isEmpty())
    }

    @Test
    fun switchingToWhiteAndBack_keepsTheColorValues() = editorTest { f ->
        f.viewModel.setHue(f.ceiling, 200f)
        f.viewModel.setMode(f.ceiling, LightMode.WHITE)
        f.viewModel.setKelvin(f.ceiling, 4000)
        f.viewModel.setMode(f.ceiling, LightMode.COLOR)

        val saved = f.storedLight(f.ceiling) { it.kelvin == 4000 && it.mode == LightMode.COLOR }
        assertEquals(200f, saved.hue)
    }

    @Test
    fun rapidChanges_endWithTheFinalValueSaved() = editorTest { f ->
        for (hue in 1..30) {
            f.viewModel.setHue(f.ceiling, hue.toFloat())
            delay(10) // a fast slider drag, quicker than the save delay
        }

        assertEquals(30f, f.storedLight(f.ceiling) { it.hue == 30f }.hue)
    }

    @Test
    fun nameAndFade_saveAutomatically() = editorTest { f ->
        f.viewModel.setName("Dwarven forge")
        f.viewModel.setFadeMs(2_500)

        val saved = f.scenes.observeScene(f.sceneId).first { it?.name == "Dwarven forge" }!!
        assertEquals(2_500, saved.fadeMs)
    }

    @Test
    fun deletedWhileOpen_showsMissing() = editorTest { f ->
        f.scenes.deleteScene(f.sceneId)

        assertTrue(f.viewModel.uiState.first { it.isMissing }.isMissing)
    }

    @Test
    fun adventureCopy_canResetToItsLibraryScene() = editorTest(
        sceneIdOverride = { scenes, adventures, library ->
            scenes.addLibraryScenesToAdventure(adventures.create("Mine"), listOf(library)).single()
        },
    ) { f ->
        assertTrue(f.viewModel.uiState.first { it.canReset }.canReset)
        assertFalse(f.viewModel.uiState.value.isLibraryScene)

        f.viewModel.setHue(f.ceiling, 90f)
        f.storedLight(f.ceiling) { it.hue == 90f }

        f.viewModel.resetToLibraryVersion()

        val reset = f.viewModel.uiState.first { state -> state.rows.any { it.light?.hue == 0f } }
        assertEquals(0f, reset.rows.first { it.slot.id == f.ceiling }.light!!.hue)
    }

    /* Effects */

    private val breathing = GroupEffects(
        brightnessShift = BrightnessShiftSettings(
            enabled = true,
            levelMin = 0.1f,
            levelMax = 0.9f,
            speedMinMs = 600,
            speedMaxMs = 600,
        ),
    )

    private fun Fixture.effectFrames(serial: String) =
        fake.commands.value.filterIsInstance<Command.SetState>().filter { it.serial == serial && !it.reliable }

    @Test
    fun effects_saveShortlyAfterChange() = editorTest { f ->
        f.viewModel.setLivePreview(false)
        f.viewModel.setEffects(f.ceiling, breathing)

        // Effect rows aren't observed as a flow here, so poll until the debounced save lands.
        while (f.scenes.getSceneEffects(f.sceneId).isEmpty()) delay(50)
        assertEquals(breathing, f.scenes.getSceneEffects(f.sceneId)[f.ceiling])
        assertEquals(breathing, f.row(f.ceiling) { it.effects != null }.effects)
    }

    @Test
    fun effects_needTheGroupsSettingsFirst() = editorTest { f ->
        f.viewModel.setEffects(f.lamp, breathing)
        delay(SceneEditorViewModel.SAVE_DEBOUNCE_MS + 300)

        assertTrue(f.scenes.getSceneEffects(f.sceneId).isEmpty())
        assertEquals(null, f.viewModel.uiState.value.rows.first { it.slot.id == f.lamp }.effects)
    }

    @Test
    fun livePreview_runsTheGroupsEffects_andTurningItOffSettlesOnTheBase() = editorTest { f ->
        f.viewModel.setEffects(f.ceiling, breathing)
        delay(1_200)

        // Breathing frames reach the ceiling bulb, and only it.
        val frames = f.effectFrames(serial1)
        assertTrue("${frames.size} frames", frames.size >= 3)
        assertTrue(frames.map { it.state.brightness }.toSet().size >= 3)
        assertTrue(f.effectFrames(serial2).isEmpty())

        f.viewModel.setLivePreview(false)
        delay(800)
        val base = f.fake.commands.value.last { it.serial == serial1 } as Command.SetState
        assertTrue(base.reliable)
        assertEquals(1f, base.state.brightness)

        f.fake.clearLog()
        delay(600)
        assertTrue(f.fake.commands.value.isEmpty())
    }

    @Test
    fun turningEffectsOff_handsTheGroupBackToItsPlainSettings() = editorTest { f ->
        f.viewModel.setEffects(f.ceiling, breathing)
        delay(800)
        f.viewModel.setEffects(f.ceiling, breathing.copy(brightnessShift = breathing.brightnessShift.copy(enabled = false)))
        delay(600)

        f.fake.clearLog()
        delay(600)
        // The runner has let go of the bulb, and its last state is the plain scene setting.
        assertTrue(f.effectFrames(serial1).isEmpty())
        assertEquals(1f, f.fake.bulb(serial1)!!.state.brightness)
    }

    @Test
    fun flashNow_playsAFlashStraightAway() = editorTest { f ->
        val rare = GroupEffects(
            flashes = FlashSettings(enabled = true, gapMinMs = 60_000, gapMaxMs = 60_000, attackMs = 0, holdMs = 300, releaseMs = 0),
        )
        f.viewModel.setEffects(f.ceiling, rare)
        delay(600)
        f.fake.clearLog()

        f.viewModel.flashNow(f.ceiling)
        delay(150)
        // White at full brightness: the flash, not the red base.
        assertTrue(f.effectFrames(serial1).any { it.state.kelvin == 6_500 && it.state.brightness == 1f })
    }

    /** The row for [slotId] once it matches [predicate] (state updates arrive asynchronously). */
    private suspend fun Fixture.row(slotId: Long, predicate: (SlotRowState) -> Boolean): SlotRowState =
        viewModel.uiState.first { state -> state.rows.any { it.slot.id == slotId && predicate(it) } }
            .rows.first { it.slot.id == slotId }

    @Test
    fun multiColor_startsFromTheGroupsColor_andSwitchingBackKeepsThePalette() = editorTest { f ->
        f.viewModel.setLivePreview(false)
        f.viewModel.setMultiColor(f.ceiling, true)

        val on = f.row(f.ceiling) { it.effects?.colorShift?.enabled == true }.effects!!
        // The ceiling is red (hue 0), so the palette starts red, then 40° on.
        assertEquals(listOf(0f, 40f), on.palette.map { it.hue })

        f.viewModel.setMultiColor(f.ceiling, false)
        val off = f.row(f.ceiling) { it.effects?.colorShift?.enabled == false }
        assertEquals(on.palette, off.effects!!.palette)
        assertEquals(LightMode.COLOR, off.light!!.mode)
    }

    @Test
    fun multiColor_onAGroupWithNoSettings_givesItSettingsFirst() = editorTest { f ->
        f.viewModel.setLivePreview(false)
        f.viewModel.setMultiColor(f.lamp, true)

        val row = f.row(f.lamp) { it.effects?.colorShift?.enabled == true }
        assertTrue(row.light != null)
        while (f.scenes.getSceneEffects(f.sceneId)[f.lamp] == null) delay(50)
    }

    /* All lights */

    @Test
    fun allLights_sharesOneLookOnEveryBulb_andGivesBackTheGroupsWhenOff() = editorTest { f ->
        f.viewModel.setOn(f.ceiling, false)
        f.viewModel.setAllLightsOn(true)

        val on = f.viewModel.uiState.first { it.allLightsOn && it.roomRow?.light != null }
        assertTrue(on.rows.all { it.isOn && it.forcedOn })
        val room = on.roomRow!!.slot.id
        // The shared look starts as the default warm white; make it blue.
        f.viewModel.setMode(room, LightMode.COLOR)
        f.viewModel.setHue(room, 240f)
        delay(400)
        // Both bulbs show the shared blue (previews go out at most every 100 ms per bulb).
        assertEquals(240f, f.fake.bulb(serial1)!!.state.hue)
        assertEquals(240f, f.fake.bulb(serial2)!!.state.hue)
        assertEquals(true, f.fake.bulb(serial2)!!.isOn)

        f.viewModel.setAllLightsOn(false)
        val off = f.viewModel.uiState.first { !it.allLightsOn }
        assertFalse(off.rows.first { it.slot.id == f.ceiling }.isOn)
        assertFalse(off.rows.first { it.slot.id == f.lamp }.isOn)
        // The groups' own red is untouched underneath.
        assertEquals(0f, off.rows.first { it.slot.id == f.ceiling }.light!!.hue)
    }

    @Test
    fun allLights_andItsSettings_areSaved() = editorTest { f ->
        f.viewModel.setAllLightsOn(true)
        val room = f.viewModel.uiState.first { it.roomRow?.light != null }.roomRow!!.slot.id
        f.viewModel.setBrightness(room, 0.3f)
        f.scenes.observeScene(f.sceneId).first { it?.allLightsOn == true }
        f.storedLight(room) { it.brightness == 0.3f }
    }

    /* Taking over the bulbs */

    @Test
    fun opening_makesTheSceneActive_andShowsItInFull() = editorTest { f ->
        assertEquals(f.sceneId, f.player.active.value!!.sceneId)
        // The ceiling's red is on its bulb; the lamp has no settings in this scene, so it's off.
        assertEquals(true, f.fake.bulb(serial1)!!.isOn)
        assertEquals(0f, f.fake.bulb(serial1)!!.state.hue)
        assertEquals(false, f.fake.bulb(serial2)!!.isOn)
    }

    @Test
    fun opening_stopsWhatWasPlaying() = editorTest(
        beforeOpen = { player, scenes ->
            // Another scene, with effects on the ceiling, is playing when the editor opens.
            val ceiling = scenes.getSceneLights(scenes.observeLibraryScenes().first().single().id).single().slotId
            val storm = scenes.createLibraryScene("Storm")
            scenes.saveSceneLight(SceneLightEntity(storm, ceiling, true, LightMode.COLOR, 240f, 1f, 2700, 0.5f))
            scenes.saveGroupEffects(storm, ceiling, breathing)
            player.play(storm)
            delay(1_800)
            assertTrue(player.effectsRunning.value)
        },
    ) { f ->
        assertEquals(f.sceneId, f.player.active.value!!.sceneId)
        assertFalse(f.player.effectsRunning.value)
        delay(600)
        // No more frames from the old scene: the ceiling stays on this scene's red.
        assertTrue(f.effectFrames(serial1).isEmpty())
        assertEquals(0f, f.fake.bulb(serial1)!!.state.hue)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
