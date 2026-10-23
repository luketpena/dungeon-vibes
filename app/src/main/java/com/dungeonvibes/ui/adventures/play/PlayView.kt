package com.dungeonvibes.ui.adventures.play

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.data.db.FxTransition
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.fx.FxItem
import com.dungeonvibes.ui.common.SceneHaptic
import com.dungeonvibes.ui.common.FxHaptic
import com.dungeonvibes.ui.common.ConfirmDialog
import com.dungeonvibes.ui.common.ItemMenuButton
import com.dungeonvibes.ui.common.UndoDeleteSnackbars
import com.dungeonvibes.ui.common.LocalOnLocalNetwork
import com.dungeonvibes.ui.common.ScenePalette
import com.dungeonvibes.ui.common.TopLevelScreen
import com.dungeonvibes.ui.common.Swatch
import com.dungeonvibes.ui.common.SwatchSize
import com.dungeonvibes.ui.scenes.SceneListItem
import com.dungeonvibes.ui.scenes.fx.fxSwatchColor
import com.dungeonvibes.ui.scenes.markColors
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * An adventure at the table: scenes as big tiles, tap to apply. Edit mode reorders (long-press and
 * drag), deletes and opens tiles in the editor instead; tiles never apply in edit mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayView(
    adventureId: Long,
    showBack: Boolean,
    onBack: () -> Unit,
    onOpenEditor: (Long) -> Unit,
) {
    val viewModel = hiltViewModel<PlayViewModel, PlayViewModel.Factory>(key = "adventure-$adventureId") { factory ->
        factory.create(adventureId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val library by viewModel.libraryScenes.collectAsStateWithLifecycle()
    val glowBulbs by viewModel.glowBulbs.collectAsStateWithLifecycle()
    val fxList by viewModel.fx.collectAsStateWithLifecycle()
    val favoriteIds by viewModel.favoriteFxIds.collectAsStateWithLifecycle()
    var showBoard by rememberSaveable(adventureId) { mutableStateOf(false) }
    // The board opens on this adventure's favorites when it has some; "All FX" shows the library.
    var showAllFx by rememberSaveable(adventureId) { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    UndoDeleteSnackbars(viewModel.deletions, snackbar)
    val onLocalNetwork = LocalOnLocalNetwork.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val newSceneName = stringResource(R.string.scene_new_name)

    KeepScreenOn()

    var editMode by rememberSaveable(adventureId) { mutableStateOf(false) }
    var showAddChoice by remember { mutableStateOf(false) }
    var showLibraryPicker by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SceneEntity?>(null) }

    var ordered by remember { mutableStateOf(state.tiles) }
    LaunchedEffect(state.tiles) { ordered = state.tiles }
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        // Only tiles move (never onto the ghost tile, which comes after them).
        if (from.index >= ordered.size || to.index >= ordered.size) return@rememberReorderableLazyGridState
        ordered = ordered.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    TopLevelScreen(
        title = state.adventureName,
        snackbarHostState = snackbar,
        // Beside an open list (tablets) the list pane carries the lights menu.
        lightsMenu = showBack,
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = {
            if (state.tiles.isNotEmpty()) {
                IconButton(onClick = { editMode = !editMode }) {
                    Icon(
                        painterResource(if (editMode) R.drawable.ic_check else R.drawable.ic_edit),
                        contentDescription = stringResource(if (editMode) R.string.play_done_editing else R.string.play_edit),
                    )
                }
            }
        },
        floatingActionButton = {
            if (editMode || (state.isLoaded && state.tiles.isEmpty() && !state.isMissing)) {
                ExtendedFloatingActionButton(
                    onClick = { showAddChoice = true },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.play_add_scene)) },
                )
            } else if (state.tiles.isNotEmpty()) {
                // Playing: the FX board, opened and closed from a round button (owner request).
                FloatingActionButton(onClick = { showBoard = !showBoard }, shape = CircleShape) {
                    Icon(painterResource(R.drawable.ic_fx), contentDescription = stringResource(R.string.fx_board))
                }
            }
        },
    ) { modifier ->
        if (!state.isLoaded) return@TopLevelScreen
        if (state.isMissing) {
            CenteredMessage(stringResource(R.string.adventure_missing), modifier)
            return@TopLevelScreen
        }
        if (ordered.isEmpty()) {
            CenteredMessage(stringResource(R.string.play_empty), modifier)
            return@TopLevelScreen
        }
        // A library scene an FX moved into, when this adventure has no copy of it: a ghost tile.
        val activeId = state.activeSceneId
        val ghost = if (state.activeFromFx && activeId != null && ordered.none { it.scene.id == activeId || it.scene.parentSceneId == activeId }) {
            library.firstOrNull { it.scene.id == activeId }
        } else {
            null
        }
        val fireFx: (FxItem) -> Unit = { item ->
            haptics.performHapticFeedback(FxHaptic)
            viewModel.fireFx(item)
        }
        val fxEnabled: (FxItem) -> Boolean = { item -> onLocalNetwork && canFire(item, hasActive = activeId != null) }
        BoxWithConstraints(modifier) {
            // Wide windows keep the board beside the tiles; phones open it as a sheet.
            val wide = maxWidth >= BOARD_SIDE_MIN_WIDTH
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f)) {
                    if (editMode) {
                        Text(
                            stringResource(R.string.play_edit_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 160.dp),
                        state = gridState,
                        // Room around every tile for the active one's glow.
                        contentPadding = PaddingValues(start = TILE_GAP, end = TILE_GAP, top = TILE_GAP, bottom = 96.dp),
                        horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
                        verticalArrangement = Arrangement.spacedBy(TILE_GAP),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(ordered, key = { it.scene.id }) { tile ->
                            ReorderableItem(reorderState, key = tile.scene.id, enabled = editMode) { isDragging ->
                                val sceneId = tile.scene.id
                                val glow = rememberSceneGlowModifier(
                                    active = sceneId == state.activeSceneId,
                                    swatches = tile.swatches,
                                    allLightsOn = tile.scene.allLightsOn,
                                    hasEffects = tile.hasEffects,
                                    bulbs = glowBulbs,
                                    shape = GlowCardShape,
                                    observe = viewModel::observeLight,
                                )
                                SceneTile(
                                    tile = tile,
                                    glow = glow,
                                    isActive = sceneId == state.activeSceneId,
                                    failedSlotIds = if (sceneId == state.activeSceneId) state.failedSlotIds else emptySet(),
                                    editMode = editMode,
                                    isDragging = isDragging,
                                    canApply = onLocalNetwork,
                                    onClick = {
                                        if (editMode) {
                                            onOpenEditor(sceneId)
                                        } else if (onLocalNetwork) {
                                            haptics.performHapticFeedback(SceneHaptic)
                                            viewModel.play(sceneId)
                                        }
                                    },
                                    onDuplicate = { viewModel.duplicateScene(sceneId) },
                                    onDelete = { pendingDelete = tile.scene },
                                    modifier = if (editMode) {
                                        Modifier.longPressDraggableHandle(
                                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                            onDragStopped = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                                viewModel.reorder(ordered.map { it.scene.id })
                                            },
                                        )
                                    } else {
                                        Modifier
                                    },
                                )
                            }
                        }
                        if (ghost != null) {
                            item(key = "ghost") {
                                GhostTile(
                                    item = ghost,
                                    glow = rememberSceneGlowModifier(
                                        active = true,
                                        swatches = ghost.swatches,
                                        allLightsOn = ghost.scene.allLightsOn,
                                        hasEffects = ghost.hasEffects,
                                        bulbs = glowBulbs,
                                        shape = GlowCardShape,
                                        observe = viewModel::observeLight,
                                        dashed = true,
                                    ),
                                    editMode = editMode,
                                    canApply = onLocalNetwork,
                                    onClick = {
                                        if (!editMode && onLocalNetwork) {
                                            haptics.performHapticFeedback(SceneHaptic)
                                            viewModel.playGhost(ghost.scene.id)
                                        }
                                    },
                                    onAdd = { viewModel.addFromLibrary(listOf(ghost.scene.id)) },
                                )
                            }
                        }
                    }
                }
                if (wide && showBoard && !editMode) {
                    FxBoard(
                        fx = fxList,
                        favoriteIds = favoriteIds,
                        showAll = showAllFx,
                        onShowAllChange = { showAllFx = it },
                        onFavoriteChange = viewModel::setFavorite,
                        enabled = fxEnabled,
                        showReturnHint = activeId == null,
                        onFire = fireFx,
                        modifier = Modifier.width(BOARD_SIDE_WIDTH).fillMaxHeight(),
                        clearFab = true,
                    )
                }
            }
            if (!wide && showBoard && !editMode) {
                ModalBottomSheet(onDismissRequest = { showBoard = false }) {
                    FxBoard(
                        fx = fxList,
                        favoriteIds = favoriteIds,
                        showAll = showAllFx,
                        onShowAllChange = { showAllFx = it },
                        onFavoriteChange = viewModel::setFavorite,
                        enabled = fxEnabled,
                        showReturnHint = activeId == null,
                        onFire = fireFx,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                    )
                }
            }
        }
    }

    if (showAddChoice) {
        AddSceneChoiceDialog(
            hasLibraryScenes = library.isNotEmpty(),
            onBlank = {
                showAddChoice = false
                scope.launch { viewModel.addBlankScene(newSceneName)?.let(onOpenEditor) }
            },
            onFromLibrary = {
                showAddChoice = false
                showLibraryPicker = true
            },
            onDismiss = { showAddChoice = false },
        )
    }
    if (showLibraryPicker) {
        LibraryPickerDialog(
            library = library,
            onAdd = { ids ->
                viewModel.addFromLibrary(ids)
                showLibraryPicker = false
            },
            onDismiss = { showLibraryPicker = false },
        )
    }
    pendingDelete?.let { scene ->
        ConfirmDialog(
            title = stringResource(R.string.scene_delete_title, scene.name),
            message = stringResource(R.string.play_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.deleteScene(scene.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** Space between tiles and around the grid, so the active tile's glow has room. */
private val TILE_GAP = 16.dp

/** Wide windows (tablets) show the FX board beside the tiles. */
private val BOARD_SIDE_MIN_WIDTH = 720.dp
private val BOARD_SIDE_WIDTH = 240.dp

/**
 * Whether an FX can fire: it needs bulbs, and one that returns needs a scene to return to
 * (a Scene transition without its scene counts as Return).
 */
private fun canFire(item: FxItem, hasActive: Boolean): Boolean {
    if (item.hasNoTargets) return false
    val fx = item.fx
    val returns = fx.transition == FxTransition.RETURN || (fx.transition == FxTransition.SCENE && fx.transitionSceneId == null)
    return hasActive || !returns
}

/**
 * The FX board: this adventure's favorites (in the order they were starred) when it has some,
 * otherwise, or under "All FX", the whole library in library order with a star by each to pin it
 * here. Tap fires.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FxBoard(
    fx: List<FxItem>,
    favoriteIds: List<Long>,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onFavoriteChange: (fxId: Long, favorite: Boolean) -> Unit,
    enabled: (FxItem) -> Boolean,
    showReturnHint: Boolean,
    onFire: (FxItem) -> Unit,
    modifier: Modifier = Modifier,
    clearFab: Boolean = false,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        // Beside the tiles, bottom room keeps the last buttons clear of the floating FX button.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = if (clearFab) 88.dp else 12.dp)) {
            // Favorites that still exist, in starred order.
            val favorites = remember(fx, favoriteIds) {
                val byId = HashMap<Long, FxItem>(fx.size * 2)
                for (item in fx) byId[item.fx.id] = item
                favoriteIds.mapNotNull { byId[it] }
            }
            val favoriteSet = remember(favoriteIds) { favoriteIds.toHashSet() }
            val showingAll = showAll || favorites.isEmpty()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.fx_board), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (favorites.isNotEmpty()) {
                    TextButton(onClick = { onShowAllChange(!showAll) }) {
                        Text(stringResource(if (showAll) R.string.fx_board_favorites else R.string.fx_board_all))
                    }
                }
            }
            if (fx.isEmpty()) {
                Text(
                    stringResource(R.string.fx_board_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }
            if (showReturnHint) {
                Text(
                    stringResource(R.string.fx_board_return_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (favorites.isEmpty()) {
                Text(
                    stringResource(R.string.fx_board_star_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 8.dp),
            ) {
                for (item in if (showingAll) fx else favorites) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(
                            onClick = { onFire(item) },
                            enabled = enabled(item),
                            contentPadding = PaddingValues(start = 12.dp, end = 16.dp),
                        ) {
                            Swatch(fxSwatchColor(item.fx), SwatchSize.Medium)
                            Spacer(Modifier.width(8.dp))
                            Text(item.fx.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (showingAll) {
                            val id = item.fx.id
                            val starred = id in favoriteSet
                            IconButton(onClick = { onFavoriteChange(id, !starred) }, modifier = Modifier.size(36.dp)) {
                                Icon(
                                    painterResource(if (starred) R.drawable.ic_star else R.drawable.ic_star_outline),
                                    contentDescription = stringResource(if (starred) R.string.fx_unfavorite else R.string.fx_favorite, item.fx.name),
                                    tint = if (starred) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A library scene an FX moved into, not in this adventure: a dotted outline in the grid. Tapping
 * plays it again; in edit mode it can be added to the adventure (a normal copy).
 */
@Composable
private fun GhostTile(
    item: SceneListItem,
    glow: Modifier,
    editMode: Boolean,
    canApply: Boolean,
    onClick: () -> Unit,
    onAdd: () -> Unit,
) {
    GlowCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TILE_MIN_HEIGHT)
            .alpha(if (!editMode && !canApply) 0.5f else 1f),
        // A dashed glowing outline: playing, but not one of this adventure's scenes.
        glow = glow,
        squish = !editMode,
        stateLabel = stringResource(R.string.play_active),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = TILE_MIN_HEIGHT)) {
            Column(Modifier.padding(12.dp)) {
                Text(item.scene.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.play_ghost_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.heightIn(min = 12.dp))
                ScenePalette(item.swatches, size = SwatchSize.Medium, modifier = Modifier.padding(end = PLAY_ICON_ROOM))
                if (editMode) {
                    TextButton(onClick = onAdd, modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.play_ghost_add))
                    }
                }
            }
            // The ghost is always the scene playing.
            if (!editMode) {
                PlayStateIcon(
                    running = true,
                    colors = item.swatches.markColors(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(PLAY_ICON_INSET),
                )
            }
        }
    }
}

