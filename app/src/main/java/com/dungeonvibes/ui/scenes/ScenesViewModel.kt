package com.dungeonvibes.ui.scenes

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.db.LightDefaults
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.ScenePlayer
import com.dungeonvibes.ui.common.DeletedItem
import com.dungeonvibes.ui.common.LightColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One lit group in a scene's palette. Groups that are off in the scene get no swatch. A group
 * running Color shift lists its palette in [shiftColors].
 */
/** One lit group's swatch; [hasEffects] (Brightness shift or Flashes, not Multi-color) puts a ✦ inside it. */
data class SceneSwatch(
    val slotId: Long,
    val color: Color,
    val shiftColors: List<Color> = emptyList(),
    val hasEffects: Boolean = false,
)

/**
 * The colors a scene's play mark spins in: each swatch's color, or its whole Multi-color palette
 * (a Multi-color group's single color is hidden and usually the default warm white, which showed
 * as an orange spinner).
 */
fun List<SceneSwatch>.markColors(): List<Color> {
    val count = size
    if (count == 0) return emptyList()
    val result = ArrayList<Color>(count + 2)
    for (i in 0 until count) {
        val swatch = this[i]
        if (swatch.shiftColors.size >= 2) result.addAll(swatch.shiftColors) else result.add(swatch.color)
    }
    return result
}

data class SceneListItem(
    val scene: SceneEntity,
    /** The scene's lit groups, in group order; empty for a scene with everything off. */
    val swatches: List<SceneSwatch>,
    /** Some lit group has effects (shown as ✦). */
    val hasEffects: Boolean = false,
)

data class ScenesUiState(
    val scenes: List<SceneListItem> = emptyList(),
    val isLoaded: Boolean = false,
)

/** One-off messages for the snackbar. */
sealed interface ScenesEvent {
    data class SomeBulbsFailed(val count: Int) : ScenesEvent
}

/**
 * A scene's palette: one swatch per lit slot, in precedence order ([slots]: individual lights,
 * then groups in their order); off ones are left out so big setups don't fill cards with empty
 * circles. A scene with All lights on shows its lit individual lights, then one swatch for the look
 * every other bulb shares. Shared with the adventure tiles.
 */
fun buildSwatches(
    slots: List<LightSlotEntity>,
    lightsByScene: Map<Long, Map<Long, SceneLightEntity>>,
    sceneId: Long,
    effectsByScene: Map<Long, Map<Long, GroupEffects>> = emptyMap(),
    allLightsOn: Boolean = false,
    roomSlotId: Long? = null,
): List<SceneSwatch> {
    val lights = lightsByScene[sceneId]
    val effects = effectsByScene[sceneId]
    if (allLightsOn) {
        val result = ArrayList<SceneSwatch>(2)
        // Individual lights still win under All lights.
        if (lights != null) {
            for (slot in slots) {
                if (slot.bulbId == null) continue
                val light = lights[slot.id] ?: continue
                if (!light.isOn) continue
                val own = effects?.get(slot.id)
                result.add(SceneSwatch(slot.id, LightColors.displayColor(light), shiftColorsOf(own), own?.hasEffectLayers == true))
            }
        }
        // The room's settings, or the default warm white if they were never set.
        val roomId = roomSlotId ?: ROOM_UNKNOWN
        val room = lights?.get(roomId)
        val color = if (room != null) LightColors.displayColor(room) else DefaultSwatch
        val roomEffects = effects?.get(roomId)
        result.add(SceneSwatch(roomId, color, shiftColorsOf(roomEffects), roomEffects?.hasEffectLayers == true))
        return result
    }
    if (lights == null) return emptyList()
    val count = slots.size
    val swatches = ArrayList<SceneSwatch>(minOf(count, lights.size))
    for (i in 0 until count) {
        val slotId = slots[i].id
        val light = lights[slotId] ?: continue
        if (!light.isOn) continue
        val groupEffects = effects?.get(slotId)
        swatches.add(SceneSwatch(slotId, LightColors.displayColor(light), shiftColorsOf(groupEffects), groupEffects?.hasEffectLayers == true))
    }
    return swatches
}

