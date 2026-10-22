package com.dungeonvibes.ui.scenes.fx

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.data.db.FxEntity
import com.dungeonvibes.data.db.FxPrimitive
import com.dungeonvibes.ui.common.FxHaptic
import com.dungeonvibes.ui.common.ConfirmDialog
import com.dungeonvibes.ui.common.LightColors
import com.dungeonvibes.ui.common.LocalOnLocalNetwork
import com.dungeonvibes.ui.common.Swatch
import com.dungeonvibes.ui.common.TopLevelScreen
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** The FX library: every FX the DM has built, shared by all adventures. */
@Composable
fun FxLibraryPane(
    selectedFxId: Long?,
    onOpen: (Long) -> Unit,
    onDeleted: (Long) -> Unit,
    header: @Composable () -> Unit,
    viewModel: FxLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onLocalNetwork = LocalOnLocalNetwork.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val newName = stringResource(R.string.fx_new_name)

    var ordered by remember { mutableStateOf(state.fx) }
    LaunchedEffect(state.fx) { ordered = state.fx }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        ordered = ordered.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    var pendingDelete by remember { mutableStateOf<FxEntity?>(null) }

    TopLevelScreen(
        title = stringResource(R.string.nav_scenes),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { onOpen(viewModel.create(newName)) } },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text(stringResource(R.string.fx_add)) },
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
                items(ordered, key = { it.item.fx.id }) { row ->
                    val fx = row.item.fx
                    ReorderableItem(reorderState, key = fx.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "dragElevation")
                        Surface(
                            shadowElevation = elevation,
                            color = if (fx.id == selectedFxId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpen(fx.id) }
                                    .padding(4.dp),
                            ) {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                        onDragStopped = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            viewModel.reorder(ordered.map { it.item.fx.id })
                                        },
                                    ),
                                ) {
                                    Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = stringResource(R.string.action_reorder))
                                }
                                Swatch(fxSwatchColor(fx), 24.dp, Modifier.padding(end = 12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(fx.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        fxSummary(row),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        haptics.performHapticFeedback(FxHaptic)
                                        viewModel.tryFx(row.item)
                                    },
                                    enabled = onLocalNetwork && !row.item.hasNoTargets,
                                ) {
                                    Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.fx_try))
                                }
                                IconButton(onClick = { pendingDelete = fx }) {
                                    Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.action_delete))
                                }
                            }
                        }
                    }
                }
                if (state.isLoaded && ordered.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.fx_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { fx ->
        ConfirmDialog(
            title = stringResource(R.string.fx_delete_title, fx.name),
            message = stringResource(R.string.fx_delete_message),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                viewModel.delete(fx.id)
                onDeleted(fx.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/** "Pulse · All lights", "Flicker · Ceiling, Lamp". */
@Composable
internal fun fxSummary(row: FxListItem): String {
    val primitive = stringResource(if (row.item.fx.primitive == FxPrimitive.FLICKER) R.string.fx_flicker else R.string.fx_pulse)
    return if (row.item.fx.targetAll) {
        stringResource(R.string.fx_summary_all, primitive)
    } else {
        stringResource(R.string.fx_summary_groups, primitive, row.groupNames.joinToString(", "))
    }
}

/**
 * The color an FX shows: a Pulse's color, or null (a hollow swatch) for an FX that has no color
 * of its own: a Flicker, or a Pulse that only moves brightness.
 */
internal fun fxSwatchColor(fx: FxEntity): Color? {
    if (fx.primitive != FxPrimitive.PULSE) return null
    val pulse = fx.pulse
    if (!pulse.affectsColor && !pulse.affectsSaturation) return null
    return LightColors.displayColor(pulse.color)
}