/** Keeps the display on while this is on screen (the GM shouldn't have to wake the phone mid-scene). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * A scene tile: a dark card that, while its scene plays, glows with what the bulbs are being sent
 * ([glow]). It squishes under the finger (a light tick) except in edit mode.
 */
@Composable
private fun SceneTile(
    tile: PlayTile,
    glow: Modifier,
    isActive: Boolean,
    failedSlotIds: Set<Long>,
    editMode: Boolean,
    isDragging: Boolean,
    canApply: Boolean,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val elevation by animateDpAsState(if (isDragging) 8.dp else 1.dp, label = "tileElevation")
    val dimmed = !editMode && !canApply
    GlowCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = TILE_MIN_HEIGHT)
            .alpha(if (dimmed) 0.5f else 1f),
        glow = glow,
        squish = !editMode,
        stateLabel = if (isActive) stringResource(R.string.play_active) else null,
        elevation = elevation,
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = TILE_MIN_HEIGHT)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        tile.scene.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (editMode) {
                        ItemMenuButton(onDuplicate = onDuplicate, onDelete = onDelete, modifier = Modifier.size(32.dp))
                    }
                }
                Spacer(Modifier.weight(1f, fill = false).heightIn(min = 12.dp))
                ScenePalette(
                    tile.swatches,
                    size = SwatchSize.Medium,
                    failedSlotIds = failedSlotIds,
                    modifier = Modifier.padding(end = PLAY_ICON_ROOM),
                )
                // The glow says it's playing; words only for bulbs that didn't answer.
                if (isActive && failedSlotIds.isNotEmpty()) {
                    Text(
                        stringResource(R.string.play_active_with_problems),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, end = PLAY_ICON_ROOM),
                    )
                }
            }
            // Play in the corner; three spinning arcs in the scene's colors while it plays. Not in
            // edit mode, where a tap opens the editor instead.
            if (!editMode) {
                PlayStateIcon(
                    running = isActive,
                    colors = tile.swatches.markColors(),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(PLAY_ICON_INSET),
                )
            }
        }
    }
}

