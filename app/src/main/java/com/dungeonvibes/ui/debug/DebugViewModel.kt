package com.dungeonvibes.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.bulb.BulbDiscovery
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.AdventureWithSceneCount
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.debug.SampleData
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.LightState
import com.dungeonvibes.light.LocalNetworkPermission
import com.dungeonvibes.light.fx.EffectGroup
import com.dungeonvibes.light.fx.EffectRunner
import com.dungeonvibes.light.toAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DebugState(
    val bulbCount: Int = 0,
    val slotCount: Int = 0,
    val libraryScenes: List<SceneEntity> = emptyList(),
    val adventures: List<AdventureWithSceneCount> = emptyList(),
    /** Adventure scenes grouped by adventure id, already in display order. */
    val adventureScenesById: Map<Long, List<SceneEntity>> = emptyMap(),
)

data class BulbControlState(
    val bulbs: List<BulbEntity> = emptyList(),
    val onlineIds: Set<Long> = emptySet(),
    val isScanning: Boolean = false,
)

data class FxGroupOption(val slotId: Long, val name: String, val bulbCount: Int)

data class FxHarnessState(
    val groups: List<FxGroupOption> = emptyList(),
    val selectedSlotId: Long? = null,
    /** Null runs each pattern with its own spreads. */
    val spreadOverride: Spread? = null,
    val runningLabel: String? = null,
    val isRunning: Boolean = false,
)

private data class FxSelection(val slotId: Long? = null, val spread: Spread? = null, val runningLabel: String? = null)

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val bulbs: BulbRepository,
    private val slots: LightSlotRepository,
    scenes: SceneRepository,
    adventures: AdventureRepository,
    private val sampleData: SampleData,
    private val discovery: BulbDiscovery,
    private val lights: LightController,
    private val localNetwork: LocalNetworkPermission,
    private val effects: EffectRunner,
) : ViewModel() {

    // combine() re-emits whenever any source changes; stateIn keeps the latest value for the
    // UI and stops listening 5 s after the screen goes away (so a rotation doesn't restart it).
    val state: StateFlow<DebugState> = combine(
        bulbs.observeBulbs(),
        slots.observeSlots(),
        scenes.observeLibraryScenes(),
        adventures.observeAdventures(),
        scenes.observeAllAdventureScenes(),
    ) { bulbList, slotList, library, adventureList, adventureScenes ->
        DebugState(
            bulbCount = bulbList.size,
            slotCount = slotList.size,
            libraryScenes = library,
            adventures = adventureList,
            adventureScenesById = adventureScenes.groupBy { it.adventureId!! },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DebugState())

    val bulbState: StateFlow<BulbControlState> = combine(
        bulbs.observeBulbs(),
        discovery.onlineBulbIds,
        discovery.isScanning,
    ) { bulbList, online, scanning ->
        BulbControlState(bulbs = bulbList, onlineIds = online, isScanning = scanning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BulbControlState())

    private val fxSelection = MutableStateFlow(FxSelection())

    /** The FX1 test harness: pick a group, optionally force a spread, run a test pattern. */
    val fxState: StateFlow<FxHarnessState> = combine(
        slots.observeSlots(),
        slots.observeAssignments(),
        fxSelection,
        effects.isRunning,
    ) { slotList, assignments, selection, running ->
        val counts = assignments.groupingBy { it.slotId }.eachCount()
        val options = slotList.map { FxGroupOption(it.id, it.name, counts[it.id] ?: 0) }
        // Default to the first group that has bulbs.
        val selected = selection.slotId?.takeIf { id -> options.any { it.slotId == id } }
            ?: options.firstOrNull { it.bulbCount > 0 }?.slotId
        FxHarnessState(
            groups = options,
            selectedSlotId = selected,
            spreadOverride = selection.spread,
            runningLabel = if (running) selection.runningLabel else null,
            isRunning = running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FxHarnessState())

    private val _message = MutableStateFlow("")

    /** Result of the last bulb command, shown under the buttons. */
    val message: StateFlow<String> = _message.asStateFlow()

    fun addSampleData() {
        viewModelScope.launch { sampleData.seed() }
    }

    fun clearAllData() {
        viewModelScope.launch { sampleData.clearAll() }
    }


    fun scan() {
        viewModelScope.launch {
            if (!localNetwork.isGranted()) {
                _message.value = "Local network permission not granted: real bulbs can't be reached. " +
                    "Allow \"Nearby devices\" for Dungeon Vibes in Settings."
            } else {
                _message.value = "Scanning…"
            }
            discovery.scan()
            _message.value = "Scan finished: ${discovery.onlineBulbIds.value.size} bulb(s) answered"
        }
    }

    fun turnOn(bulb: BulbEntity) = runCommand(bulb, "On") { lights.powerOn(bulb.toAddress(), DEMO_FADE_MS) }

    fun turnOff(bulb: BulbEntity) = runCommand(bulb, "Off") { lights.turnOff(bulb.toAddress(), DEMO_FADE_MS) }

    fun red(bulb: BulbEntity) = runCommand(bulb, "Red") { lights.turnOn(bulb.toAddress(), RED, DEMO_FADE_MS) }

    fun blue(bulb: BulbEntity) = runCommand(bulb, "Blue") { lights.turnOn(bulb.toAddress(), BLUE, DEMO_FADE_MS) }

    fun identify(bulb: BulbEntity) = runCommand(bulb, "Identify") { lights.identify(bulb.toAddress()) }

    /* FX1 harness */

    fun selectFxGroup(slotId: Long) {
        fxSelection.value = fxSelection.value.copy(slotId = slotId)
    }

    fun setFxSpread(spread: Spread?) {
        fxSelection.value = fxSelection.value.copy(spread = spread)
    }

    fun runFxPattern(pattern: FxTestPattern) {
        val slotId = fxState.value.selectedSlotId ?: return
        val spread = fxSelection.value.spread
        viewModelScope.launch {
            val members = slots.observeAssignments().first().filter { it.slotId == slotId }.map { it.bulbId }.toHashSet()
            // Wave order is the group's bulbs by name.
            val addresses = bulbs.observeBulbs().first().filter { it.id in members }.map { it.toAddress() }
            if (addresses.isEmpty()) {
                _message.value = "That group has no bulbs."
                return@launch
            }
            fxSelection.value = fxSelection.value.copy(runningLabel = pattern.label)
            effects.start(
                listOf(EffectGroup(slotId, pattern.base, pattern.effectsWith(spread), addresses)),
                startFadeMs = FX_START_FADE_MS,
            )
        }
    }

    fun stopFx() {
        effects.stop()
    }

    override fun onCleared() {
        // FX1 has no background service yet, so leaving the debug screen ends the test run.
        effects.stop()
    }

    private fun runCommand(bulb: BulbEntity, label: String, command: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _message.value = "$label → ${bulb.name}…"
            val result = command()
            _message.value = if (result.isSuccess) {
                "$label → ${bulb.name}: OK"
            } else {
                "$label → ${bulb.name}: FAILED (${result.exceptionOrNull()?.message})"
            }
        }
    }

    private companion object {
        /** Long enough to see the fade clearly. */
        const val DEMO_FADE_MS = 1_500
        const val FX_START_FADE_MS = 1_000
        val RED = LightState(hue = 0f, saturation = 1f, brightness = 1f, kelvin = null)
        val BLUE = LightState(hue = 240f, saturation = 1f, brightness = 1f, kelvin = null)
    }
}
