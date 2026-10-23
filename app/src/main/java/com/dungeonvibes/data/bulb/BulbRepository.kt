package com.dungeonvibes.data.bulb

import com.dungeonvibes.data.TimeSource
import com.dungeonvibes.data.db.AppDatabase
import com.dungeonvibes.data.db.BulbEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Known bulbs. Phase 3 adds discovery on top of [recordSeen]. */
@Singleton
class BulbRepository @Inject constructor(
    db: AppDatabase,
    private val time: TimeSource,
) {
    private val bulbDao = db.bulbDao()

    fun observeBulbs(): Flow<List<BulbEntity>> = bulbDao.observeAll()

    /** One bulb, or null once it's removed. */
    fun observeBulb(bulbId: Long): Flow<BulbEntity?> = bulbDao.observe(bulbId)

    suspend fun hasAnyBulbs(): Boolean = bulbDao.hasAny()

    /**
     * Records a bulb seen on the network. A known serial keeps its id and name and just gets the
     * new IP; an unknown one is added with [defaultName]. Returns the bulb id.
     */
    suspend fun recordSeen(protocolId: String, serial: String, ip: String, defaultName: String): Long {
        val now = time.nowMillis()
        val existing = bulbDao.findBySerial(protocolId, serial)
        if (existing != null) {
            bulbDao.updateSeen(existing.id, ip, now)
            return existing.id
        }
        return bulbDao.insert(
            BulbEntity(
                protocolId = protocolId,
                serial = serial,
                lastKnownIp = ip,
                name = defaultName,
                lastSeenAt = now,
            ),
        )
    }

    suspend fun rename(bulbId: Long, name: String) = bulbDao.rename(bulbId, name)

    /** Removes a bulb and its slot assignment. It comes back on the next scan if still online. */
    suspend fun remove(bulbId: Long) = bulbDao.delete(bulbId)
}
