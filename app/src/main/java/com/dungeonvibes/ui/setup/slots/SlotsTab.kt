package com.dungeonvibes.ui.setup.slots

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.ui.common.NameDialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Setup → Groups: the groups in their order (drag to reorder: the higher group wins a shared bulb).
 * Tapping one opens its page; Add group asks for a name, then opens the new group's page.
 */
@Composable
fun SlotsTab(
    onOpenGroup: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SlotsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    // The list shown while dragging is local; the new order is saved when the drag ends.
    var orderedSlots by remember { mutableStateOf(state.slots) }
    LaunchedEffect(state.slots) { orderedSlots = state.slots }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        orderedSlots = orderedSlots.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    var adding by rememberSaveable { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // Order matters once a bulb is in more than one group: the higher group wins it. Above the
            // list rather than in it, so drag positions stay list positions.
            if (orderedSlots.size > 1) {
                Text(
                    stringResource(R.string.slots_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                // Leave room at the bottom so the last group isn't hidden behind the Add button.
                contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp),
            ) {
                items(orderedSlots, key = { it.slot.id }) { item ->
                    ReorderableItem(reorderState, key = item.slot.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "dragElevation")
                        Surface(shadowElevation = elevation) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenGroup(item.slot.id) }
                                    .padding(4.dp),
                            ) {
                                IconButton(
                                    onClick = {},
                                    modifier = Modifier.draggableHandle(
                                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                        onDragStopped = {
                                            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            viewModel.reorder(orderedSlots.map { it.slot.id })
                                        },
                                    ),
                                ) {
                                    Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = stringResource(R.string.action_reorder))
                                }
                                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                    Text(item.slot.name, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        text = if (item.bulbNames.isEmpty()) {
                                            stringResource(R.string.slot_no_bulbs)
                                        } else {
                                            item.bulbNames.joinToString(", ")
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Icon(
                                    painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                        }
                    }
                }
                if (state.isLoaded && orderedSlots.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.slots_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = { adding = true },
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.slot_add)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    if (adding) {
        NameDialog(
            title = stringResource(R.string.slot_add),
            initialName = "",
            confirmLabel = stringResource(R.string.action_create),
            onConfirm = { name ->
                adding = false
                scope.launch { onOpenGroup(viewModel.createGroup(name)) }
            },
            onDismiss = { adding = false },
        )
    }
}
