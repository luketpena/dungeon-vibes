package com.dungeonvibes.ui.setup.slots

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.ui.common.NameKeyboardOptions
import com.dungeonvibes.data.db.LightSlotEntity
import com.dungeonvibes.ui.common.ConfirmDialog
import com.dungeonvibes.ui.common.WarningNote
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Which slot dialog is open: adding a new slot, or editing an existing one. */
private sealed interface SlotDialogMode {
    data object Add : SlotDialogMode
    data class Edit(val item: SlotItem) : SlotDialogMode
}

@Composable
fun SlotsTab(
    modifier: Modifier = Modifier,
    viewModel: SlotsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current

    // The list shown while dragging is local; the new order is saved when the drag ends.
    var orderedSlots by remember { mutableStateOf(state.slots) }
    LaunchedEffect(state.slots) { orderedSlots = state.slots }

    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        orderedSlots = orderedSlots.toMutableList().apply { add(to.index, removeAt(from.index)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    var dialog by remember { mutableStateOf<SlotDialogMode?>(null) }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Leave room at the bottom so the last slot isn't hidden behind the Add button.
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
                                .clickable { dialog = SlotDialogMode.Edit(item) }
                                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
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
                            Column(Modifier.weight(1f)) {
                                Text(item.slot.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = if (item.bulbNames.isEmpty()) {
                                        stringResource(R.string.slot_no_bulbs)
                                    } else {
                                        item.bulbNames.joinToString(", ")
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.requestDelete(item.slot) }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.action_delete))
                            }
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

        ExtendedFloatingActionButton(
            onClick = { dialog = SlotDialogMode.Add },
            icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
            text = { Text(stringResource(R.string.slot_add)) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
    }

    when (val mode = dialog) {
        null -> Unit
        SlotDialogMode.Add -> SlotDialog(
            title = stringResource(R.string.slot_add),
            slot = null,
            initialName = "",
            initialBulbIds = emptySet(),
            bulbs = state.bulbs,
            onSave = { name, bulbIds -> viewModel.addSlot(name, bulbIds) },
            onDismiss = { dialog = null },
        )
        is SlotDialogMode.Edit -> SlotDialog(
            title = stringResource(R.string.slot_edit),
            slot = mode.item.slot,
            initialName = mode.item.slot.name,
            initialBulbIds = mode.item.bulbIds,
            bulbs = state.bulbs,
            onSave = { name, bulbIds -> viewModel.saveSlot(mode.item.slot, name, bulbIds) },
            onDismiss = { dialog = null },
        )
    }

    pendingDelete?.let { pending ->
        ConfirmDialog(
            title = stringResource(R.string.slot_delete_title, pending.slot.name),
            message = if (pending.sceneCount == 0) {
                stringResource(R.string.slot_delete_unused)
            } else {
                pluralStringResource(R.plurals.slot_delete_used, pending.sceneCount, pending.sceneCount)
            },
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/**
 * Name plus a checkbox per bulb. A bulb already in another slot shows that slot's name; ticking it
 * moves it here when saved.
 */
@Composable
private fun SlotDialog(
    title: String,
    slot: LightSlotEntity?,
    initialName: String,
    initialBulbIds: Set<Long>,
    bulbs: List<BulbChoice>,
    onSave: (name: String, bulbIds: Set<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var selected by remember { mutableStateOf(initialBulbIds) }
    val trimmed = name.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_name)) },
                    placeholder = { Text(stringResource(R.string.slot_name_hint)) },
                    keyboardOptions = NameKeyboardOptions,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.slot_bulbs_heading), style = MaterialTheme.typography.titleSmall)
                if (bulbs.isEmpty()) {
                    Text(
                        stringResource(R.string.slot_no_bulbs_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Column(
                    Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    for (choice in bulbs) {
                        val id = choice.bulb.id
                        val checked = id in selected
                        val toggle = { selected = if (checked) selected - id else selected + id }
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = toggle),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            // The checkbox sits in a 48 dp touch target; 12 dp lines the name up with its box.
                            Column(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                                Text(choice.bulb.name, style = MaterialTheme.typography.bodyLarge)
                                val otherSlot = choice.slotName?.takeIf { choice.slotId != slot?.id }
                                if (otherSlot != null) {
                                    WarningNote(stringResource(R.string.slot_bulb_in_other, otherSlot))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(trimmed, selected)
                    onDismiss()
                },
                enabled = trimmed.isNotEmpty(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
