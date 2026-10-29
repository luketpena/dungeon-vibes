package com.dungeonvibes.ui.scenes.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.BulbApplyTarget
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.db.EffectLayerType
import com.dungeonvibes.data.scene.EffectLayer
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.scene.SceneSetup
import com.dungeonvibes.data.scene.resolveScene
import com.dungeonvibes.data.scene.withRestingColors
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.ApplicationScope
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.LightMirror
import com.dungeonvibes.light.LightState
import com.dungeonvibes.light.ScenePlayer
import com.dungeonvibes.light.fx.EffectGroup
import com.dungeonvibes.light.fx.EffectRunner
import com.dungeonvibes.light.fx.effectGroupsFor
import com.dungeonvibes.light.toAddress
import com.dungeonvibes.light.toLightStateOrNull
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
    /**
     * Individual lights set in this scene, by bulb name: each wins over All lights and every group
     * for its bulb (owner request). Their slot is the bulb's hidden individual-light slot.
     */
    val singleRows: List<SlotRowState> = emptyList(),
    /** Bulbs that can still be added as individual lights. */
    val addableBulbs: List<BulbEntity> = emptyList(),
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
    private val setupReady = MutableStateFlow(false)
    private val singlesReady = MutableStateFlow(false)

    private val slotList: StateFlow<List<LightSlotEntity>> =
        slots.observeSlots().onEach { slotsReady.value = true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Bulbs, groups and individual-light slots: what the preview works the scene out against. */
    private val setup: StateFlow<SceneSetup> =
        slots.observeSetup(bulbs.observeBulbs()).onEach { setupReady.value = true }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SceneSetup.EMPTY)

    /** Every bulb's individual-light slot that exists, named after its bulb. */
    private val singleSlots: StateFlow<List<LightSlotEntity>> =
        slots.observeBulbSlots().onEach { singlesReady.value = true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState: StateFlow<SceneEditorUiState> = combine(
        combine(draft, loaded) { current, isLoaded -> current to isLoaded },
        sceneRow,
        combine(slotList, singleSlots) { groups, singles -> groups to singles },
        setup,
        livePreview,
    ) { (current, isLoaded), row, (slotRows, singles), sceneSetup, preview ->
        if (current == null) {
            // Not loaded yet, or the scene doesn't exist (any more).
            return@combine SceneEditorUiState(isLoaded = isLoaded, isMissing = isLoaded)
        }
        val filled = HashSet<Long>(sceneSetup.assignments.size * 2)
        for (assignment in sceneSetup.assignments) filled.add(assignment.slotId)
        val rows = ArrayList<SlotRowState>(slotRows.size)
        for (slot in slotRows) {
            rows.add(
                SlotRowState(
                    slot,
                    current.lights[slot.id],
                    slot.id in filled,
                    current.effects[slot.id],
                    forcedOn = current.allLightsOn,
                ),
            )
        }
        // Individual lights: the bulbs this scene sets one by one, and the rest, which can be added.
        val singleRows = ArrayList<SlotRowState>()
        val taken = HashSet<Long>()
        for (slot in singles) {
            val light = current.lights[slot.id] ?: continue
            singleRows.add(SlotRowState(slot, light, hasBulbs = true, effects = current.effects[slot.id]))
            slot.bulbId?.let(taken::add)
        }
        val addable = sceneSetup.bulbs.filter { it.id !in taken }
        val room = roomSlotId
        val roomRow = if (room == NO_ROOM) {
            null
        } else {
            SlotRowState(
                slot = LightSlotEntity(id = room, name = "", sortOrder = -1, isRoom = true),
                // The row reads as on exactly when the scene's All lights is; with no settings yet
                // (a new default-lighting scene) it shows the defaults it would get when changed.
                light = (current.lights[room] ?: defaultLight(room)).copy(isOn = current.allLightsOn),
                hasBulbs = sceneSetup.assignments.isNotEmpty(),
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
            singleRows = singleRows,
            addableBulbs = addable,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SceneEditorUiState())

    private val _events = Channel<SceneEditorEvent>(Channel.BUFFERED)
    val events: Flow<SceneEditorEvent> = _events.receiveAsFlow()

    /* Pending writes, all touched only on the main thread. */
    private var detailsDirty = false
    private var allLightsDirty = false
    private val dirtySlots = HashSet<Long>()
    private val dirtyEffects = HashSet<Long>()

    /** Individual lights taken out of the scene, to delete on the next save (before any re-add). */
    private val removedSlots = HashSet<Long>()
    private var saveJob: Job? = null

    /* Live effects preview, main thread only. */

    /**
     * Whether this scene has taken over the bulbs. Opening the editor (with live preview on) makes
     * it the active scene, stopping whatever was playing, and shows the whole scene, so nothing
     * from the previous scene keeps painting over the edits (owner decision).
     */
    private var activated = false

    private var effectRunId: Long? = null
    private var runnerPushJob: Job? = null

    /** The runner groups waiting for the throttle (see [scheduleRunnerPush]). */
    private var pendingGroups: List<EffectGroup>? = null

    /** Keys (LightMirror.keyOf) of the bulbs the effect runner is driving. */
    private var runnerBulbs: Set<String> = emptySet()

    /** Slots whose effects are running (for Flash now). */
    private var runnerSlots: Set<Long> = emptySet()

    /** Bulbs just taken out of the runner, waiting a moment before their plain preview. */
    private var heldBack: Set<String> = emptySet()

    /** What each bulb was last sent as a plain preview (null: off), so unchanged bulbs aren't resent. */
    private val previewed = HashMap<String, LightState?>()

    init {
        viewModelScope.launch {
            load()
            // Opening a scene makes it the active scene, shown in full (owner decision): wait for
            // the groups and bulbs, then take over the bulbs.
            combine(slotsReady, setupReady, singlesReady) { a, b, c -> a && b && c }.first { it }
            if (livePreview.value && draft.value != null) previewAll()
            // Bulbs, groups or individual-light slots changing (an individual light just added)
            // change what the bulbs should show.
            setup.collect { if (activated && livePreview.value) refreshPreview() }
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
                    removedSlots.clear()
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
        if (livePreview.value) refreshPreview()
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

    /* Individual lights */

    /**
     * Adds bulbs to this scene as individual lights, on, in the default warm white. Each wins over
     * All lights and every group for its bulb (owner request), and has the full set of controls.
     */
    fun addSingleLights(bulbIds: Collection<Long>) {
        if (bulbIds.isEmpty()) return
        viewModelScope.launch {
            for (bulbId in bulbIds) {
                val slotId = slots.ensureBulbSlot(bulbId)
                if (draft.value?.lights?.containsKey(slotId) == true) continue
                editLight(slotId) { it.copy(isOn = true) }
            }
        }
    }

    fun addSingleLight(bulbId: Long) = addSingleLights(listOf(bulbId))

    /** Takes an individual light out of the scene: its bulb follows All lights or its groups again. */
    fun removeSingleLight(slotId: Long) {
        val current = draft.value ?: return
        if (!current.lights.containsKey(slotId)) return
        draft.value = current.copy(lights = current.lights - slotId, effects = current.effects - slotId)
        dirtySlots.remove(slotId)
        dirtyEffects.remove(slotId)
        removedSlots.add(slotId)
        scheduleSave()
        if (livePreview.value) refreshPreview()
    }

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
        if (livePreview.value) refreshPreview()
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

    /** Ids for layers added while editing (negative, so they never clash with saved rows). */
    private var nextLayerId = -1L

    /**
     * Adds a layered effect of [type] with default settings at the bottom of [slotId]'s list (the
     * lowest priority) and returns its id, for opening its editor; null when the group has no
     * settings yet or already has [GroupEffects.MAX_LAYERS] layers.
     */
    fun addLayer(slotId: Long, type: EffectLayerType): Long? {
        val current = draft.value ?: return null
        if (!current.lights.containsKey(slotId)) return null
        val effects = current.effects[slotId] ?: GroupEffects()
        if (effects.layers.size >= GroupEffects.MAX_LAYERS) return null
        val id = nextLayerId--
        setEffects(slotId, effects.copy(layers = effects.layers + EffectLayer(id, type)))
        return id
    }

    /** Replaces one layer's settings. */
    fun updateLayer(slotId: Long, layer: EffectLayer) {
        val effects = draft.value?.effects?.get(slotId) ?: return
        setEffects(slotId, effects.copy(layers = effects.layers.map { if (it.id == layer.id) layer else it }))
    }

    fun removeLayer(slotId: Long, layerId: Long) {
        val effects = draft.value?.effects?.get(slotId) ?: return
        setEffects(slotId, effects.copy(layers = effects.layers.filter { it.id != layerId }))
    }

    /** Persists a drag: [orderedIds] top (highest priority) first. */
    fun reorderLayers(slotId: Long, orderedIds: List<Long>) {
        val effects = draft.value?.effects?.get(slotId) ?: return
        val byId = effects.layers.associateBy { it.id }
        val ordered = orderedIds.mapNotNull { byId[it] }
        if (ordered.size != effects.layers.size) return
        setEffects(slotId, effects.copy(layers = ordered))
    }

    /** Plays a burst now on a Flashes layer that's previewing, so its shape can be judged. */
    fun flashNow(slotId: Long, layerId: Long? = null) {
        if (!livePreview.value || slotId !in runnerSlots) return
        // A pending push may be about to restart the group; let it land first.
        if (runnerPushJob?.isActive == true) {
            runnerPushJob?.cancel()
            pendingGroups?.let(::pushRunner)
        }
        effectRunner.triggerFlash(slotId, layerId)
    }

    /* Live preview */

    /**
     * Live preview is always on in the app (owner decision: no switch); this stays for tests that
     * check edits without the bulbs. On shows the whole scene as edited so far: slots without
     * settings go off, groups with effects run them. Off stops effects, settling bulbs on their base.
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
        if (draft.value == null) return
        if (!activated) {
            activated = true
            player.activate(sceneId, isDefault = isDefaultScene)
            // Whatever ran before is gone; this scene starts its own run from scratch.
            effectRunId = null
            runnerBulbs = emptySet()
            runnerSlots = emptySet()
            heldBack = emptySet()
            previewed.clear()
        }
        showDraft(immediate = true)
    }

    /** Shows the latest edit. Not driving the bulbs yet: takes over first, which shows it all. */
    private fun refreshPreview() {
        if (!activated) {
            previewAll()
            return
        }
        showDraft(immediate = false)
    }

    /**
     * Shows the draft on the bulbs, worked out exactly as playing it would be (SceneResolver: each
     * bulb's individual light, All lights, or its highest lit group). Bulbs whose slot runs effects,
     * or that another lit group flashes, go to the effect runner; the rest get plain previews. While
     * something is dragged the runner takes new settings at most every [RUNNER_THROTTLE_MS], except
     * when the bulbs it drives change. A bulb leaving the runner waits a moment, so a frame still in
     * flight can't land after (and over) its plain preview.
     */
    private fun showDraft(immediate: Boolean) {
        val current = draft.value ?: return
        val targets = resolveDraft(current)
        val groups = effectGroupsFor(targets, current.effects::get)
        val inRunner = HashSet<String>()
        val slotsRunning = HashSet<Long>(groups.size * 2)
        for (group in groups) {
            slotsRunning.add(group.slotId)
            for (address in group.bulbs) {
                if (address !in group.flashOnly) inRunner.add(LightMirror.keyOf(address.protocolId, address.serial))
            }
        }
        val left = runnerBulbs - inRunner
        val changed = inRunner != runnerBulbs
        runnerBulbs = inRunner
        runnerSlots = slotsRunning
        if (immediate || changed) {
            runnerPushJob?.cancel()
            pushRunner(groups)
        } else {
            pendingGroups = groups
            scheduleRunnerPush()
        }
        if (left.isNotEmpty()) {
            heldBack = heldBack + left
            viewModelScope.launch {
                delay(EFFECT_HANDOFF_MS)
                heldBack = heldBack - left
                if (livePreview.value) showPlain(null)
            }
        }
        showPlain(targets)
    }

    /** What every bulb shows in the draft, resting colors applied. */
    private fun resolveDraft(current: Draft): List<BulbApplyTarget> =
        resolveScene(setup.value, current.lights, current.allLightsOn, roomSlotId.takeIf { it != NO_ROOM })
            .withRestingColors(current.effects::get)

    /** Plain previews for the bulbs the runner isn't driving ([targets], or the draft worked out afresh). */
    private fun showPlain(targets: List<BulbApplyTarget>?) {
        val list = targets ?: resolveDraft(draft.value ?: return)
        val count = list.size
        for (i in 0 until count) {
            val target = list[i]
            val key = LightMirror.keyOf(target.protocolId, target.serial)
            if (key in runnerBulbs) {
                // Driven by the runner: resend its plain look when it comes back out.
                previewed.remove(key)
                continue
            }
            if (key in heldBack) continue
            val state = target.toLightStateOrNull()
            if (previewed.containsKey(key) && previewed[key] == state) continue
            previewed[key] = state
            lights.preview(target.toAddress(), state)
        }
    }

    /** Hands the runner the latest settings at most every [RUNNER_THROTTLE_MS], so drags stay smooth. */
    private fun scheduleRunnerPush() {
        if (runnerPushJob?.isActive == true) return
        runnerPushJob = viewModelScope.launch {
            delay(RUNNER_THROTTLE_MS)
            pendingGroups?.let(::pushRunner)
        }
    }

    private fun pushRunner(groups: List<EffectGroup>) {
        pendingGroups = null
        if (groups.isEmpty()) {
            effectRunId?.let { effectRunner.stopIfCurrent(it, settleFadeMs = -1) }
            effectRunId = null
            return
        }
        effectRunId = effectRunner.update(groups, LightController.PREVIEW_FADE_MS)
    }

    private fun stopEffectsPreview() {
        runnerPushJob?.cancel()
        pendingGroups = null
        runnerBulbs = emptySet()
        runnerSlots = emptySet()
        heldBack = emptySet()
        previewed.clear()
        effectRunId?.let { effectRunner.stopIfCurrent(it) }
        effectRunId = null
        // Turning live preview back on takes over the bulbs again.
        activated = false
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
        val removed: List<Long>,
        val lights: List<SceneLightEntity>,
        val effects: List<Pair<Long, GroupEffects>>,
    )

    private fun takePending(): PendingWrite? {
        val current = draft.value ?: return null
        if (!detailsDirty && !allLightsDirty && dirtySlots.isEmpty() && dirtyEffects.isEmpty() && removedSlots.isEmpty()) return null
        val details = if (detailsDirty) current.name.trim().ifEmpty { current.name } to current.fadeMs else null
        val changed = ArrayList<SceneLightEntity>(dirtySlots.size)
        for (slotId in dirtySlots) current.lights[slotId]?.let(changed::add)
        val changedEffects = ArrayList<Pair<Long, GroupEffects>>(dirtyEffects.size)
        for (slotId in dirtyEffects) current.effects[slotId]?.let { changedEffects.add(slotId to it) }
        val allLights = if (allLightsDirty) current.allLightsOn else null
        val removed = ArrayList(removedSlots)
        detailsDirty = false
        allLightsDirty = false
        dirtySlots.clear()
        dirtyEffects.clear()
        removedSlots.clear()
        return PendingWrite(details, allLights, removed, changed, changedEffects)
    }

    private suspend fun write(pending: PendingWrite) {
        // The scene may have been deleted while open; writing its lights would break foreign keys.
        if (scenes.getScene(sceneId) == null) return
        pending.details?.let { (name, fade) -> scenes.updateSceneDetails(sceneId, name, fade) }
        pending.allLightsOn?.let { scenes.setAllLightsOn(sceneId, it) }
        // Removals first: an individual light taken out and added back is saved fresh.
        for (slotId in pending.removed) scenes.removeSceneLight(sceneId, slotId)
        for (light in pending.lights) scenes.saveSceneLight(light)
        // After the lights: a group's effects need its settings row to exist.
        for ((slotId, effects) in pending.effects) scenes.saveGroupEffects(sceneId, slotId, effects)
    }

    override fun onCleared() {
        // The edited scene stays active and keeps playing, effects and all. Hand the runner any
        // settings that were still waiting for their throttle (viewModelScope is cancelled now).
        if (runnerPushJob?.isActive == true && livePreview.value) pendingGroups?.let(::pushRunner)
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
