package com.dungeonvibes.ui.adventures.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.db.SlotBulbEntity
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.data.fx.FxItem
import com.dungeonvibes.data.fx.FxRepository
import com.dungeonvibes.light.ActiveScene
import com.dungeonvibes.light.CommandedLight
import com.dungeonvibes.light.LightMirror
import com.dungeonvibes.light.ScenePlayer
import com.dungeonvibes.ui.common.DeletedItem
import com.dungeonvibes.ui.scenes.SceneListItem
import com.dungeonvibes.ui.scenes.SceneSwatch
import com.dungeonvibes.ui.scenes.buildSwatches
import com.dungeonvibes.ui.scenes.groupLights
import com.dungeonvibes.ui.scenes.sceneHasEffects
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One scene tile: the scene, its palette (lit groups only) and whether it has effects (✦). */
data class PlayTile(
    val scene: SceneEntity,
    val swatches: List<SceneSwatch>,
    val hasEffects: Boolean = false,
)

data class PlayUiState(
    val isLoaded: Boolean = false,
    /** The adventure was deleted while open (e.g. from the list on a tablet). */
    val isMissing: Boolean = false,
    val adventureName: String = "",
    val tiles: List<PlayTile> = emptyList(),
    val activeSceneId: Long? = null,
    /** Slots whose bulbs didn't respond when the active scene was applied. */
    val failedSlotIds: Set<Long> = emptySet(),
    /** The active scene was reached through an FX (a library scene: maybe a ghost tile here). */
    val activeFromFx: Boolean = false,
)

/** Which bulbs light each group, as [LightMirror] keys, for the active tile's glow. */
data class GlowBulbs(
    /** Each group's bulbs, in name order (the order Wave follows). */
    val bySlot: Map<Long, List<String>> = emptyMap(),
    /** Every assigned bulb, by group order then name: an All-lights scene's glow. */
    val all: List<String> = emptyList(),
)

/** Groups [bulbs] (which come in name order) by their [assignments]; unassigned bulbs are left out. */
internal fun buildGlowBulbs(
    slots: List<LightSlotEntity>,
    assignments: List<SlotBulbEntity>,
    bulbs: List<BulbEntity>,
): GlowBulbs {
    val bulbCount = bulbs.size
    if (bulbCount == 0) return GlowBulbs()
    val keyOf = HashMap<Long, String>(bulbCount * 2)
    for (bulb in bulbs) keyOf[bulb.id] = LightMirror.keyOf(bulb.protocolId, bulb.serial)
    // A bulb can be in several groups. Each group's bulbs in its bulb order (Setup → Groups).
    val members = HashMap<Long, ArrayList<SlotBulbEntity>>()
    for (assignment in assignments) members.getOrPut(assignment.slotId) { ArrayList(4) }.add(assignment)
    val bySlot = HashMap<Long, List<String>>()
    // Each group's bulbs once, groups in order: an All-lights glow covers every bulb once.
    val all = LinkedHashSet<String>()
    for (slot in slots) {
        val single = slot.bulbId
        if (single != null) {
            // An individual light's slot: just its bulb.
            keyOf[single]?.let { bySlot[slot.id] = listOf(it) }
            continue
        }
        val ids = members[slot.id] ?: continue
        ids.sortWith(compareBy<SlotBulbEntity>({ it.position }, { it.bulbId }))
        val keys = ArrayList<String>(ids.size)
        for (member in ids) keyOf[member.bulbId]?.let(keys::add)
        bySlot[slot.id] = keys
        all.addAll(keys)
    }
    return GlowBulbs(bySlot, ArrayList(all))
}

