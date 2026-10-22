package com.dungeonvibes.data.slot

import androidx.room.withTransaction
import com.dungeonvibes.data.db.AppDatabase
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.db.SlotBulbEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class LightSlotRepository @Inject constructor(
    private val db: AppDatabase,
) {
    private val slotDao = db.lightSlotDao()

    /** The user's groups (never the hidden All-lights slot). */
    fun observeSlots(): Flow<List<LightSlotEntity>> = slotDao.observeAll()

    /** The hidden All-lights slot, or null before anything has needed it. */
    fun observeRoomSlotId(): Flow<Long?> = slotDao.observeRoomSlotId()

    /** The hidden All-lights slot, created on first use (and again after "clear all data"). */
    suspend fun ensureRoomSlot(): Long = db.withTransaction {
        slotDao.roomSlotId() ?: slotDao.insert(LightSlotEntity(name = ROOM_SLOT_NAME, sortOrder = -1, isRoom = true))
    }

    fun observeAssignments(): Flow<List<SlotBulbEntity>> = slotDao.observeAssignments()

    /**
     * Adds a slot at the end of the list. Existing scenes get no light row for it, so it stays off
     * in them until they're edited (rule 9).
     */
    suspend fun createSlot(name: String): Long = db.withTransaction {
        slotDao.insert(LightSlotEntity(name = name, sortOrder = slotDao.nextSortOrder()))
    }

    suspend fun rename(slotId: Long, name: String) = slotDao.rename(slotId, name)

    /** Deletes the slot; its bulb assignments and scene light rows cascade away. */
    suspend fun delete(slotId: Long) = slotDao.delete(slotId)

    suspend fun reorder(orderedSlotIds: List<Long>) = db.withTransaction {
        val count = orderedSlotIds.size
        for (i in 0 until count) {
            slotDao.setSortOrder(orderedSlotIds[i], i)
        }
    }

    /** How many scenes (library and adventure) have settings for this slot; shown before deleting it. */
    suspend fun countScenesUsing(slotId: Long): Int = slotDao.countScenesUsing(slotId)

    /**
     * Makes [bulbIds] exactly the bulbs in this slot: bulbs no longer listed leave it, and listed
     * bulbs that were in another slot move here.
     */
    suspend fun setSlotBulbs(slotId: Long, bulbIds: Collection<Long>) = db.withTransaction {
        slotDao.unassignAll(slotId)
        for (bulbId in bulbIds) {
            slotDao.assign(SlotBulbEntity(slotId = slotId, bulbId = bulbId))
        }
    }

    /** Puts a bulb in a slot, moving it out of any other slot. Null removes it from its slot. */
    suspend fun assignBulb(bulbId: Long, slotId: Long?) {
        // (The All-lights slot is never offered in Setup, so it never receives bulbs.)
        if (slotId == null) {
            slotDao.unassign(bulbId)
            return
        }
        slotDao.assign(SlotBulbEntity(slotId = slotId, bulbId = bulbId))
    }

    private companion object {
        /** Internal only; the editor shows its own label. */
        const val ROOM_SLOT_NAME = "All lights"
    }
}
