package com.dungeonvibes.data.scene

import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.LightDefaults
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.slot.LightSlotRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The scene "All lights" switch (owner request): every bulb shares the All-lights settings (the
 * hidden room slot), without changing any group's own settings.
 */
class AllLightsTest : DatabaseTest() {

    private lateinit var scenes: SceneRepository
    private lateinit var slots: LightSlotRepository
    private var ceiling = 0L
    private var lamp = 0L
    private var room = 0L
    private var ceilingBulb = 0L
    private var lampBulb = 0L
    private var adventure = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        scenes = SceneRepository(db, time)
        slots = LightSlotRepository(db)
        val bulbs = BulbRepository(db, time)
        ceiling = slots.createSlot("Ceiling")
        lamp = slots.createSlot("Lamp")
        room = slots.ensureRoomSlot()
        ceilingBulb = bulbs.recordSeen("lifx_lan", "aa", "10.0.0.1", "Ceiling bulb")
        lampBulb = bulbs.recordSeen("lifx_lan", "bb", "10.0.0.2", "Lamp bulb")
        slots.assignBulb(ceilingBulb, ceiling)
        slots.assignBulb(lampBulb, lamp)
        adventure = AdventureRepository(db, time).create("Lost Mine")
    }

    /** Ceiling is red but switched off; Lamp has no settings; All lights is blue. */
    private suspend fun scene(allOn: Boolean, roomSettings: Boolean = true): Long {
        val id = scenes.createLibraryScene("Tavern")
        scenes.saveSceneLight(SceneLightEntity(id, ceiling, false, LightMode.COLOR, 0f, 1f, 2_700, 0.4f))
        if (roomSettings) scenes.saveSceneLight(SceneLightEntity(id, room, true, LightMode.COLOR, 240f, 1f, 2_700, 0.7f))
        if (allOn) scenes.setAllLightsOn(id, true)
        return id
    }

    @Test
    fun theRoomSlot_isHiddenFromGroups_andMadeOnlyOnce() = runBlocking {
        assertEquals(listOf(ceiling, lamp), slots.observeSlots().first().map { it.id })
        assertEquals(room, slots.ensureRoomSlot())
    }

    @Test
    fun off_groupsFollowTheirOwnSettings() = runBlocking {
        val targets = scenes.applyTargets(scene(allOn = false)).associateBy { it.bulbId }
        assertFalse(targets.getValue(ceilingBulb).isOn)
        assertFalse(targets.getValue(lampBulb).isOn)
    }

    @Test
    fun on_everyBulbSharesTheAllLightsSettings() = runBlocking {
        val targets = scenes.applyTargets(scene(allOn = true))
        assertEquals(2, targets.size)
        for (target in targets) {
            assertTrue(target.isOn)
            assertEquals(LightMode.COLOR, target.mode)
            assertEquals(240f, target.hue)
            assertEquals(0.7f, target.brightness)
        }
        // The All-lights slot decides every bulb (its swatch takes failure badges), and each bulb
        // still lists its own groups.
        assertEquals(setOf(room), targets.map { it.slotId }.toSet())
        assertEquals(setOf(listOf(ceiling), listOf(lamp)), targets.map { it.groupIds }.toSet())
    }

    @Test
    fun on_withoutAllLightsSettings_usesTheDefaults() = runBlocking {
        val target = scenes.applyTargets(scene(allOn = true, roomSettings = false)).first()
        assertTrue(target.isOn)
        assertEquals(LightMode.WHITE, target.mode)
        assertEquals(LightDefaults.KELVIN, target.kelvin)
    }

    @Test
    fun turningItOff_bringsBackTheGroupsOwnSettings() = runBlocking {
        val id = scene(allOn = true)
        scenes.setAllLightsOn(id, false)
        val ceilingTarget = scenes.applyTargets(id).single { it.bulbId == ceilingBulb }
        assertFalse(ceilingTarget.isOn)
        assertEquals(0f, ceilingTarget.hue)
    }

    @Test
    fun travelsWithCopies_resets_andPromotion() = runBlocking {
        val library = scene(allOn = true)
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()
        assertTrue(scenes.getScene(copy)!!.allLightsOn)
        assertEquals(240f, scenes.getSceneLights(copy).single { it.slotId == room }.hue)

        scenes.setAllLightsOn(copy, false)
        assertTrue(scenes.getScene(library)!!.allLightsOn) // the library scene is untouched
        scenes.resetToLibraryVersion(copy)
        assertTrue(scenes.getScene(copy)!!.allLightsOn)

        val promoted = scenes.promoteToLibrary(copy)!!
        assertTrue(scenes.getScene(promoted)!!.allLightsOn)
    }
}
