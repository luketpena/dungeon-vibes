package com.dungeonvibes.data.fx

import androidx.room.withTransaction
import com.dungeonvibes.data.TimeSource
import com.dungeonvibes.data.copyName
import com.dungeonvibes.data.placedAfter
import com.dungeonvibes.data.db.AdventureFxEntity
import com.dungeonvibes.data.db.AppDatabase
import com.dungeonvibes.data.db.FxEntity
import com.dungeonvibes.data.db.FxTargetEntity
import com.dungeonvibes.data.db.FxTransition
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** An FX with its target groups (empty when it targets All lights). */
data class FxItem(
    val fx: FxEntity,
    val targetSlotIds: Set<Long> = emptySet(),
) {
    /** Group-targeted with no groups left (all deleted): can't be fired until some are picked. */
    val hasNoTargets: Boolean get() = !fx.targetAll && targetSlotIds.isEmpty()
}

/** A deleted FX with everything that pointed at it, for Undo ([FxRepository.restore]). */
data class DeletedFx(
    val fx: FxEntity,
    val targets: List<FxTargetEntity>,
    val favorites: List<AdventureFxEntity>,
    /** Scenes that opened with it (its delete cleared their intro). */
    val introSceneIds: List<Long> = emptyList(),
)

/**
 * The FX library (FX_REQUIREMENTS.md → FX library and board): one list shared by every adventure.
 * Transitions only go into library scenes; anything else is stored as Return.
 */
@Singleton
class FxRepository @Inject constructor(
    private val db: AppDatabase,
    private val time: TimeSource,
) {
    private val fxDao = db.fxDao()
    private val sceneDao = db.sceneDao()

    fun observeFx(): Flow<List<FxItem>> = combine(fxDao.observeAll(), fxDao.observeAllTargets()) { list, targets ->
        val bySlot = HashMap<Long, MutableSet<Long>>()
        for (target in targets) bySlot.getOrPut(target.fxId) { HashSet() }.add(target.slotId)
        list.map { FxItem(it, bySlot[it.id].orEmpty()) }
    }

    suspend fun get(fxId: Long): FxItem? = db.withTransaction {
        val fx = fxDao.get(fxId) ?: return@withTransaction null
        FxItem(fx, fxDao.targetsOf(fxId).toHashSet())
    }

    /** A new FX at the end of the library: a white Pulse on All lights that returns. */
    suspend fun create(name: String): Long = db.withTransaction {
        val now = time.nowMillis()
        fxDao.insert(FxEntity(name = name, sortOrder = fxDao.nextSortOrder(), createdAt = now, updatedAt = now))
    }

    /**
     * Saves an FX and its targets. A transition into anything but an existing library scene is
     * saved as Return. Does nothing if the FX was deleted meanwhile.
     */
    suspend fun save(item: FxItem) = db.withTransaction {
        val stored = fxDao.get(item.fx.id) ?: return@withTransaction
        var fx = item.fx.copy(
            // A blank name (mid-edit) keeps the old one.
            name = item.fx.name.trim().ifEmpty { stored.name },
            createdAt = stored.createdAt,
            sortOrder = stored.sortOrder,
            updatedAt = time.nowMillis(),
        )
        if (fx.transition == FxTransition.SCENE) {
            val target = fx.transitionSceneId?.let { sceneDao.get(it) }
            if (target == null || !target.isLibraryScene) fx = fx.copy(transition = FxTransition.RETURN, transitionSceneId = null)
        } else if (fx.transitionSceneId != null) {
            fx = fx.copy(transitionSceneId = null)
        }
        fxDao.update(fx)
        fxDao.clearTargets(fx.id)
        if (!fx.targetAll && item.targetSlotIds.isNotEmpty()) {
            // Only groups that still exist (one may have been deleted while the FX was open).
            val groups = db.lightSlotDao().groupIds().toHashSet()
            val targets = item.targetSlotIds.filter { it in groups }.map { FxTargetEntity(fx.id, it) }
            if (targets.isNotEmpty()) fxDao.insertTargets(targets)
        }
    }

    /** Deletes an FX (its targets and favorites cascade) and returns what Undo needs, or null if it was gone. */
    suspend fun delete(fxId: Long): DeletedFx? = db.withTransaction {
        val fx = fxDao.get(fxId) ?: return@withTransaction null
        val deleted = DeletedFx(fx, fxDao.targetRows(fxId), fxDao.favoritesOfFx(fxId), sceneDao.introducedBy(fxId))
        fxDao.delete(fxId)
        deleted
    }

    /**
     * Puts a deleted FX back with its id, place, targets and favorites. Groups, adventures or a
     * transition scene deleted since are left out (the transition falls back to Return).
     */
    suspend fun restore(deleted: DeletedFx) = db.withTransaction {
        if (fxDao.get(deleted.fx.id) != null) return@withTransaction
        var fx = deleted.fx
        val sceneId = fx.transitionSceneId
        if (sceneId != null && sceneDao.get(sceneId) == null) fx = fx.copy(transitionSceneId = null)
        fxDao.insert(fx)
        if (deleted.targets.isNotEmpty()) {
            val slots = db.lightSlotDao().allIds().toHashSet()
            val targets = deleted.targets.filter { it.slotId in slots }
            if (targets.isNotEmpty()) fxDao.insertTargets(targets)
        }
        if (deleted.favorites.isNotEmpty()) {
            val adventureDao = db.adventureDao()
            val favorites = deleted.favorites.filter { adventureDao.get(it.adventureId) != null }
            if (favorites.isNotEmpty()) fxDao.insertFavorites(favorites)
        }
        if (deleted.introSceneIds.isNotEmpty()) sceneDao.relinkIntros(deleted.introSceneIds, fx.id)
    }

    /**
     * Copies an FX (settings and targets) into a new one right after it, named "<name> copy".
     * Favorites stay with the original. Returns the copy's id, or null if the FX was gone.
     */
    suspend fun duplicate(fxId: Long): Long? = db.withTransaction {
        val source = fxDao.get(fxId) ?: return@withTransaction null
        val now = time.nowMillis()
        val copyId = fxDao.insert(
            source.copy(id = 0, name = copyName(source.name), sortOrder = fxDao.nextSortOrder(), createdAt = now, updatedAt = now),
        )
        val targets = fxDao.targetRows(fxId)
        if (targets.isNotEmpty()) fxDao.insertTargets(targets.map { it.copy(fxId = copyId) })
        reorder(placedAfter(fxDao.orderedIds(), fxId, copyId))
        copyId
    }

    /* Per-adventure favorites */

    /** One adventure's favorite FX ids, in the order they were starred. */
    fun observeFavorites(adventureId: Long): Flow<List<Long>> = fxDao.observeFavorites(adventureId)

    /** Stars or un-stars an FX in one adventure; a new favorite goes last. */
    suspend fun setFavorite(adventureId: Long, fxId: Long, favorite: Boolean) = db.withTransaction {
        if (!favorite) {
            fxDao.removeFavorite(adventureId, fxId)
            return@withTransaction
        }
        if (fxDao.get(fxId) == null || db.adventureDao().get(adventureId) == null) return@withTransaction
        fxDao.insertFavorites(listOf(AdventureFxEntity(adventureId, fxId, fxDao.nextFavoritePosition(adventureId))))
    }

    suspend fun reorder(orderedIds: List<Long>) = db.withTransaction {
        val count = orderedIds.size
        for (i in 0 until count) fxDao.setSortOrder(orderedIds[i], i)
    }
}
