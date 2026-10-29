package com.dungeonvibes.ui.scenes.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dungeonvibes.R
import com.dungeonvibes.data.db.BrightnessMode
import com.dungeonvibes.data.db.BrightnessShiftSettings
import com.dungeonvibes.data.db.EffectLayerType
import com.dungeonvibes.data.db.FlashSettings
import com.dungeonvibes.data.db.SpinSettings
import com.dungeonvibes.data.db.Spread
import com.dungeonvibes.data.scene.EffectLayer
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.ui.common.ConfirmDialog
import com.dungeonvibes.ui.common.LightColors
import com.dungeonvibes.ui.common.SettingsCard
import com.dungeonvibes.ui.common.SettingsCardGap
import com.dungeonvibes.ui.common.Swatch
import com.dungeonvibes.ui.common.SwatchSize
import com.dungeonvibes.ui.scenes.fx.LevelControl
import sh.calvin.reorderable.ReorderableColumn

/*
 * Layered effects (owner request, Oct 23, 2026): a group stacks any number of Brightness shift,
 * Flashes and Spin layers, each shown as a card in priority order (the top card wins where layers
 * overlap). + adds one from a picker and opens it in a full-screen editor over the scene editor,
 * which stays where it was underneath.
 */

/** The kinds in the order the picker lists them. */
private val LAYER_KINDS = listOf(EffectLayerType.BRIGHTNESS_SHIFT, EffectLayerType.FLASHES, EffectLayerType.SPIN)

internal fun layerTitle(type: EffectLayerType): Int = when (type) {
    EffectLayerType.BRIGHTNESS_SHIFT -> R.string.fx_brightness_shift
    EffectLayerType.FLASHES -> R.string.fx_flashes
    EffectLayerType.SPIN -> R.string.fx_spin
}

internal fun layerIcon(type: EffectLayerType): Int = when (type) {
    EffectLayerType.BRIGHTNESS_SHIFT -> R.drawable.ic_fx_brightness
    EffectLayerType.FLASHES -> R.drawable.ic_fx_flash
    EffectLayerType.SPIN -> R.drawable.ic_fx_spin
}

internal fun layerHint(type: EffectLayerType): Int = when (type) {
    EffectLayerType.BRIGHTNESS_SHIFT -> R.string.fx_brightness_shift_hint
    EffectLayerType.FLASHES -> R.string.fx_flashes_hint
    EffectLayerType.SPIN -> R.string.fx_spin_hint
}

/**
 * The group panel's "Layered effects", in a settings box: a header with + (off while the group is
 * dark or full), then one card per layer, top priority first, dragged by the handle to reorder.
 * Layer cards sit a step lighter than the box, with a faint rim and a soft shadow.
 */
