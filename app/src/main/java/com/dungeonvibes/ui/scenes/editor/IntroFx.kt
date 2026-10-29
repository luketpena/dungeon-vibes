package com.dungeonvibes.ui.scenes.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.R
import com.dungeonvibes.data.fx.FxItem
import com.dungeonvibes.data.fx.FxRepository
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.ui.common.SettingsCard
import com.dungeonvibes.ui.common.Swatch
import com.dungeonvibes.ui.common.SwatchSize
import com.dungeonvibes.ui.scenes.fx.fxSwatchColor
import com.dungeonvibes.ui.scenes.fx.primitiveLabel
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The scene's intro FX (null for none) and the FX library to pick from, in library order. */
data class IntroFxUiState(
    val intro: FxItem? = null,
    val library: List<FxItem> = emptyList(),
)

/**
 * The scene editor's intro FX (FX_REQUIREMENTS.md → Intro FX). Saved straight away, outside the
 * editor's draft: it's one pick, not a slider, and it doesn't change what the bulbs show while
 * editing (it plays when the scene is applied).
 */
@HiltViewModel(assistedFactory = IntroFxViewModel.Factory::class)
class IntroFxViewModel @AssistedInject constructor(
    @Assisted private val sceneId: Long,
    private val scenes: SceneRepository,
    fx: FxRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(sceneId: Long): IntroFxViewModel
    }

    val uiState: StateFlow<IntroFxUiState> = combine(scenes.observeScene(sceneId), fx.observeFx()) { scene, library ->
        val introId = scene?.introFxId
        IntroFxUiState(intro = if (introId == null) null else library.firstOrNull { it.fx.id == introId }, library = library)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), IntroFxUiState())

    /** Picks the intro FX, or with null clears it. */
    fun setIntro(fxId: Long?) {
        viewModelScope.launch { scenes.setIntroFx(sceneId, fxId) }
    }
}

/**
 * The scene's Introduction box (owner request, Oct 23, 2026): its fade in and, for scenes that can
 * be played from a tile or the library, its intro FX (tap to pick or clear one).
 */
@Composable
internal fun IntroductionCard(
    sceneId: Long,
    fadeMs: Int,
    onFadeChange: (Int) -> Unit,
    showIntroFx: Boolean,
) {
    SettingsCard(
        title = stringResource(R.string.scene_intro),
        icon = R.drawable.ic_card_intro,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FadeControl(fadeMs, onFadeChange)
        if (showIntroFx) IntroFxRow(sceneId)
    }
}

@Composable
private fun IntroFxRow(sceneId: Long) {
    val viewModel = hiltViewModel<IntroFxViewModel, IntroFxViewModel.Factory>(key = "intro-$sceneId") { it.create(sceneId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val intro = state.intro

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clickable { picking = true }
            .padding(vertical = 8.dp),
    ) {
        // Labelled like every other control: what it is (muted), then what's picked (bright).
        Text(
            stringResource(R.string.scene_intro_fx),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            intro?.fx?.name ?: stringResource(R.string.scene_intro_none),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        if (intro != null) {
            Swatch(fxSwatchColor(intro.fx), SwatchSize.Large)
            Spacer(Modifier.width(8.dp))
        }
        Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
    }

    if (picking) {
        IntroFxDialog(
            library = state.library,
            selectedId = intro?.fx?.id,
            onPick = { id ->
                viewModel.setIntro(id)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

/** Pick an intro FX from the library (a scrolling list), or clear it. Picking closes the dialog. */
@Composable
private fun IntroFxDialog(
    library: List<FxItem>,
    selectedId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scene_intro_fx)) },
        text = {
            if (library.isEmpty()) {
                Text(stringResource(R.string.fx_board_empty), style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.heightIn(max = DIALOG_LIST_MAX)) {
                    items(library, key = { it.fx.id }) { item ->
                        val fx = item.fx
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(fx.id) }
                                .padding(vertical = 10.dp),
                        ) {
                            Swatch(fxSwatchColor(fx), SwatchSize.Large)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(fx.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    stringResource(primitiveLabel(fx.primitive)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (fx.id == selectedId) {
                                Icon(
                                    painterResource(R.drawable.ic_check),
                                    contentDescription = stringResource(R.string.scene_intro_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (selectedId != null) {
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.scene_intro_clear)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The picker's list scrolls past this height. */
private val DIALOG_LIST_MAX = 400.dp
