package com.dungeonvibes.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.bulb.BulbDiscovery
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.CommandedLight
import com.dungeonvibes.light.LightMirror
import com.dungeonvibes.light.LocalNetworkPermission
import com.dungeonvibes.light.NetworkStatus
import com.dungeonvibes.light.fx.EffectRunner
import com.dungeonvibes.light.fx.NotificationPermission
import com.dungeonvibes.ui.adventures.play.buildGlowBulbs
import com.dungeonvibes.ui.navigation.TopLevelDestination
import com.dungeonvibes.ui.navigation.startDestinationFor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// A ViewModel survives rotation (the Activity is destroyed and recreated on rotate),
// so the start screen is decided once and not re-queried every time the device turns.
@HiltViewModel
class MainViewModel @Inject constructor(
    private val bulbRepository: BulbRepository,
    private val bulbDiscovery: BulbDiscovery,
    private val localNetwork: LocalNetworkPermission,
    networkStatus: NetworkStatus,
    effectRunner: EffectRunner,
    private val notifications: NotificationPermission,
    slots: LightSlotRepository,
    private val mirror: LightMirror,
) : ViewModel() {

    /** Every assigned bulb, by group order then name ([LightMirror] keys), for the nav edge's light. */
    val roomBulbs: StateFlow<List<String>> = combine(
        slots.observeSlots(),
        slots.observeAssignments(),
        bulbRepository.observeBulbs(),
    ) { slotList, assignments, bulbs ->
        buildGlowBulbs(slotList, assignments, bulbs).all
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** What the app last sent one bulb, for the nav edge's light to follow. */
    fun observeLight(key: String): StateFlow<CommandedLight?> = mirror.observe(key)

    /** The first time this turns true, the UI asks for notification permission (Android 13+). */
    val effectsRunning: StateFlow<Boolean> = effectRunner.isRunning

    /** True at most once per install: ask now, then never again. */
    fun takeNotificationPermissionAsk(): Boolean {
        if (!notifications.shouldAsk()) return false
        notifications.markAsked()
        return true
    }

    /** Drives the "not on Wi-Fi" banner on every screen. */
    val isOnLocalNetwork: StateFlow<Boolean> = networkStatus.isOnLocalNetwork

    private val _startDestination = MutableStateFlow<TopLevelDestination?>(null)

    /** Null while loading; the UI shows nothing until this is known. */
    val startDestination: StateFlow<TopLevelDestination?> = _startDestination.asStateFlow()

    private var startupScanDone = false

    /** Whether the UI must ask for the local network permission before bulbs can be reached. */
    val needsLocalNetworkPermission: Boolean get() = !localNetwork.isGranted()

    init {
        viewModelScope.launch {
            _startDestination.value = startDestinationFor(bulbRepository.hasAnyBulbs())
        }
    }

    /**
     * Re-runs discovery once per app start so known bulbs pick up IP changes. The UI calls this
     * once the local network permission is settled; without the permission it does nothing.
     */
    fun startupScan() {
        if (startupScanDone) return
        if (!localNetwork.isGranted()) return
        startupScanDone = true
        viewModelScope.launch { bulbDiscovery.scan() }
    }
}
