package com.dungeonvibes.data.slot

import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.bulb.BulbRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LightSlotRepositoryTest : DatabaseTest() {

    private lateinit var slots: LightSlotRepository
    private lateinit var bulbs: BulbRepository

    @Before
    fun setUp() {
        slots = LightSlotRepository(db)
        bulbs = BulbRepository(db, time)
    }

    @Test
    fun aBulbCanBeInSeveralGroups_assigningAddsIt(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        val lamp = slots.createSlot("Lamp")
        val bulb = bulbs.recordSeen("fake", "aa", "10.0.0.1", "Bulb")

        slots.assignBulb(bulb, ceiling)
        slots.assignBulb(bulb, lamp)
        // Assigning again changes nothing.
        slots.assignBulb(bulb, lamp)

        assertEquals(setOf(ceiling, lamp), slots.observeAssignments().first().map { it.slotId }.toSet())
    }

    @Test
    fun setInGroup_addsOrRemovesOneGroup_leavingTheOthers(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        val lamp = slots.createSlot("Lamp")
        val bulb = bulbs.recordSeen("fake", "aa", "10.0.0.1", "Bulb")
        slots.setInGroup(bulb, ceiling, true)
        slots.setInGroup(bulb, lamp, true)

        slots.setInGroup(bulb, ceiling, false)

        assertEquals(listOf(lamp), slots.observeAssignments().first().map { it.slotId })
    }

    @Test
    fun individualLightSlots_areHidden_oneEach_andGoWithTheirBulb(): Unit = runBlocking {
        slots.createSlot("Ceiling")
        val bulb = bulbs.recordSeen("fake", "aa", "10.0.0.1", "Corner")

        val first = slots.ensureBulbSlot(bulb)
        assertEquals(first, slots.ensureBulbSlot(bulb))
        // Not a group: never listed in Setup.
        assertEquals(listOf("Ceiling"), slots.observeSlots().first().map { it.name })
        // Named after its bulb.
        assertEquals(listOf("Corner"), slots.observeBulbSlots().first().map { it.name })

        bulbs.remove(bulb)

        assertTrue(slots.observeBulbSlots().first().isEmpty())
    }

    @Test
    fun unassign_removesBulbFromItsSlot(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        val bulb = bulbs.recordSeen("fake", "aa", "10.0.0.1", "Bulb")
        slots.assignBulb(bulb, ceiling)

        slots.assignBulb(bulb, null)

        assertTrue(slots.observeAssignments().first().isEmpty())
    }

    @Test
    fun removingBulb_removesItsAssignment(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        val bulb = bulbs.recordSeen("fake", "aa", "10.0.0.1", "Bulb")
        slots.assignBulb(bulb, ceiling)

        bulbs.remove(bulb)

        assertTrue(slots.observeAssignments().first().isEmpty())
        assertFalse(bulbs.hasAnyBulbs())
    }

    @Test
    fun setSlotBulbs_makesTheListExact_leavingOtherGroupsAlone(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        val lamp = slots.createSlot("Lamp")
        val a = bulbs.recordSeen("fake", "aa", "10.0.0.1", "A")
        val b = bulbs.recordSeen("fake", "bb", "10.0.0.2", "B")
        val c = bulbs.recordSeen("fake", "cc", "10.0.0.3", "C")
        slots.assignBulb(a, ceiling)
        slots.assignBulb(b, ceiling)
        slots.assignBulb(c, lamp)

        // Ceiling keeps B, drops A, and adds C, which stays in Lamp too.
        slots.setSlotBulbs(ceiling, setOf(b, c))

        val bySlot = slots.observeAssignments().first().groupBy({ it.slotId }, { it.bulbId })
        assertEquals(setOf(b, c), bySlot[ceiling]?.toSet())
        assertEquals(listOf(c), bySlot[lamp])
        assertTrue(slots.observeAssignments().first().none { it.bulbId == a })
    }

    @Test
    fun countScenesUsing_countsLibraryAndAdventureScenesWithARowForTheSlot(): Unit = runBlocking {
        val scenes = com.dungeonvibes.data.scene.SceneRepository(db, time)
        val adventures = com.dungeonvibes.data.adventure.AdventureRepository(db, time)
        val ceiling = slots.createSlot("Ceiling")
        val lamp = slots.createSlot("Lamp")
        val library = scenes.createLibraryScene("Tavern")
        scenes.saveSceneLight(light(library, ceiling, isOn = true))
        val adventureScene = scenes.createAdventureScene(adventures.create("Mine"), "Cave")!!
        // An off row still counts: deleting the slot removes it too.
        scenes.saveSceneLight(light(adventureScene, ceiling, isOn = false))
        scenes.saveSceneLight(light(adventureScene, lamp, isOn = true))

        assertEquals(2, slots.countScenesUsing(ceiling))
        assertEquals(1, slots.countScenesUsing(lamp))
        assertEquals(0, slots.countScenesUsing(slots.createSlot("Unused")))
    }

    private fun light(sceneId: Long, slotId: Long, isOn: Boolean) = com.dungeonvibes.data.db.SceneLightEntity(
        sceneId = sceneId, slotId = slotId, isOn = isOn, mode = com.dungeonvibes.data.db.LightMode.COLOR,
        hue = 0f, saturation = 1f, kelvin = 2700, brightness = 1f,
    )

    @Test
    fun newSlotsAppendToTheEnd_andReorderPersists(): Unit = runBlocking {
        val a = slots.createSlot("A")
        val b = slots.createSlot("B")

        assertEquals(listOf(a, b), slots.observeSlots().first().map { it.id })

        slots.reorder(listOf(b, a))
        assertEquals(listOf(b, a), slots.observeSlots().first().map { it.id })
    }

    @Test
    fun groupBulbs_keepTheOrderTheyWereAdded_andCanBeReordered(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        val a = bulbs.recordSeen("fake", "aa", "10.0.0.1", "A")
        val b = bulbs.recordSeen("fake", "bb", "10.0.0.2", "B")
        val c = bulbs.recordSeen("fake", "cc", "10.0.0.3", "C")

        slots.addBulbs(ceiling, listOf(c, a))
        slots.addBulbs(ceiling, listOf(a, b)) // A is already in: it keeps its place
        assertEquals(listOf(c, a, b), slots.observeAssignments().first().map { it.bulbId })

        slots.reorderBulbs(ceiling, listOf(b, c, a))
        assertEquals(listOf(b, c, a), slots.observeAssignments().first().map { it.bulbId })
    }
}
