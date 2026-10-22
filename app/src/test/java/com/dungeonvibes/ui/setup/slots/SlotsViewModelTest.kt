package com.dungeonvibes.ui.setup.slots

import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SlotsViewModelTest : DatabaseTest() {

    private lateinit var slots: LightSlotRepository
    private lateinit var bulbs: BulbRepository
    private lateinit var viewModel: SlotsViewModel

    @Before
    fun setUp() {
        // viewModelScope runs on the Main dispatcher, which tests replace with a test dispatcher.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        slots = LightSlotRepository(db)
        bulbs = BulbRepository(db, time)
        viewModel = SlotsViewModel(slots, bulbs)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_listsSlotBulbs_andWhichSlotEachBulbIsIn(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        slots.createSlot("Lamp")
        val zed = bulbs.recordSeen("fake", "aa", "1", "Zed")
        val amy = bulbs.recordSeen("fake", "bb", "2", "Amy")
        bulbs.recordSeen("fake", "cc", "3", "Loose")
        slots.setSlotBulbs(ceiling, setOf(zed, amy))

        val state = viewModel.uiState.first { it.isLoaded && it.bulbs.size == 3 && it.slots.size == 2 }

        assertEquals(listOf("Ceiling", "Lamp"), state.slots.map { it.slot.name })
        assertEquals(listOf("Amy", "Zed"), state.slots[0].bulbNames)
        assertTrue(state.slots[1].bulbNames.isEmpty())
        val choices = state.bulbs.associateBy { it.bulb.name }
        assertEquals("Ceiling", choices.getValue("Amy").slotName)
        assertNull(choices.getValue("Loose").slotName)
    }

    @Test
    fun addSlot_createsItWithTheChosenBulbs(): Unit = runBlocking {
        val bulb = bulbs.recordSeen("fake", "aa", "1", "Bulb")

        viewModel.addSlot("Fireplace", setOf(bulb))

        val slot = slots.observeSlots().first { it.isNotEmpty() }.single()
        assertEquals("Fireplace", slot.name)
        assertEquals(slot.id, slots.observeAssignments().first { it.isNotEmpty() }.single().slotId)
    }

    @Test
    fun delete_asksFirstWithTheSceneCount_thenDeletesOnConfirm(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        val scenes = SceneRepository(db, time)
        for (name in listOf("Tavern", "Cave")) {
            val scene = scenes.createLibraryScene(name)
            scenes.saveSceneLight(
                SceneLightEntity(scene, ceiling, true, LightMode.COLOR, 0f, 1f, 2700, 1f),
            )
        }
        val slot = slots.observeSlots().first().single()

        viewModel.requestDelete(slot)
        val pending = viewModel.pendingDelete.filterNotNull().first()
        assertEquals(2, pending.sceneCount)
        assertEquals(1, slots.observeSlots().first().size) // nothing deleted yet

        viewModel.confirmDelete()

        assertTrue(slots.observeSlots().first { it.isEmpty() }.isEmpty())
        assertNull(viewModel.pendingDelete.value)
    }

    @Test
    fun cancelDelete_keepsTheSlot(): Unit = runBlocking {
        slots.createSlot("Ceiling")
        val slot = slots.observeSlots().first().single()

        viewModel.requestDelete(slot)
        viewModel.pendingDelete.filterNotNull().first()
        viewModel.cancelDelete()

        assertNull(viewModel.pendingDelete.value)
        assertEquals(1, slots.observeSlots().first().size)
    }

    @Test
    fun saveSlot_renamesAndReplacesBulbs(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        val a = bulbs.recordSeen("fake", "aa", "1", "A")
        val b = bulbs.recordSeen("fake", "bb", "2", "B")
        slots.setSlotBulbs(ceiling, setOf(a))
        val slot = slots.observeSlots().first().single()

        viewModel.saveSlot(slot, "Ceiling fan", setOf(b))

        assertEquals("Ceiling fan", slots.observeSlots().first { it.single().name == "Ceiling fan" }.single().name)
        assertEquals(b, slots.observeAssignments().first { list -> list.any { it.bulbId == b } }.single().bulbId)
    }
}
