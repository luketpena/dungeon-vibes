package com.dungeonvibes.data.scene

import androidx.room.withTransaction
import com.dungeonvibes.data.TimeSource
import com.dungeonvibes.data.copyName
import com.dungeonvibes.data.db.AdventureEntity
import com.dungeonvibes.data.db.AdventureFxEntity
import com.dungeonvibes.data.db.AppDatabase
import com.dungeonvibes.data.db.BulbApplyTarget
import com.dungeonvibes.data.db.EffectColorEntity
import com.dungeonvibes.data.db.EffectLayerEntity
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.db.SceneLightEffectsEntity
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.placedAfter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * A deleted scene with everything that pointed at it, for Undo ([SceneRepository.restoreScene]):
 * its rows, the adventure copies its delete unlinked, and the FX that turned into it.
 */
data class DeletedScene(
    val scene: SceneEntity,
    val lights: List<SceneLightEntity>,
    val effects: List<SceneLightEffectsEntity>,
    val colors: List<EffectColorEntity>,
    val layers: List<EffectLayerEntity> = emptyList(),
    val copyIds: List<Long>,
    val fxIds: List<Long>,
)

/** A deleted adventure with its scenes and FX favorites, for Undo ([SceneRepository.restoreAdventure]). */
data class DeletedAdventure(
    val adventure: AdventureEntity,
    val scenes: List<DeletedScene>,
    val favorites: List<AdventureFxEntity>,
)

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
    fun observeLibraryEffects(): Flow<Map<Long, Map<Long, GroupEffects>>> = combine(
        effectsDao.observeLibraryEffects(),
        effectsDao.observeLibraryEffectColors(),
        effectsDao.observeLibraryLayers(),
    ) { rows, colors, layers -> groupEffectsByScene(rows, colors, layers) }

    /** Effects of every scene in one adventure: scene id → slot id → effects. */
    fun observeAdventureEffects(adventureId: Long): Flow<Map<Long, Map<Long, GroupEffects>>> = combine(
        effectsDao.observeAdventureEffects(adventureId),
        effectsDao.observeAdventureEffectColors(adventureId),
        effectsDao.observeAdventureLayers(adventureId),
    ) { rows, colors, layers -> groupEffectsByScene(rows, colors, layers) }

    suspend fun getScene(sceneId: Long): SceneEntity? = sceneDao.get(sceneId)

    /** The adventure's copy of a library scene (its parent), if it holds one. */
    suspend fun findCopyInAdventure(adventureId: Long, librarySceneId: Long): Long? =
        sceneDao.findCopy(adventureId, librarySceneId)

    suspend fun getSceneLights(sceneId: Long): List<SceneLightEntity> = lightDao.getForScene(sceneId)

    /** Effect layers per slot id; slots without any effect settings are absent. */
    suspend fun getSceneEffects(sceneId: Long): Map<Long, GroupEffects> = db.withTransaction {
        val rows = effectsDao.getForScene(sceneId)
        val layers = effectsDao.getLayersForScene(sceneId).layersBySlot()
        val count = rows.size
        if (count == 0 && layers.isEmpty()) return@withTransaction emptyMap()
        val palettes = effectsDao.getColorsForScene(sceneId).palettesBySlot()
        val result = HashMap<Long, GroupEffects>((count + layers.size) * 2)
        for (i in 0 until count) {
            val row = rows[i]
            result[row.slotId] = row.toGroupEffects(palettes[row.slotId].orEmpty(), layers[row.slotId].orEmpty())
        }
        // Groups with layers but no Multi-color row.
        for ((slotId, list) in layers) if (slotId !in result) result[slotId] = GroupEffects(layers = list)
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

    /** Sets (or with null clears) the FX the scene opens with. */
    suspend fun setIntroFx(sceneId: Long, fxId: Long?) {
        sceneDao.setIntroFx(sceneId, fxId, time.nowMillis())
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
     * Saves one group's effects, replacing its palette and its layered effects (kept in the
     * order given: the first is the top). At most [GroupEffects.MAX_LAYERS] layers are kept. The group needs settings in this
     * scene first (effects belong to them); returns false and saves nothing when it has none.
     * Palettes longer than [GroupEffects.MAX_PALETTE] are cut to that length.
     */
    suspend fun saveGroupEffects(sceneId: Long, slotId: Long, effects: GroupEffects): Boolean = db.withTransaction {
        if (!lightDao.exists(sceneId, slotId)) return@withTransaction false
        effectsDao.upsert(SceneLightEffectsEntity(sceneId, slotId, effects.colorShift))
        effectsDao.deleteLayers(sceneId, slotId)
        val layers = effects.layers
        val layerCount = minOf(layers.size, GroupEffects.MAX_LAYERS)
        if (layerCount > 0) {
            val rows = ArrayList<EffectLayerEntity>(layerCount)
            for (i in 0 until layerCount) rows.add(layerEntity(sceneId, slotId, i, layers[i]))
            effectsDao.insertLayers(rows)
        }
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

    /**
     * Takes a slot out of the scene entirely: its settings, effect layers and palette. Used for an
     * individual light removed from a scene (it then follows its groups again).
     */
    suspend fun removeSceneLight(sceneId: Long, slotId: Long) = db.withTransaction {
        effectsDao.deleteLayers(sceneId, slotId)
        effectsDao.deleteColors(sceneId, slotId)
        effectsDao.delete(sceneId, slotId)
        lightDao.delete(sceneId, slotId)
        sceneDao.touch(sceneId, time.nowMillis())
    }

    /** Removes a group's effect layers and palette entirely. */
    suspend fun clearGroupEffects(sceneId: Long, slotId: Long) = db.withTransaction {
        effectsDao.delete(sceneId, slotId)
        effectsDao.deleteLayers(sceneId, slotId)
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
                introFxId = source.introFxId,
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
        sceneDao.setIntroFx(sceneId, parent.introFxId, time.nowMillis())
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
            introFxId = scene.introFxId,
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
    suspend fun deleteScene(sceneId: Long): DeletedScene? = db.withTransaction {
        val scene = sceneDao.get(sceneId) ?: return@withTransaction null
        val deleted = snapshot(scene)
        sceneDao.delete(sceneId)
        deleted
    }

    /**
     * Undo for [deleteScene]: puts the scene back with its id and place, relinking its copies and
     * FX. Returns false if it can't (it's back already, or its adventure is gone). Groups deleted
     * since are left out.
     */
    suspend fun restoreScene(deleted: DeletedScene): Boolean = db.withTransaction {
        insertSnapshot(deleted, db.lightSlotDao().allIds().toHashSet())
    }

    /**
     * Deletes an adventure; its scenes and favorites cascade away (library scenes are untouched).
     * Returns what Undo needs, or null if it was gone.
     */
    suspend fun deleteAdventure(adventureId: Long): DeletedAdventure? = db.withTransaction {
        val adventure = adventureDao.get(adventureId) ?: return@withTransaction null
        val scenes = sceneDao.getList(adventureId)
        val count = scenes.size
        val snapshots = ArrayList<DeletedScene>(count)
        for (i in 0 until count) snapshots.add(snapshot(scenes[i]))
        val deleted = DeletedAdventure(adventure, snapshots, db.fxDao().favoritesOfAdventure(adventureId))
        adventureDao.delete(adventureId)
        deleted
    }

    /** Undo for [deleteAdventure]: the adventure, its scenes and the favorites whose FX still exist. */
    suspend fun restoreAdventure(deleted: DeletedAdventure): Boolean = db.withTransaction {
        if (adventureDao.get(deleted.adventure.id) != null) return@withTransaction false
        adventureDao.insert(deleted.adventure)
        val slots = db.lightSlotDao().allIds().toHashSet()
        for (scene in deleted.scenes) insertSnapshot(scene, slots)
        if (deleted.favorites.isNotEmpty()) {
            val fxDao = db.fxDao()
            val fxIds = fxDao.allIds().toHashSet()
            val favorites = deleted.favorites.filter { it.fxId in fxIds }
            if (favorites.isNotEmpty()) fxDao.insertFavorites(favorites)
        }
        true
    }

    /* Duplicate */

    /**
     * Copies a scene into the same list (library or adventure), right after it, as "<name> copy".
     * An adventure scene's copy keeps its library parent, so it can be reset too. Returns the new
     * id, or null for a missing or default scene.
     */
    suspend fun duplicateScene(sceneId: Long): Long? = db.withTransaction {
        val source = sceneDao.get(sceneId) ?: return@withTransaction null
        if (source.isDefault) return@withTransaction null
        val newId = insertScene(
            name = copyName(source.name),
            adventureId = source.adventureId,
            parentSceneId = source.parentSceneId,
            fadeMs = source.fadeMs,
            allLightsOn = source.allLightsOn,
            introFxId = source.introFxId,
        )
        copyLightsAndEffects(fromSceneId = source.id, toSceneId = newId)
        val ordered = sceneDao.getList(source.adventureId)
        val ids = ArrayList<Long>(ordered.size)
        for (scene in ordered) ids.add(scene.id)
        reorderScenes(placedAfter(ids, source.id, newId))
        newId
    }

    /**
     * Copies an adventure, right after it, as "<name> copy": every scene (each keeping its library
     * parent) and its FX favorites. Returns the new id, or null if it was gone.
     */
    suspend fun duplicateAdventure(adventureId: Long): Long? = db.withTransaction {
        val source = adventureDao.get(adventureId) ?: return@withTransaction null
        val now = time.nowMillis()
        val newId = adventureDao.insert(
            AdventureEntity(name = copyName(source.name), sortOrder = adventureDao.nextSortOrder(), createdAt = now, updatedAt = now),
        )
        for (scene in sceneDao.getList(adventureId)) {
            val copyId = insertScene(scene.name, newId, scene.parentSceneId, scene.fadeMs, scene.allLightsOn, scene.introFxId)
            copyLightsAndEffects(fromSceneId = scene.id, toSceneId = copyId)
        }
        val fxDao = db.fxDao()
        val favorites = fxDao.favoritesOfAdventure(adventureId)
        if (favorites.isNotEmpty()) fxDao.insertFavorites(favorites.map { it.copy(adventureId = newId) })
        val order = placedAfter(adventureDao.orderedIds(), adventureId, newId)
        val count = order.size
        for (i in 0 until count) adventureDao.setSortOrder(order[i], i)
        newId
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
     * What every bulb gets from this scene ([resolveScene]: its individual light, All lights, or its
     * highest lit group). Groups with no light row (including groups added after the scene, rule 9)
     * or switched off light nothing. Bulbs in no group are left out unless the scene sets them
     * individually, so they are never touched. A scene that doesn't exist turns every bulb off.
     */
    suspend fun applyTargets(sceneId: Long): List<BulbApplyTarget> = db.withTransaction {
        val setup = loadSetup()
        val scene = sceneDao.get(sceneId)
        if (scene == null) return@withTransaction resolveScene(setup, emptyMap(), allLightsOn = false, roomSlotId = null)
        val rows = lightDao.getForScene(sceneId)
        val lights = HashMap<Long, SceneLightEntity>(rows.size * 2)
        for (row in rows) lights[row.slotId] = row
        resolveScene(setup, lights, scene.allLightsOn, db.lightSlotDao().roomSlotId())
    }

    /** The bulbs, groups and individual-light slots a scene is worked out against. */
    private suspend fun loadSetup(): SceneSetup {
        val slotDao = db.lightSlotDao()
        val bulbSlots = HashMap<Long, Long>()
        for (slot in slotDao.bulbSlots()) {
            val bulbId = slot.bulbId ?: continue
            bulbSlots[bulbId] = slot.id
        }
        return SceneSetup(db.bulbDao().all(), slotDao.groups(), slotDao.assignments(), bulbSlots)
    }

    /* Internals */

    /**
     * Copies a scene's light rows, then their effect layers and palettes (FX rules: effects travel
     * with the rest of a group's settings). Must be called inside a transaction; [toSceneId] must
     * have no light rows yet.
     */
    private suspend fun copyLightsAndEffects(fromSceneId: Long, toSceneId: Long) {
        lightDao.copyLights(fromSceneId = fromSceneId, toSceneId = toSceneId)
        // Layers belong to light rows (not the Multi-color row), so they copy on their own.
        val layers = effectsDao.getLayersForScene(fromSceneId)
        if (layers.isNotEmpty()) effectsDao.insertLayers(layers.map { it.copy(id = 0, sceneId = toSceneId) })
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

    /** Everything Undo needs to put [scene] back. Must be called inside a transaction. */
    private suspend fun snapshot(scene: SceneEntity): DeletedScene {
        val id = scene.id
        return DeletedScene(
            scene = scene,
            lights = lightDao.getForScene(id),
            effects = effectsDao.getForScene(id),
            colors = effectsDao.getColorsForScene(id),
            layers = effectsDao.getLayersForScene(id),
            copyIds = sceneDao.copiesOf(id),
            fxIds = db.fxDao().transitioningTo(id),
        )
    }

    /**
     * Re-inserts a [snapshot] with its own id. Rows for slots not in [slots] (deleted since) are
     * left out, and a parent deleted since is dropped. Must be called inside a transaction.
     */
    private suspend fun insertSnapshot(deleted: DeletedScene, slots: Set<Long>): Boolean {
        var scene = deleted.scene
        if (sceneDao.get(scene.id) != null) return false
        val adventureId = scene.adventureId
        if (adventureId != null && adventureDao.get(adventureId) == null) return false
        val parentId = scene.parentSceneId
        if (parentId != null && sceneDao.get(parentId) == null) scene = scene.copy(parentSceneId = null)
        val introId = scene.introFxId
        if (introId != null && db.fxDao().get(introId) == null) scene = scene.copy(introFxId = null)
        sceneDao.insert(scene)

        val lights = deleted.lights.filter { it.slotId in slots }
        if (lights.isNotEmpty()) {
            lightDao.insertAll(lights)
            // Effect layers belong to light rows, so only those of restored rows can come back.
            val lit = HashSet<Long>(lights.size * 2)
            for (light in lights) lit.add(light.slotId)
            val effects = deleted.effects.filter { it.slotId in lit }
            if (effects.isNotEmpty()) effectsDao.insertAll(effects)
            val colors = deleted.colors.filter { it.slotId in lit }
            if (colors.isNotEmpty()) effectsDao.insertColors(colors)
            val layers = deleted.layers.filter { it.slotId in lit }
            if (layers.isNotEmpty()) effectsDao.insertLayers(layers)
        }
        if (deleted.copyIds.isNotEmpty()) sceneDao.relinkCopies(deleted.copyIds, scene.id)
        if (deleted.fxIds.isNotEmpty()) db.fxDao().relinkTransitions(deleted.fxIds, scene.id)
        return true
    }

    /** Must be called inside a transaction, so the sort order can't race with another insert. */
    private suspend fun insertScene(
        name: String,
        adventureId: Long?,
        parentSceneId: Long?,
        fadeMs: Int,
        allLightsOn: Boolean = false,
        introFxId: Long? = null,
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
                introFxId = introFxId,
            ),
        )
    }

    companion object {
        const val DEFAULT_FADE_MS = 1_000
        /** Ten minutes, for sunrises and slowly gathering dread (owner request). */
        const val MAX_FADE_MS = 600_000

        /** Stored name of the default-lighting scene; the app shows its own label instead. */
        const val DEFAULT_SCENE_NAME = "Default lighting"
    }
}
