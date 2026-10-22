package com.dungeonvibes.data.scene

import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.slot.LightSlotRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** One test (or more) per rule in REQUIREMENTS.md → Data model → Rules. */
class SceneRepositoryTest : DatabaseTest() {

    private lateinit var scenes: SceneRepository
    private lateinit var adventures: AdventureRepository
    private lateinit var slots: LightSlotRepository
    private lateinit var bulbs: BulbRepository

    private var ceiling = 0L
    private var lamp = 0L
    private var adventure = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        scenes = SceneRepository(db, time)
        adventures = AdventureRepository(db, time)
        slots = LightSlotRepository(db)
        bulbs = BulbRepository(db, time)

        ceiling = slots.createSlot("Ceiling")
        lamp = slots.createSlot("Lamp")
        adventure = adventures.create("Lost Mine")
    }

    /* Helpers */

    private fun color(sceneId: Long, slotId: Long, hue: Float, isOn: Boolean = true) = SceneLightEntity(
        sceneId = sceneId,
        slotId = slotId,
        isOn = isOn,
        mode = LightMode.COLOR,
        hue = hue,
        saturation = 1f,
        kelvin = 2700,
        brightness = 0.5f,
    )

    /** A library scene with a red ceiling and a blue lamp. */
    private suspend fun libraryScene(name: String = "Tavern"): Long {
        val id = scenes.createLibraryScene(name)
        scenes.saveSceneLight(color(id, ceiling, hue = 0f))
        scenes.saveSceneLight(color(id, lamp, hue = 240f))
        scenes.updateSceneDetails(id, name, fadeMs = 2_000)
        return id
    }

    private suspend fun lightsOf(sceneId: Long): Map<Long, SceneLightEntity> =
        scenes.getSceneLights(sceneId).associateBy { it.slotId }

    /* Rule 1 */

    @Test
    fun rule1_addLibrarySceneToAdventure_deepCopiesSceneAndLightsWithParent(): Unit = runBlocking {
        val library = libraryScene()

        val copyId = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()

        val copy = scenes.getScene(copyId)!!
        assertEquals(adventure, copy.adventureId)
        assertEquals(library, copy.parentSceneId)
        assertEquals("Tavern", copy.name)
        assertEquals(2_000, copy.fadeMs)
        val copied = lightsOf(copyId)
        assertEquals(2, copied.size)
        assertEquals(0f, copied.getValue(ceiling).hue)
        assertEquals(240f, copied.getValue(lamp).hue)
    }

    @Test
    fun rule1_sameLibrarySceneCanBeAddedRepeatedly_eachAsItsOwnCopy(): Unit = runBlocking {
        val library = libraryScene()

        val ids = scenes.addLibraryScenesToAdventure(adventure, listOf(library, library, library))

        assertEquals(3, ids.toSet().size)
        assertEquals(3, scenes.observeAdventureScenes(adventure).first().size)
        // Copies are appended in the order picked.
        assertEquals(ids, scenes.observeAdventureScenes(adventure).first().map { it.id })
    }

    /* Rule 2 */

    @Test
    fun rule2_editingAdventureScene_changesOnlyThatCopy(): Unit = runBlocking {
        val library = libraryScene()
        val (copyA, copyB) = scenes.addLibraryScenesToAdventure(adventure, listOf(library, library))

        scenes.saveSceneLight(color(copyA, ceiling, hue = 120f))
        scenes.updateSceneDetails(copyA, "Tavern (brawl)", fadeMs = 500)

        assertEquals(120f, lightsOf(copyA).getValue(ceiling).hue)
        assertEquals("Tavern (brawl)", scenes.getScene(copyA)!!.name)
        // Parent untouched.
        assertEquals(0f, lightsOf(library).getValue(ceiling).hue)
        assertEquals("Tavern", scenes.getScene(library)!!.name)
        assertEquals(2_000, scenes.getScene(library)!!.fadeMs)
        // Sibling copy untouched.
        assertEquals(0f, lightsOf(copyB).getValue(ceiling).hue)
        assertEquals("Tavern", scenes.getScene(copyB)!!.name)
    }

    /* Rule 3 */

    @Test
    fun rule3_editingLibraryScene_neverChangesExistingCopies(): Unit = runBlocking {
        val library = libraryScene()
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()

        scenes.saveSceneLight(color(library, ceiling, hue = 60f))
        scenes.updateSceneDetails(library, "Tavern v2", fadeMs = 9_000)

        assertEquals(0f, lightsOf(copy).getValue(ceiling).hue)
        assertEquals("Tavern", scenes.getScene(copy)!!.name)
        assertEquals(2_000, scenes.getScene(copy)!!.fadeMs)
    }

    /* Rule 4 */

    @Test
    fun rule4_reset_replacesCopysLightsFadeAndNameWithParents(): Unit = runBlocking {
        val library = libraryScene()
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()
        scenes.saveSceneLight(color(copy, ceiling, hue = 120f))
        scenes.updateSceneDetails(copy, "Changed", fadeMs = 0)
        // The parent changes too, after the copy was made: reset takes the parent as it is now.
        scenes.saveSceneLight(color(library, lamp, hue = 300f))

        assertTrue(scenes.resetToLibraryVersion(copy))

        val reset = scenes.getScene(copy)!!
        assertEquals("Tavern", reset.name)
        assertEquals(2_000, reset.fadeMs)
        assertEquals(library, reset.parentSceneId)
        assertEquals(lightsOf(library).mapValues { it.value.copy(sceneId = 0) },
            lightsOf(copy).mapValues { it.value.copy(sceneId = 0) })
        assertEquals(300f, lightsOf(copy).getValue(lamp).hue)
    }

    @Test
    fun rule4_reset_alsoRemovesLightRowsTheParentDoesNotHave(): Unit = runBlocking {
        val library = scenes.createLibraryScene("Ceiling only")
        scenes.saveSceneLight(color(library, ceiling, hue = 0f))
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()
        scenes.saveSceneLight(color(copy, lamp, hue = 240f))

        scenes.resetToLibraryVersion(copy)

        assertEquals(setOf(ceiling), lightsOf(copy).keys)
    }

    @Test
    fun rule4_resetIsUnavailableWithoutParent(): Unit = runBlocking {
        val blank = scenes.createAdventureScene(adventure, "Blank")!!
        val library = libraryScene()

        assertFalse(scenes.getScene(blank)!!.canResetToLibrary)
        assertFalse(scenes.resetToLibraryVersion(blank))
        // Library scenes never show Reset.
        assertFalse(scenes.getScene(library)!!.canResetToLibrary)
        assertFalse(scenes.resetToLibraryVersion(library))
    }

    /* Rule 5 */

    @Test
    fun rule5_promote_createsLibraryCopyAndReparentsAdventureScene(): Unit = runBlocking {
        val blank = scenes.createAdventureScene(adventure, "Dragon's lair")!!
        scenes.saveSceneLight(color(blank, ceiling, hue = 15f))
        scenes.updateSceneDetails(blank, "Dragon's lair", fadeMs = 3_000)

        val libraryId = scenes.promoteToLibrary(blank)

        assertNotNull(libraryId)
        val promoted = scenes.getScene(libraryId!!)!!
        assertTrue(promoted.isLibraryScene)
        assertNull(promoted.parentSceneId)
        assertEquals("Dragon's lair", promoted.name)
        assertEquals(3_000, promoted.fadeMs)
        assertEquals(15f, lightsOf(libraryId).getValue(ceiling).hue)
        // The adventure scene now resets to the new library scene.
        val adventureScene = scenes.getScene(blank)!!
        assertEquals(libraryId, adventureScene.parentSceneId)
        assertTrue(adventureScene.canResetToLibrary)
        // And it's an independent copy: editing one leaves the other alone.
        scenes.saveSceneLight(color(blank, ceiling, hue = 200f))
        assertEquals(15f, lightsOf(libraryId).getValue(ceiling).hue)
    }

    @Test
    fun rule5_promoteOfACopy_repointsParentToTheNewLibraryScene(): Unit = runBlocking {
        val original = libraryScene()
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(original)).single()

        val promoted = scenes.promoteToLibrary(copy)!!

        assertNotEquals(original, promoted)
        assertEquals(promoted, scenes.getScene(copy)!!.parentSceneId)
        assertEquals(2, scenes.observeLibraryScenes().first().size)
    }

    @Test
    fun rule5_libraryScenesCannotBePromoted(): Unit = runBlocking {
        assertNull(scenes.promoteToLibrary(libraryScene()))
    }

    /* Rule 6 */

    @Test
    fun rule6_deletingLibraryScene_keepsCopiesAndClearsTheirParent(): Unit = runBlocking {
        val library = libraryScene()
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()

        scenes.deleteScene(library)

        assertNull(scenes.getScene(library))
        val orphan = scenes.getScene(copy)!!
        assertNull(orphan.parentSceneId)
        assertFalse(orphan.canResetToLibrary)
        assertEquals(2, lightsOf(copy).size)
    }

    /* Rule 7 */

    @Test
    fun rule7_offLightKeepsItsColorAndBothModesKeepTheirValues(): Unit = runBlocking {
        val id = scenes.createLibraryScene("Moody")
        val on = SceneLightEntity(
            sceneId = id, slotId = ceiling, isOn = true, mode = LightMode.COLOR,
            hue = 280f, saturation = 0.8f, kelvin = 4000, brightness = 0.3f,
        )
        scenes.saveSceneLight(on)

        // Turn it off: color is kept.
        scenes.saveSceneLight(on.copy(isOn = false))
        val off = lightsOf(id).getValue(ceiling)
        assertFalse(off.isOn)
        assertEquals(280f, off.hue)
        assertEquals(0.8f, off.saturation)

        // Switch to white: the color values are kept alongside the kelvin.
        scenes.saveSceneLight(off.copy(isOn = true, mode = LightMode.WHITE))
        val white = lightsOf(id).getValue(ceiling)
        assertEquals(LightMode.WHITE, white.mode)
        assertEquals(4000, white.kelvin)
        assertEquals(280f, white.hue)

        // And back to color: the kelvin is kept too.
        scenes.saveSceneLight(white.copy(mode = LightMode.COLOR))
        assertEquals(4000, lightsOf(id).getValue(ceiling).kelvin)
    }

    /* Rule 8 */

    @Test
    fun rule8_apply_turnsOffSlotsWithNoRowOrOffRow_andSkipsUnassignedBulbs(): Unit = runBlocking {
        val left = slots.createSlot("Left")
        val ceilingBulb = bulbs.recordSeen("fake", "aa", "10.0.0.1", "Ceiling bulb")
        val lampBulb = bulbs.recordSeen("fake", "bb", "10.0.0.2", "Lamp bulb")
        val leftBulb = bulbs.recordSeen("fake", "cc", "10.0.0.3", "Left bulb")
        val loose = bulbs.recordSeen("fake", "dd", "10.0.0.4", "Unassigned bulb")
        slots.assignBulb(ceilingBulb, ceiling)
        slots.assignBulb(lampBulb, lamp)
        slots.assignBulb(leftBulb, left)

        val scene = scenes.createLibraryScene("Spotlight")
        scenes.saveSceneLight(color(scene, ceiling, hue = 0f))
        scenes.saveSceneLight(color(scene, lamp, hue = 0f, isOn = false))
        // "Left" has no row at all.

        val targets = scenes.applyTargets(scene).associateBy { it.bulbId }

        assertEquals(setOf(ceilingBulb, lampBulb, leftBulb), targets.keys)
        assertFalse(loose in targets)
        assertTrue(targets.getValue(ceilingBulb).isOn)
        assertEquals(0.5f, targets.getValue(ceilingBulb).brightness)
        assertFalse(targets.getValue(lampBulb).isOn)
        assertFalse(targets.getValue(leftBulb).isOn)
    }

    @Test
    fun rule8_sceneWithEveryLightOff_isABlackout(): Unit = runBlocking {
        val bulb = bulbs.recordSeen("fake", "aa", "10.0.0.1", "Ceiling bulb")
        slots.assignBulb(bulb, ceiling)
        val blackout = scenes.createLibraryScene("Blackout")

        val targets = scenes.applyTargets(blackout)

        assertEquals(1, targets.size)
        assertFalse(targets.single().isOn)
    }

    /* Rule 9 */

    @Test
    fun rule9_newSlot_getsNoRowInExistingScenes_soItStaysOff(): Unit = runBlocking {
        val library = libraryScene()
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()

        val newSlot = slots.createSlot("Fireplace")
        val bulb = bulbs.recordSeen("fake", "ff", "10.0.0.9", "Fireplace bulb")
        slots.assignBulb(bulb, newSlot)

        assertFalse(newSlot in lightsOf(library))
        assertFalse(newSlot in lightsOf(copy))
        val target = scenes.applyTargets(library).single { it.bulbId == bulb }
        assertFalse(target.isOn)
    }

    /* Foreign keys and ordering */

    @Test
    fun deletingAdventure_deletesItsScenes_butNotLibraryScenes(): Unit = runBlocking {
        val library = libraryScene()
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()

        adventures.delete(adventure)

        assertNull(scenes.getScene(copy))
        assertTrue(scenes.getSceneLights(copy).isEmpty())
        assertNotNull(scenes.getScene(library))
    }

    @Test
    fun deletingSlot_removesItsLightRowsFromEveryScene(): Unit = runBlocking {
        val library = libraryScene()
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()

        slots.delete(lamp)

        assertEquals(setOf(ceiling), lightsOf(library).keys)
        assertEquals(setOf(ceiling), lightsOf(copy).keys)
    }

    @Test
    fun reorder_persistsNewOrder(): Unit = runBlocking {
        val a = scenes.createLibraryScene("A")
        val b = scenes.createLibraryScene("B")
        val c = scenes.createLibraryScene("C")

        scenes.reorderScenes(listOf(c, a, b))

        assertEquals(listOf(c, a, b), scenes.observeLibraryScenes().first().map { it.id })
    }

    @Test
    fun libraryAndAdventureSortOrders_areIndependent(): Unit = runBlocking {
        scenes.createLibraryScene("L1")
        val first = scenes.createAdventureScene(adventure, "A1")!!

        assertEquals(0, scenes.getScene(first)!!.sortOrder)
    }

    @Test
    fun defaultScene_isCreatedOnce_allLightsOn_andNeverListedInTheLibrary(): Unit = runBlocking {
        scenes.createLibraryScene("Tavern")
        val id = scenes.ensureDefaultScene()
        assertEquals(id, scenes.ensureDefaultScene())

        val scene = scenes.getScene(id)!!
        assertTrue(scene.isDefault)
        assertTrue(scene.allLightsOn)
        assertFalse(scene.isLibraryScene)
        assertEquals(listOf("Tavern"), scenes.observeLibraryScenes().first().map { it.name })
        // It can't be saved into the library either.
        assertNull(scenes.promoteToLibrary(id))
    }
}
