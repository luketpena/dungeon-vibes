package com.dungeonvibes.ui.scenes.fx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.data.db.FlickerSettings
import com.dungeonvibes.data.db.FxEntity
import com.dungeonvibes.data.db.FxPrimitive
import com.dungeonvibes.data.db.FxTransition
import com.dungeonvibes.data.db.PulseSettings
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.fx.FxItem
import com.dungeonvibes.ui.common.FxHaptic
import com.dungeonvibes.ui.common.LocalOnLocalNetwork
import com.dungeonvibes.ui.common.NameKeyboardOptions
import com.dungeonvibes.ui.common.SteadySlider
import com.dungeonvibes.ui.common.TopLevelScreen
import com.dungeonvibes.ui.scenes.editor.Choice
import com.dungeonvibes.ui.scenes.editor.ControlLabel
import com.dungeonvibes.ui.scenes.editor.CountRangeControl
import com.dungeonvibes.ui.scenes.editor.FxColorControls
import com.dungeonvibes.ui.scenes.editor.FxRanges
import com.dungeonvibes.ui.scenes.editor.PercentRangeControl
import com.dungeonvibes.ui.scenes.editor.SpreadControl
import com.dungeonvibes.ui.scenes.editor.TimeControl
import com.dungeonvibes.ui.scenes.editor.TimeRangeControl
import kotlin.math.roundToInt

/** Ranges for the FX editor's sliders, in ms (FX_REQUIREMENTS.md → Primitives). */
private object FxEditorRanges {
    const val DURATION_MIN = 200
    const val DURATION_MAX = 30_000
    const val INTERVAL_MIN = 100
    const val INTERVAL_MAX = 1_000
}

