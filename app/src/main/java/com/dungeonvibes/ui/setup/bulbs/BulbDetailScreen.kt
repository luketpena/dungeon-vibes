package com.dungeonvibes.ui.setup.bulbs

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.ui.common.ConfirmDialog
import com.dungeonvibes.ui.common.NameKeyboardOptions
import com.dungeonvibes.ui.common.TopLevelScreen

/**
 * One bulb's page (Setup → Bulbs → a bulb): its name, maker, status and address; Identify and
 * Remove; and every group with a checkbox, since a bulb can be in several. The bulb's highest
 * group is marked: it decides the bulb's look when more than one of its groups is lit.
 */
@Composable
fun BulbDetailScreen(bulbId: Long, onBack: () -> Unit) {
    val viewModel = hiltViewModel<BulbDetailViewModel, BulbDetailViewModel.Factory>(key = "bulb-$bulbId") { factory ->
        factory.create(bulbId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var confirmRemove by rememberSaveable(bulbId) { mutableStateOf(false) }
    // A removed bulb (here or elsewhere) has no page; go back to the list.
    LaunchedEffect(state.isMissing) { if (state.isMissing) onBack() }

    val bulb = state.bulb
    TopLevelScreen(
        title = bulb?.name ?: "",
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
            }
        },
    ) { modifier ->
        if (bulb == null) {
            Box(modifier.fillMaxSize())
            return@TopLevelScreen
        }
        var name by rememberSaveable(bulb.id) { mutableStateOf(bulb.name) }
        // The name saves when editing ends (Done, or leaving the field), if it changed.
        val saveName: () -> Unit = {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) {
                name = bulb.name
            } else if (trimmed != bulb.name) {
                viewModel.rename(trimmed)
            }
        }
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "name") {
                var focused by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_name)) },
                    keyboardOptions = NameKeyboardOptions.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .onFocusChanged {
                            if (focused && !it.isFocused) saveName()
                            focused = it.isFocused
                        },
                )
            }
            item(key = "details") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetailLine(stringResource(R.string.bulb_brand), state.brand ?: stringResource(R.string.bulb_brand_unknown))
                    DetailLine(
                        stringResource(R.string.bulb_status_label),
                        stringResource(if (state.isOnline) R.string.bulb_online else R.string.bulb_offline),
                    )
                    DetailLine(stringResource(R.string.bulb_address), bulb.lastKnownIp)
                }
            }
            item(key = "actions") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    OutlinedButton(onClick = viewModel::identify) { Text(stringResource(R.string.action_identify)) }
                    OutlinedButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.action_remove)) }
                }
            }
            item(key = "divider") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            item(key = "groups-heading") {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(stringResource(R.string.bulb_groups_heading), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(if (state.groups.isEmpty()) R.string.bulb_groups_none else R.string.bulb_groups_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.groups, key = { "group-${it.slot.id}" }) { membership ->
                val toggle = { viewModel.setInGroup(membership.slot.id, !membership.isMember) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = toggle)
                        .padding(horizontal = 4.dp),
                ) {
                    Checkbox(checked = membership.isMember, onCheckedChange = { toggle() })
                    Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                        Text(membership.slot.name, style = MaterialTheme.typography.bodyLarge)
                        if (membership.isTop && state.groups.count { it.isMember } > 1) {
                            Text(
                                stringResource(R.string.bulb_group_top),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove && bulb != null) {
        ConfirmDialog(
            title = stringResource(R.string.bulb_remove_title, bulb.name),
            message = stringResource(R.string.bulb_remove_message),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = {
                confirmRemove = false
                viewModel.remove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.65f))
    }
}
