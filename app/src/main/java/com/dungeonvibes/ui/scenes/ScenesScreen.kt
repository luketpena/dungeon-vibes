package com.dungeonvibes.ui.scenes

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.ui.adventures.play.PlayStateIcon
import com.dungeonvibes.ui.common.SceneHaptic
import com.dungeonvibes.ui.common.ConfirmDialog
import com.dungeonvibes.ui.common.ItemMenuButton
import com.dungeonvibes.ui.common.UndoDeleteSnackbars
import com.dungeonvibes.ui.common.LocalOnLocalNetwork
import com.dungeonvibes.ui.common.ScenePalette
import com.dungeonvibes.ui.common.TopLevelScreen
import com.dungeonvibes.ui.scenes.editor.SceneEditor
import com.dungeonvibes.ui.scenes.fx.FxEditor
import com.dungeonvibes.ui.scenes.fx.FxLibraryPane
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * The scene library. Phones show the list, then the editor full-screen; tablets in landscape show
 * both side by side. The list-detail scaffold decides which, and handles Back.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ScenesScreen() {
    // The detail key is a scene id, or an FX id negated (one navigator serves both libraries).
    val navigator = rememberListDetailPaneScaffoldNavigator<Long>()
    val scope = rememberCoroutineScope()
    val selectedKey = navigator.currentDestination?.contentKey
    val listHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden
    var showFx by rememberSaveable { mutableStateOf(false) }
    val modeSwitch: @Composable () -> Unit = { LibrarySwitch(showFx) { showFx = it } }

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                if (showFx) {
                    FxLibraryPane(
                        selectedFxId = selectedKey?.takeIf { it < 0 }?.let { -it },
                        onOpen = { id -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, -id) } },
                        onDeleted = { id -> if (-id == selectedKey) scope.launch { navigator.navigateBack() } },
                        header = modeSwitch,
                    )
                } else {
                    SceneLibraryPane(
                        selectedId = selectedKey?.takeIf { it > 0 },
                        onOpen = { id -> scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, id) } },
                        onDeleted = { id -> if (id == selectedKey) scope.launch { navigator.navigateBack() } },
                        header = modeSwitch,
                    )
                }
            }
        },
        detailPane = {
            AnimatedPane {
                val key = selectedKey
                when {
                    key == null -> NothingSelected(if (showFx) R.string.fx_none_selected else R.string.scene_none_selected)
                    key < 0 -> FxEditor(
                        fxId = -key,
                        showBack = listHidden,
                        onBack = { scope.launch { navigator.navigateBack() } },
                    )
                    else -> SceneEditor(
                        sceneId = key,
                        showBack = listHidden,
                        onBack = { scope.launch { navigator.navigateBack() } },
                    )
                }
            }
        },
    )
}

/** Scenes | FX: the two libraries share the nav destination, as tabs (the same row as Setup's). */
@Composable
private fun LibrarySwitch(showFx: Boolean, onChange: (Boolean) -> Unit) {
    PrimaryTabRow(selectedTabIndex = if (showFx) 1 else 0) {
        Tab(
            selected = !showFx,
            onClick = { onChange(false) },
            text = { Text(stringResource(R.string.library_scenes)) },
            icon = { Icon(painterResource(R.drawable.ic_library_scenes), contentDescription = null) },
        )
        Tab(
            selected = showFx,
            onClick = { onChange(true) },
            text = { Text(stringResource(R.string.library_fx)) },
            icon = { Icon(painterResource(R.drawable.ic_fx), contentDescription = null) },
        )
    }
}

@Composable
private fun SceneLibraryPane(
    selectedId: Long?,
    onOpen: (Long) -> Unit,
    onDeleted: (Long) -> Unit,
    header: @Composable () -> Unit,
    viewModel: ScenesViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val activeSceneId by viewModel.activeSceneId.collectAsStateWithLifecycle()
    val applyLabel = stringResource(R.string.action_apply)
    val onLocalNetwork = LocalOnLocalNetwork.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val newSceneName = stringResource(R.string.scene_new_name)

    // Snackbar text is resolved here, where string resources are available.
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ScenesEvent.SomeBulbsFailed -> snackbar.showSnackbar(
                    context.resources.getQuantityString(R.plurals.apply_failed, event.count, event.count),
                )
            }
        }
    }

    UndoDeleteSnackbars(viewModel.deletions, snackbar)

    var ordered by remember { mutableStateOf(state.scenes) }
    LaunchedEffect(state.scenes) { ordered = state.scenes }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        ordered = ordered.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    var pendingDelete by remember { mutableStateOf<SceneEntity?>(null) }

    TopLevelScreen(
        title = stringResource(R.string.nav_scenes),
        snackbarHostState = snackbar,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { onOpen(viewModel.createScene(newSceneName)) } },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.scene_add)) },
            )
        },
    ) { modifier ->
        Column(modifier.fillMaxSize()) {
        header()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
        ) {
            items(ordered, key = { it.scene.id }) { item ->
                ReorderableItem(reorderState, key = item.scene.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "dragElevation")
                    val selected = item.scene.id == selectedId
                    Surface(
                        shadowElevation = elevation,
                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(item.scene.id) }
                                .padding(4.dp),
                        ) {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                    onDragStopped = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        viewModel.reorder(ordered.map { it.scene.id })
                                    },
                                ),
                            ) {
                                Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = stringResource(R.string.action_reorder))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(item.scene.name, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(4.dp))
                                ScenePalette(item.swatches)
                            }
                            IconButton(
                                onClick = {
                                    haptics.performHapticFeedback(SceneHaptic)
                                    viewModel.play(item.scene.id)
                                },
                                enabled = onLocalNetwork,
                            ) {
                                // The same mark as the adventure tiles: play, or spinning while this scene plays.
                                PlayStateIcon(
                                    running = item.scene.id == activeSceneId,
                                    colors = item.swatches.markColors(),
                                    modifier = Modifier.semantics { contentDescription = applyLabel },
                                )
                            }
                            ItemMenuButton(
                                onDuplicate = { viewModel.duplicate(item.scene.id) },
                                onDelete = { pendingDelete = item.scene },
                            )
                        }
                    }
                }
            }
            if (state.isLoaded && ordered.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.scenes_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
        }
        }
    }

    pendingDelete?.let { scene ->
        ConfirmDialog(
            title = stringResource(R.string.scene_delete_title, scene.name),
            message = stringResource(R.string.scene_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.delete(scene.id)
                onDeleted(scene.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun NothingSelected(textRes: Int) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            stringResource(textRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
