package com.dungeonvibes.data.scene

import com.dungeonvibes.data.DatabaseTest
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.db.ColorShiftSettings
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.FxColor
import com.dungeonvibes.data.db.FxTransition
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.fx.FxRepository
import com.dungeonvibes.data.placedAfter
import com.dungeonvibes.data.slot.LightSlotRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Undo for deletes, Duplicate, and per-adventure FX favorites (owner requests, Oct 23, 2026). */
class UndoAndDuplicateTest : DatabaseTest() {

    private lateinit var scenes: SceneRepository
    private lateinit var adventures: AdventureRepository
    private lateinit var slots: LightSlotRepository
    private lateinit var fx: FxRepository

    private var ceiling = 0L
    private var lamp = 0L
    private var adventure = 0L

    @Before
    fun setUp(): Unit = runBlocking {
        scenes = SceneRepository(db, time)
        adventures = AdventureRepository(db, time)
        slots = LightSlotRepository(db)
        fx = FxRepository(db, time)
        ceiling = slots.createSlot("Ceiling")
        lamp = slots.createSlot("Lamp")
        adventure = adventures.create("Lost Mine")
    }

    private fun light(sceneId: Long, slotId: Long, hue: Float) =
        SceneLightEntity(sceneId, slotId, true, LightMode.COLOR, hue, 1f, 2700, 0.5f)

    private val effects = GroupEffects(colorShift = ColorShiftSettings(enabled = true), palette = listOf(FxColor.color(10f), FxColor.color(200f)), layers = listOf(EffectLayer.flashes(FlashSettings(enabled = true))))

    /** A library scene with two lit groups, effects on the ceiling. */
    private suspend fun richScene(name: String): Long {
        val id = scenes.createLibraryScene(name)
        scenes.saveSceneLight(light(id, ceiling, 0f))
        scenes.saveSceneLight(light(id, lamp, 240f))
        assertTrue(scenes.saveGroupEffects(id, ceiling, effects))
        return id
    }

    /* Undo */

    @Test
    fun restoreScene_bringsBackRowsPlace_copiesAndFxLinks(): Unit = runBlocking {
        val first = richScene("Tavern")
        val second = richScene("Cave")
        val third = richScene("Road")
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(second)).single()
        val teleport = fx.create("Teleport")
        val item = fx.get(teleport)!!
        fx.save(item.copy(fx = item.fx.copy(transition = FxTransition.SCENE, transitionSceneId = second)))
        val lightsBefore = scenes.getSceneLights(second).toSet()

        val deleted = scenes.deleteScene(second)!!
        assertNull(scenes.getScene(copy)!!.parentSceneId)
        assertNull(fx.get(teleport)!!.fx.transitionSceneId)

        assertTrue(scenes.restoreScene(deleted))
        assertEquals(listOf(first, second, third), scenes.observeLibraryScenes().first().map { it.id })
        assertEquals(lightsBefore, scenes.getSceneLights(second).toSet())
        assertEquals(effects, scenes.getSceneEffects(second)[ceiling])
        assertEquals(second, scenes.getScene(copy)!!.parentSceneId)
        assertEquals(second, fx.get(teleport)!!.fx.transitionSceneId)