@Composable
internal fun LayeredEffectsSection(
    row: SlotRowState,
    onAdd: (EffectLayerType) -> Unit,
    onOpen: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onReorder: (List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val light = row.light
    val usable = light != null && row.isOn
    val layers = row.effects?.layers.orEmpty()
    val haptics = LocalHapticFeedback.current
    var picking by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<EffectLayer?>(null) }

    SettingsCard(
        title = stringResource(R.string.fx_layers),
        // The ✦ effect mark, the same shape as inside effect swatches.
        icon = R.drawable.ic_effect_star,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = SettingsCardGap, bottom = 16.dp),
        action = {
            IconButton(onClick = { picking = true }, enabled = usable && layers.size < GroupEffects.MAX_LAYERS) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.fx_layer_add))
            }
        },
    ) {
        if (!usable) {
            Text(
                stringResource(R.string.fx_off_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SettingsCard
        }
        // Dragged by id: two layers with the same settings are equal, but still two cards.
        val ids = layers.map { it.id }
        val byId = layers.associateBy { it.id }
        ReorderableColumn(
            list = ids,
            onSettle = { from, to ->
                val ordered = ids.toMutableList()
                ordered.add(to, ordered.removeAt(from))
                onReorder(ordered)
            },
            onMove = { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { _, layerId, isDragging ->
            val layer = byId[layerId] ?: return@ReorderableColumn
            key(layerId) {
                ReorderableItem {
                    val elevation by animateDpAsState(if (isDragging) LAYER_DRAG_SHADOW else LAYER_SHADOW, label = "layerDrag")
                    Card(
                        onClick = { onOpen(layer.id) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = LAYER_RIM_ALPHA)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                    onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                ),
                            ) {
                                Icon(painterResource(R.drawable.ic_drag_handle), contentDescription = stringResource(R.string.action_reorder))
                            }
                            Icon(
                                painterResource(layerIcon(layer.type)),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                stringResource(layerTitle(layer.type)),
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            LayerSwatch(layer)
                            IconButton(onClick = { pendingRemove = layer }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.fx_layer_remove))
                            }
                        }
                    }
                }
            }
        }
    }

    if (picking) {
        LayerPickerDialog(
            onPick = { type ->
                picking = false
                onAdd(type)
            },
            onDismiss = { picking = false },
        )
    }
    pendingRemove?.let { layer ->
        ConfirmDialog(
            title = stringResource(R.string.fx_layer_remove_title, stringResource(layerTitle(layer.type))),
            message = stringResource(R.string.fx_layer_remove_message),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = {
                pendingRemove = null
                onRemove(layer.id)
            },
            onDismiss = { pendingRemove = null },
        )
    }
}

/** A layer's swatch: its color for Flashes and Spin. A Brightness shift has no color of its own, so none. */
@Composable
private fun LayerSwatch(layer: EffectLayer) {
    when (layer.type) {
        EffectLayerType.FLASHES -> Swatch(LightColors.displayColor(layer.flashes.color), SwatchSize.Large)
        EffectLayerType.SPIN -> Swatch(LightColors.displayColor(layer.spin.color), SwatchSize.Large)
        EffectLayerType.BRIGHTNESS_SHIFT -> Unit
    }
}

