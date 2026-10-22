package com.dungeonvibes.ui.setup.slots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.slot.LightSlotRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SlotItem(
    val slot: LightSlotEntity,
    val bulbIds: Set<Long>,
    val bulbNames: List<String>,
)

/** A bulb as offered in a slot's checkbox list, with the slot it's currently in (if any). */
data class BulbChoice(
    val bulb: BulbEntity,
    val slotId: Long?,
    val slotName: String?,
)

data class SlotsUiState(
    val slots: List<SlotItem> = emptyList(),
    val bulbs: List<BulbChoice> = emptyList(),
    val isLoaded: Boolean = false,
)

/** A delete waiting for confirmation, with the number of scenes that use the slot. */
data class PendingSlotDelete(val slot: LightSlotEntity, val sceneCount: Int)

@HiltViewModel
class SlotsViewModel @Inject constructor(
    private val slots: LightSlotRepository,
    bulbs: BulbRepository,
) : ViewModel() {

    val uiState: StateFlow<SlotsUiState> = combine(
        slots.observeSlots(),
        slots.observeAssignments(),
        bulbs.observeBulbs(),
    ) { slotList, assignments, bulbList ->
        val slotNames = HashMap<Long, String>(slotList.size)
        for (slot in slotList) slotNames[slot.id] = slot.name
        val bulbNames = HashMap<Long, String>(bulbList.size)
        for (bulb in bulbList) bulbNames[bulb.id] = bulb.name
        val slotOfBulb = HashMap<Long, Long>(assignments.size)
        val bulbsOfSlot = HashMap<Long, MutableSet<Long>>()
        for (assignment in assignments) {
            slotOfBulb[assignment.bulbId] = assignment.slotId
            bulbsOfSlot.getOrPut(assignment.slotId) { LinkedHashSet() }.add(assignment.bulbId)
        }

        val slotItems = ArrayList<SlotItem>(slotList.size)
        for (slot in slotList) {
            val ids = bulbsOfSlot[slot.id] ?: emptySet()
            slotItems.add(SlotItem(slot, ids, ids.mapNotNull { bulbNames[it] }.sorted()))
        }
        val choices = ArrayList<BulbChoice>(bulbList.size)
        for (bulb in bulbList) {
            val slotId = slotOfBulb[bulb.id]
            choices.add(BulbChoice(bulb, slotId, slotId?.let { slotNames[it] }))
        }
        SlotsUiState(slots = slotItems, bulbs = choices, isLoaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SlotsUiState())

    private val _pendingDelete = MutableStateFlow<PendingSlotDelete?>(null)
    val pendingDelete: StateFlow<PendingSlotDelete?> = _pendingDelete.asStateFlow()

    /** Creates a slot at the end of the list and puts the chosen bulbs in it. */
    fun addSlot(name: String, bulbIds: Set<Long>) {
        viewModelScope.launch {
            val slotId = slots.createSlot(name)
            if (bulbIds.isNotEmpty()) slots.setSlotBulbs(slotId, bulbIds)
        }
    }

    /** Saves a slot's name and its exact set of bulbs (bulbs from other slots move here). */
    fun saveSlot(slot: LightSlotEntity, name: String, bulbIds: Set<Long>) {
        viewModelScope.launch {
            if (name != slot.name) slots.rename(slot.id, name)
            slots.setSlotBulbs(slot.id, bulbIds)
        }
    }

    /** Looks up how many scenes use the slot, then asks for confirmation. */
    fun requestDelete(slot: LightSlotEntity) {
        viewModelScope.launch {
            _pendingDelete.value = PendingSlotDelete(slot, slots.countScenesUsing(slot.id))
        }
    }

    fun confirmDelete() {
        val pending = _pendingDelete.value ?: return
        _pendingDelete.value = null
        viewModelScope.launch { slots.delete(pending.slot.id) }
    }

    fun cancelDelete() {
        _pendingDelete.value = null
    }

    /** Persists the order after a drag ends. */
    fun reorder(orderedSlotIds: List<Long>) {
        viewModelScope.launch { slots.reorder(orderedSlotIds) }
    }
}
