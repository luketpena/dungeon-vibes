package com.dungeonvibes.ui.setup.bulbs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.bulb.BulbDiscovery
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.LocalNetworkPermission
import com.dungeonvibes.light.NetworkStatus
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
    /** The groups this bulb is in, in group order (the first wins it when several are lit). */
    val groupNames: List<String>,
)

data class BulbsUiState(
    val bulbs: List<BulbItem> = emptyList(),
    val isScanning: Boolean = false,
    /** False until the first scan of this screen finishes, so the empty state doesn't flash. */
    val hasScanned: Boolean = false,
)

@HiltViewModel
class BulbsViewModel @Inject constructor(
    bulbs: BulbRepository,
    slots: LightSlotRepository,
    private val discovery: BulbDiscovery,
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
        // Index each bulb's groups once, in group order, instead of searching per row.
        val members = HashMap<Long, HashSet<Long>>()
        for (assignment in assignments) members.getOrPut(assignment.bulbId) { HashSet() }.add(assignment.slotId)

        val items = ArrayList<BulbItem>(bulbList.size)
        for (bulb in bulbList) {
            items.add(
                BulbItem(
                    bulb = bulb,
                    isOnline = bulb.id in online,
                    groupNames = members[bulb.id]?.let { ids -> slotList.filter { it.id in ids }.map { it.name } }.orEmpty(),
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

}
