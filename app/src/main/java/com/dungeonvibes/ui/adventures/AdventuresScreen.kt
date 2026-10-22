package com.dungeonvibes.ui.adventures

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.data.db.AdventureWithSceneCount
import com.dungeonvibes.light.CommandedLight
import com.dungeonvibes.ui.adventures.play.GlowCard
import com.dungeonvibes.ui.adventures.play.GlowCardShape
import com.dungeonvibes.ui.adventures.play.PlayView
import com.dungeonvibes.ui.adventures.play.rememberRoomGlowModifier
import com.dungeonvibes.ui.common.ConfirmDialog
import com.dungeonvibes.ui.common.NameDialog
import com.dungeonvibes.ui.common.TopLevelScreen
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Adventures: the list, then an adventure's play view. Wide windows show both side by side.
 * Editing an adventure's scene opens the full-screen editor through [onOpenSceneEditor].
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun AdventuresScreen(onOpenSceneEditor: (Long) -> Unit) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Long>()
    val scope = rememberCoroutineScope()
    val selectedId = navigator.currentDestination?.contentKey
    val listHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                AdventureListPane(
                    selectedId = selectedId,
                    onOpen = { id -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) } },
                    onDeleted = { id -> if (id == selectedId) scope.launch { navigator.navigateBack() } },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val id = selectedId
                if (id == null) {
                    NoAdventureSelected()
                } else {
                    PlayView(
                        adventureId = id,
                        showBack = listHidden,
                        onBack = { scope.launch { navigator.navigateBack() } },
                        onOpenEditor = onOpenSceneEditor,
                    )
                }
            }
        },
    )
}

/** Which adventure dialog is open. */
private sealed interface AdventureDialog {
    data object Create : AdventureDialog
    data class Rename(val item: AdventureWithSceneCount) : AdventureDialog
    data class Delete(val item: AdventureWithSceneCount) : AdventureDialog
}

@Composable
private fun AdventureListPane(
    selectedId: Long?,
    onOpen: (Long) -> Unit,
    onDeleted: (Long) -> Unit,
    viewModel: AdventuresViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeAdventureId by viewModel.activeAdventureId.collectAsStateWithLifecycle()
    val roomBulbs by viewModel.roomBulbs.collectAsStateWithLifecycle()
    val effectsRunning by viewModel.effectsRunning.collectAsStateWithLifecycle()
    val observeLight: (String) -> StateFlow<CommandedLight?> = remember(viewModel) { viewModel::observeLight }
    val activeLabel = stringResource(R.string.play_active)
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var ordered by remember { mutableStateOf(state.adventures) }
    LaunchedEffect(state.adventures) { ordered = state.adventures }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        ordered = ordered.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    var dialog by remember { mutableStateOf<AdventureDialog?>(null) }

    TopLevelScreen(
        title = stringResource(R.string.nav_adventures),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { dialog = AdventureDialog.Create },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.adventure_add)) },
            )
        },
    ) { modifier ->
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            // Room around every card for the active one's glow, as in the play view.
            contentPadding = PaddingValues(start = CARD_GAP, end = CARD_GAP, top = CARD_GAP, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            items(ordered, key = { it.adventure.id }) { item ->
                ReorderableItem(reorderState, key = item.adventure.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 1.dp, label = "dragElevation")
                    val id = item.adventure.id
                    val playing = id == activeAdventureId
                    // The adventure whose scene is playing glows with the room, like its active tile.
                    val glow = rememberRoomGlowModifier(
                        active = playing,
                        bulbs = roomBulbs,
                        effectsRunning = effectsRunning,
                        shape = GlowCardShape,
                        observe = observeLight,
                    )
                    GlowCard(
                        onClick = { onOpen(id) },
                        modifier = Modifier.fillMaxWidth(),
                        glow = glow,
                        stateLabel = if (playing) activeLabel else null,
                        // The open adventure (side by side on tablets) is a shade lighter, still dark.
                        containerColor = if (id == selectedId) {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh
                        },
                        elevation = elevation,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(4.dp),
                        ) {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                    onDragStopped = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        viewModel.reorder(ordered.map { it.adventure.id })
                                    },
                                ),
                            ) {
                                Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = stringResource(R.string.action_reorder))
                            }
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(item.adventure.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    pluralStringResource(R.plurals.adventure_scene_count, item.sceneCount, item.sceneCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { dialog = AdventureDialog.Rename(item) }) {
                                Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.action_rename))
                            }
                            IconButton(onClick = { dialog = AdventureDialog.Delete(item) }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.action_delete))
                            }
                        }
                    }
                }
            }
            if (state.isLoaded && ordered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.adventures_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
    }

    when (val current = dialog) {
        null -> Unit
        AdventureDialog.Create -> NameDialog(
            title = stringResource(R.string.adventure_add),
            initialName = "",
            confirmLabel = stringResource(R.string.action_create),
            onConfirm = { name ->
                dialog = null
                scope.launch { onOpen(viewModel.create(name)) }
            },
            onDismiss = { dialog = null },
        )
        is AdventureDialog.Rename -> NameDialog(
            title = stringResource(R.string.adventure_rename),
            initialName = current.item.adventure.name,
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = { name ->
                viewModel.rename(current.item.adventure.id, name)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        is AdventureDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.adventure_delete_title, current.item.adventure.name),
            message = pluralStringResource(R.plurals.adventure_delete_message, current.item.sceneCount, current.item.sceneCount),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.delete(current.item.adventure.id)
                onDeleted(current.item.adventure.id)
                dialog = null
            },
            onDismiss = { dialog = null },
        )
    }
}

/** Space between and around adventure cards: enough for the playing one's glow. */
private val CARD_GAP = 16.dp

@Composable
private fun NoAdventureSelected() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(R.string.adventure_none_selected),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