        // A second Undo does nothing.
        assertFalse(scenes.restoreScene(deleted))
    }

    @Test
    fun restoreScene_leavesOutGroupsDeletedMeanwhile(): Unit = runBlocking {
        val id = richScene("Tavern")
        val deleted = scenes.deleteScene(id)!!
        slots.delete(ceiling)

        assertTrue(scenes.restoreScene(deleted))
        assertEquals(listOf(lamp), scenes.getSceneLights(id).map { it.slotId })
        assertTrue(scenes.getSceneEffects(id).isEmpty())
    }

    @Test
    fun restoreAdventure_bringsBackScenesAndFavorites(): Unit = runBlocking {
        val library = richScene("Tavern")
        val copies = scenes.addLibraryScenesToAdventure(adventure, listOf(library, library))
        val heal = fx.create("Heal")
        fx.setFavorite(adventure, heal, true)

        val deleted = scenes.deleteAdventure(adventure)!!
        assertTrue(scenes.observeAdventureScenes(adventure).first().isEmpty())
        assertTrue(fx.observeFavorites(adventure).first().isEmpty())

        assertTrue(scenes.restoreAdventure(deleted))
        assertEquals("Lost Mine", adventures.observeAdventure(adventure).first()!!.name)
        assertEquals(copies, scenes.observeAdventureScenes(adventure).first().map { it.id })
        assertEquals(library, scenes.getScene(copies[0])!!.parentSceneId)
        assertEquals(effects, scenes.getSceneEffects(copies[1])[ceiling])
        assertEquals(listOf(heal), fx.observeFavorites(adventure).first())
    }

    @Test
    fun restoreFx_bringsBackTargetsAndFavorites(): Unit = runBlocking {
        val a = fx.create("A")
        val b = fx.create("B")
        val item = fx.get(b)!!
        fx.save(item.copy(fx = item.fx.copy(targetAll = false), targetSlotIds = setOf(ceiling, lamp)))
        fx.setFavorite(adventure, b, true)

        val deleted = fx.delete(b)!!
        slots.delete(lamp)
        fx.restore(deleted)

        assertEquals(listOf(a, b), fx.observeFx().first().map { it.fx.id })
        assertEquals(setOf(ceiling), fx.get(b)!!.targetSlotIds)
        assertEquals(listOf(b), fx.observeFavorites(adventure).first())
    }

    /* Duplicate */

    @Test
    fun duplicateScene_copiesEverything_rightAfterTheOriginal(): Unit = runBlocking {
        val first = richScene("Tavern")
        val last = richScene("Cave")

        val dup = scenes.duplicateScene(first)!!

        assertEquals(listOf(first, dup, last), scenes.observeLibraryScenes().first().map { it.id })
        assertEquals("Tavern copy", scenes.getScene(dup)!!.name)
        assertEquals(
            scenes.getSceneLights(first).map { it.copy(sceneId = 0) }.toSet(),
            scenes.getSceneLights(dup).map { it.copy(sceneId = 0) }.toSet(),
        )
        assertEquals(effects, scenes.getSceneEffects(dup)[ceiling])

        // Independent: editing the copy leaves the original alone.
        scenes.saveSceneLight(light(dup, ceiling, 120f))
        assertEquals(0f, scenes.getSceneLights(first).single { it.slotId == ceiling }.hue)
    }

    @Test
    fun duplicateScene_inAnAdventure_keepsItsLibraryParent(): Unit = runBlocking {
        val library = richScene("Tavern")
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()

        val dup = scenes.duplicateScene(copy)!!

        val scene = scenes.getScene(dup)!!
        assertEquals(adventure, scene.adventureId)
        assertEquals(library, scene.parentSceneId)
        assertEquals(listOf(copy, dup), scenes.observeAdventureScenes(adventure).first().map { it.id })
    }

    @Test
    fun duplicateAdventure_copiesScenesAndFavorites(): Unit = runBlocking {
        val other = adventures.create("Crypt")
        val library = richScene("Tavern")
        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()
        val heal = fx.create("Heal")
        fx.setFavorite(adventure, heal, true)

        val dup = scenes.duplicateAdventure(adventure)!!

        assertEquals(listOf(adventure, dup, other), adventures.observeAdventures().first().map { it.adventure.id })
        val dupScene = scenes.observeAdventureScenes(dup).first().single()
        assertNotEquals(copy, dupScene.id)
        assertEquals(library, dupScene.parentSceneId)
        assertEquals(listOf(heal), fx.observeFavorites(dup).first())
    }

    @Test
    fun duplicateFx_copiesSettingsAndTargets_notFavorites(): Unit = runBlocking {
        val a = fx.create("Heal")
        val last = fx.create("Last")
        val item = fx.get(a)!!
        fx.save(item.copy(fx = item.fx.copy(targetAll = false), targetSlotIds = setOf(lamp)))
        fx.setFavorite(adventure, a, true)

        val dup = fx.duplicate(a)!!

        assertEquals(listOf(a, dup, last), fx.observeFx().first().map { it.fx.id })
        val copy = fx.get(dup)!!
        assertEquals("Heal copy", copy.fx.name)
        assertEquals(setOf(lamp), copy.targetSlotIds)
        assertEquals(listOf(a), fx.observeFavorites(adventure).first())
    }

    /* Favorites */

    @Test
    fun favorites_keepStarOrder_andGoWithTheirFx(): Unit = runBlocking {
        val a = fx.create("A")
        val b = fx.create("B")
        fx.setFavorite(adventure, b, true)
        fx.setFavorite(adventure, a, true)
        fx.setFavorite(adventure, b, true) // starring twice changes nothing
        assertEquals(listOf(b, a), fx.observeFavorites(adventure).first())

        fx.setFavorite(adventure, b, false)
        assertEquals(listOf(a), fx.observeFavorites(adventure).first())

        fx.delete(a)
        assertTrue(fx.observeFavorites(adventure).first().isEmpty())
    }

    @Test
    fun placedAfter_movesTheNewIdBehindItsSource() {
        assertEquals(listOf(1L, 9L, 2L, 3L), placedAfter(listOf(1L, 2L, 3L, 9L), 1L, 9L))
        assertEquals(listOf(1L, 2L, 9L), placedAfter(listOf(1L, 2L), 5L, 9L))
    }

    /* Intro FX (FX4b) */

    @Test
    fun introFx_travelsWithCopies_resetPromoteAndDuplicate(): Unit = runBlocking {
        val heal = fx.create("Heal")
        val storm = fx.create("Storm")
        val library = richScene("Tavern")
        scenes.setIntroFx(library, heal)

        val copy = scenes.addLibraryScenesToAdventure(adventure, listOf(library)).single()
        assertEquals(heal, scenes.getScene(copy)!!.introFxId)

        scenes.setIntroFx(copy, storm)
        assertTrue(scenes.resetToLibraryVersion(copy))
        assertEquals(heal, scenes.getScene(copy)!!.introFxId)

        scenes.setIntroFx(copy, storm)
        val promoted = scenes.promoteToLibrary(copy)!!
        assertEquals(storm, scenes.getScene(promoted)!!.introFxId)
        assertEquals(storm, scenes.getScene(scenes.duplicateScene(copy)!!)!!.introFxId)
        val dupAdventure = scenes.duplicateAdventure(adventure)!!
        assertEquals(storm, scenes.observeAdventureScenes(dupAdventure).first().first().introFxId)
    }

    @Test
    fun deletingTheIntroFx_clearsIt_andUndoBringsItBack(): Unit = runBlocking {
        val heal = fx.create("Heal")
        val scene = richScene("Tavern")
        scenes.setIntroFx(scene, heal)

        val deleted = fx.delete(heal)!!
        assertNull(scenes.getScene(scene)!!.introFxId)

        fx.restore(deleted)
        assertEquals(heal, scenes.getScene(scene)!!.introFxId)
    }

    /* Layered effects (database version 13) */

    @Test
    fun layers_saveInPriorityOrder_withSeveralOfOneKind(): Unit = runBlocking {
        val scene = scenes.createLibraryScene("Storm")
        scenes.saveSceneLight(light(scene, ceiling, 0f))
        val layered = GroupEffects(
            layers = listOf(
                EffectLayer.spin(com.dungeonvibes.data.db.SpinSettings(stepMs = 200)),
                EffectLayer.flashes(FlashSettings(attackMs = 10)),
                EffectLayer.spin(com.dungeonvibes.data.db.SpinSettings(stepMs = 900, reverse = true)),
            ),
        )
        assertTrue(scenes.saveGroupEffects(scene, ceiling, layered))

        val saved = scenes.getSceneEffects(scene).getValue(ceiling)
        assertEquals(layered, saved)
        assertTrue(saved.layers.all { it.id > 0 })
        // Reordered and saved again: the new order sticks.
        val reordered = saved.copy(layers = saved.layers.reversed())
        assertTrue(scenes.saveGroupEffects(scene, ceiling, reordered))
        assertEquals(listOf(900, 0, 200).map { it }, scenes.getSceneEffects(scene).getValue(ceiling).layers.map { if (it.type == com.dungeonvibes.data.db.EffectLayerType.SPIN) it.spin.stepMs else 0 })
    }
}
