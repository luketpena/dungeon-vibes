package com.dungeonvibes.ui.scenes.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.ApplicationScope
import com.dungeonvibes.light.BulbAddress
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.LightState
import com.dungeonvibes.light.ScenePlayer
import com.dungeonvibes.light.fx.EffectGroup
import com.dungeonvibes.light.fx.EffectRunner
import com.dungeonvibes.light.fx.restingState
import com.dungeonvibes.light.toAddress
import com.dungeonvibes.ui.common.LightColors
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One slot's row in the editor. [light] is null when the scene has no settings for the slot (= off);
 * [effects] is null until effects are first set up for it.
 */
data class SlotRowState(
    val slot: LightSlotEntity,
    val light: SceneLightEntity?,
    val hasBulbs: Boolean,
    val effects: GroupEffects? = null,
    /** The scene's "All lights" is on: the group plays lit whatever its own switch says. */
    val forcedOn: Boolean = false,
) {
    /** Whether the group plays lit in this scene. */
    val isOn: Boolean get() = forcedOn || light?.isOn == true
}

data class SceneEditorUiState(
    val isLoaded: Boolean = false,
    /** The scene was deleted (e.g. from the list on a tablet) while open. */
    val isMissing: Boolean = false,
    val name: String = "",
    val fadeMs: Int = SceneRepository.DEFAULT_FADE_MS,
    val isLibraryScene: Boolean = true,
    /** The default-lighting scene (Setup): no name field, no library actions. */
    val isDefault: Boolean = false,
    val canReset: Boolean = false,
    val livePreview: Boolean = true,
    val allLightsOn: Boolean = false,
    /** The "All lights" row: its switch is the scene's All lights, its panel the shared settings. */
    val roomRow: SlotRowState? = null,
    val rows: List<SlotRowState> = emptyList(),
)

sealed interface SceneEditorEvent {
    data object SavedToLibrary : SceneEditorEvent
}

/**
 * Edits one scene, library or adventure. The in-memory draft is the source of truth while the
 * editor is open: every change updates it at once (so sliders and live preview stay instant) and is
 * written to the database shortly after the last change ("changes save automatically").
 */
