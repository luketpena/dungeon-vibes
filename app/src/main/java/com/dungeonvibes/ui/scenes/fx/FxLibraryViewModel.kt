package com.dungeonvibes.ui.scenes.fx

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.fx.FxItem
import com.dungeonvibes.data.fx.FxRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.ScenePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the FX library: the FX and the names of its target groups (empty for All lights). */
data class FxListItem(val item: FxItem, val groupNames: List<String>)

data class FxLibraryUiState(
    val fx: List<FxListItem> = emptyList(),
    val isLoaded: Boolean = false,
)

@HiltViewModel
class FxLibraryViewModel @Inject constructor(
    private val fxRepository: FxRepository,
    slots: LightSlotRepository,
    private val player: ScenePlayer,
) : ViewModel() {

    val uiState: StateFlow<FxLibraryUiState> = combine(fxRepository.observeFx(), slots.observeSlots()) { list, slotList ->
        FxLibraryUiState(
            fx = list.map { item -> FxListItem(item, slotList.filter { it.id in item.targetSlotIds }.map { it.name }) },
            isLoaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FxLibraryUiState())

    suspend fun create(name: String): Long = fxRepository.create(name)

    fun delete(fxId: Long) {
        viewModelScope.launch { fxRepository.delete(fxId) }
    }

    fun reorder(orderedIds: List<Long>) {
        viewModelScope.launch { fxRepository.reorder(orderedIds) }
    }

    /** Plays an FX on the bulbs without its transition, to see what it looks like. */
    fun tryFx(item: FxItem) {
        viewModelScope.launch { player.fireFx(item, adventureId = null, withTransition = false) }
    }
}
