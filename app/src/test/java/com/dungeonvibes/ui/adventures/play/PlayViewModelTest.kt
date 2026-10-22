package com.dungeonvibes.ui.adventures.play

import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.fx.FxRepository
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.LightMirror
import com.dungeonvibes.light.ScenePlayer
import com.dungeonvibes.light.fake.FakeLightAdapter
import com.dungeonvibes.light.fx.realTimeEffectRunner
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same real-time, single-main-thread setup as SceneEditorViewModelTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayViewModelTest : DatabaseTest() {

    private class Fixture(
        val viewModel: PlayViewModel,
        val scenes: SceneRepository,
        val adventures: AdventureRepository,
        val adventureId: Long,
        val tavern: Long,
        val crypt: Long,
        val ceiling: Long,
        val slots: LightSlotRepository,
        val bulbs: BulbRepository,
    )

    private fun playTest(block: suspend (Fixture) -> Unit): Unit = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        val lightScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            withContext(main) {
                val scenes = SceneRepository(db, time)
                val adventures = AdventureRepository(db, time)
                val slots = LightSlotRepository(db)
                val bulbs = BulbRepository(db, time)
                val ceiling = slots.createSlot("Ceiling")
                slots.assignBulb(
                    bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(1), "0.0.0.1", "One"),
                    ceiling,
                )
                val tavern = scenes.createLibraryScene("Tavern")
                scenes.saveSceneLight(SceneLightEntity(tavern, ceiling, true, LightMode.WHITE, 0f, 0f, 2700, 0.6f))
                val crypt = scenes.createLibraryScene("Crypt")
                val adventureId = adventures.create("The Lost Mine")
                val mirror = LightMirror()
                val controller = LightController(setOf(FakeLightAdapter()), lightScope, mirror)
                val player = ScenePlayer(scenes, controller, realTimeEffectRunner(controller, lightScope))
                val viewModel = PlayViewModel(adventureId, scenes, adventures, slots, player, FxRepository(db, time), bulbs, mirror)
                try {
                    withTimeout(10_000) {
                        viewModel.uiState.first { it.isLoaded }
                        block(Fixture(viewModel, scenes, adventures, adventureId, tavern, crypt, ceiling, slots, bulbs))
                    }
                } finally {
                    viewModel.viewModelScope.cancel()
                }
            }
        } finally {
            lightScope.cancel()
            Dispatchers.resetMain()
            main.close()
        }
    }

    @Test
    fun addFromLibrary_addsEachPickAsItsOwnCopy_inOrder() = playTest { f ->
        f.viewModel.addFromLibrary(listOf(f.tavern, f.crypt, f.tavern))

        val tiles = f.viewModel.uiState.first { it.tiles.size == 3 }.tiles
        assertEquals(listOf("Tavern", "Crypt", "Tavern"), tiles.map { it.scene.name })
        assertEquals(listOf(f.tavern, f.crypt, f.tavern), tiles.map { it.scene.parentSceneId })
        // Copies, not the library scenes themselves.
        assertTrue(tiles.none { it.scene.id == f.tavern || it.scene.id == f.crypt })
        assertNotEquals(tiles[0].scene.id, tiles[2].scene.id)
    }

    @Test
    fun tiles_showSwatchesOnlyForLitGroups() = playTest { f ->
        f.viewModel.addFromLibrary(listOf(f.tavern, f.crypt))

        // Tiles and their light settings arrive from separate queries; wait until both are in.
        val tiles = f.viewModel.uiState.first { it.tiles.size == 2 && it.tiles[0].swatches.isNotEmpty() }.tiles
        // Tavern lights the ceiling: one swatch, for that group.
        assertEquals(listOf(f.ceiling), tiles[0].swatches.map { it.slotId })
        // Crypt has everything off: no swatches at all (the card says "Empty scene").
        assertTrue(tiles[1].swatches.isEmpty())
    }

    @Test
    fun tiles_markScenesWithEffects_andShowColorShiftPalettes() = playTest { f ->
        val drift = GroupEffects(
            colorShift = ColorShiftSettings(enabled = true),
            palette = listOf(FxColor.color(0f), FxColor.color(120f), FxColor.white(4_000)),
        )
        f.scenes.saveGroupEffects(f.tavern, f.ceiling, drift)
        f.viewModel.addFromLibrary(listOf(f.tavern, f.crypt))

        val tiles = f.viewModel.uiState.first { it.tiles.size == 2 && it.tiles[0].hasEffects }.tiles
        assertEquals(3, tiles[0].swatches.single().shiftColors.size)
        assertFalse(tiles[1].hasEffects)
    }

    @Test
    fun addBlankScene_createsAnEmptySceneInThisAdventure() = playTest { f ->
        val id = f.viewModel.addBlankScene("Ambush")!!

        val scene = f.scenes.getScene(id)!!
        assertEquals(f.adventureId, scene.adventureId)
        assertNull(scene.parentSceneId)
        assertTrue(f.scenes.getSceneLights(id).isEmpty())
    }

    @Test
    fun play_marksTheTappedTileActive() = playTest { f ->
        f.viewModel.addFromLibrary(listOf(f.tavern, f.crypt))
        val tiles = f.viewModel.uiState.first { it.tiles.size == 2 }.tiles

        f.viewModel.play(tiles[1].scene.id)

        assertEquals(tiles[1].scene.id, f.viewModel.uiState.first { it.activeSceneId != null }.activeSceneId)
    }

    @Test
    fun glowBulbs_listEachGroupsBulbsInNameOrder() = playTest { f ->
        val alpha = f.bulbs.recordSeen(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(2), "0.0.0.2", "Alpha")
        f.slots.assignBulb(alpha, f.ceiling)

        val glow = f.viewModel.glowBulbs.first { it.all.size == 2 }
        val expected = listOf(
            LightMirror.keyOf(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(2)),
            LightMirror.keyOf(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(1)),
        )
        assertEquals(expected, glow.bySlot[f.ceiling])
        assertEquals(expected, glow.all)
    }

    @Test
    fun play_mirrorsWhatWasSentToTheBulbs() = playTest { f ->
        f.viewModel.addFromLibrary(listOf(f.tavern))
        val tile = f.viewModel.uiState.first { it.tiles.size == 1 }.tiles[0]

        f.viewModel.play(tile.scene.id)

        val key = LightMirror.keyOf(FakeLightAdapter.PROTOCOL_ID, FakeLightAdapter.serialFor(1))
        val sent = f.viewModel.observeLight(key).first { it?.color != null }!!
        assertEquals(2700, sent.color!!.kelvin)
        assertEquals(0.6f, sent.color!!.brightness, 0.0001f)
        assertEquals(tile.scene.fadeMs, sent.fadeMs)
    }

    @Test
    fun deletingTheAdventure_showsMissing() = playTest { f ->
        f.adventures.delete(f.adventureId)

        assertTrue(f.viewModel.uiState.first { it.isMissing }.isMissing)
    }
}
