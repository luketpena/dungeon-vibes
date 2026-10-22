package com.dungeonvibes.data.adventure

import androidx.room.withTransaction
import com.dungeonvibes.data.TimeSource
import com.dungeonvibes.data.db.AdventureEntity
import com.dungeonvibes.data.db.AdventureWithSceneCount
import com.dungeonvibes.data.db.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Adventure list operations. Scenes inside an adventure are handled by SceneRepository. */
@Singleton
class AdventureRepository @Inject constructor(
    private val db: AppDatabase,
    private val time: TimeSource,
) {
    private val adventureDao = db.adventureDao()

    fun observeAdventures(): Flow<List<AdventureWithSceneCount>> =
        adventureDao.observeAllWithSceneCounts()

    fun observeAdventure(adventureId: Long): Flow<AdventureEntity?> = adventureDao.observe(adventureId)

    suspend fun create(name: String): Long = db.withTransaction {
        val now = time.nowMillis()
        adventureDao.insert(
            AdventureEntity(
                name = name,
                sortOrder = adventureDao.nextSortOrder(),
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    suspend fun rename(adventureId: Long, name: String) =
        adventureDao.rename(adventureId, name, time.nowMillis())

    /** Deletes the adventure; its scenes cascade away. Library scenes are untouched. */
    suspend fun delete(adventureId: Long) = adventureDao.delete(adventureId)

    suspend fun reorder(orderedAdventureIds: List<Long>) = db.withTransaction {
        val count = orderedAdventureIds.size
        for (i in 0 until count) {
            adventureDao.setSortOrder(orderedAdventureIds[i], i)
        }
    }
}
