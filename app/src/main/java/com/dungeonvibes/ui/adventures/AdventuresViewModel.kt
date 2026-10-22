package com.dungeonvibes.ui.adventures

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.adventure.AdventureRepository
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.db.AdventureWithSceneCount
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.CommandedLight
import com.dungeonvibes.light.LightMirror
import com.dungeonvibes.light.ScenePlayer
import com.dungeonvibes.ui.adventures.play.buildGlowBulbs
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AdventuresUiState(
    val adventures: List<AdventureWithSceneCount> = emptyList(),
    val isLoaded: Boolean = false,
)

@HiltViewModel
class AdventuresViewModel @Inject constructor(
    private val adventures: AdventureRepository,
    scenes: SceneRepository,
    player: ScenePlayer,
    slots: LightSlotRepository,
    bulbs: BulbRepository,
    private val mirror: LightMirror,
) : ViewModel() {

    /**
     * The adventure whose scene is playing: the active scene's own adventure, or the one a library
     * scene was reached in through an FX. Null for library scenes played from the library, and
     * after Lights off.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeAdventureId: StateFlow<Long?> = player.active
        .mapLatest { active -> if (active == null) null else active.adventureId ?: scenes.getScene(active.sceneId)?.adventureId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every assigned bulb, for the active adventure's glow (it shows the whole room). */
    val roomBulbs: StateFlow<List<String>> = combine(
        slots.observeSlots(),
        slots.observeAssignments(),
        bulbs.observeBulbs(),
    ) { slotList, assignments, bulbList ->
        buildGlowBulbs(slotList, assignments, bulbList).all
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether effects are animating (the glow breathes only while they aren't). */
    val effectsRunning: StateFlow<Boolean> = player.effectsRunning

    /** What the app last sent one bulb, for the glow to follow. */
    fun observeLight(key: String): StateFlow<CommandedLight?> = mirror.observe(key)

    val uiState: StateFlow<AdventuresUiState> = adventures.observeAdventures()
        .map { AdventuresUiState(it, isLoaded = true) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdventuresUiState())

    /** Creates an empty adventure at the end of the list and returns its id. */
    suspend fun create(name: String): Long = adventures.create(name)

    fun rename(adventureId: Long, name: String) {
        viewModelScope.launch { adventures.rename(adventureId, name) }
    }

    /** Deletes the adventure and its scenes; library scenes are untouched. */
    fun delete(adventureId: Long) {
        viewModelScope.launch { adventures.delete(adventureId) }
    }

    fun reorder(orderedAdventureIds: List<Long>) {
        viewModelScope.launch { adventures.reorder(orderedAdventureIds) }
    }
}
