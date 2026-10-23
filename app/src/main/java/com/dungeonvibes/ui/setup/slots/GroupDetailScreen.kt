package com.dungeonvibes.ui.setup.slots

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.ui.common.ConfirmDialog
import com.dungeonvibes.ui.common.NameKeyboardOptions
import com.dungeonvibes.ui.common.TopLevelScreen
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * One group's page (Setup → Groups → a group): its name, then its bulbs in order. Drag a bulb to
 * change the order Wave and Spin follow across the group; add bulbs from the rest (a bulb can be
 * in several groups), take one out, blink one to find it, or delete the group.
 */
@Composable
fun GroupDetailScreen(slotId: Long, onBack: () -> Unit) {
    val viewModel = hiltViewModel<GroupDetailViewModel, GroupDetailViewModel.Factory>(key = "group-$slotId") { factory ->
        factory.create(slotId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDeleteCount.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    var showAdd by rememberSaveable(slotId) { mutableStateOf(false) }
    // A deleted group (here or elsewhere) has no page; go back to the list.
    LaunchedEffect(state.isMissing) { if (state.isMissing) onBack() }

    // The order shown while dragging is local; it's saved when the drag ends.
    var ordered by remember { mutableStateOf(state.members) }
    LaunchedEffect(state.members) { ordered = state.members }
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        // Only bulb rows move (their keys are bulb ids).
        val fromKey = from.key as? Long ?: return@rememberReorderableLazyListState
        val toKey = to.key as? Long ?: return@rememberReorderableLazyListState
        val fromIndex = ordered.indexOfFirst { it.bulb.id == fromKey }
        val toIndex = ordered.indexOfFirst { it.bulb.id == toKey }
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState
        ordered = ordered.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    val group = state.group
    TopLevelScreen(
        title = group?.name ?: "",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
            }
        },
        floatingActionButton = {
            if (group != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        focusManager.clearFocus()
                        showAdd = true
                    },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    text = { Text(stringResource(R.string.group_add_bulbs)) },
                )
            }
        },
    ) { modifier ->
        if (group == null) {
            Box(modifier.fillMaxSize())
            return@TopLevelScreen
        }
        var name by rememberSaveable(group.id) { mutableStateOf(group.name) }
        // The name saves when editing ends (Done, or leaving the field), if it changed.
        val saveName: () -> Unit = {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                name = group.name
            } else if (trimmed != group.name) {
                viewModel.rename(trimmed)
            }
        }
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp),
        ) {
            item(key = "name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_name)) },
                    keyboardOptions = NameKeyboardOptions.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        saveName()
                        focusManager.clearFocus()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .onFocusChanged { if (!it.isFocused) saveName() },
                )
            }
            item(key = "heading") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)) {
                    Text(stringResource(R.string.slot_bulbs_heading), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (ordered.size > 1) R.string.group_order_hint else R.string.group_bulbs_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (ordered.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.slot_no_bulbs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
            items(ordered, key = { it.bulb.id }) { member ->
                ReorderableItem(reorderState, key = member.bulb.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp, label = "dragElevation")
                    Surface(shadowElevation = elevation) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 4.dp),
                        ) {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        focusManager.clearFocus()
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        viewModel.reorder(ordered.map { it.bulb.id })
                                    },
                                ),
                            ) {
                                Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = stringResource(R.string.action_reorder))
                            }
                            MemberText(member, Modifier.weight(1f))
                            TextButton(onClick = { viewModel.identify(member.bulb) }) {
                                Text(stringResource(R.string.action_identify))
                            }
                            IconButton(onClick = { viewModel.removeBulb(member.bulb.id) }) {
                                Icon(
                                    painterResource(R.drawable.ic_close),
                                    contentDescription = stringResource(R.string.group_remove_bulb, member.bulb.name),
                                )
                            }
                        }
                    }
                }
            }
            item(key = "delete") {
                OutlinedButton(
                    onClick = viewModel::requestDelete,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp),
                ) { Text(stringResource(R.string.group_delete)) }
            }
        }
    }

    if (showAdd) {
        AddBulbsDialog(
            others = state.others,
            onAdd = { ids ->
                viewModel.addBulbs(ids)
                showAdd = false
            },
            onDismiss = { showAdd = false },
        )
    }
    pendingDelete?.let { count ->
        ConfirmDialog(
            title = stringResource(R.string.slot_delete_title, group?.name ?: ""),
            message = if (count == 0) {
                stringResource(R.string.slot_delete_unused)
            } else {
                pluralStringResource(R.plurals.slot_delete_used, count, count)
            },
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::cancelDelete,
        )
    }
}

/** A bulb's name with a status dot, and the other groups it's in. */
@Composable
private fun MemberText(member: GroupMember, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(
                        if (member.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        CircleShape,
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(member.bulb.name, style = MaterialTheme.typography.bodyLarge)
        }
        val detail = buildList {
            if (!member.isOnline) add(stringResource(R.string.bulb_offline))
            if (member.otherGroups.isNotEmpty()) {
                add(stringResource(R.string.slot_bulb_also_in, member.otherGroups.joinToString(", ")))
            }
        }
        if (detail.isNotEmpty()) {
            Text(
                detail.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** Pick bulbs to add to the end of the group, in name order. */
@Composable
private fun AddBulbsDialog(others: List<GroupMember>, onAdd: (List<Long>) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_add_bulbs)) },
        text = {
            if (others.isEmpty()) {
                Text(stringResource(R.string.group_add_none), style = MaterialTheme.typography.bodyMedium)
            } else Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                for (member in others) {
                    val id = member.bulb.id
                    val checked = id in selected
                    val toggle = { selected = if (checked) selected - id else selected + id }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                        MemberText(member, Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(others.mapNotNull { if (it.bulb.id in selected) it.bulb.id else null }) },
                enabled = selected.isNotEmpty(),
            ) { Text(stringResource(R.string.group_add_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
