package com.dungeonvibes.data.debug

import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.db.AppDatabase
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Debug-only helpers for filling and wiping the database. Only reachable from the debug screen. */
@Singleton
class SampleData @Inject constructor(
    private val db: AppDatabase,
    private val slots: LightSlotRepository,
    private val scenes: SceneRepository,
    private val adventures: AdventureRepository,
) {
    /**
     * Adds 3 groups (no bulbs: assign real ones in Setup), 3 library scenes and an adventure
     * holding 2 copies.
     */
    suspend fun seed() {
        val ceiling = slots.createSlot("Ceiling")
        val leftLamp = slots.createSlot("Left lamp")
        val rightLamp = slots.createSlot("Right lamp")

        val tavern = scenes.createLibraryScene("Tavern")
        scenes.saveSceneLight(white(tavern, ceiling, kelvin = 2700, brightness = 0.6f))
        scenes.saveSceneLight(color(tavern, leftLamp, hue = 30f, brightness = 0.8f))
        scenes.saveSceneLight(color(tavern, rightLamp, hue = 30f, brightness = 0.8f))

        val dungeon = scenes.createLibraryScene("Dungeon")
        scenes.saveSceneLight(color(dungeon, ceiling, hue = 220f, brightness = 0.15f))

        scenes.createLibraryScene("Blackout")

        val adventure = adventures.create("The Lost Mine")
        scenes.addLibraryScenesToAdventure(adventure, listOf(tavern, dungeon))
    }

    /** Wipes every table. Room's clearAllTables blocks, so it runs on the IO thread pool. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        db.clearAllTables()
    }

    private fun color(sceneId: Long, slotId: Long, hue: Float, brightness: Float) = SceneLightEntity(
        sceneId = sceneId, slotId = slotId, isOn = true, mode = LightMode.COLOR,
        hue = hue, saturation = 1f, kelvin = 2700, brightness = brightness,
    )

    private fun white(sceneId: Long, slotId: Long, kelvin: Int, brightness: Float) = SceneLightEntity(
        sceneId = sceneId, slotId = slotId, isOn = true, mode = LightMode.WHITE,
        hue = 0f, saturation = 0f, kelvin = kelvin, brightness = brightness,
    )
}