/** Every kind of layer with its icon and what it does; tapping one adds it. */
@Composable
private fun LayerPickerDialog(onPick: (EffectLayerType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.fx_layer_add)) },
        text = {
            Column {
                for (type in LAYER_KINDS) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(type) }
                            .heightIn(min = 56.dp)
                            .padding(vertical = 8.dp),
                    ) {
                        Icon(painterResource(layerIcon(type)), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(stringResource(layerTitle(type)), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(layerHint(type)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** What the full-screen layer editor is showing: the layer, and where it lives for the subtitle. */
internal class LayerEditing(
    val slotId: Long,
    val layer: EffectLayer,
    /** "Tavern › Lantern 1": the scene, then the group or light. */
    val breadcrumb: String,
    val canFlashNow: Boolean,
)

/**
 * The full-screen layer editor, sliding up over the scene editor (owner decision: an overlay, so
 * closing it lands exactly where you were). Title: the effect; subtitle: scene › group. Back
 * closes it; the bin removes the layer (after confirming).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayerEditorOverlay(
    editing: LayerEditing?,
    onChange: (Long, EffectLayer) -> Unit,
    onFlashNow: (Long, Long) -> Unit,
    onRemove: (Long, Long) -> Unit,
    onClose: () -> Unit,
) {
    // Keep showing the last layer while the overlay slides away.
    var shown by remember { mutableStateOf(editing) }
    if (editing != null) shown = editing
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    AnimatedVisibility(
        visible = editing != null,
        enter = slideInVertically { it / 4 } + fadeIn(),
        exit = slideOutVertically { it / 4 } + fadeOut(),
    ) {
        val current = shown ?: return@AnimatedVisibility
        BackHandler(enabled = editing != null, onBack = onClose)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // The effect's own icon, as on its card.
                                Icon(
                                    painterResource(layerIcon(current.layer.type)),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 12.dp),
                                )
                                Column {
                                    Text(stringResource(layerTitle(current.layer.type)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        current.breadcrumb,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onClose) {
                                Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                            }
                        },
                        actions = {
                            IconButton(onClick = { confirmRemove = true }) {
                                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.fx_layer_remove))
                            }
                        },
                    )
                },
            ) { padding ->
                // The same stacked settings boxes as the FX and scene editors (owner request, Oct 29).
                Column(
                    verticalArrangement = Arrangement.spacedBy(SettingsCardGap),
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
                ) {
                    Text(
                        stringResource(layerHint(current.layer.type)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val layer = current.layer
                    val slotId = current.slotId
                    when (layer.type) {
                        EffectLayerType.BRIGHTNESS_SHIFT -> BrightnessShiftControls(layer.brightnessShift) {
                            onChange(slotId, layer.copy(brightnessShift = it))
                        }
                        EffectLayerType.FLASHES -> FlashControls(
                            settings = layer.flashes,
                            onChange = { onChange(slotId, layer.copy(flashes = it)) },
                            canFlashNow = current.canFlashNow,
                            onFlashNow = { onFlashNow(slotId, layer.id) },
                        )
                        EffectLayerType.SPIN -> SpinControls(layer.spin) { onChange(slotId, layer.copy(spin = it)) }
                    }
                }
            }
        }
        if (confirmRemove) {
            ConfirmDialog(
                title = stringResource(R.string.fx_layer_remove_title, stringResource(layerTitle(current.layer.type))),
                message = stringResource(R.string.fx_layer_remove_message),
                confirmLabel = stringResource(R.string.action_remove),
                onConfirm = {
                    confirmRemove = false
                    onRemove(current.slotId, current.layer.id)
                    onClose()
                },
                onDismiss = { confirmRemove = false },
            )
        }
    }
}

/*
 * Each kind's controls, shown in the layer editor as settings boxes grouped like the FX editor's:
 * Color, Timing, then Spread (how the group's bulbs share it). Each function is called inside the
 * editor's spaced column, so its boxes stack with the standard gap.
 */

@Composable
private fun BrightnessShiftControls(settings: BrightnessShiftSettings, onChange: (BrightnessShiftSettings) -> Unit) {
    val flicker = settings.mode == BrightnessMode.FLICKER
    SettingsCard(stringResource(R.string.fx_card_behavior), R.drawable.ic_card_behavior) {
        Choice(
            options = listOf(BrightnessMode.SMOOTH to R.string.fx_mode_smooth, BrightnessMode.FLICKER to R.string.fx_mode_flicker),
            selected = settings.mode,
            onSelect = { mode -> onChange(settings.withMode(mode)) },
        )
        PercentRangeControl(R.string.fx_brightness_range, settings.levelMin, settings.levelMax) { lo, hi ->
            onChange(settings.copy(levelMin = lo, levelMax = hi))
        }
    }
    SettingsCard(stringResource(R.string.fx_card_timing), R.drawable.ic_card_timing) {
        TimeRangeControl(
            labelRes = if (flicker) R.string.fx_change_every else R.string.fx_cycle,
            lowMs = settings.speedMinMs,
            highMs = settings.speedMaxMs,
            minMs = if (flicker) FxRanges.FLICKER_MIN else FxRanges.SMOOTH_MIN,
            maxMs = if (flicker) FxRanges.FLICKER_MAX else FxRanges.SMOOTH_MAX,
        ) { lo, hi -> onChange(settings.copy(speedMinMs = lo, speedMaxMs = hi)) }
    }
    SpreadCard(settings.spread, settings.waveOffsetMs) { spread, offset ->
        onChange(settings.copy(spread = spread, waveOffsetMs = offset))
    }
}

@Composable
private fun FlashControls(
    settings: FlashSettings,
    onChange: (FlashSettings) -> Unit,
    canFlashNow: Boolean,
    onFlashNow: () -> Unit,
) {
    // Flash now sits on top, like the FX editor's Try it.
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = onFlashNow, enabled = canFlashNow) { Text(stringResource(R.string.fx_flash_now)) }
        if (!canFlashNow) {
            Text(
                stringResource(R.string.fx_flash_now_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
    SettingsCard(stringResource(R.string.settings_color), R.drawable.ic_card_color) {
        FxColorControls(settings.color) { onChange(settings.copy(color = it)) }
        PercentRangeControl(R.string.fx_peak, settings.peakMin, settings.peak) { lo, hi -> onChange(settings.copy(peakMin = lo, peak = hi)) }
    }
    SettingsCard(stringResource(R.string.fx_card_timing), R.drawable.ic_card_timing) {
        // Single: one pulse per flash. Flicker: a quick stutter of 2–3, one bright and the rest faint.
        Choice(
            options = listOf(false to R.string.fx_flash_single, true to R.string.fx_mode_flicker),
            selected = settings.flicker,
            onSelect = { onChange(settings.withFlicker(it)) },
        )
        // Flicker pulses snap on at a fixed speed: Attack and Hold only shape Single flashes.
        if (settings.flicker) {
            TimeControl(R.string.fx_release, settings.releaseMs, 0, FxRanges.ENVELOPE_MAX) { onChange(settings.copy(releaseMs = it)) }
        } else {
            AhrControl(
                attackMs = settings.attackMs,
                holdMs = settings.holdMs,
                releaseMs = settings.releaseMs,
                maxMs = FxRanges.ENVELOPE_MAX,
                color = LightColors.displayColor(settings.color),
                onChange = { a, h, r -> onChange(settings.copy(attackMs = a, holdMs = h, releaseMs = r)) },
            )
        }
        TimeRangeControl(R.string.fx_gap, settings.gapMinMs, settings.gapMaxMs, FxRanges.GAP_MIN, FxRanges.GAP_MAX) { lo, hi ->
            onChange(settings.copy(gapMinMs = lo, gapMaxMs = hi))
        }
    }
    SpreadCard(settings.spread, settings.waveOffsetMs) { spread, offset -> onChange(settings.copy(spread = spread, waveOffsetMs = offset)) }
}

/**
 * Spin: a color stepping through the group's bulbs in their order (Setup → Groups), lap after lap,
 * laid over whatever the group shows. Its order is the bulbs' own, so it has no Spread box.
 */
@Composable
private fun SpinControls(settings: SpinSettings, onChange: (SpinSettings) -> Unit) {
    SettingsCard(stringResource(R.string.settings_color), R.drawable.ic_card_color) {
        FxColorControls(settings.color) { onChange(settings.copy(color = it)) }
        LevelControl(settings.level) { onChange(settings.copy(level = it)) }
    }
    SettingsCard(stringResource(R.string.fx_card_timing), R.drawable.ic_card_timing) {
        TimeControl(R.string.fx_spin_step, settings.stepMs, FxRanges.SPIN_STEP_MIN, FxRanges.SPIN_STEP_MAX) { onChange(settings.copy(stepMs = it)) }
        TimeControl(R.string.fx_spin_gap, settings.lapGapMs, 0, FxRanges.SPIN_GAP_MAX) { onChange(settings.copy(lapGapMs = it)) }
        SpinDirectionControl(settings.reverse) { onChange(settings.copy(reverse = it)) }
        AhrControl(
            attackMs = settings.attackMs,
            holdMs = settings.holdMs,
            releaseMs = settings.releaseMs,
            maxMs = FxRanges.ENVELOPE_MAX,
            color = LightColors.displayColor(settings.color),
            onChange = { a, h, r -> onChange(settings.copy(attackMs = a, holdMs = h, releaseMs = r)) },
        )
    }
}

/** How the group's bulbs share a layer, in its own box (the box's title stands in for the field label). */
@Composable
private fun SpreadCard(spread: Spread, waveOffsetMs: Int, onChange: (Spread, Int) -> Unit) {
    SettingsCard(stringResource(R.string.fx_spread), R.drawable.ic_card_targets) {
        SpreadControl(spread, waveOffsetMs, showLabel = false, onChange = onChange)
    }
}

/** A layer card's resting shadow (soft) and its lift while dragged. */
private val LAYER_SHADOW = 2.dp
private val LAYER_DRAG_SHADOW = 6.dp

/** A layer card's rim: a touch brighter than the settings box's own. */
private const val LAYER_RIM_ALPHA = 0.10f
