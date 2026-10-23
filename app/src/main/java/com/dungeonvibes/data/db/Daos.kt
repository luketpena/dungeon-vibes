package com.dungeonvibes.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    /** Every bulb in name order (the order Wave follows). */
    @Query("SELECT * FROM bulbs ORDER BY name COLLATE NOCASE, id")
    suspend fun all(): List<BulbEntity>

    @Query("SELECT * FROM bulbs WHERE id = :id")
    fun observe(id: Long): Flow<BulbEntity?>

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
    /** The user's groups in their order (which decides shared bulbs), without the hidden slots. */
    @Query("SELECT * FROM light_slots WHERE isRoom = 0 AND bulbId IS NULL ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<LightSlotEntity>>

    @Query("SELECT * FROM light_slots WHERE isRoom = 0 AND bulbId IS NULL ORDER BY sortOrder, id")
    suspend fun groups(): List<LightSlotEntity>

    /** Individual lights' hidden slots, named after their bulbs (kept current with renames). */
    @Query(
        """
        SELECT s.id, b.name AS name, s.sortOrder, s.isRoom, s.bulbId
        FROM light_slots s JOIN bulbs b ON b.id = s.bulbId
        ORDER BY b.name COLLATE NOCASE, s.id
        """,
    )
    fun observeBulbSlots(): Flow<List<LightSlotEntity>>

    @Query("SELECT * FROM light_slots WHERE bulbId IS NOT NULL")
    suspend fun bulbSlots(): List<LightSlotEntity>

    @Query("SELECT id FROM light_slots WHERE bulbId = :bulbId ORDER BY id LIMIT 1")
    suspend fun bulbSlotId(bulbId: Long): Long?

    @Query("SELECT id FROM light_slots WHERE isRoom = 1 ORDER BY id LIMIT 1")
    suspend fun roomSlotId(): Long?

    /** Ids of every slot, hidden ones included. */
    @Query("SELECT id FROM light_slots")
    suspend fun allIds(): List<Long>

    /** Ids of the user's groups (not the hidden slots). */
    @Query("SELECT id FROM light_slots WHERE isRoom = 0 AND bulbId IS NULL")
    suspend fun groupIds(): List<Long>

    @Query("SELECT id FROM light_slots WHERE isRoom = 1 ORDER BY id LIMIT 1")
    fun observeRoomSlotId(): Flow<Long?>

    /** Every assignment, each group's in its bulb order. */
    @Query("SELECT * FROM slot_bulbs ORDER BY slotId, position, bulbId")
    fun observeAssignments(): Flow<List<SlotBulbEntity>>

    @Query("SELECT * FROM slot_bulbs ORDER BY slotId, position, bulbId")
    suspend fun assignments(): List<SlotBulbEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM slot_bulbs WHERE slotId = :slotId")
    suspend fun nextPosition(slotId: Long): Int

    @Query("UPDATE slot_bulbs SET position = :position WHERE slotId = :slotId AND bulbId = :bulbId")
    suspend fun setPosition(slotId: Long, bulbId: Long, position: Int)

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM light_slots WHERE isRoom = 0 AND bulbId IS NULL")
    suspend fun nextSortOrder(): Int

    @Insert
    suspend fun insert(slot: LightSlotEntity): Long

    @Query("UPDATE light_slots SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE light_slots SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    @Query("DELETE FROM light_slots WHERE id = :id")
    suspend fun delete(id: Long)

    /** Puts a bulb in a slot; a bulb already there keeps its place (it can be in other slots too). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun assign(assignment: SlotBulbEntity)

    /** Takes a bulb out of every slot. */
    @Query("DELETE FROM slot_bulbs WHERE bulbId = :bulbId")
    suspend fun unassign(bulbId: Long)

    @Query("DELETE FROM slot_bulbs WHERE slotId = :slotId AND bulbId = :bulbId")
    suspend fun unassignFrom(slotId: Long, bulbId: Long)

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

    @Query("SELECT id FROM adventures ORDER BY sortOrder, id")
    suspend fun orderedIds(): List<Long>

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

    @Query("UPDATE scenes SET introFxId = :fxId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun setIntroFx(id: Long, fxId: Long?, updatedAt: Long)

    /** Scenes opening with this FX (for undoing its delete, which clears them). */
    @Query("SELECT id FROM scenes WHERE introFxId = :fxId")
    suspend fun introducedBy(fxId: Long): List<Long>

    /** Gives scenes their intro FX back, unless one was given another meanwhile. */
    @Query("UPDATE scenes SET introFxId = :fxId WHERE id IN (:ids) AND introFxId IS NULL")
    suspend fun relinkIntros(ids: List<Long>, fxId: Long)

    @Query("UPDATE scenes SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int)

    /** The adventure's (first) copy of a library scene, if it has one. */
    @Query("SELECT id FROM scenes WHERE adventureId = :adventureId AND parentSceneId = :librarySceneId ORDER BY sortOrder, id LIMIT 1")
    suspend fun findCopy(adventureId: Long, librarySceneId: Long): Long?

    @Query("DELETE FROM scenes WHERE id = :id")
    suspend fun delete(id: Long)

    /** Every scene in one list (the library, or one adventure), in order; never the default scene. */
    @Query("SELECT * FROM scenes WHERE adventureId IS :adventureId AND isDefault = 0 ORDER BY sortOrder, id")
    suspend fun getList(adventureId: Long?): List<SceneEntity>

    /** Adventure copies of a library scene (for undoing its delete, which unlinks them). */
    @Query("SELECT id FROM scenes WHERE parentSceneId = :sceneId")
    suspend fun copiesOf(sceneId: Long): List<Long>

    /** Relinks copies that are still unlinked (one promoted meanwhile keeps its new parent). */
    @Query("UPDATE scenes SET parentSceneId = :parentSceneId WHERE id IN (:ids) AND parentSceneId IS NULL")
    suspend fun relinkCopies(ids: List<Long>, parentSceneId: Long)
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

    @Query("DELETE FROM scene_lights WHERE sceneId = :sceneId AND slotId = :slotId")
    suspend fun delete(sceneId: Long, slotId: Long)

    @Insert
    suspend fun insertAll(lights: List<SceneLightEntity>)
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

    @Query("SELECT * FROM fx_targets WHERE fxId = :fxId")
    suspend fun targetRows(fxId: Long): List<FxTargetEntity>

    @Query("SELECT id FROM fx ORDER BY sortOrder, id")
    suspend fun orderedIds(): List<Long>

    /** FX that turn into this scene (for undoing its delete, which sets them back to Return). */
    @Query("SELECT id FROM fx WHERE transitionSceneId = :sceneId")
    suspend fun transitioningTo(sceneId: Long): List<Long>

    /** Points FX back at a restored scene, unless one was given another scene meanwhile. */
    @Query("UPDATE fx SET transitionSceneId = :sceneId WHERE id IN (:ids) AND transitionSceneId IS NULL")
    suspend fun relinkTransitions(ids: List<Long>, sceneId: Long)

    /* Per-adventure favorites */

    /** One adventure's favorite FX ids, in board order. */
    @Query("SELECT fxId FROM adventure_fx WHERE adventureId = :adventureId ORDER BY position, fxId")
    fun observeFavorites(adventureId: Long): Flow<List<Long>>

    @Query("SELECT * FROM adventure_fx WHERE adventureId = :adventureId")
    suspend fun favoritesOfAdventure(adventureId: Long): List<AdventureFxEntity>

    @Query("SELECT * FROM adventure_fx WHERE fxId = :fxId")
    suspend fun favoritesOfFx(fxId: Long): List<AdventureFxEntity>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM adventure_fx WHERE adventureId = :adventureId")
    suspend fun nextFavoritePosition(adventureId: Long): Int

    /** Rows whose adventure or FX is gone are skipped by the caller first; duplicates are ignored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFavorites(rows: List<AdventureFxEntity>)

    @Query("DELETE FROM adventure_fx WHERE adventureId = :adventureId AND fxId = :fxId")
    suspend fun removeFavorite(adventureId: Long, fxId: Long)

    @Query("SELECT id FROM fx")
    suspend fun allIds(): List<Long>
}
