package com.dungeonvibes.ui.setup.slots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.bulb.BulbDiscovery
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.toAddress
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One bulb on a group's page: the bulb, whether it answered the last scan, and its other groups. */
data class GroupMember(
    val bulb: BulbEntity,
    val isOnline: Boolean,
    val otherGroups: List<String>,
)

data class GroupDetailUiState(
    val isLoaded: Boolean = false,
    /** The group was deleted (here, or elsewhere) while the page was open. */
    val isMissing: Boolean = false,
    val group: LightSlotEntity? = null,
    /** The group's bulbs, in its bulb order (the order Wave and Spin follow). */
    val members: List<GroupMember> = emptyList(),
    /** Bulbs not in the group, by name, for "Add bulbs". */
    val others: List<GroupMember> = emptyList(),
)

/** One group's page in Setup: its name, its bulbs in order (drag to reorder), add, remove, delete. */
@HiltViewModel(assistedFactory = GroupDetailViewModel.Factory::class)
class GroupDetailViewModel @AssistedInject constructor(
    @Assisted private val slotId: Long,
    private val slots: LightSlotRepository,
    bulbs: BulbRepository,
    discovery: BulbDiscovery,
    private val lights: LightController,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(slotId: Long): GroupDetailViewModel
    }

    val uiState: StateFlow<GroupDetailUiState> = combine(
        slots.observeSlots(),
        slots.observeAssignments(),
        bulbs.observeBulbs(),
        discovery.onlineBulbIds,
    ) { groups, assignments, bulbList, online ->
        val group = groups.firstOrNull { it.id == slotId }
            ?: return@combine GroupDetailUiState(isLoaded = true, isMissing = true)
        val byId = HashMap<Long, BulbEntity>(bulbList.size * 2)
        for (bulb in bulbList) byId[bulb.id] = bulb
        val groupNames = HashMap<Long, String>(groups.size * 2)
        for (g in groups) groupNames[g.id] = g.name
        // Every bulb's groups, in group order (assignments come grouped by slot, in bulb order).
        val rank = HashMap<Long, Int>(groups.size * 2)
        for (i in groups.indices) rank[groups[i].id] = i
        val groupsOf = HashMap<Long, MutableList<Long>>()
        val memberIds = ArrayList<Long>()
        for (assignment in assignments) {
            if (assignment.slotId !in rank) continue
            groupsOf.getOrPut(assignment.bulbId) { ArrayList(2) }.add(assignment.slotId)
            if (assignment.slotId == slotId) memberIds.add(assignment.bulbId)
        }
        fun memberOf(bulb: BulbEntity): GroupMember {
            val others = groupsOf[bulb.id].orEmpty().filter { it != slotId }.sortedBy { rank.getValue(it) }
            return GroupMember(bulb, bulb.id in online, others.mapNotNull { groupNames[it] })
        }
        val inGroup = HashSet<Long>(memberIds.size * 2)
        val members = ArrayList<GroupMember>(memberIds.size)
        for (id in memberIds) {
            val bulb = byId[id] ?: continue
            if (inGroup.add(id)) members.add(memberOf(bulb))
        }
        val others = ArrayList<GroupMember>()
        for (bulb in bulbList) if (bulb.id !in inGroup) others.add(memberOf(bulb))
        GroupDetailUiState(isLoaded = true, group = group, members = members, others = others)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupDetailUiState())

    private val _pendingDeleteCount = MutableStateFlow<Int?>(null)

    /** Scenes using the group, while the delete confirmation is open; null when it isn't. */
    val pendingDeleteCount: StateFlow<Int?> = _pendingDeleteCount.asStateFlow()

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { slots.rename(slotId, trimmed) }
    }

    /** Persists the bulb order after a drag ends. */
    fun reorder(orderedBulbIds: List<Long>) {
        viewModelScope.launch { slots.reorderBulbs(slotId, orderedBulbIds) }
    }

    /** Adds bulbs at the end, in name order. */
    fun addBulbs(bulbIds: List<Long>) {
        if (bulbIds.isEmpty()) return
        viewModelScope.launch { slots.addBulbs(slotId, bulbIds) }
    }

    /** Takes a bulb out of this group only. */
    fun removeBulb(bulbId: Long) {
        viewModelScope.launch { slots.setInGroup(bulbId, slotId, inGroup = false) }
    }

    /** Blinks a bulb so it can be found in the room. */
    fun identify(bulb: BulbEntity) {
        viewModelScope.launch { lights.identify(bulb.toAddress()) }
    }

    fun requestDelete() {
        viewModelScope.launch { _pendingDeleteCount.value = slots.countScenesUsing(slotId) }
    }

    fun cancelDelete() {
        _pendingDeleteCount.value = null
    }

    fun confirmDelete() {
        _pendingDeleteCount.value = null
        viewModelScope.launch { slots.delete(slotId) }
    }
}
