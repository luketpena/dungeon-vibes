package com.dungeonvibes.data.scene

import androidx.room.withTransaction
import com.dungeonvibes.data.TimeSource
import com.dungeonvibes.data.db.AppDatabase
import com.dungeonvibes.data.db.BulbApplyTarget
import com.dungeonvibes.data.db.EffectColorEntity
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.db.SceneLightEffectsEntity
import com.dungeonvibes.data.db.SceneLightEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Owns every scene rule from REQUIREMENTS.md → Data model. Each write that touches more than one
 * row runs inside a single Room transaction, so a crash mid-way never leaves a half-copied scene.
 *
 * Adventure scenes are always independent copies; the only link back to the library is
 * [SceneEntity.parentSceneId], used for reset.
 */
@Singleton
class SceneRepository @Inject constructor(
    private val db: AppDatabase,
    private val time: TimeSource,
) {
    private val sceneDao = db.sceneDao()
    private val lightDao = db.sceneLightDao()
    private val adventureDao = db.adventureDao()
    private val effectsDao = db.sceneEffectsDao()

    /* Reads */

    fun observeLibraryScenes(): Flow<List<SceneEntity>> = sceneDao.observeLibrary()

    fun observeAdventureScenes(adventureId: Long): Flow<List<SceneEntity>> =
        sceneDao.observeForAdventure(adventureId)

    fun observeAllAdventureScenes(): Flow<List<SceneEntity>> = sceneDao.observeAllAdventureScenes()

    fun observeScene(sceneId: Long): Flow<SceneEntity?> = sceneDao.observe(sceneId)

    fun observeSceneLights(sceneId: Long): Flow<List<SceneLightEntity>> =
        lightDao.observeForScene(sceneId)

    fun observeLibraryLights(): Flow<List<SceneLightEntity>> = lightDao.observeLibraryLights()

    fun observeAdventureLights(adventureId: Long): Flow<List<SceneLightEntity>> =
        lightDao.observeAdventureLights(adventureId)

    /** Effects of every library scene: scene id → slot id → effects. */
    fun observeLibraryEffects(): Flow<Map<Long, Map<Long, GroupEffects>>> =
        combine(effectsDao.observeLibraryEffects(), effectsDao.observeLibraryEffectColors()) { rows, colors ->
            groupEffectsByScene(rows, colors)
        }

    /** Effects of every scene in one adventure: scene id → slot id → effects. */
    fun observeAdventureEffects(adventureId: Long): Flow<Map<Long, Map<Long, GroupEffects>>> = combine(
        effectsDao.observeAdventureEffects(adventureId),
        effectsDao.observeAdventureEffectColors(adventureId),
    ) { rows, colors -> groupEffectsByScene(rows, colors) }

    suspend fun getScene(sceneId: Long): SceneEntity? = sceneDao.get(sceneId)

    /** The adventure's copy of a library scene (its parent), if it holds one. */
    suspend fun findCopyInAdventure(adventureId: Long, librarySceneId: Long): Long? =
        sceneDao.findCopy(adventureId, librarySceneId)

    suspend fun getSceneLights(sceneId: Long): List<SceneLightEntity> = lightDao.getForScene(sceneId)

    /** Effect layers per slot id; slots without any effect settings are absent. */
    suspend fun getSceneEffects(sceneId: Long): Map<Long, GroupEffects> = db.withTransaction {
        val rows = effectsDao.getForScene(sceneId)
        val count = rows.size
        if (count == 0) return@withTransaction emptyMap()
        val palettes = effectsDao.getColorsForScene(sceneId).palettesBySlot()
        val result = HashMap<Long, GroupEffects>(count * 2)
        for (i in 0 until count) {
            val row = rows[i]
            result[row.slotId] = row.toGroupEffects(palettes[row.slotId].orEmpty())
        }
        result
    }

    /* Create */

    suspend fun createLibraryScene(name: String): Long =
        db.withTransaction { insertScene(name, adventureId = null, parentSceneId = null, fadeMs = DEFAULT_FADE_MS) }

    /**
     * The default-lighting scene's id, created on first use: All lights on, in the default warm
     * white (it can be edited like any scene, from Setup).
     */
    suspend fun ensureDefaultScene(): Long = db.withTransaction {
        sceneDao.findDefault()?.let { return@withTransaction it.id }
        val id = insertScene(DEFAULT_SCENE_NAME, adventureId = null, parentSceneId = null, fadeMs = DEFAULT_FADE_MS)
        sceneDao.markDefault(id)
        id
    }

    /** Returns null if the adventure doesn't exist. */
    suspend fun createAdventureScene(adventureId: Long, name: String): Long? = db.withTransaction {
        if (adventureDao.get(adventureId) == null) return@withTransaction null
        insertScene(name, adventureId = adventureId, parentSceneId = null, fadeMs = DEFAULT_FADE_MS)
    }

    /* Edit (rules 2, 3, 7) */

    // Edits only ever touch the one scene's own rows, so copies and parents are never affected.

    suspend fun updateSceneDetails(sceneId: Long, name: String, fadeMs: Int) {
        sceneDao.updateDetails(sceneId, name, fadeMs.coerceIn(0, MAX_FADE_MS), time.nowMillis())
    }

    /** The hidden All-lights slot, if it exists yet. */
    suspend fun roomSlotId(): Long? = db.lightSlotDao().roomSlotId()

    /** "All lights": every bulb plays the scene's All-lights settings; groups keep their own underneath. */
    suspend fun setAllLightsOn(sceneId: Long, on: Boolean) {
        sceneDao.setAllLightsOn(sceneId, on, time.nowMillis())
    }

    /** Saves one slot's settings. Off rows and both color/white values are stored as given (rule 7). */
    suspend fun saveSceneLight(light: SceneLightEntity) = db.withTransaction {
        lightDao.upsert(light)
        sceneDao.touch(light.sceneId, time.nowMillis())
    }

    /**
     * Saves one group's effect layers, replacing its palette. The group needs settings in this
     * scene first (effects belong to them); returns false and saves nothing when it has none.
     * Palettes longer than [GroupEffects.MAX_PALETTE] are cut to that length.
     */
    suspend fun saveGroupEffects(sceneId: Long, slotId: Long, effects: GroupEffects): Boolean = db.withTransaction {
        if (!lightDao.exists(sceneId, slotId)) return@withTransaction false
        effectsDao.upsert(
            SceneLightEffectsEntity(sceneId, slotId, effects.colorShift, effects.brightnessShift, effects.flashes),
        )
        effectsDao.deleteColors(sceneId, slotId)
        val palette = effects.palette
        val count = minOf(palette.size, GroupEffects.MAX_PALETTE)
        if (count > 0) {
            val rows = ArrayList<EffectColorEntity>(count)
            for (i in 0 until count) rows.add(EffectColorEntity(sceneId, slotId, i, palette[i]))
            effectsDao.insertColors(rows)
        }
        sceneDao.touch(sceneId, time.nowMillis())
        true
    }

    /** Removes a group's effect layers and palette entirely. */
    suspend fun clearGroupEffects(sceneId: Long, slotId: Long) = db.withTransaction {
        effectsDao.delete(sceneId, slotId)
        sceneDao.touch(sceneId, time.nowMillis())
    }

    /* Rule 1: add library scenes to an adventure */

    /**
     * Deep-copies each library scene into the adventure, in the given order, each as its own copy
     * with parentSceneId pointing back at its library scene. The same scene may appear repeatedly.
     * Ids that aren't library scenes are skipped. Returns the new scene ids.
     */
    suspend fun addLibraryScenesToAdventure(
        adventureId: Long,
        librarySceneIds: List<Long>,
    ): List<Long> = db.withTransaction {
        val count = librarySceneIds.size
        if (count == 0) return@withTransaction emptyList()
        if (adventureDao.get(adventureId) == null) return@withTransaction emptyList()

        val newIds = ArrayList<Long>(count)
        for (i in 0 until count) {
            val source = sceneDao.get(librarySceneIds[i]) ?: continue
            if (!source.isLibraryScene) continue
            val newId = insertScene(
                name = source.name,
                adventureId = adventureId,
                parentSceneId = source.id,
                fadeMs = source.fadeMs,
                allLightsOn = source.allLightsOn,
            )
            copyLightsAndEffects(fromSceneId = source.id, toSceneId = newId)
            newIds.add(newId)
        }
        newIds
    }

    /* Rule 4: reset to library version */

    /**
     * Replaces the adventure scene's lights, fade and name with its parent's.
     * Returns false (and changes nothing) when there is no parent to reset to.
     */
    suspend fun resetToLibraryVersion(sceneId: Long): Boolean = db.withTransaction {
        val scene = sceneDao.get(sceneId) ?: return@withTransaction false
        if (!scene.canResetToLibrary) return@withTransaction false
        val parent = sceneDao.get(scene.parentSceneId!!) ?: return@withTransaction false

        // Deleting the light rows cascades to their effect layers and palettes.
        lightDao.deleteForScene(sceneId)
        copyLightsAndEffects(fromSceneId = parent.id, toSceneId = sceneId)
        sceneDao.updateDetails(sceneId, parent.name, parent.fadeMs, time.nowMillis())
        sceneDao.setAllLightsOn(sceneId, parent.allLightsOn, time.nowMillis())
        true
    }

    /* Rule 5: promote */

    /**
     * Saves an adventure scene into the library as a new library scene, then points the adventure
     * scene's parent at it. Returns the new library scene id, or null for a library scene.
     */
    suspend fun promoteToLibrary(sceneId: Long): Long? = db.withTransaction {
        val scene = sceneDao.get(sceneId) ?: return@withTransaction null
        if (scene.adventureId == null) return@withTransaction null // library or default scene

        val libraryId = insertScene(
            name = scene.name,
            adventureId = null,
            parentSceneId = null,
            fadeMs = scene.fadeMs,
            allLightsOn = scene.allLightsOn,
        )
        copyLightsAndEffects(fromSceneId = sceneId, toSceneId = libraryId)
        sceneDao.setParent(sceneId, libraryId)
        libraryId
    }

    /* Rule 6: delete */

    /**
     * Deletes any scene. For a library scene, copies stay in their adventures and the foreign key
     * sets their parentSceneId to null, which hides Reset for them. Light rows cascade.
     */
    suspend fun deleteScene(sceneId: Long) {
        sceneDao.delete(sceneId)
    }

    /** Persists a new order for scenes in one list (the library, or one adventure). */
    suspend fun reorderScenes(orderedSceneIds: List<Long>) = db.withTransaction {
        val count = orderedSceneIds.size
        for (i in 0 until count) {
            sceneDao.setSortOrder(orderedSceneIds[i], i)
        }
    }

    /* Rule 8: what applying a scene does */

    /**
     * Every bulb assigned to a slot, with the settings this scene gives that slot. Slots with no
     * light row (including slots added after the scene, rule 9) or with isOn = false come back off.
     * Bulbs not assigned to any slot are left out, so they are never touched.
     */
    suspend fun applyTargets(sceneId: Long): List<BulbApplyTarget> = lightDao.applyTargets(sceneId)

    /* Internals */

    /**
     * Copies a scene's light rows, then their effect layers and palettes (FX rules: effects travel
     * with the rest of a group's settings). Must be called inside a transaction; [toSceneId] must
     * have no light rows yet.
     */
    private suspend fun copyLightsAndEffects(fromSceneId: Long, toSceneId: Long) {
        lightDao.copyLights(fromSceneId = fromSceneId, toSceneId = toSceneId)
        val effects = effectsDao.getForScene(fromSceneId)
        val effectCount = effects.size
        if (effectCount == 0) return
        val effectCopies = ArrayList<SceneLightEffectsEntity>(effectCount)
        for (i in 0 until effectCount) effectCopies.add(effects[i].copy(sceneId = toSceneId))
        effectsDao.insertAll(effectCopies)

        val colors = effectsDao.getColorsForScene(fromSceneId)
        val colorCount = colors.size
        if (colorCount == 0) return
        val colorCopies = ArrayList<EffectColorEntity>(colorCount)
        for (i in 0 until colorCount) colorCopies.add(colors[i].copy(sceneId = toSceneId))
        effectsDao.insertColors(colorCopies)
    }

    /** Must be called inside a transaction, so the sort order can't race with another insert. */
    private suspend fun insertScene(
        name: String,
        adventureId: Long?,
        parentSceneId: Long?,
        fadeMs: Int,
        allLightsOn: Boolean = false,
    ): Long {
        val now = time.nowMillis()
        return sceneDao.insert(
            SceneEntity(
                name = name,
                adventureId = adventureId,
                parentSceneId = parentSceneId,
                sortOrder = sceneDao.nextSortOrder(adventureId),
                fadeMs = fadeMs,
                createdAt = now,
                updatedAt = now,
                allLightsOn = allLightsOn,
            ),
        )
    }

    companion object {
        const val DEFAULT_FADE_MS = 1_000
        const val MAX_FADE_MS = 10_000

        /** Stored name of the default-lighting scene; the app shows its own label instead. */
        const val DEFAULT_SCENE_NAME = "Default lighting"
    }
}
