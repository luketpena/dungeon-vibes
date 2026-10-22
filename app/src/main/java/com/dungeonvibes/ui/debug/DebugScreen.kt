package com.dungeonvibes.ui.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.ui.common.TopLevelScreen

@Composable
fun DebugScreen(
    onBack: () -> Unit,
    viewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bulbState by viewModel.bulbState.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val fx by viewModel.fxState.collectAsStateWithLifecycle()

    TopLevelScreen(
        title = stringResource(R.string.debug_title),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
            }
        },
    ) { modifier ->
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            /* Bulb control */

            item { SectionHeader("Bulb control", divider = false) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = viewModel::scan, enabled = !bulbState.isScanning) {
                        Text(if (bulbState.isScanning) "Scanning…" else "Scan")
                    }
                }
            }
            if (message.isNotEmpty()) {
                item { Text(message, style = MaterialTheme.typography.bodyMedium) }
            }
            if (bulbState.bulbs.isEmpty()) {
                item {
                    Text(
                        "No bulbs yet. Tap Scan (the bulb must be set up in the LIFX app and on this Wi-Fi).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(bulbState.bulbs, key = { "bulb-${it.id}" }) { bulb ->
                BulbRow(
                    bulb = bulb,
                    online = bulb.id in bulbState.onlineIds,
                    onOn = { viewModel.turnOn(bulb) },
                    onOff = { viewModel.turnOff(bulb) },
                    onRed = { viewModel.red(bulb) },
                    onBlue = { viewModel.blue(bulb) },
                    onIdentify = { viewModel.identify(bulb) },
                )
            }

            /* Effects test harness (FX1) */

            item { SectionHeader("Effects test (FX1)") }
            item {
                FxHarness(
                    state = fx,
                    onSelectGroup = viewModel::selectFxGroup,
                    onSpread = viewModel::setFxSpread,
                    onRun = viewModel::runFxPattern,
                    onStop = viewModel::stopFx,
                )
            }

            /* Data */

            item { SectionHeader("Data") }
            item {
                Text(
                    text = "Bulbs: ${state.bulbCount}  ·  Groups: ${state.slotCount}  ·  " +
                        "Library scenes: ${state.libraryScenes.size}  ·  " +
                        "Adventures: ${state.adventures.size}",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::addSampleData) {
                        Text(stringResource(R.string.debug_add_sample))
                    }
                    OutlinedButton(onClick = viewModel::clearAllData) {
                        Text(stringResource(R.string.debug_clear_all))
                    }
                }
            }

            item { SectionHeader("Library scenes") }
            items(state.libraryScenes, key = { it.id }) { scene -> SceneLine(scene) }

            for (entry in state.adventures) {
                val adventureId = entry.adventure.id
                item(key = "adventure-$adventureId") {
                    SectionHeader("Adventure: ${entry.adventure.name} (${entry.sceneCount} scenes)")
                }
                items(
                    state.adventureScenesById[adventureId].orEmpty(),
                    key = { it.id },
                ) { scene -> SceneLine(scene) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BulbRow(
    bulb: BulbEntity,
    online: Boolean,
    onOn: () -> Unit,
    onOff: () -> Unit,
    onRed: () -> Unit,
    onBlue: () -> Unit,
    onIdentify: () -> Unit,
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text("${bulb.name}  ·  ${if (online) "online" else "offline"}", style = MaterialTheme.typography.titleSmall)
        Text(
            "${bulb.protocolId}  ·  ${bulb.serial}  ·  ${bulb.lastKnownIp}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // FlowRow wraps the buttons onto a second line on narrow phones.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOn) { Text("On") }
            OutlinedButton(onClick = onOff) { Text("Off") }
            OutlinedButton(onClick = onRed) { Text("Red") }
            OutlinedButton(onClick = onBlue) { Text("Blue") }
            OutlinedButton(onClick = onIdentify) { Text("Identify") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FxHarness(
    state: FxHarnessState,
    onSelectGroup: (Long) -> Unit,
    onSpread: (Spread?) -> Unit,
    onRun: (FxTestPattern) -> Unit,
    onStop: () -> Unit,
) {
    if (state.groups.isEmpty()) {
        Text(
            "Create a group with bulbs in Setup first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Group", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (group in state.groups) {
                FilterChip(
                    selected = group.slotId == state.selectedSlotId,
                    onClick = { onSelectGroup(group.slotId) },
                    label = { Text("${group.name} (${group.bulbCount})") },
                )
            }
        }
        Text("Spread", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.spreadOverride == null, onClick = { onSpread(null) }, label = { Text("Pattern's own") })
            for (spread in Spread.entries) {
                FilterChip(
                    selected = state.spreadOverride == spread,
                    onClick = { onSpread(spread) },
                    label = { Text(spread.name.lowercase().replaceFirstChar { it.uppercase() }) },
                )
            }
        }
        Text("Test pattern", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (pattern in FxTestPattern.entries) {
                OutlinedButton(onClick = { onRun(pattern) }, enabled = state.selectedSlotId != null) { Text(pattern.label) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.runningLabel?.let { "Running: $it" } ?: "Not running",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = onStop, enabled = state.isRunning) { Text("Stop") }
        }
    }
}

@Composable
private fun SectionHeader(text: String, divider: Boolean = true) {
    if (divider) HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SceneLine(scene: SceneEntity) {
    val parent = scene.parentSceneId?.let { "copy of #$it" } ?: "no parent"
    Text(
        text = "#${scene.id}  ${scene.name}  ·  fade ${scene.fadeMs} ms" +
            if (scene.isLibraryScene) "" else "  ·  $parent",
        style = MaterialTheme.typography.bodyMedium,
    )
}