/** Editor for one FX in the library. Shows a back arrow when [showBack] (it's the only pane). */
@Composable
fun FxEditor(fxId: Long, showBack: Boolean, onBack: () -> Unit) {
    val viewModel = hiltViewModel<FxEditorViewModel, FxEditorViewModel.Factory>(key = "fx-$fxId") { it.create(fxId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val onLocalNetwork = LocalOnLocalNetwork.current
    val focusManager = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current
    val item = state.item
    val canTry = item != null && onLocalNetwork && !item.hasNoTargets

    TopLevelScreen(
        title = item?.fx?.name?.ifBlank { null } ?: stringResource(R.string.library_fx),
        // Beside an open list (tablets) the list pane carries the lights menu.
        lightsMenu = showBack,
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        floatingActionButton = {
            // Always in reach while scrolling through the settings. Disabled (dimmed) off Wi-Fi or
            // with no groups picked; FABs have no enabled state of their own.
            if (item != null) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (canTry) {
                            focusManager.clearFocus()
                            haptics.performHapticFeedback(FxHaptic)
                            viewModel.tryIt()
                        }
                    },
                    icon = { Icon(painterResource(R.drawable.ic_play), contentDescription = null) },
                    text = { Text(stringResource(R.string.fx_try)) },
                    containerColor = if (canTry) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (canTry) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        },
    ) { modifier ->
        if (!state.isLoaded) return@TopLevelScreen
        if (item == null) {
            Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.fx_missing), style = MaterialTheme.typography.bodyLarge)
            }
            return@TopLevelScreen
        }
        val fx = item.fx
        LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
            item(key = "name") {
                OutlinedTextField(
                    value = fx.name,
                    onValueChange = { name -> viewModel.edit { it.copy(name = name) } },
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_name)) },
                    keyboardOptions = NameKeyboardOptions,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
            item(key = "try") {
                Text(
                    stringResource(R.string.fx_try_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "primitive") {
                Column {
                    ControlLabel(stringResource(R.string.fx_primitive))
                    Choice(
                        options = listOf(FxPrimitive.PULSE to R.string.fx_pulse, FxPrimitive.FLICKER to R.string.fx_flicker),
                        selected = fx.primitive,
                        onSelect = { primitive -> viewModel.edit { it.copy(primitive = primitive) } },
                    )
                }
            }
            item(key = "settings") {
                Column {
                    when (fx.primitive) {
                        FxPrimitive.PULSE -> PulseControls(fx.pulse) { pulse -> viewModel.edit { it.copy(pulse = pulse) } }
                        FxPrimitive.FLICKER -> FlickerControls(fx.flicker) { flicker -> viewModel.edit { it.copy(flicker = flicker) } }
                    }
                }
            }
            item(key = "targets") {
                Column {
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                    TargetControls(
                        item = item,
                        groups = state.groups,
                        onAll = viewModel::setTargetAll,
                        onGroup = viewModel::setGroupTargeted,
                    )
                    SpreadControl(fx.spread, fx.waveOffsetMs) { spread, offset ->
                        viewModel.edit { it.copy(spread = spread, waveOffsetMs = offset) }
                    }
                }
            }
            item(key = "transition") {
                Column {
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                    TransitionControls(fx, state.libraryScenes) { change -> viewModel.edit(change) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PulseControls(pulse: PulseSettings, onChange: (PulseSettings) -> Unit) {
    ControlLabel(stringResource(R.string.fx_pulse_color))
    FxColorControls(pulse.color) { onChange(pulse.copy(color = it)) }
    ControlLabel(stringResource(R.string.fx_channels))
    // At least one channel stays on, or the pulse would do nothing.
    val onCount = listOf(pulse.affectsColor, pulse.affectsSaturation, pulse.affectsBrightness).count { it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = pulse.affectsColor,
            onClick = { if (!pulse.affectsColor || onCount > 1) onChange(pulse.copy(affectsColor = !pulse.affectsColor)) },
            label = { Text(stringResource(R.string.fx_channel_color)) },
        )
        FilterChip(
            selected = pulse.affectsSaturation,
            onClick = { if (!pulse.affectsSaturation || onCount > 1) onChange(pulse.copy(affectsSaturation = !pulse.affectsSaturation)) },
            label = { Text(stringResource(R.string.fx_channel_saturation)) },
        )
        FilterChip(
            selected = pulse.affectsBrightness,
            onClick = { if (!pulse.affectsBrightness || onCount > 1) onChange(pulse.copy(affectsBrightness = !pulse.affectsBrightness)) },
            label = { Text(stringResource(R.string.fx_channel_brightness)) },
        )
    }
    PercentRangeControl(R.string.fx_peak, pulse.peakMin, pulse.peak) { lo, hi -> onChange(pulse.copy(peakMin = lo, peak = hi)) }
    TimeControl(R.string.fx_attack, pulse.attackMs, 0, FxRanges.ENVELOPE_MAX) { onChange(pulse.copy(attackMs = it)) }
    TimeControl(R.string.fx_hold, pulse.holdMs, 0, FxRanges.ENVELOPE_MAX) { onChange(pulse.copy(holdMs = it)) }
    TimeControl(R.string.fx_release, pulse.releaseMs, 0, FxRanges.ENVELOPE_MAX) { onChange(pulse.copy(releaseMs = it)) }
    CountRangeControl(R.string.fx_pulses, pulse.pulsesMin, pulse.pulsesMax, 1, FxRanges.MAX_PULSES) { lo, hi ->
        onChange(pulse.copy(pulsesMin = lo, pulsesMax = hi))
    }
    if (pulse.pulsesMax > 1) {
        TimeRangeControl(R.string.fx_spacing, pulse.spacingMinMs, pulse.spacingMaxMs, FxRanges.SPACING_MIN, FxRanges.SPACING_MAX) { lo, hi ->
            onChange(pulse.copy(spacingMinMs = lo, spacingMaxMs = hi))
        }
    }
}

@Composable
private fun FlickerControls(flicker: FlickerSettings, onChange: (FlickerSettings) -> Unit) {
    TimeControl(R.string.fx_duration, flicker.durationMs, FxEditorRanges.DURATION_MIN, FxEditorRanges.DURATION_MAX) {
        onChange(flicker.copy(durationMs = it))
    }
    ControlLabel(stringResource(R.string.fx_depth, (flicker.depth * 100).roundToInt()))
    SteadySlider(
        value = flicker.depth,
        onValueChange = { onChange(flicker.copy(depth = (it * 100f).roundToInt() / 100f)) },
    )
    TimeRangeControl(
        R.string.fx_change_every,
        flicker.intervalMinMs,
        flicker.intervalMaxMs,
        FxEditorRanges.INTERVAL_MIN,
        FxEditorRanges.INTERVAL_MAX,
    ) { lo, hi -> onChange(flicker.copy(intervalMinMs = lo, intervalMaxMs = hi)) }
}

@Composable
private fun TargetControls(
    item: FxItem,
    groups: List<com.dungeonvibes.data.db.LightSlotEntity>,
    onAll: (Boolean) -> Unit,
    onGroup: (Long, Boolean) -> Unit,
) {
    ControlLabel(stringResource(R.string.fx_targets))
    Choice(
        options = listOf(true to R.string.fx_targets_all, false to R.string.fx_targets_groups),
        selected = item.fx.targetAll,
        onSelect = onAll,
    )
    if (item.fx.targetAll) return
    for (group in groups) {
        val checked = group.id in item.targetSlotIds
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onGroup(group.id, !checked) },
        ) {
            Checkbox(checked = checked, onCheckedChange = { onGroup(group.id, it) })
            Text(group.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
    if (item.targetSlotIds.isEmpty()) {
        Text(
            stringResource(R.string.fx_targets_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun TransitionControls(fx: FxEntity, library: List<SceneEntity>, onChange: ((FxEntity) -> FxEntity) -> Unit) {
    ControlLabel(stringResource(R.string.fx_transition))
    Choice(
        options = listOf(
            FxTransition.RETURN to R.string.fx_transition_return,
            FxTransition.LIGHTS_OFF to R.string.fx_transition_off,
            FxTransition.SCENE to R.string.fx_transition_scene,
        ),
        selected = fx.transition,
        onSelect = { transition ->
            // Picking Scene starts on the first library scene, so it's never left without one.
            onChange { it.copy(transition = transition, transitionSceneId = if (transition == FxTransition.SCENE) it.transitionSceneId ?: library.firstOrNull()?.id else null) }
        },
    )
    if (fx.transition != FxTransition.SCENE) return
    if (library.isEmpty()) {
        Text(
            stringResource(R.string.fx_transition_no_scenes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    ControlLabel(stringResource(R.string.fx_transition_pick))
    for (scene in library) {
        val selected = scene.id == fx.transitionSceneId
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { onChange { it.copy(transitionSceneId = scene.id) } },
        ) {
            RadioButton(selected = selected, onClick = { onChange { it.copy(transitionSceneId = scene.id) } })
            Text(scene.name, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
