package com.dungeonvibes.ui.scenes.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dungeonvibes.R
import com.dungeonvibes.ui.common.NameKeyboardOptions
import com.dungeonvibes.data.db.BulbEntity
import com.dungeonvibes.data.db.LightMode
import com.dungeonvibes.data.db.SceneLightEntity
import com.dungeonvibes.data.scene.GroupEffects
import com.dungeonvibes.data.scene.SceneRepository
import com.dungeonvibes.ui.common.ConfirmDialog
import com.dungeonvibes.ui.common.GradientSlider
import com.dungeonvibes.ui.common.LightColors
import com.dungeonvibes.ui.common.SettingsCard
import com.dungeonvibes.ui.common.Swatch
import com.dungeonvibes.ui.common.SwatchSize
import com.dungeonvibes.ui.common.SteadySlider
import com.dungeonvibes.ui.common.snapMs
import com.dungeonvibes.ui.common.TopLevelScreen
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Editor for one scene (library or adventure). Shows a back arrow when [showBack], i.e. when it is
 * the only pane on screen.
 */
@Composable
fun SceneEditor(
    sceneId: Long,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    // One ViewModel per scene: the key keeps two scenes opened in turn from sharing state.
    val viewModel = hiltViewModel<SceneEditorViewModel, SceneEditorViewModel.Factory>(key = "scene-$sceneId") { factory ->
        factory.create(sceneId)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SceneEditorEvent.SavedToLibrary -> snackbar.showSnackbar(context.getString(R.string.scene_saved_to_library))
            }
        }
    }

    var expandedSlotId by rememberSaveable(sceneId) { mutableStateOf<Long?>(null) }
    var confirmReset by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val nameFocus = remember { FocusRequester() }

    // The name field only takes focus (and the keyboard) when the user taps it, or when the scene
    // has no name yet. Android can hand focus to the first text field on its own; that is undone.
    var nameTouched by remember { mutableStateOf(false) }
    LaunchedEffect(sceneId, state.isLoaded) {
        if (state.isLoaded && !state.isMissing && !state.isDefault && state.name.isBlank()) {
            nameTouched = true
            // The field may not be laid out yet on the very first frame; skip rather than crash.
            try {
                nameFocus.requestFocus()
            } catch (e: IllegalStateException) {
                nameTouched = false
            }
        }
    }
    // Scrolling by hand puts the keyboard away.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) focusManager.clearFocus() }
    }

    TopLevelScreen(
        title = when {
            state.isDefault -> stringResource(R.string.default_lighting)
            else -> state.name.ifBlank { stringResource(R.string.scene_untitled) }
        },
        snackbarHostState = snackbar,
        // Beside an open list (tablets) the list pane carries the lights menu.
        lightsMenu = showBack,
        navigationIcon = {
            if (showBack) {
                IconButton(onClick = onBack) {
                    Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.action_back))
                }
            }
        },
    ) { modifier ->
        if (!state.isLoaded) return@TopLevelScreen
        if (state.isMissing) {
            Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.scene_missing), style = MaterialTheme.typography.bodyLarge)
            }
            return@TopLevelScreen
        }
        // Items above the groups, so a row's list index is known for scrolling to it: name (the
        // default scene shows a hint instead), fade, (adventure actions), divider, ("no groups"),
        // then the individual lights (a header and one row each), then "All lights" when there are
        // groups. Live preview is always on (owner decision).
        val showName = !state.isDefault
        val showAdventureActions = !state.isLibraryScene && !state.isDefault
        val prefix = 3 + (if (showAdventureActions) 1 else 0) + (if (state.rows.isEmpty()) 1 else 0)
        val showSingles = state.singleRows.isNotEmpty() || state.addableBulbs.isNotEmpty()
        val singlesStart = prefix + 1
        val afterSingles = prefix + (if (showSingles) 1 + state.singleRows.size else 0)
        val headerCount = afterSingles + (if (state.rows.isEmpty()) 0 else 1)
        LazyColumn(state = listState, modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            if (state.isDefault) {
                item(key = "default-hint") {
                    Text(
                        stringResource(R.string.default_lighting_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (showName) item(key = "name") {
                OutlinedTextField(
                    value = state.name,
                    onValueChange = viewModel::setName,
                    singleLine = true,
                    label = { Text(stringResource(R.string.label_name)) },
                    keyboardOptions = NameKeyboardOptions.copy(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(nameFocus)
                        .pointerInput(Unit) {
                            // Sees the touch before the field does, so the focus that follows is wanted.
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                nameTouched = true
                            }
                        }
                        .onFocusChanged { focus ->
                            if (!focus.isFocused) {
                                nameTouched = false
                            } else if (!nameTouched) {
                                focusManager.clearFocus()
                            }
                        },
                )
            }
            // Introduction: how the scene arrives. The default lighting is never played from a
            // tile or the library, so it has no intro FX.
            item(key = "intro") {
                IntroductionCard(sceneId, state.fadeMs, viewModel::setFadeMs, showIntroFx = !state.isDefault)
            }
            if (showAdventureActions) {
                item(key = "adventure-actions") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        if (state.canReset) {
                            OutlinedButton(onClick = { confirmReset = true }) {
                                Text(stringResource(R.string.scene_reset_to_library))
                            }
                        }
                        OutlinedButton(onClick = viewModel::saveToLibrary) {
                            Text(stringResource(R.string.scene_save_to_library))
                        }
                    }
                }
            }
            item(key = "divider") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            if (state.rows.isEmpty()) {
                item(key = "no-slots") {
                    Text(
                        stringResource(R.string.scene_no_slots),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            // Opens or closes a row, bringing an opened one to the top where its controls have room.
            val toggle: (Long, Int) -> Unit = { slotId, listIndex ->
                focusManager.clearFocus()
                if (expandedSlotId == slotId) {
                    expandedSlotId = null
                } else {
                    expandedSlotId = slotId
                    scope.launch { listState.animateScrollToItem(listIndex) }
                }
            }
            // Individual lights: single bulbs set on their own, above everything they win over.
            if (showSingles) {
                item(key = "singles-header") {
                    SinglesHeader(addable = state.addableBulbs, onAdd = viewModel::addSingleLights)
                }
                itemsIndexed(state.singleRows, key = { _, row -> "single-${row.slot.id}" }) { index, row ->
                    val slotId = row.slot.id
                    SlotRow(
                        row = row,
                        expanded = expandedSlotId == slotId,
                        onToggleExpanded = { toggle(slotId, singlesStart + index) },
                        onOnChange = { viewModel.setOn(slotId, it) },
                        onModeChange = { viewModel.setMode(slotId, it) },
                        onHueChange = { viewModel.setHue(slotId, it) },
                        onSaturationChange = { viewModel.setSaturation(slotId, it) },
                        onBrightnessChange = { viewModel.setBrightness(slotId, it) },
                        onKelvinChange = { viewModel.setKelvin(slotId, it) },
                        onMultiColorChange = { viewModel.setMultiColor(slotId, it) },
                        livePreview = state.livePreview,
                        onEffectsChange = { viewModel.setEffects(slotId, it) },
                        onFlashNow = { viewModel.flashNow(slotId) },
                        onRemove = {
                            if (expandedSlotId == slotId) expandedSlotId = null
                            viewModel.removeSingleLight(slotId)
                        },
                    )
                    HorizontalDivider()
                }
            }
            val roomRow = state.roomRow
            if (state.rows.isNotEmpty() && roomRow != null) {
                item(key = "all-lights") {
                    val roomId = roomRow.slot.id
                    Column {
                    SlotRow(
                        row = roomRow,
                        // Its settings only matter (and only open) while All lights is on.
                        expanded = expandedSlotId == roomId && roomRow.isOn,
                        onToggleExpanded = { toggle(roomId, headerCount - 1) },
                        onOnChange = {
                            focusManager.clearFocus()
                            viewModel.setAllLightsOn(it)
                        },
                        onModeChange = { viewModel.setMode(roomId, it) },
                        onHueChange = { viewModel.setHue(roomId, it) },
                        onSaturationChange = { viewModel.setSaturation(roomId, it) },
                        onBrightnessChange = { viewModel.setBrightness(roomId, it) },
                        onKelvinChange = { viewModel.setKelvin(roomId, it) },
                        onMultiColorChange = { viewModel.setMultiColor(roomId, it) },
                        livePreview = state.livePreview,
                        onEffectsChange = { viewModel.setEffects(roomId, it) },
                        onFlashNow = { viewModel.flashNow(roomId) },
                    )
                    HorizontalDivider()
                    }
                }
            }
            itemsIndexed(state.rows, key = { _, row -> "slot-${row.slot.id}" }) { index, row ->
                val slotId = row.slot.id
                SlotRow(
                    row = row,
                    // Groups are locked (and closed) while All lights is on.
                    expanded = expandedSlotId == slotId && !row.forcedOn,
                    onToggleExpanded = { toggle(slotId, headerCount + index) },
                    onOnChange = { viewModel.setOn(slotId, it) },
                    onModeChange = { viewModel.setMode(slotId, it) },
                    onHueChange = { viewModel.setHue(slotId, it) },
                    onSaturationChange = { viewModel.setSaturation(slotId, it) },
                    onBrightnessChange = { viewModel.setBrightness(slotId, it) },
                    onKelvinChange = { viewModel.setKelvin(slotId, it) },
                    onMultiColorChange = { viewModel.setMultiColor(slotId, it) },
                    livePreview = state.livePreview,
                    onEffectsChange = { viewModel.setEffects(slotId, it) },
                    onFlashNow = { viewModel.flashNow(slotId) },
                )
                HorizontalDivider()
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            title = stringResource(R.string.scene_reset_title),
            message = stringResource(R.string.scene_reset_message),
            confirmLabel = stringResource(R.string.scene_reset_confirm),
            onConfirm = {
                viewModel.resetToLibraryVersion()
                confirmReset = false
            },
            onDismiss = { confirmReset = false },
        )
    }
}

/** "Individual lights", what they do, and a button that opens the bulb picker (when any are left). */
@Composable
private fun SinglesHeader(addable: List<BulbEntity>, onAdd: (List<Long>) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.scene_singles_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.scene_singles_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (addable.isNotEmpty()) {
            TextButton(onClick = { picking = true }) { Text(stringResource(R.string.scene_singles_add)) }
        }
    }
    // Close the picker if the last addable bulb goes (added here, or removed in Setup).
    if (picking && addable.isNotEmpty()) {
        AddLightsDialog(
            bulbs = addable,
            onAdd = { ids ->
                picking = false
                onAdd(ids)
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
internal fun FadeControl(fadeMs: Int, onChange: (Int) -> Unit) {
    val max = SceneRepository.MAX_FADE_MS
    Column {
        Text(
            stringResource(R.string.scene_fade_label, formatFade(fadeMs)),
            style = MaterialTheme.typography.bodyLarge,
        )
        SteadySlider(
            // A squared scale, so the usual few seconds get most of the slider and ten minutes
            // still fit; whole seconds only (owner request: no tiny steps).
            value = sqrt((fadeMs.toFloat() / max).coerceIn(0f, 1f)),
            onValueChange = { p ->
                val seconds = (p * p * max / 1_000f).roundToInt()
                onChange((seconds * 1_000).coerceIn(0, max))
            },
        )
    }
}

/** "0 s", "45 s", "2 min 30 s". */
private fun formatFade(ms: Int): String {
    val seconds = ms / 1_000
    if (seconds < 60) return "$seconds s"
    val rest = seconds % 60
    return if (rest == 0) "${seconds / 60} min" else "${seconds / 60} min $rest s"
}

@Composable
private fun SlotRow(
    row: SlotRowState,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onOnChange: (Boolean) -> Unit,
    onModeChange: (LightMode) -> Unit,
    onHueChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onKelvinChange: (Int) -> Unit,
    onMultiColorChange: (Boolean) -> Unit,
    livePreview: Boolean,
    onEffectsChange: (GroupEffects) -> Unit,
    onFlashNow: () -> Unit,
    /** Individual lights only: takes the light out of the scene. */
    onRemove: (() -> Unit)? = null,
) {
    val chevronTurn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val isRoom = row.slot.isRoom
    // A group under All lights is locked; the All-lights row only opens while it's on.
    val locked = row.forcedOn
    val canOpen = !locked && (!isRoom || row.isOn)
    // A slot without settings shows the defaults it would get when first changed.
    val light = row.light
    // The swatch shows a color only when the bulbs actually show it: a group that's off, or
    // overridden by All lights, is an empty swatch (owner request).
    val visible = row.isOn && !locked
    val swatchColor = if (light != null && visible) LightColors.displayColor(light) else null
    val effects = row.effects
    // The ✦ marks effect layers; Multi-color is a way of coloring, not an effect.
    val runsEffects = visible && effects?.hasEffectLayers == true
    val shiftColors = remember(effects, visible) {
        if (visible && effects != null && effects.runsColorShift) effects.palette.map(LightColors::displayColor) else emptyList()
    }

    val name = if (isRoom) stringResource(R.string.scene_all_lights) else row.slot.name
    val hint = when {
        isRoom -> null
        row.slot.bulbId != null -> stringResource(R.string.scene_single_hint)
        locked -> stringResource(R.string.scene_using_all_lights)
        !row.hasBulbs -> stringResource(R.string.slot_no_bulbs)
        else -> null
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = canOpen, onClick = onToggleExpanded)
                .alpha(if (locked) 0.5f else 1f)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            // Under All lights a group's switch shows lit and can't be changed; its own setting is
            // kept underneath and comes back when All lights goes off.
            // A padlock on top says why it can't be changed.
            Box(contentAlignment = Alignment.Center) {
                Switch(checked = row.isOn, onCheckedChange = onOnChange, enabled = !locked)
                if (locked) {
                    Icon(
                        painterResource(R.drawable.ic_lock),
                        contentDescription = stringResource(R.string.scene_locked_by_all_lights),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(LOCK_ICON_SIZE),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Swatch(
                swatchColor,
                SwatchSize.ExtraLarge,
                Modifier.clickable(enabled = canOpen, onClick = onToggleExpanded),
                shiftColors,
                hasEffects = runsEffects,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyLarge)
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.scene_single_remove, name),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (canOpen) {
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = stringResource(if (expanded) R.string.action_collapse else R.string.action_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronTurn),
                )
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                LightControls(
                    light = light,
                    effects = effects,
                    onModeChange = onModeChange,
                    onMultiColorChange = onMultiColorChange,
                    onEffectsChange = onEffectsChange,
                    onHueChange = onHueChange,
                    onSaturationChange = onSaturationChange,
                    onBrightnessChange = onBrightnessChange,
                    onKelvinChange = onKelvinChange,
                )
                EffectsSection(row, livePreview, onEffectsChange, onFlashNow)
            }
        }
    }
}

@Composable
private fun LightControls(
    light: SceneLightEntity?,
    effects: GroupEffects?,
    onModeChange: (LightMode) -> Unit,
    onMultiColorChange: (Boolean) -> Unit,
    onEffectsChange: (GroupEffects) -> Unit,
    onHueChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onKelvinChange: (Int) -> Unit,
) {
    val mode = light?.mode ?: LightMode.WHITE
    val multi = effects?.colorShift?.enabled == true
    val hue = light?.hue ?: LightColors.DEFAULT_HUE
    val saturation = light?.saturation ?: LightColors.DEFAULT_SATURATION
    val brightness = light?.brightness ?: LightColors.DEFAULT_BRIGHTNESS
    val kelvin = light?.kelvin ?: LightColors.DEFAULT_KELVIN

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        // Three ways to color a group. Multi-color is the Color shift layer; picking Color or White
        // switches it off but keeps its palette for next time.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = !multi && mode == LightMode.COLOR,
                onClick = {
                    onModeChange(LightMode.COLOR)
                    onMultiColorChange(false)
                },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
            ) { Text(stringResource(R.string.scene_mode_color)) }
            SegmentedButton(
                selected = !multi && mode == LightMode.WHITE,
                onClick = {
                    onModeChange(LightMode.WHITE)
                    onMultiColorChange(false)
                },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
            ) { Text(stringResource(R.string.scene_mode_white)) }
            SegmentedButton(
                selected = multi,
                onClick = { onMultiColorChange(true) },
                shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
            ) { Text(stringResource(R.string.scene_mode_multi)) }
        }
        Spacer(Modifier.height(8.dp))
        // Each mode's settings sit in boxes under the selector (SettingsCard).
        when {
            multi -> MultiColorControls(effects, onEffectsChange)
            mode == LightMode.COLOR -> SettingsCard(stringResource(R.string.settings_color)) {
                SliderLabel(stringResource(R.string.scene_hue))
                GradientSlider(hue, onHueChange, 0f..360f, LightColors.HueBrush)
                SliderLabel(stringResource(R.string.scene_saturation))
                val saturationBrush = remember(hue) { LightColors.saturationBrush(hue) }
                GradientSlider(saturation, onSaturationChange, 0f..1f, saturationBrush)
                SliderLabel(stringResource(R.string.scene_brightness))
                val brightnessBrush = remember(hue, saturation) {
                    LightColors.brightnessBrush(Color.hsv(hue, saturation, 1f))
                }
                GradientSlider(brightness, onBrightnessChange, 0f..1f, brightnessBrush)
            }
            else -> SettingsCard(stringResource(R.string.settings_color)) {
                SliderLabel(stringResource(R.string.scene_temperature, kelvin))
                GradientSlider(
                    value = kelvin.toFloat(),
                    onValueChange = { onKelvinChange(it.roundToInt()) },
                    valueRange = LightColors.MIN_KELVIN.toFloat()..LightColors.MAX_KELVIN.toFloat(),
                    brush = LightColors.KelvinBrush,
                )
                SliderLabel(stringResource(R.string.scene_brightness))
                val brightnessBrush = remember(kelvin) { LightColors.brightnessBrush(LightColors.kelvinToColor(kelvin)) }
                GradientSlider(brightness, onBrightnessChange, 0f..1f, brightnessBrush)
            }
        }
    }
}

@Composable
private fun SliderLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** The padlock over a group's switch while All lights is on. */
private val LOCK_ICON_SIZE = 18.dp