@HiltViewModel(assistedFactory = PlayViewModel.Factory::class)
class PlayViewModel @AssistedInject constructor(
    @Assisted private val adventureId: Long,
    private val scenes: SceneRepository,
    adventures: AdventureRepository,
    slots: LightSlotRepository,
    private val player: ScenePlayer,
    private val fxRepository: FxRepository,
    bulbs: BulbRepository,
    private val mirror: LightMirror,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(adventureId: Long): PlayViewModel
    }

    // combine() takes at most five flows with typed parameters, so lights and effects are paired first.
    val uiState: StateFlow<PlayUiState> = combine(
        adventures.observeAdventure(adventureId),
        scenes.observeAdventureScenes(adventureId),
        combine(slots.observeSwatchSlots(), slots.observeRoomSlotId()) { s, r -> s to r },
        combine(scenes.observeAdventureLights(adventureId), scenes.observeAdventureEffects(adventureId)) { l, e -> l to e },
        player.active,
    ) { adventure, sceneList, (slotList, roomId), (lights, effects), active ->
        if (adventure == null) return@combine PlayUiState(isLoaded = true, isMissing = true)
        val grouped = groupLights(lights)
        val tiles = ArrayList<PlayTile>(sceneList.size)
        for (scene in sceneList) {
            tiles.add(
                PlayTile(
                    scene,
                    buildSwatches(slotList, grouped, scene.id, effects, scene.allLightsOn, roomId),
                    sceneHasEffects(grouped, effects, scene.id, scene.allLightsOn, roomId),
                ),
            )
        }
        PlayUiState(
            isLoaded = true,
            adventureName = adventure.name,
            tiles = tiles,
            activeSceneId = active?.sceneId,
            activeFromFx = active?.fromFx == true,
            failedSlotIds = failedSwatches(active, sceneList, roomId),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayUiState())

    /**
     * Which swatches of the active tile get a "!": its failed groups, or with All lights on (one
     * swatch for the whole room) that single swatch if any bulb failed.
     */
    private fun failedSwatches(active: ActiveScene?, sceneList: List<SceneEntity>, roomId: Long?): Set<Long> {
        val failed = active?.failedSlotIds ?: return emptySet()
        if (failed.isEmpty() || roomId == null) return failed
        val scene = sceneList.firstOrNull { it.id == active.sceneId } ?: return failed
        return if (scene.allLightsOn) failed + roomId else failed
    }

    /** Library scenes for the "From library" picker. */
    val libraryScenes: StateFlow<List<SceneListItem>> = combine(
        scenes.observeLibraryScenes(),
        slots.observeSwatchSlots(),
        scenes.observeLibraryLights(),
        scenes.observeLibraryEffects(),
        slots.observeRoomSlotId(),
    ) { library, slotList, lights, effects, roomId ->
        val grouped = groupLights(lights)
        library.map {
            SceneListItem(
                it,
                buildSwatches(slotList, grouped, it.id, effects, it.allLightsOn, roomId),
                sceneHasEffects(grouped, effects, it.id, it.allLightsOn, roomId),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The bulbs behind each group, so the active tile's glow can follow them. */
    val glowBulbs: StateFlow<GlowBulbs> = combine(
        slots.observeSwatchSlots(),
        slots.observeAssignments(),
        bulbs.observeBulbs(),
    ) { slotList, assignments, bulbList ->
        buildGlowBulbs(slotList, assignments, bulbList)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GlowBulbs())

    /** What the app last sent one bulb ([LightMirror] key), for the glow to animate toward. */
    fun observeLight(key: String): StateFlow<CommandedLight?> = mirror.observe(key)

    /** The FX library, as the board's buttons. */
    val fx: StateFlow<List<FxItem>> = fxRepository.observeFx().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** This adventure's favorite FX ids, in the order they were starred: the board shows these first. */
    val favoriteFxIds: StateFlow<List<Long>> = fxRepository.observeFavorites(adventureId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Stars or un-stars an FX for this adventure's board. */
    fun setFavorite(fxId: Long, favorite: Boolean) {
        viewModelScope.launch { fxRepository.setFavorite(adventureId, fxId, favorite) }
    }

    /** Fires an FX from the board; moving into a library scene plays this adventure's copy if it has one. */
    fun fireFx(item: FxItem) {
        viewModelScope.launch { player.fireFx(item, adventureId) }
    }

    /** Plays the ghost tile's library scene again (it stays the ghost). */
    fun playGhost(librarySceneId: Long) {
        viewModelScope.launch { player.replayFromFx(librarySceneId, adventureId) }
    }

    /** Applies a tile's scene. The tile turns active at once; failure badges follow if a bulb doesn't answer. */
    fun play(sceneId: Long) {
        viewModelScope.launch { player.play(sceneId) }
    }

    /** Adds an empty scene (every slot off) at the end and returns its id, for opening the editor. */
    suspend fun addBlankScene(name: String): Long? = scenes.createAdventureScene(adventureId, name)

    /** Rule 1: each picked library scene becomes its own copy, in the order given. */
    fun addFromLibrary(librarySceneIds: List<Long>) {
        if (librarySceneIds.isEmpty()) return
        viewModelScope.launch { scenes.addLibraryScenesToAdventure(adventureId, librarySceneIds) }
    }

    private val _deletions = Channel<DeletedItem>(Channel.BUFFERED)

    /** Each delete, for the Undo snackbar. */
    val deletions: Flow<DeletedItem> = _deletions.receiveAsFlow()

    fun deleteScene(sceneId: Long) {
        viewModelScope.launch {
            val deleted = scenes.deleteScene(sceneId) ?: return@launch
            _deletions.send(DeletedItem(deleted.scene.name) { viewModelScope.launch { scenes.restoreScene(deleted) } })
        }
    }

    /** Copies a scene right after itself in this adventure. */
    fun duplicateScene(sceneId: Long) {
        viewModelScope.launch { scenes.duplicateScene(sceneId) }
    }

    fun reorder(orderedSceneIds: List<Long>) {
        viewModelScope.launch { scenes.reorderScenes(orderedSceneIds) }
    }
}
