package com.dungeonvibes.ui.setup.bulbs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.bulb.BulbDiscovery
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.LocalNetworkPermission
import com.dungeonvibes.light.NetworkStatus
import com.dungeonvibes.light.toAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BulbItem(
    val bulb: BulbEntity,
    val isOnline: Boolean,
    /** Name of the slot this bulb is in, or null. */
    val slotName: String?,
)

data class BulbsUiState(
    val bulbs: List<BulbItem> = emptyList(),
    val isScanning: Boolean = false,
    /** False until the first scan of this screen finishes, so the empty state doesn't flash. */
    val hasScanned: Boolean = false,
)

@HiltViewModel
class BulbsViewModel @Inject constructor(
    private val bulbs: BulbRepository,
    slots: LightSlotRepository,
    private val discovery: BulbDiscovery,
    private val lights: LightController,
    private val permission: LocalNetworkPermission,
    networkStatus: NetworkStatus,
) : ViewModel() {

    private val hasScanned = MutableStateFlow(false)

    val uiState: StateFlow<BulbsUiState> = combine(
        bulbs.observeBulbs(),
        slots.observeSlots(),
        slots.observeAssignments(),
        discovery.onlineBulbIds,
        combine(discovery.isScanning, hasScanned) { scanning, scanned -> scanning to scanned },
    ) { bulbList, slotList, assignments, online, (scanning, scanned) ->
        // Index slots by id and assignments by bulb once, instead of searching per row.
        val slotNames = HashMap<Long, String>(slotList.size)
        for (slot in slotList) slotNames[slot.id] = slot.name
        val slotOfBulb = HashMap<Long, Long>(assignments.size)
        for (assignment in assignments) slotOfBulb[assignment.bulbId] = assignment.slotId

        val items = ArrayList<BulbItem>(bulbList.size)
        for (bulb in bulbList) {
            items.add(
                BulbItem(
                    bulb = bulb,
                    isOnline = bulb.id in online,
                    slotName = slotOfBulb[bulb.id]?.let { slotNames[it] },
                ),
            )
        }
        BulbsUiState(bulbs = items, isScanning = scanning, hasScanned = scanned)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BulbsUiState())

    val isOnLocalNetwork: StateFlow<Boolean> = networkStatus.isOnLocalNetwork

    private val _hasPermission = MutableStateFlow(permission.isGranted())

    /** False on Android 17+ until the user allows local network access. */
    val hasPermission: StateFlow<Boolean> = _hasPermission.asStateFlow()

    /** Re-reads the permission, e.g. when returning from the system Settings app. */
    fun refreshPermission() {
        _hasPermission.value = permission.isGranted()
    }

    /** Scans unless off Wi-Fi or without permission (the screen explains those cases instead). */
    fun scan() {
        refreshPermission()
        if (!_hasPermission.value) return
        if (!isOnLocalNetwork.value) return
        viewModelScope.launch {
            discovery.scan()
            hasScanned.value = true
        }
    }

    fun rename(bulb: BulbEntity, name: String) {
        viewModelScope.launch { bulbs.rename(bulb.id, name) }
    }

    fun identify(bulb: BulbEntity) {
        viewModelScope.launch { lights.identify(bulb.toAddress()) }
    }

    fun remove(bulb: BulbEntity) {
        viewModelScope.launch { bulbs.remove(bulb.id) }
    }
}
