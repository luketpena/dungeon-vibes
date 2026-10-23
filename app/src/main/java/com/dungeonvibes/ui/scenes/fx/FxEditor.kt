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
import com.dungeonvibes.ui.common.snapPercent
import com.dungeonvibes.R
import com.dungeonvibes.data.db.FlickerSettings
import com.dungeonvibes.data.db.FxEntity
import com.dungeonvibes.data.db.FxPrimitive
import com.dungeonvibes.data.db.FxTransition
import com.dungeonvibes.data.db.PulseSettings
import com.dungeonvibes.data.db.SpinFxSettings
import com.dungeonvibes.data.db.StrobeSettings
import com.dungeonvibes.light.fx.PulseShot
import com.dungeonvibes.light.fx.StrobeShot
import com.dungeonvibes.ui.common.SettingsCard
import java.util.Locale
import com.dungeonvibes.data.db.SceneEntity
import com.dungeonvibes.data.fx.FxItem
import com.dungeonvibes.ui.common.FxHaptic
import com.dungeonvibes.ui.common.LightColors
import com.dungeonvibes.ui.common.LocalOnLocalNetwork
import com.dungeonvibes.ui.common.NameKeyboardOptions
import com.dungeonvibes.ui.common.SteadySlider
import com.dungeonvibes.ui.common.TopLevelScreen
import com.dungeonvibes.ui.scenes.editor.AhrControl
import com.dungeonvibes.ui.scenes.editor.Choice
import com.dungeonvibes.ui.scenes.editor.ControlLabel
import com.dungeonvibes.ui.scenes.editor.CountControl
import com.dungeonvibes.ui.scenes.editor.CountRangeControl
import com.dungeonvibes.ui.scenes.editor.FxColorControls
import com.dungeonvibes.ui.scenes.editor.FxRanges
import com.dungeonvibes.ui.scenes.editor.PercentRangeControl
import com.dungeonvibes.ui.scenes.editor.SpinDirectionControl
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
        // Each part of the FX sits in its own box, like the scene editor's groups.
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "name") {
                Column {
                    OutlinedTextField(
                        value = fx.name,
                        onValueChange = { name -> viewModel.edit { it.copy(name = name) } },
                        singleLine = true,
                        label = { Text(stringResource(R.string.label_name)) },
                        keyboardOptions = NameKeyboardOptions,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        stringResource(R.string.fx_try_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item(key = "primitive") {
                Choice(
                    options = listOf(
                        FxPrimitive.PULSE to R.string.fx_pulse,
                        FxPrimitive.FLICKER to R.string.fx_flicker,
                        FxPrimitive.STROBE to R.string.fx_strobe,
                        FxPrimitive.SPIN to R.string.fx_spin,
                    ),
                    selected = fx.primitive,
                    onSelect = { primitive -> viewModel.edit { it.copy(primitive = primitive) } },
                )
            }
            // A Flicker has no color of its own: it dims what's there.
            if (fx.primitive != FxPrimitive.FLICKER) {
                item(key = "color") {
                    SettingsCard(stringResource(R.string.settings_color)) {
                        when (fx.primitive) {
                            FxPrimitive.PULSE -> PulseColorControls(fx.pulse) { pulse -> viewModel.edit { it.copy(pulse = pulse) } }
                            FxPrimitive.STROBE -> {
                                FxColorControls(fx.strobe.color) { color -> viewModel.edit { it.copy(strobe = it.strobe.copy(color = color)) } }
                                LevelControl(fx.strobe.level) { level -> viewModel.edit { it.copy(strobe = it.strobe.copy(level = level)) } }
                            }
                            FxPrimitive.SPIN -> {
                                FxColorControls(fx.spin.color) { color -> viewModel.edit { it.copy(spin = it.spin.copy(color = color)) } }
                                LevelControl(fx.spin.level) { level -> viewModel.edit { it.copy(spin = it.spin.copy(level = level)) } }
                            }
                            FxPrimitive.FLICKER -> Unit
                        }
                    }
                }
            }
            item(key = "timing") {
                SettingsCard(stringResource(R.string.fx_card_timing)) {
                    when (fx.primitive) {
                        FxPrimitive.PULSE -> PulseTimingControls(fx.pulse) { pulse -> viewModel.edit { it.copy(pulse = pulse) } }
                        FxPrimitive.FLICKER -> FlickerControls(fx.flicker) { flicker -> viewModel.edit { it.copy(flicker = flicker) } }
                        FxPrimitive.STROBE -> StrobeTimingControls(fx.strobe) { strobe -> viewModel.edit { it.copy(strobe = strobe) } }
                        FxPrimitive.SPIN -> SpinTimingControls(fx.spin) { spin -> viewModel.edit { it.copy(spin = spin) } }
                    }
                }
            }
            item(key = "targets") {
                SettingsCard(stringResource(R.string.fx_targets)) {
                    TargetControls(
                        item = item,
                        groups = state.groups,
                        onAll = viewModel::setTargetAll,
                        onGroup = viewModel::setGroupTargeted,
                    )
                    if (fx.primitive == FxPrimitive.SPIN) {
                        // Spin's order is the bulbs' own; spread doesn't apply to it.
                        Text(
                            stringResource(R.string.fx_spin_order_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        SpreadControl(fx.spread, fx.waveOffsetMs) { spread, offset ->
                            viewModel.edit { it.copy(spread = spread, waveOffsetMs = offset) }
                        }
                    }
                }
            }
            item(key = "transition") {
                SettingsCard(stringResource(R.string.fx_card_transition)) {
                    TransitionControls(fx, state.libraryScenes) { change -> viewModel.edit(change) }
                }
            }
        }
    }
}

/** Pulse: its color and which parts of the light it moves. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PulseColorControls(pulse: PulseSettings, onChange: (PulseSettings) -> Unit) {
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
}

/** Pulse: its peak, envelope and burst. */
@Composable
private fun PulseTimingControls(pulse: PulseSettings, onChange: (PulseSettings) -> Unit) {
    PercentRangeControl(R.string.fx_peak, pulse.peakMin, pulse.peak) { lo, hi -> onChange(pulse.copy(peakMin = lo, peak = hi)) }
    AhrControl(
        attackMs = pulse.attackMs,
        holdMs = pulse.holdMs,
        releaseMs = pulse.releaseMs,
        maxMs = FxRanges.ENVELOPE_MAX,
        // A brightness-only pulse has no color of its own: the theme's.
        color = if (pulse.affectsColor || pulse.affectsSaturation) LightColors.displayColor(pulse.color) else null,
        // Drawn with the curved release the pulse actually plays.
        releaseCurve = PulseShot.RELEASE_CURVE,
        onChange = { a, h, r -> onChange(pulse.copy(attackMs = a, holdMs = h, releaseMs = r)) },
    )
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
        onValueChange = { onChange(flicker.copy(depth = snapPercent(it))) },
    )
    TimeRangeControl(
        R.string.fx_change_every,
        flicker.intervalMinMs,
        flicker.intervalMaxMs,
        FxEditorRanges.INTERVAL_MIN,
        FxEditorRanges.INTERVAL_MAX,
    ) { lo, hi -> onChange(flicker.copy(intervalMinMs = lo, intervalMaxMs = hi)) }
}

/** Strobe: how fast (half-flash steps, up to 4 a second) and for how long. */
@Composable
private fun StrobeTimingControls(strobe: StrobeSettings, onChange: (StrobeSettings) -> Unit) {
    val rate = strobe.flashesPerSecond.coerceIn(StrobeShot.MIN_FLASHES_PER_SECOND, StrobeShot.MAX_FLASHES_PER_SECOND)
    ControlLabel(stringResource(R.string.fx_strobe_rate, String.format(Locale.getDefault(), "%.1f", rate)))
    SteadySlider(
        value = rate,
        onValueChange = { onChange(strobe.copy(flashesPerSecond = (it * 2f).roundToInt() / 2f)) },
        valueRange = StrobeShot.MIN_FLASHES_PER_SECOND..StrobeShot.MAX_FLASHES_PER_SECOND,
        steps = ((StrobeShot.MAX_FLASHES_PER_SECOND - StrobeShot.MIN_FLASHES_PER_SECOND) * 2f).roundToInt() - 1,
    )
    TimeControl(R.string.fx_duration, strobe.durationMs, FxEditorRanges.DURATION_MIN, FxEditorRanges.DURATION_MAX) {
        onChange(strobe.copy(durationMs = it))
    }
}

/** Spin: time from bulb to bulb, laps, and each bulb's envelope. */
@Composable
private fun SpinTimingControls(spin: SpinFxSettings, onChange: (SpinFxSettings) -> Unit) {
    TimeControl(R.string.fx_spin_step, spin.stepMs, FxRanges.SPIN_STEP_MIN, FxRanges.SPIN_STEP_MAX) { onChange(spin.copy(stepMs = it)) }
    CountControl(R.string.fx_spin_laps, spin.laps, 1, FxRanges.MAX_LAPS) { onChange(spin.copy(laps = it)) }
    SpinDirectionControl(spin.reverse) { onChange(spin.copy(reverse = it)) }
    AhrControl(
        attackMs = spin.attackMs,
        holdMs = spin.holdMs,
        releaseMs = spin.releaseMs,
        maxMs = FxRanges.ENVELOPE_MAX,
        color = LightColors.displayColor(spin.color),
        onChange = { a, h, r -> onChange(spin.copy(attackMs = a, holdMs = h, releaseMs = r)) },
    )
}

/** A brightness level (0–100 % in 5 % steps). */
@Composable
internal fun LevelControl(level: Float, onChange: (Float) -> Unit) {
    ControlLabel(stringResource(R.string.fx_level, (level * 100).roundToInt()))
    SteadySlider(value = level, onValueChange = { onChange(snapPercent(it)) })
}

@Composable
private fun TargetControls(
    item: FxItem,
    groups: List<com.dungeonvibes.data.db.LightSlotEntity>,
    onAll: (Boolean) -> Unit,
    onGroup: (Long, Boolean) -> Unit,
) {
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
