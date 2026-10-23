package com.dungeonvibes.data.slot

import androidx.room.withTransaction
import com.dungeonvibes.data.db.AppDatabase
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.db.SlotBulbEntity
import com.dungeonvibes.data.scene.SceneSetup
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

@Singleton
class LightSlotRepository @Inject constructor(
    private val db: AppDatabase,
) {
    private val slotDao = db.lightSlotDao()

    /** The user's groups, in their order (never the hidden slots). Higher wins a shared bulb. */
    fun observeSlots(): Flow<List<LightSlotEntity>> = slotDao.observeAll()

    /** Individual lights' hidden slots (one per bulb that has been set individually), named after their bulbs. */
    fun observeBulbSlots(): Flow<List<LightSlotEntity>> = slotDao.observeBulbSlots()

    /**
     * Every slot a scene can light that shows as a swatch, in precedence order: individual lights
     * first, then the groups.
     */
    fun observeSwatchSlots(): Flow<List<LightSlotEntity>> =
        combine(slotDao.observeBulbSlots(), slotDao.observeAll()) { single, groups -> single + groups }

    /** What scenes are worked out against (see SceneResolver), kept current. */
    fun observeSetup(bulbs: Flow<List<BulbEntity>>): Flow<SceneSetup> = combine(
        bulbs,
        slotDao.observeAll(),
        slotDao.observeAssignments(),
        slotDao.observeBulbSlots(),
    ) { bulbList, groups, assignments, single ->
        val bulbSlots = HashMap<Long, Long>(single.size * 2)
        for (slot in single) {
            val bulbId = slot.bulbId ?: continue
            bulbSlots[bulbId] = slot.id
        }
        SceneSetup(bulbList, groups, assignments, bulbSlots)
    }

    /** The bulb's individual-light slot, created on first use (it's hidden, and goes with the bulb). */
    suspend fun ensureBulbSlot(bulbId: Long): Long = db.withTransaction {
        slotDao.bulbSlotId(bulbId)
            ?: slotDao.insert(LightSlotEntity(name = INDIVIDUAL_SLOT_NAME, sortOrder = -1, bulbId = bulbId))
    }

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
     * Makes [bulbIds] exactly the bulbs in this group. Bulbs can be in several groups, so this
     * never takes a bulb out of another group.
     */
    suspend fun setSlotBulbs(slotId: Long, bulbIds: Collection<Long>) = db.withTransaction {
        slotDao.unassignAll(slotId)
        var position = 0
        for (bulbId in bulbIds) {
            slotDao.assign(SlotBulbEntity(slotId = slotId, bulbId = bulbId, position = position++))
        }
    }

    /** Adds bulbs at the end of a group, in the order given (ones already in it stay where they are). */
    suspend fun addBulbs(slotId: Long, bulbIds: Collection<Long>) = db.withTransaction {
        var position = slotDao.nextPosition(slotId)
        for (bulbId in bulbIds) {
            slotDao.assign(SlotBulbEntity(slotId = slotId, bulbId = bulbId, position = position++))
        }
    }

    /** Persists a group's bulb order (the order Wave and Spin follow). */
    suspend fun reorderBulbs(slotId: Long, orderedBulbIds: List<Long>) = db.withTransaction {
        val count = orderedBulbIds.size
        for (i in 0 until count) slotDao.setPosition(slotId, orderedBulbIds[i], i)
    }

    /**
     * Adds a bulb to a group (it stays in any others). Null takes it out of every group.
     */
    suspend fun assignBulb(bulbId: Long, slotId: Long?) {
        // (The hidden slots are never offered in Setup, so they never receive bulbs.)
        if (slotId == null) {
            slotDao.unassign(bulbId)
            return
        }
        addBulbs(slotId, listOf(bulbId))
    }

    /** Puts a bulb in a group (at the end) or takes it out, leaving its other groups alone. */
    suspend fun setInGroup(bulbId: Long, slotId: Long, inGroup: Boolean) {
        if (inGroup) {
            addBulbs(slotId, listOf(bulbId))
        } else {
            slotDao.unassignFrom(slotId, bulbId)
        }
    }

    private companion object {
        /** Internal only; the editor shows its own label. */
        const val ROOM_SLOT_NAME = "All lights"

        /** Internal only; individual lights show their bulb's name. */
        const val INDIVIDUAL_SLOT_NAME = "Individual light"
    }
}