/** Tiles' minimum height (the card's), so the play mark sits in the card's bottom corner. */
private val TILE_MIN_HEIGHT = 120.dp

/** Inset of the play mark from the card's corner, and the room text keeps clear of it. */
private val PLAY_ICON_INSET = 12.dp
private val PLAY_ICON_ROOM = 32.dp

@Composable
private fun CenteredMessage(text: String, modifier: Modifier) {
    Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AddSceneChoiceDialog(
    hasLibraryScenes: Boolean,
    onBlank: () -> Unit,
    onFromLibrary: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.play_add_scene)) },
        text = {
            Column {
                TextButton(onClick = onBlank, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.play_add_blank), modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = onFromLibrary, enabled = hasLibraryScenes, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.play_add_from_library), modifier = Modifier.fillMaxWidth())
                }
                if (!hasLibraryScenes) {
                    Text(
                        stringResource(R.string.play_library_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Pick several library scenes at once; each is added as its own copy, in library order. */
@Composable
private fun LibraryPickerDialog(
    library: List<SceneListItem>,
    onAdd: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.play_add_from_library)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (item in library) {
                    val id = item.scene.id
                    val checked = id in selected
                    val toggle = { selected = if (checked) selected - id else selected + id }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = toggle),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                        Column {
                            Text(item.scene.name, style = MaterialTheme.typography.bodyLarge)
                            ScenePalette(
                                item.swatches,
                                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(library.mapNotNull { if (it.scene.id in selected) it.scene.id else null }) },
                enabled = selected.isNotEmpty(),
            ) { Text(stringResource(R.string.play_add_selected, selected.size)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