private fun shiftColorsOf(fx: GroupEffects?): List<Color> =
    if (fx != null && fx.runsColorShift) fx.palette.map(LightColors::displayColor) else emptyList()

private const val ROOM_UNKNOWN = -1L

/** All lights with no settings yet: warm white at the default brightness. */
private val DefaultSwatch: Color = LightColors.kelvinToColor(LightDefaults.KELVIN).let {
    val value = 0.35f + 0.65f * LightDefaults.BRIGHTNESS
    Color(it.red * value, it.green * value, it.blue * value)
}

/** Whether the scene runs effects: its lit groups', or the room's with All lights on. */
fun sceneHasEffects(
    lightsByScene: Map<Long, Map<Long, SceneLightEntity>>,
    effectsByScene: Map<Long, Map<Long, GroupEffects>>,
    sceneId: Long,
    allLightsOn: Boolean = false,
    roomSlotId: Long? = null,
): Boolean {
    val effects = effectsByScene[sceneId] ?: return false
    if (allLightsOn) return roomSlotId != null && effects[roomSlotId]?.hasAny == true
    val lights = lightsByScene[sceneId] ?: return false
    for ((slotId, fx) in effects) {
        if (fx.hasAny && lights[slotId]?.isOn == true) return true
    }
    return false
}

/** Groups light rows by scene, then by slot. */
fun groupLights(lights: List<SceneLightEntity>): Map<Long, Map<Long, SceneLightEntity>> {
    val result = HashMap<Long, HashMap<Long, SceneLightEntity>>()
    for (light in lights) result.getOrPut(light.sceneId) { HashMap() }[light.slotId] = light
    return result
}

@HiltViewModel
class ScenesViewModel @Inject constructor(
    private val scenes: SceneRepository,
    slots: LightSlotRepository,
    private val player: ScenePlayer,
) : ViewModel() {

    val uiState: StateFlow<ScenesUiState> = combine(
        scenes.observeLibraryScenes(),
        slots.observeSwatchSlots(),
        scenes.observeLibraryLights(),
        scenes.observeLibraryEffects(),
        slots.observeRoomSlotId(),
    ) { library, slotList, lights, effects, roomId ->
        val grouped = groupLights(lights)
        val items = ArrayList<SceneListItem>(library.size)
        for (scene in library) {
            items.add(
                SceneListItem(
                    scene,
                    buildSwatches(slotList, grouped, scene.id, effects, scene.allLightsOn, roomId),
                    sceneHasEffects(grouped, effects, scene.id, scene.allLightsOn, roomId),
                ),
            )
        }
        ScenesUiState(items, isLoaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScenesUiState())

    /** The scene playing, for its row's spinner (null when none, or after the lights go off). */
    val activeSceneId: StateFlow<Long?> = player.active
        .map { it?.sceneId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<ScenesEvent>(Channel.BUFFERED)
    val events: Flow<ScenesEvent> = _events.receiveAsFlow()

    /** Creates an empty library scene (every slot off) and returns its id. */
    suspend fun createScene(name: String): Long = scenes.createLibraryScene(name)

    private val _deletions = Channel<DeletedItem>(Channel.BUFFERED)

    /** Each delete, for the Undo snackbar. */
    val deletions: Flow<DeletedItem> = _deletions.receiveAsFlow()

    fun delete(sceneId: Long) {
        viewModelScope.launch {
            val deleted = scenes.deleteScene(sceneId) ?: return@launch
            _deletions.send(DeletedItem(deleted.scene.name) { viewModelScope.launch { scenes.restoreScene(deleted) } })
        }
    }

    /** Copies the scene right after itself in the library. */
    fun duplicate(sceneId: Long) {
        viewModelScope.launch { scenes.duplicateScene(sceneId) }
    }

    fun reorder(orderedSceneIds: List<Long>) {
        viewModelScope.launch { scenes.reorderScenes(orderedSceneIds) }
    }

    /** Applies the scene to the bulbs; reports bulbs that didn't respond. */
    fun play(sceneId: Long) {
        viewModelScope.launch {
            val result = player.play(sceneId) ?: return@launch
            val failed = result.failedBulbIds.size
            if (failed > 0) _events.send(ScenesEvent.SomeBulbsFailed(failed))
        }
    }
}
