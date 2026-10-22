package com.dungeonvibes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

// DAOs are interfaces Room implements at compile time (via KSP), a bit like a typed query layer.
// Functions returning Flow re-emit whenever the tables they read change.

@Dao
interface BulbDao {
    @Query("SELECT * FROM bulbs ORDER BY name COLLATE NOCASE, id")
    fun observeAll(): Flow<List<BulbEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM bulbs)")
    suspend fun hasAny(): Boolean

    @Query("SELECT * FROM bulbs WHERE protocolId = :protocolId AND serial = :serial")
    suspend fun findBySerial(protocolId: String, serial: String): BulbEntity?

    @Insert
    suspend fun insert(bulb: BulbEntity): Long

    @Query("UPDATE bulbs SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE bulbs SET lastKnownIp = :ip, lastSeenAt = :seenAt WHERE id = :id")
    suspend fun updateSeen(id: Long, ip: String, seenAt: Long)

    @Query("DELETE FROM bulbs WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LightSlotDao {
    /** The user's groups, without the hidden All-lights slot. */
    @Query("SELECT * FROM light_slots WHERE isRoom = 0 ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<LightSlotEntity>>

    @Query("SELECT id FROM light_slots WHERE isRoom = 1 ORDER BY id LIMIT 1")
    suspend fun roomSlotId(): Long?

    /** Ids of the user's groups (not the All-lights slot). */
    @Query("SELECT id FROM light_slots WHERE isRoom = 0")
    suspend fun groupIds(): List<Long>

    @Query("SELECT id FROM light_slots WHERE isRoom = 1 ORDER BY id LIMIT 1")
    fun observeRoomSlotId(): Flow<Long?>

    @Query("SELECT * FROM slot_bulbs")
    fun observeAssignments(): Flow<List<SlotBulbEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM light_slots WHERE isRoom = 0")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(slot: LightSlotEntity): Long

    @Query("UPDATE light_slots SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE light_slots SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM light_slots WHERE id = :id")
    suspend fun delete(id: Long)

    /** Insert or replace by bulbId, so assigning a bulb to a new slot moves it. */
    @Upsert
    suspend fun assign(assignment: SlotBulbEntity)

    @Query("DELETE FROM slot_bulbs WHERE bulbId = :bulbId")
    suspend fun unassign(bulbId: Long)

    @Query("SELECT * FROM slot_bulbs WHERE bulbId = :bulbId")
    suspend fun assignmentFor(bulbId: Long): SlotBulbEntity?

    @Query("DELETE FROM slot_bulbs WHERE slotId = :slotId")
    suspend fun unassignAll(slotId: Long)

    /** Scenes (library and adventure) that have a light row for this slot, on or off. */
    @Query("SELECT COUNT(DISTINCT sceneId) FROM scene_lights WHERE slotId = :slotId")
    suspend fun countScenesUsing(slotId: Long): Int
}

@Dao
interface AdventureDao {
    @Query(
        """
        SELECT a.*, COUNT(s.id) AS sceneCount
        FROM adventures a
        LEFT JOIN scenes s ON s.adventureId = a.id
        GROUP BY a.id
        ORDER BY a.sortOrder, a.id
        """,
    )
    fun observeAllWithSceneCounts(): Flow<List<AdventureWithSceneCount>>

    @Query("SELECT * FROM adventures WHERE id = :id")
    suspend fun get(id: Long): AdventureEntity?

    @Query("SELECT * FROM adventures WHERE id = :id")
    fun observe(id: Long): Flow<AdventureEntity?>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM adventures")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(adventure: AdventureEntity): Long

    @Query("UPDATE adventures SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE adventures SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM adventures WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SceneDao {
    @Query("SELECT * FROM scenes WHERE adventureId IS NULL AND isDefault = 0 ORDER BY sortOrder, id")
    fun observeLibrary(): Flow<List<SceneEntity>>

    @Query("SELECT * FROM scenes WHERE isDefault = 1 ORDER BY id LIMIT 1")
    suspend fun findDefault(): SceneEntity?

    @Query("UPDATE scenes SET isDefault = 1, allLightsOn = 1 WHERE id = :id")
    suspend fun markDefault(id: Long)

    @Query("SELECT * FROM scenes WHERE adventureId = :adventureId ORDER BY sortOrder, id")
    fun observeForAdventure(adventureId: Long): Flow<List<SceneEntity>>

    @Query("SELECT * FROM scenes WHERE adventureId IS NOT NULL ORDER BY adventureId, sortOrder, id")
    fun observeAllAdventureScenes(): Flow<List<SceneEntity>>

    @Query("SELECT * FROM scenes WHERE id = :id")
    fun observe(id: Long): Flow<SceneEntity?>

    @Query("SELECT * FROM scenes WHERE id = :id")
    suspend fun get(id: Long): SceneEntity?

    /** `IS` instead of `=` so a null adventureId (the library) matches too. */
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM scenes WHERE adventureId IS :adventureId")
    suspend fun nextSortOrder(adventureId: Long?): Int

    @Insert
    suspend fun insert(scene: SceneEntity): Long

    @Query("UPDATE scenes SET name = :name, fadeMs = :fadeMs, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDetails(id: Long, name: String, fadeMs: Int, updatedAt: Long)

    @Query("UPDATE scenes SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("UPDATE scenes SET allLightsOn = :on, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setAllLightsOn(id: Long, on: Boolean, updatedAt: Long)

    @Query("UPDATE scenes SET parentSceneId = :parentSceneId WHERE id = :id")
    suspend fun setParent(id: Long, parentSceneId: Long?)

    @Query("UPDATE scenes SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** The adventure's (first) copy of a library scene, if it has one. */
    @Query("SELECT id FROM scenes WHERE adventureId = :adventureId AND parentSceneId = :librarySceneId ORDER BY sortOrder, id LIMIT 1")
    suspend fun findCopy(adventureId: Long, librarySceneId: Long): Long?

    @Query("DELETE FROM scenes WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SceneLightDao {
    @Query("SELECT * FROM scene_lights WHERE sceneId = :sceneId")
    fun observeForScene(sceneId: Long): Flow<List<SceneLightEntity>>

    /** Light rows of every library scene, for the swatches in the library list. */
    @Query("SELECT sl.* FROM scene_lights sl JOIN scenes s ON s.id = sl.sceneId WHERE s.adventureId IS NULL AND s.isDefault = 0")
    fun observeLibraryLights(): Flow<List<SceneLightEntity>>

    /** Light rows of every scene in one adventure, for its tiles. */
    @Query("SELECT sl.* FROM scene_lights sl JOIN scenes s ON s.id = sl.sceneId WHERE s.adventureId = :adventureId")
    fun observeAdventureLights(adventureId: Long): Flow<List<SceneLightEntity>>

    @Query("SELECT * FROM scene_lights WHERE sceneId = :sceneId")
    suspend fun getForScene(sceneId: Long): List<SceneLightEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM scene_lights WHERE sceneId = :sceneId AND slotId = :slotId)")
    suspend fun exists(sceneId: Long, slotId: Long): Boolean

    @Upsert
    suspend fun upsert(light: SceneLightEntity)

    @Query("DELETE FROM scene_lights WHERE sceneId = :sceneId")
    suspend fun deleteForScene(sceneId: Long)

    /** Copies every light row from one scene to another in a single SQL statement. */
    @Query(
        """
        INSERT INTO scene_lights (sceneId, slotId, isOn, mode, hue, saturation, kelvin, brightness)
        SELECT :toSceneId, slotId, isOn, mode, hue, saturation, kelvin, brightness
        FROM scene_lights WHERE sceneId = :fromSceneId
        """,
    )
    suspend fun copyLights(fromSceneId: Long, toSceneId: Long)

    /**
     * One row per bulb that is assigned to a slot (rule 8): unassigned bulbs never appear, and
     * slots with no light row in this scene come back with isOn = false. With the scene's "All
     * lights" on, every bulb comes back lit with the All-lights settings (the room slot's row),
     * or the defaults ([LightDefaults]) if it has none. Within a slot, bulbs come in name order.
     */
    @Query(
        """
        SELECT b.id AS bulbId, b.name AS bulbName, b.protocolId, b.serial, b.lastKnownIp, sb.slotId,
               CASE WHEN sc.allLightsOn = 1 THEN 1 ELSE COALESCE(sl.isOn, 0) END AS isOn,
               COALESCE(CASE WHEN sc.allLightsOn = 1 THEN rl.mode ELSE sl.mode END, 'WHITE') AS mode,
               COALESCE(CASE WHEN sc.allLightsOn = 1 THEN rl.hue ELSE sl.hue END, ${LightDefaults.HUE}) AS hue,
               COALESCE(CASE WHEN sc.allLightsOn = 1 THEN rl.saturation ELSE sl.saturation END, ${LightDefaults.SATURATION}) AS saturation,
               COALESCE(CASE WHEN sc.allLightsOn = 1 THEN rl.kelvin ELSE sl.kelvin END, ${LightDefaults.KELVIN}) AS kelvin,
               COALESCE(CASE WHEN sc.allLightsOn = 1 THEN rl.brightness ELSE sl.brightness END, ${LightDefaults.BRIGHTNESS}) AS brightness
        FROM slot_bulbs sb
        JOIN bulbs b ON b.id = sb.bulbId
        LEFT JOIN scenes sc ON sc.id = :sceneId
        LEFT JOIN scene_lights sl ON sl.slotId = sb.slotId AND sl.sceneId = :sceneId
        LEFT JOIN scene_lights rl ON rl.sceneId = :sceneId
            AND rl.slotId = (SELECT id FROM light_slots WHERE isRoom = 1 ORDER BY id LIMIT 1)
        ORDER BY sb.slotId, b.name COLLATE NOCASE, b.id
        """,
    )
    suspend fun applyTargets(sceneId: Long): List<BulbApplyTarget>
}

@Dao
interface SceneEffectsDao {
    @Query("SELECT * FROM scene_light_effects WHERE sceneId = :sceneId")
    suspend fun getForScene(sceneId: Long): List<SceneLightEffectsEntity>

    @Query("SELECT * FROM scene_light_effects WHERE sceneId = :sceneId")
    fun observeForScene(sceneId: Long): Flow<List<SceneLightEffectsEntity>>

    /** Palette entries of every group in the scene, in palette order. */
    @Query("SELECT * FROM scene_light_effect_colors WHERE sceneId = :sceneId ORDER BY slotId, position")
    suspend fun getColorsForScene(sceneId: Long): List<EffectColorEntity>

    @Query("SELECT * FROM scene_light_effect_colors WHERE sceneId = :sceneId ORDER BY slotId, position")
    fun observeColorsForScene(sceneId: Long): Flow<List<EffectColorEntity>>

    /** Effect rows of every library scene, for the ✦ and palettes in the library list. */
    @Query("SELECT e.* FROM scene_light_effects e JOIN scenes s ON s.id = e.sceneId WHERE s.adventureId IS NULL AND s.isDefault = 0")
    fun observeLibraryEffects(): Flow<List<SceneLightEffectsEntity>>

    @Query(
        """
        SELECT c.* FROM scene_light_effect_colors c JOIN scenes s ON s.id = c.sceneId
        WHERE s.adventureId IS NULL AND s.isDefault = 0 ORDER BY c.sceneId, c.slotId, c.position
        """,
    )
    fun observeLibraryEffectColors(): Flow<List<EffectColorEntity>>

    /** Effect rows of every scene in one adventure, for its tiles. */
    @Query("SELECT e.* FROM scene_light_effects e JOIN scenes s ON s.id = e.sceneId WHERE s.adventureId = :adventureId")
    fun observeAdventureEffects(adventureId: Long): Flow<List<SceneLightEffectsEntity>>

    @Query(
        """
        SELECT c.* FROM scene_light_effect_colors c JOIN scenes s ON s.id = c.sceneId
        WHERE s.adventureId = :adventureId ORDER BY c.sceneId, c.slotId, c.position
        """,
    )
    fun observeAdventureEffectColors(adventureId: Long): Flow<List<EffectColorEntity>>

    @Upsert
    suspend fun upsert(effects: SceneLightEffectsEntity)

    @Insert
    suspend fun insertAll(effects: List<SceneLightEffectsEntity>)

    @Insert
    suspend fun insertColors(colors: List<EffectColorEntity>)

    @Query("DELETE FROM scene_light_effect_colors WHERE sceneId = :sceneId AND slotId = :slotId")
    suspend fun deleteColors(sceneId: Long, slotId: Long)

    @Query("DELETE FROM scene_light_effects WHERE sceneId = :sceneId AND slotId = :slotId")
    suspend fun delete(sceneId: Long, slotId: Long)
}

@Dao
interface FxDao {
    @Query("SELECT * FROM fx ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<FxEntity>>

    @Query("SELECT * FROM fx_targets")
    fun observeAllTargets(): Flow<List<FxTargetEntity>>

    @Query("SELECT * FROM fx WHERE id = :id")
    suspend fun get(id: Long): FxEntity?

    @Query("SELECT slotId FROM fx_targets WHERE fxId = :fxId")
    suspend fun targetsOf(fxId: Long): List<Long>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM fx")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(fx: FxEntity): Long

    @Update
    suspend fun update(fx: FxEntity)

    @Query("UPDATE fx SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM fx WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM fx_targets WHERE fxId = :fxId")
    suspend fun clearTargets(fxId: Long)

    @Insert
    suspend fun insertTargets(targets: List<FxTargetEntity>)
}