@HiltViewModel(assistedFactory = SceneEditorViewModel.Factory::class)
class SceneEditorViewModel @AssistedInject constructor(
    @Assisted private val sceneId: Long,
    private val scenes: SceneRepository,
    private val slots: LightSlotRepository,
    bulbs: BulbRepository,
    private val lights: LightController,
    private val effectRunner: EffectRunner,
    private val player: ScenePlayer,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(sceneId: Long): SceneEditorViewModel
    }

    private data class Draft(
        val name: String,
        val fadeMs: Int,
        val lights: Map<Long, SceneLightEntity>,
        val effects: Map<Long, GroupEffects>,
        val allLightsOn: Boolean,
    )

    private val draft = MutableStateFlow<Draft?>(null)

    /** The hidden slot holding All-lights settings; set by [load] before the draft appears. */
    private var roomSlotId = NO_ROOM
    private val livePreview = MutableStateFlow(true)

    /** True once the first load finished (whether or not the scene existed). */
    private val loaded = MutableStateFlow(false)

    /** Live scene row: whether Reset is available right now (rules 4 and 6). */
    private val sceneRow = MutableStateFlow<SceneEntity?>(null)

    /* Set once the groups and bulbs below have loaded, so the scene can take over the bulbs. */
    private val slotsReady = MutableStateFlow(false)
    private val bulbsReady = MutableStateFlow(false)
    private val bulbMapReady = MutableStateFlow(false)

    private val slotList: StateFlow<List<LightSlotEntity>> =
        slots.observeSlots().onEach { slotsReady.value = true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Where each slot's bulbs are, for live preview, in name order (the order Wave follows). */
    private val bulbsBySlot: StateFlow<Map<Long, List<BulbAddress>>> = combine(
        slots.observeAssignments(),
        bulbs.observeBulbs(),
    ) { assignments, bulbList ->
        val slotOf = HashMap<Long, Long>(assignments.size * 2)
        for (assignment in assignments) slotOf[assignment.bulbId] = assignment.slotId
        val result = HashMap<Long, MutableList<BulbAddress>>()
        for (bulb in bulbList) {
            val slotId = slotOf[bulb.id] ?: continue
            result.getOrPut(slotId) { ArrayList(2) }.add(bulb.toAddress())
        }
        result
    }.onEach { bulbMapReady.value = true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Every bulb in a group, in name order: what "All lights" previews on. */
    private val assignedBulbs: StateFlow<List<BulbAddress>> = combine(
        slots.observeAssignments(),
        bulbs.observeBulbs(),
    ) { assignments, bulbList ->
        val assigned = HashSet<Long>(assignments.size * 2)
        for (assignment in assignments) assigned.add(assignment.bulbId)
        bulbList.filter { it.id in assigned }.map { it.toAddress() }
    }.onEach { bulbsReady.value = true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState: StateFlow<SceneEditorUiState> = combine(
        combine(draft, loaded) { current, isLoaded -> current to isLoaded },
        sceneRow,
        slotList,
        bulbsBySlot,
        livePreview,
    ) { (current, isLoaded), row, slotRows, bulbMap, preview ->
        if (current == null) {
            // Not loaded yet, or the scene doesn't exist (any more).
            return@combine SceneEditorUiState(isLoaded = isLoaded, isMissing = isLoaded)
        }
        val rows = ArrayList<SlotRowState>(slotRows.size)
        for (slot in slotRows) {
            rows.add(
                SlotRowState(
                    slot,
                    current.lights[slot.id],
                    !bulbMap[slot.id].isNullOrEmpty(),
                    current.effects[slot.id],
                    forcedOn = current.allLightsOn,
                ),
            )
        }
        val room = roomSlotId
        val roomRow = if (room == NO_ROOM) {
            null
        } else {
            SlotRowState(
                slot = LightSlotEntity(id = room, name = "", sortOrder = -1, isRoom = true),
                // The row reads as on exactly when the scene's All lights is.
                light = current.lights[room]?.copy(isOn = current.allLightsOn),
                hasBulbs = bulbMap.isNotEmpty(),
                effects = current.effects[room],
            )
        }
        SceneEditorUiState(
            isLoaded = true,
            name = current.name,
            fadeMs = current.fadeMs,
            isLibraryScene = row?.isLibraryScene ?: true,
            isDefault = row?.isDefault == true,
            canReset = row?.canResetToLibrary == true,
            livePreview = preview,
            allLightsOn = current.allLightsOn,
            roomRow = roomRow,
            rows = rows,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SceneEditorUiState())

    private val _events = Channel<SceneEditorEvent>(Channel.BUFFERED)
    val events: Flow<SceneEditorEvent> = _events.receiveAsFlow()

    /* Pending writes, all touched only on the main thread. */
    private var detailsDirty = false
    private var allLightsDirty = false
    private val dirtySlots = HashSet<Long>()
    private val dirtyEffects = HashSet<Long>()
    private var saveJob: Job? = null

    /* Live effects preview, main thread only. */

    /**
     * Whether this scene has taken over the bulbs. Opening the editor (with live preview on) makes
     * it the active scene, stopping whatever was playing, and shows the whole scene, so nothing
     * from the previous scene keeps painting over the edits (owner decision).
     */
    private var activated = false

    /** Slots whose effects are playing on their bulbs. */
    private val effectSlots = HashSet<Long>()
    private var effectRunId: Long? = null
    private var runnerPushJob: Job? = null

    init {
        viewModelScope.launch {
            load()
            // Opening a scene makes it the active scene, shown in full (owner decision): wait for
            // the groups and bulbs, then take over the bulbs.
            combine(slotsReady, bulbsReady, bulbMapReady) { a, b, c -> a && b && c }.first { it }
            if (livePreview.value && draft.value != null) previewAll()
        }
        viewModelScope.launch {
            // If the scene disappears while open (deleted elsewhere), drop the draft so the
            // screen shows "deleted" and nothing more is written.
            var seen = false
            scenes.observeScene(sceneId).collect { row ->
                sceneRow.value = row
                if (row != null) {
                    seen = true
                } else if (seen) {
                    draft.value = null
                    detailsDirty = false
                    allLightsDirty = false
                    dirtySlots.clear()
                    dirtyEffects.clear()
                }
            }
        }
    }

    /** Set by [load]: this is the default-lighting scene. */
    private var isDefaultScene = false

    private suspend fun load() {
        roomSlotId = slots.ensureRoomSlot()
        val row = scenes.getScene(sceneId)
        if (row == null) {
            draft.value = null
        } else {
            isDefaultScene = row.isDefault
            val stored = scenes.getSceneLights(sceneId)
            val map = HashMap<Long, SceneLightEntity>(stored.size)
            for (light in stored) map[light.slotId] = light
            draft.value = Draft(row.name, row.fadeMs, map, scenes.getSceneEffects(sceneId), row.allLightsOn)
        }
        // Set last, so the screen never briefly reads "loaded but no draft" as "deleted".
        loaded.value = true
    }

    /* Scene details */

    fun setName(name: String) {
        val current = draft.value ?: return
        draft.value = current.copy(name = name)
        detailsDirty = true
        scheduleSave()
    }

    fun setFadeMs(fadeMs: Int) {
        val current = draft.value ?: return
        val clamped = fadeMs.coerceIn(0, SceneRepository.MAX_FADE_MS)
        if (clamped == current.fadeMs) return
        draft.value = current.copy(fadeMs = clamped)
        detailsDirty = true
        scheduleSave()
    }

    /* All lights */

    /**
     * "All lights": every bulb shares the All-lights settings (the room row), and the groups below
     * are locked, keeping their own settings untouched for when it's turned off again. The room
     * row gets its default settings the first time, so it can be edited straight away.
     */
    fun setAllLightsOn(on: Boolean) {
        val current = draft.value ?: return
        if (current.allLightsOn == on) return
        val room = roomSlotId
        var lightsMap = current.lights
        if (on && room != NO_ROOM && !lightsMap.containsKey(room)) {
            lightsMap = lightsMap + (room to defaultLight(room).copy(isOn = true))
            dirtySlots.add(room)
        }
        draft.value = current.copy(allLightsOn = on, lights = lightsMap)
        allLightsDirty = true
        scheduleSave()
        if (livePreview.value) previewAll()
    }

    /** What a slot plays as. The room row is always lit (All lights itself is its switch). */
    private fun effectiveLight(current: Draft, slotId: Long): SceneLightEntity? {
        val light = current.lights[slotId]
        if (slotId != roomSlotId) return light
        val base = light ?: defaultLight(slotId)
        return if (base.isOn) base else base.copy(isOn = true)
    }

    /** The slots that drive the bulbs right now: the room alone with All lights on, else the groups. */
    private fun activeSlotIds(current: Draft): List<Long> {
        if (current.allLightsOn && roomSlotId != NO_ROOM) return listOf(roomSlotId)
        val slotRows = slotList.value
        val result = ArrayList<Long>(slotRows.size)
        for (slot in slotRows) result.add(slot.id)
        return result
    }

    /** A slot's bulbs; the room's are every bulb in a group. */
    private fun bulbsFor(slotId: Long): List<BulbAddress>? =
        if (slotId == roomSlotId) assignedBulbs.value else bulbsBySlot.value[slotId]

    /* Slot settings */

    fun setOn(slotId: Long, on: Boolean) = editLight(slotId) { it.copy(isOn = on) }

    /** Switching mode keeps both the color and the white values (rule 7). */
    fun setMode(slotId: Long, mode: LightMode) = editLight(slotId) { it.copy(mode = mode) }

    fun setHue(slotId: Long, hue: Float) = editLight(slotId) { it.copy(hue = hue) }

    fun setSaturation(slotId: Long, saturation: Float) = editLight(slotId) { it.copy(saturation = saturation) }

    fun setBrightness(slotId: Long, brightness: Float) = editLight(slotId) { it.copy(brightness = brightness) }

    fun setKelvin(slotId: Long, kelvin: Int) = editLight(slotId) { it.copy(kelvin = kelvin) }

    private inline fun editLight(slotId: Long, change: (SceneLightEntity) -> SceneLightEntity) {
        val current = draft.value ?: return
        val before = current.lights[slotId] ?: defaultLight(slotId)
        val after = change(before)
        if (after == before && current.lights.containsKey(slotId)) return
        draft.value = current.copy(lights = current.lights + (slotId to after))
        dirtySlots.add(slotId)
        scheduleSave()
        if (livePreview.value) refreshPreview(slotId)
    }

    /** A slot with no settings yet starts off, warm white at 2700 K, with a warm orange ready for Color. */
    private fun defaultLight(slotId: Long) = SceneLightEntity(
        sceneId = sceneId,
        slotId = slotId,
        isOn = false,
        mode = LightMode.WHITE,
        hue = LightColors.DEFAULT_HUE,
        saturation = LightColors.DEFAULT_SATURATION,
        kelvin = LightColors.DEFAULT_KELVIN,
        brightness = LightColors.DEFAULT_BRIGHTNESS,
    )

    /* Effects */

    /**
     * Replaces one group's effect layers. Effects belong to the group's settings, so a group with
     * none yet (never switched on) can't take them; the editor only offers effects to lit groups.
     */
    fun setEffects(slotId: Long, effects: GroupEffects) {
        val current = draft.value ?: return
        if (!current.lights.containsKey(slotId)) return
        if (current.effects[slotId] == effects) return
        draft.value = current.copy(effects = current.effects + (slotId to effects))
        dirtyEffects.add(slotId)
        scheduleSave()
        if (livePreview.value) refreshPreview(slotId)
    }

    /**
     * Multi-color on or off: the group's Color shift layer, which the editor offers as a third
     * color mode beside Color and White. A first palette starts from the group's own color. The
     * single color and white values are kept either way (rule 7).
     */
    fun setMultiColor(slotId: Long, on: Boolean) {
        if (draft.value?.lights?.containsKey(slotId) != true) {
            // A group with no settings yet gets its defaults first, like any other first change.
            editLight(slotId) { it }
        }
        val current = draft.value ?: return
        val light = current.lights[slotId] ?: return
        val effects = current.effects[slotId] ?: GroupEffects()
        if (effects.colorShift.enabled == on) return
        val palette = if (on && effects.palette.size < GroupEffects.MIN_PALETTE) defaultPalette(light) else effects.palette
        setEffects(slotId, effects.copy(colorShift = effects.colorShift.copy(enabled = on), palette = palette))
    }

    /** Plays a burst now on a group whose Flashes are previewing, so its shape can be judged. */
    fun flashNow(slotId: Long) {
        if (!livePreview.value || slotId !in effectSlots) return
        // A pending push may be about to restart the group; let it land first.
        if (runnerPushJob?.isActive == true) {
            runnerPushJob?.cancel()
            pushRunner()
        }
        effectRunner.triggerFlash(slotId)
    }

    /* Live preview */

    /**
     * Turning preview on shows the whole scene as edited so far: slots without settings go off,
     * groups with effects run them. Turning it off stops effects, settling those bulbs on their base.
     */
    fun setLivePreview(enabled: Boolean) {
        livePreview.value = enabled
        if (enabled) {
            previewAll()
        } else {
            stopEffectsPreview()
        }
    }

    private fun previewAll() {
        val current = draft.value ?: return
        if (!activated) {
            activated = true
            player.activate(sceneId, isDefault = isDefaultScene)
            // Whatever ran before is gone; this scene starts its own run from scratch.
            effectSlots.clear()
            effectRunId = null
        }
        val active = activeSlotIds(current)
        val count = active.size
        // Slots that stopped driving the bulbs (All lights switched) leave the effect runner.
        var leftEffects = effectSlots.retainAll(active.toSet())
        val plain = ArrayList<Long>(count)
        for (i in 0 until count) {
            val slotId = active[i]
            if (runsEffects(effectiveLight(current, slotId), current.effects[slotId])) {
                effectSlots.add(slotId)
            } else {
                if (effectSlots.remove(slotId)) leftEffects = true
                plain.add(slotId)
            }
        }
        runnerPushJob?.cancel()
        pushRunner()
        handOff(plain, leftEffects)
    }

    /** Shows one slot's change: through the effect runner if it has effects, else as a plain preview. */
    private fun refreshPreview(slotId: Long) {
        // Not driving the bulbs yet (still loading, or live preview was off): take over first, which
        // shows the whole scene, this change included.
        if (!activated) {
            previewAll()
            return
        }
        val current = draft.value ?: return
        // Only the slots driving the bulbs are shown: the room with All lights on, else the groups.
        if (current.allLightsOn != (slotId == roomSlotId)) return
        if (runsEffects(effectiveLight(current, slotId), current.effects[slotId])) {
            effectSlots.add(slotId)
            scheduleRunnerPush()
            return
        }
        if (effectSlots.remove(slotId)) {
            // Take the group out of the runner first, then show its plain settings.
            runnerPushJob?.cancel()
            pushRunner()
            handOff(listOf(slotId), leftEffects = true)
            return
        }
        previewSlot(slotId, effectiveLight(current, slotId))
    }

    /**
     * Plain previews for [slotIds]. When a group has just left the effect runner, waits a moment
     * so a frame still in flight can't land after (and over) its plain settings.
     */
    private fun handOff(slotIds: List<Long>, leftEffects: Boolean) {
        if (slotIds.isEmpty()) return
        if (!leftEffects) {
            val current = draft.value ?: return
            for (slotId in slotIds) previewSlot(slotId, effectiveLight(current, slotId))
            return
        }
        viewModelScope.launch {
            delay(EFFECT_HANDOFF_MS)
            if (!livePreview.value) return@launch
            val current = draft.value ?: return@launch
            for (slotId in slotIds) {
                if (slotId !in effectSlots) previewSlot(slotId, effectiveLight(current, slotId))
            }
        }
    }

    private fun runsEffects(light: SceneLightEntity?, effects: GroupEffects?): Boolean =
        light != null && light.isOn && effects != null && effects.hasAny

    /** Hands the runner the latest settings at most every [RUNNER_THROTTLE_MS], so drags stay smooth. */
    private fun scheduleRunnerPush() {
        if (runnerPushJob?.isActive == true) return
        runnerPushJob = viewModelScope.launch {
            delay(RUNNER_THROTTLE_MS)
            pushRunner()
        }
    }

    private fun pushRunner() {
        val current = draft.value ?: return
        val groups = ArrayList<EffectGroup>(effectSlots.size)
        for (slotId in effectSlots) {
            val light = effectiveLight(current, slotId) ?: continue
            val effects = current.effects[slotId] ?: continue
            if (!runsEffects(light, effects)) continue
            val addresses = bulbsFor(slotId) ?: continue
            groups.add(EffectGroup(slotId, restingState(light.toLightState(), effects), effects, addresses))
        }
        if (groups.isEmpty()) {
            effectRunId?.let { effectRunner.stopIfCurrent(it, settleFadeMs = -1) }
            effectRunId = null
            return
        }
        effectRunId = effectRunner.update(groups, LightController.PREVIEW_FADE_MS)
    }

    private fun stopEffectsPreview() {
        runnerPushJob?.cancel()
        effectSlots.clear()
        effectRunId?.let { effectRunner.stopIfCurrent(it) }
        effectRunId = null
        // Turning live preview back on takes over the bulbs again.
        activated = false
    }

    private fun previewSlot(slotId: Long, light: SceneLightEntity?) {
        val addresses = bulbsFor(slotId) ?: return
        val state = if (light != null && light.isOn) light.toLightState() else null
        val count = addresses.size
        for (i in 0 until count) lights.preview(addresses[i], state)
    }

    /* Adventure-scene actions */

    /** Rule 4: replaces this copy's settings with its library scene's. */
    fun resetToLibraryVersion() {
        viewModelScope.launch {
            flushNow()
            if (scenes.resetToLibraryVersion(sceneId)) {
                // The copy's own effect edits were just replaced along with everything else.
                dirtyEffects.clear()
                load()
                if (livePreview.value) previewAll()
            }
        }
    }

    /** Rule 5: saves this adventure scene into the library. */
    fun saveToLibrary() {
        viewModelScope.launch {
            flushNow()
            if (scenes.promoteToLibrary(sceneId) != null) _events.send(SceneEditorEvent.SavedToLibrary)
        }
    }

    /* Saving */

    /** Saves once editing pauses: each change restarts the wait. */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            writePending()
        }
    }

    /** Saves right away (before reset/promote), dropping any save that was still waiting. */
    private suspend fun flushNow() {
        saveJob?.cancel()
        saveJob = null
        writePending()
    }

    /**
     * Writes everything changed since the last save. Once taken, a batch is always written in full:
     * NonCancellable stops a newer change (which cancels the waiting job) from cutting it off midway
     * and losing it.
     */
    private suspend fun writePending() {
        val pending = takePending() ?: return
        withContext(NonCancellable) { write(pending) }
    }

    private class PendingWrite(
        val details: Pair<String, Int>?,
        val allLightsOn: Boolean?,
        val lights: List<SceneLightEntity>,
        val effects: List<Pair<Long, GroupEffects>>,
    )

    private fun takePending(): PendingWrite? {
        val current = draft.value ?: return null
        if (!detailsDirty && !allLightsDirty && dirtySlots.isEmpty() && dirtyEffects.isEmpty()) return null
        val details = if (detailsDirty) current.name.trim().ifEmpty { current.name } to current.fadeMs else null
        val changed = ArrayList<SceneLightEntity>(dirtySlots.size)
        for (slotId in dirtySlots) current.lights[slotId]?.let(changed::add)
        val changedEffects = ArrayList<Pair<Long, GroupEffects>>(dirtyEffects.size)
        for (slotId in dirtyEffects) current.effects[slotId]?.let { changedEffects.add(slotId to it) }
        val allLights = if (allLightsDirty) current.allLightsOn else null
        detailsDirty = false
        allLightsDirty = false
        dirtySlots.clear()
        dirtyEffects.clear()
        return PendingWrite(details, allLights, changed, changedEffects)
    }

    private suspend fun write(pending: PendingWrite) {
        // The scene may have been deleted while open; writing its lights would break foreign keys.
        if (scenes.getScene(sceneId) == null) return
        pending.details?.let { (name, fade) -> scenes.updateSceneDetails(sceneId, name, fade) }
        pending.allLightsOn?.let { scenes.setAllLightsOn(sceneId, it) }
        for (light in pending.lights) scenes.saveSceneLight(light)
        // After the lights: a group's effects need its settings row to exist.
        for ((slotId, effects) in pending.effects) scenes.saveGroupEffects(sceneId, slotId, effects)
    }

    override fun onCleared() {
        // The edited scene stays active and keeps playing, effects and all. Hand the runner any
        // settings that were still waiting for their throttle (viewModelScope is cancelled now).
        if (runnerPushJob?.isActive == true && livePreview.value) pushRunner()
        // viewModelScope is already cancelled here, so finish any pending save on the app scope.
        val pending = takePending() ?: return
        appScope.launch { write(pending) }
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 400L

        private const val NO_ROOM = -1L

        /** How often a running effects preview takes new settings while something is dragged. */
        const val RUNNER_THROTTLE_MS = 150L

        /** Pause between taking a group out of the effect runner and showing its plain settings. */
        const val EFFECT_HANDOFF_MS = 150L
    }
}

fun SceneLightEntity.toLightState(): LightState = LightState(
    hue = hue,
    saturation = saturation,
    brightness = brightness,
    kelvin = if (mode == LightMode.WHITE) kelvin else null,
)
