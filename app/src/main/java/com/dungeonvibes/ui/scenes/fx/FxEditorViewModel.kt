package com.dungeonvibes.ui.scenes.fx

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.data.db.FxEntity
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.fx.FxItem
import com.dungeonvibes.data.fx.FxRepository
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.ApplicationScope
import com.dungeonvibes.light.ScenePlayer
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FxEditorUiState(
    val isLoaded: Boolean = false,
    /** The FX was deleted (e.g. from the list on a tablet) while open. */
    val isMissing: Boolean = false,
    val item: FxItem? = null,
    val groups: List<LightSlotEntity> = emptyList(),
    /** Library scenes, for "Then: Scene". */
    val libraryScenes: List<SceneEntity> = emptyList(),
)

/**
 * Edits one FX. Like the scene editor, the in-memory draft is the source of truth while open and is
 * saved shortly after the last change.
 */
@HiltViewModel(assistedFactory = FxEditorViewModel.Factory::class)
class FxEditorViewModel @AssistedInject constructor(
    @Assisted private val fxId: Long,
    private val fxRepository: FxRepository,
    slots: LightSlotRepository,
    scenes: SceneRepository,
    private val player: ScenePlayer,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(fxId: Long): FxEditorViewModel
    }

    private val draft = MutableStateFlow<FxItem?>(null)
    private val loaded = MutableStateFlow(false)

    val uiState: StateFlow<FxEditorUiState> = combine(
        draft,
        loaded,
        slots.observeSlots(),
        scenes.observeLibraryScenes(),
    ) { item, isLoaded, groups, library ->
        FxEditorUiState(
            isLoaded = isLoaded,
            isMissing = isLoaded && item == null,
            item = item,
            groups = groups,
            libraryScenes = library,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FxEditorUiState())

    private var dirty = false
    private var saveJob: Job? = null

    init {
        viewModelScope.launch {
            draft.value = fxRepository.get(fxId)
            loaded.value = true
        }
        viewModelScope.launch {
            // Deleted elsewhere while open: show it and stop saving.
            var seen = false
            fxRepository.observeFx().collect { list ->
                if (list.any { it.fx.id == fxId }) {
                    seen = true
                } else if (seen) {
                    draft.value = null
                    dirty = false
                }
            }
        }
    }

    /** Applies a change to the FX itself (name, primitive, settings, spread, transition). */
    fun edit(change: (FxEntity) -> FxEntity) {
        val current = draft.value ?: return
        val updated = change(current.fx)
        if (updated == current.fx) return
        draft.value = current.copy(fx = updated)
        scheduleSave()
    }

    fun setTargetAll(all: Boolean) = edit { it.copy(targetAll = all) }

    fun setGroupTargeted(slotId: Long, targeted: Boolean) {
        val current = draft.value ?: return
        val targets = if (targeted) current.targetSlotIds + slotId else current.targetSlotIds - slotId
        if (targets == current.targetSlotIds) return
        draft.value = current.copy(targetSlotIds = targets)
        scheduleSave()
    }

    /** Fires the FX as edited so far, without its transition. */
    fun tryIt() {
        val current = draft.value ?: return
        viewModelScope.launch { player.fireFx(current, adventureId = null, withTransition = false) }
    }

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            writePending()
        }
    }

    private suspend fun writePending() {
        val item = takePending() ?: return
        withContext(NonCancellable) { fxRepository.save(item) }
    }

    private fun takePending(): FxItem? {
        if (!dirty) return null
        dirty = false
        return draft.value
    }

    override fun onCleared() {
        val item = takePending() ?: return
        appScope.launch { fxRepository.save(item) }
    }

    companion object {
        const val SAVE_DEBOUNCE_MS = 400L
    }
}
