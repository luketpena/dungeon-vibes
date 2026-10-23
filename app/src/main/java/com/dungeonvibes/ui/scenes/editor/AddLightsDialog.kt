package com.dungeonvibes.ui.scenes.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dungeonvibes.R
import com.dungeonvibes.data.db.BulbEntity

/**
 * Picks bulbs to add to a scene as individual lights: a scrolling, checkable list (there can be
 * many bulbs), with a filter box once the list is long. Adds every ticked bulb at once.
 */
@Composable
fun AddLightsDialog(
    bulbs: List<BulbEntity>,
    onAdd: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(emptySet<Long>()) }
    var filter by rememberSaveable { mutableStateOf("") }
    val showFilter = bulbs.size > FILTER_AFTER
    val query = filter.trim()
    val shown = remember(bulbs, query) {
        if (query.isEmpty()) bulbs else bulbs.filter { it.name.contains(query, ignoreCase = true) }
    }
    val count = picked.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.scene_singles_pick_title)) },
        text = {
            Column {
                if (showFilter) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.scene_singles_filter)) },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                if (shown.isEmpty()) {
                    Text(
                        stringResource(R.string.scene_singles_no_match),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT)) {
                    items(shown, key = { it.id }) { bulb ->
                        val checked = bulb.id in picked
                        val toggle = { picked = if (checked) picked - bulb.id else picked + bulb.id }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable(onClick = toggle),
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { toggle() })
                            Text(bulb.name, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Keep the bulbs' list order (name order), not the order they were ticked.
                    val chosen = picked
                    val ids = ArrayList<Long>(chosen.size)
                    for (bulb in bulbs) if (bulb.id in chosen) ids.add(bulb.id)
                    onAdd(ids)
                },
                enabled = count > 0,
            ) {
                Text(
                    if (count == 0) {
                        stringResource(R.string.scene_singles_add)
                    } else {
                        pluralStringResource(R.plurals.scene_singles_add_count, count, count)
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Past this many bulbs the dialog offers a filter box. */
private const val FILTER_AFTER = 8

/** Room for about seven rows; longer lists scroll. */
private val LIST_MAX_HEIGHT = 340.dp
