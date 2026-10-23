package com.dungeonvibes.ui.setup.bulbs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.bulb.BulbDiscovery
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.lifx.LifxLanAdapter
import com.dungeonvibes.light.toAddress
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One group on a bulb's page: whether the bulb is in it, and whether it's the group that wins it. */
data class GroupMembership(
    val slot: LightSlotEntity,
    val isMember: Boolean,
    /** The bulb's highest group: it decides the bulb's look when several of its groups are lit. */
    val isTop: Boolean,
)

data class BulbDetailUiState(
    val isLoaded: Boolean = false,
    /** The bulb was removed (here, or elsewhere) while the page was open. */
    val isMissing: Boolean = false,
    val bulb: BulbEntity? = null,
    val isOnline: Boolean = false,
    /** The maker, when the app knows it from how it talks to the bulb; null if not. */
    val brand: String? = null,
    /** Every group, in group order, with this bulb's membership. */
    val groups: List<GroupMembership> = emptyList(),
)

/** One bulb's page in Setup: its details, its groups (it can be in several), rename, identify, remove. */
@HiltViewModel(assistedFactory = BulbDetailViewModel.Factory::class)
class BulbDetailViewModel @AssistedInject constructor(
    @Assisted private val bulbId: Long,
    private val bulbs: BulbRepository,
    private val slots: LightSlotRepository,
    discovery: BulbDiscovery,
    private val lights: LightController,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(bulbId: Long): BulbDetailViewModel
    }

    val uiState: StateFlow<BulbDetailUiState> = combine(
        bulbs.observeBulb(bulbId),
        slots.observeSlots(),
        slots.observeAssignments(),
        discovery.onlineBulbIds,
    ) { bulb, groups, assignments, online ->
        if (bulb == null) return@combine BulbDetailUiState(isLoaded = true, isMissing = true)
        val mine = HashSet<Long>()
        for (assignment in assignments) if (assignment.bulbId == bulbId) mine.add(assignment.slotId)
        var topFound = false
        val memberships = ArrayList<GroupMembership>(groups.size)
        for (group in groups) {
            val member = group.id in mine
            val top = member && !topFound
            if (top) topFound = true
            memberships.add(GroupMembership(group, member, top))
        }
        BulbDetailUiState(
            isLoaded = true,
            bulb = bulb,
            isOnline = bulbId in online,
            brand = brandOf(bulb.protocolId),
            groups = memberships,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BulbDetailUiState())

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { bulbs.rename(bulbId, trimmed) }
    }

    /** Blinks the bulb so it can be found in the room. */
    fun identify() {
        val bulb = uiState.value.bulb ?: return
        viewModelScope.launch { lights.identify(bulb.toAddress()) }
    }

    /** Adds the bulb to a group or takes it out; its other groups are untouched. */
    fun setInGroup(slotId: Long, inGroup: Boolean) {
        viewModelScope.launch { slots.setInGroup(bulbId, slotId, inGroup) }
    }

    fun remove() {
        viewModelScope.launch { bulbs.remove(bulbId) }
    }
}

/** The maker, as far as the protocol tells it (the app doesn't read model names from bulbs yet). */
internal fun brandOf(protocolId: String): String? = when (protocolId) {
    LifxLanAdapter.PROTOCOL_ID -> "LIFX"
    else -> null
}
