package com.dungeonvibes.data.fx

import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.db.FxTransition
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FxRepositoryTest : DatabaseTest() {

    private val fx by lazy { FxRepository(db, time) }
    private val slots by lazy { LightSlotRepository(db) }
    private val scenes by lazy { SceneRepository(db, time) }

    @Test
    fun create_appendsAReturningPulseOnAllLights(): Unit = runBlocking {
        val first = fx.create("Thunder")
        val second = fx.create("Heal")
        val list = fx.observeFx().first()
        assertEquals(listOf(first, second), list.map { it.fx.id })
        val item = list[0]
        assertTrue(item.fx.targetAll)
        assertEquals(FxTransition.RETURN, item.fx.transition)
    }

    @Test
    fun save_keepsGroupTargets_andABlankNameKeepsTheOldOne(): Unit = runBlocking {
        val ceiling = slots.createSlot("Ceiling")
        val lamp = slots.createSlot("Lamp")
        val id = fx.create("Heal")
        val item = fx.get(id)!!
        fx.save(item.copy(fx = item.fx.copy(name = "  ", targetAll = false), targetSlotIds = setOf(ceiling, lamp)))

        val saved = fx.get(id)!!
        assertEquals("Heal", saved.fx.name)
        assertEquals(setOf(ceiling, lamp), saved.targetSlotIds)

        // Deleting a group drops it from the FX; with none left it can't fire.
        slots.delete(ceiling)
        slots.delete(lamp)
        assertTrue(fx.get(id)!!.hasNoTargets)
    }

    @Test
    fun save_onlyTransitionsIntoLibraryScenes(): Unit = runBlocking {
        val library = scenes.createLibraryScene("Dawn")
        val adventure = AdventureRepository(db, time).create("Crypt")
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()
        val id = fx.create("Teleport")
        val item = fx.get(id)!!

        fx.save(item.copy(fx = item.fx.copy(transition = FxTransition.SCENE, transitionSceneId = copy)))
        assertEquals(FxTransition.RETURN, fx.get(id)!!.fx.transition)

        fx.save(item.copy(fx = item.fx.copy(transition = FxTransition.SCENE, transitionSceneId = library)))
        assertEquals(library, fx.get(id)!!.fx.transitionSceneId)

        // Deleting the scene leaves a Scene transition without one: it plays as Return.
        scenes.deleteScene(library)
        assertNull(fx.get(id)!!.fx.transitionSceneId)
    }

    @Test
    fun reorder_andDelete(): Unit = runBlocking {
        val a = fx.create("A")
        val b = fx.create("B")
        fx.reorder(listOf(b, a))
        assertEquals(listOf(b, a), fx.observeFx().first().map { it.fx.id })
        fx.delete(b)
        assertEquals(listOf(a), fx.observeFx().first().map { it.fx.id })
    }
}
