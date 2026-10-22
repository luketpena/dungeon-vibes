package com.dungeonvibes.data.scene

import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.db.BrightnessMode
import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.ColorOrder
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.data.slot.LightSlotRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** FX_REQUIREMENTS.md → Data model: effects are stored per group and travel with their scenes. */
class SceneEffectsTest : DatabaseTest() {

    private lateinit var scenes: SceneRepository
    private lateinit var slots: LightSlotRepository
    private var ceiling = 0L
    private var lamp = 0L
    private var adventure = 0L

    private val fire = GroupEffects(
        colorShift = ColorShiftSettings(enabled = true, spread = Spread.INDEPENDENT, order = ColorOrder.RANDOM),
        palette = listOf(FxColor.color(12f), FxColor.color(25f), FxColor.white(2_200)),
        brightnessShift = BrightnessShiftSettings(enabled = true, mode = BrightnessMode.FLICKER, levelMin = 0.3f),
        flashes = FlashSettings(enabled = false, attackMs = 10, pulsesMax = 3),
    )

    @Before
    fun setUp(): Unit = runBlocking {
        scenes = SceneRepository(db, time)
        slots = LightSlotRepository(db)
        ceiling = slots.createSlot("Ceiling")
        lamp = slots.createSlot("Lamp")
        adventure = AdventureRepository(db, time).create("Lost Mine")
    }

    private fun light(sceneId: Long, slotId: Long) =
        SceneLightEntity(sceneId, slotId, true, LightMode.COLOR, 20f, 1f, 2_700, 0.6f)

    /** A library scene whose ceiling has [fire] and whose lamp has no effects. */
    private suspend fun libraryScene(): Long {
        val id = scenes.createLibraryScene("Campfire")
        scenes.saveSceneLight(light(id, ceiling))
        scenes.saveSceneLight(light(id, lamp))
        assertTrue(scenes.saveGroupEffects(id, ceiling, fire))
        return id
    }

    @Test
    fun save_roundTripsEveryLayer_andKeepsPaletteOrder() = runBlocking {
        val id = libraryScene()
        assertEquals(mapOf(ceiling to fire), scenes.getSceneEffects(id))
    }

    @Test
    fun save_needsTheGroupsSettingsFirst() = runBlocking {
        val id = scenes.createLibraryScene("Empty")
        assertFalse(scenes.saveGroupEffects(id, ceiling, fire))
        assertTrue(scenes.getSceneEffects(id).isEmpty())
    }

    @Test
    fun saveAgain_replacesThePalette() = runBlocking {
        val id = libraryScene()
        val shorter = fire.copy(palette = listOf(FxColor.color(300f), FxColor.color(310f)))
        scenes.saveGroupEffects(id, ceiling, shorter)
        assertEquals(shorter, scenes.getSceneEffects(id)[ceiling])
    }

    @Test
    fun turningTheGroupOffAndOn_keepsItsEffects() = runBlocking {
        val id = libraryScene()
        scenes.saveSceneLight(light(id, ceiling).copy(isOn = false))
        scenes.saveSceneLight(light(id, ceiling))
        assertEquals(fire, scenes.getSceneEffects(id)[ceiling])
    }

    @Test
    fun addingToAnAdventure_copiesEffects_andTheCopyIsIndependent() = runBlocking {
        val library = libraryScene()
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()
        assertEquals(mapOf(ceiling to fire), scenes.getSceneEffects(copy))

        val edited = fire.copy(palette = listOf(FxColor.color(200f), FxColor.color(220f)))
        scenes.saveGroupEffects(copy, ceiling, edited)
        assertEquals(fire, scenes.getSceneEffects(library)[ceiling])
        assertEquals(edited, scenes.getSceneEffects(copy)[ceiling])
    }

    @Test
    fun reset_bringsBackTheLibraryEffects_andDropsOnesTheLibraryDoesntHave() = runBlocking {
        val library = libraryScene()
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()
        scenes.saveGroupEffects(copy, ceiling, fire.copy(palette = listOf(FxColor.color(1f), FxColor.color(2f))))
        scenes.saveGroupEffects(copy, lamp, fire)

        assertTrue(scenes.resetToLibraryVersion(copy))
        assertEquals(mapOf(ceiling to fire), scenes.getSceneEffects(copy))
    }

    @Test
    fun promote_copiesEffectsIntoTheNewLibraryScene() = runBlocking {
        val copy = scenes.createAdventureScene(adventure, "Ambush")!!
        scenes.saveSceneLight(light(copy, lamp))
        scenes.saveGroupEffects(copy, lamp, fire)

        val library = scenes.promoteToLibrary(copy)!!
        assertEquals(mapOf(lamp to fire), scenes.getSceneEffects(library))
    }

    @Test
    fun deletingTheGroupOrTheScene_deletesItsEffects() = runBlocking {
        val first = libraryScene()
        val second = libraryScene()
        slots.delete(ceiling)
        assertTrue(scenes.getSceneEffects(first).isEmpty())

        scenes.saveGroupEffects(second, lamp, fire)
        scenes.deleteScene(second)
        assertTrue(scenes.getSceneEffects(second).isEmpty())
        assertEquals(0, db.sceneEffectsDao().getColorsForScene(second).size)
    }

    @Test
    fun clear_removesTheGroupsEffects() = runBlocking {
        val id = libraryScene()
        scenes.clearGroupEffects(id, ceiling)
        assertTrue(scenes.getSceneEffects(id).isEmpty())
    }
}
