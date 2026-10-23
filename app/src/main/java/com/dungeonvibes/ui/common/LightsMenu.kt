package com.dungeonvibes.ui.common

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.R
import com.dungeonvibes.data.bulb.BulbDiscovery
import com.dungeonvibes.data.bulb.BulbRepository
import com.dungeonvibes.data.slot.LightSlotRepository
import com.dungeonvibes.light.BulbReachability
import com.dungeonvibes.light.CommandedLight
import com.dungeonvibes.light.DimmerStore
import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.LightMirror
import com.dungeonvibes.light.MasterDimmer
import com.dungeonvibes.light.RoomLights
import com.dungeonvibes.light.ScenePlayer
import com.dungeonvibes.ui.adventures.play.buildGlowBulbs
import com.dungeonvibes.ui.adventures.play.rememberRoomHaloModifier
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class LightsMenuViewModel @Inject constructor(
    private val player: ScenePlayer,
    slots: LightSlotRepository,
    bulbs: BulbRepository,
    private val mirror: LightMirror,
    private val lights: LightController,
    dimmer: MasterDimmer,
    private val dimmerStore: DimmerStore,
    reachability: BulbReachability,
    private val discovery: BulbDiscovery,
) : ViewModel() {
    val room: StateFlow<RoomLights> = player.room

    /** How many bulbs in groups aren't answering (missed the last scene or scan): the menu's warning. */
    val unreachableCount: StateFlow<Int> = combine(slots.observeAssignments(), reachability.state) { assignments, reach ->
        val seen = HashSet<Long>()
        var count = 0
        for (assignment in assignments) {
            val id = assignment.bulbId
            if (seen.add(id) && reach.isUnreachable(id)) count++
        }
        count
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val isScanning: StateFlow<Boolean> = discovery.isScanning

    /** Looks for the bulbs again (a bulb that was switched off at the wall, say). */
    fun scanAgain() {
        viewModelScope.launch { discovery.scan() }
    }

    /** The master dimmer, 0.05–1. */
    val dimmerLevel: StateFlow<Float> = dimmer.level

    /** Moves every lit bulb to the new level and remembers it for next launch. */
    fun setDimmer(level: Float) {
        lights.setDimmer(level)
        dimmerStore.save(level)
    }

    /** Every assigned bulb, by group order then name ([LightMirror] keys), for the bulb's halo. */
    val roomBulbs: StateFlow<List<String>> = combine(
        slots.observeSlots(),
        slots.observeAssignments(),
        bulbs.observeBulbs(),
    ) { slotList, assignments, bulbList ->
        buildGlowBulbs(slotList, assignments, bulbList).all
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Whether any bulb was last sent a lit color: the bulb icon's on state. Taken from what was
     * sent, so it's dark at launch before anything has been, and goes dark the moment the lights
     * are turned off (however that happened: the menu, an FX ending in Lights off, an all-off scene).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val anyLit: StateFlow<Boolean> = roomBulbs
        .flatMapLatest { keys ->
            if (keys.isEmpty()) {
                flowOf(false)
            } else {
                combine(keys.map(mirror::observe)) { sent -> sent.any { isLit(it) } }
            }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether effects are animating (the halo breathes only while they aren't). */
    val effectsRunning: StateFlow<Boolean> = player.effectsRunning

    /** What the app last sent one bulb, for the halo to follow. */
    fun observeLight(key: String): StateFlow<CommandedLight?> = mirror.observe(key)

    fun turnOff() {
        viewModelScope.launch { player.turnOffLights() }
    }

    fun turnOn() {
        viewModelScope.launch { player.turnOnLights() }
    }

    fun setDefaultLighting(on: Boolean) {
        viewModelScope.launch { player.setDefaultLighting(on) }
    }
}

/** A bulb was last sent a color with some brightness (not off, not an FX's dark start). */
private fun isLit(sent: CommandedLight?): Boolean {
    val color = sent?.color ?: return false
    return color.brightness > 0f
}

/**
 * The lights menu in every screen's title bar: turn every light off (or back on to what was
 * showing), and switch the default lighting on or off. While any bulb is lit the icon is a filled
 * white bulb with a soft halo in the room's colors (like the cards and the nav edge); with
 * everything dark it's a dim outline.
 */
@Composable
fun LightsMenuButton(
    // One for the whole app (the activity), not one per screen: every screen has its own title
    // bar, and a fresh ViewModel per tab would start cold and show the bulb off for a moment.
    viewModel: LightsMenuViewModel = hiltViewModel(LocalActivity.current as ComponentActivity),
) {
    val room by viewModel.room.collectAsStateWithLifecycle()
    val lit by viewModel.anyLit.collectAsStateWithLifecycle()
    val roomBulbs by viewModel.roomBulbs.collectAsStateWithLifecycle()
    val effectsRunning by viewModel.effectsRunning.collectAsStateWithLifecycle()
    val dimmerLevel by viewModel.dimmerLevel.collectAsStateWithLifecycle()
    val unreachable by viewModel.unreachableCount.collectAsStateWithLifecycle()
    val scanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val observeLight: (String) -> StateFlow<CommandedLight?> = remember(viewModel) { viewModel::observeLight }
    val halo = rememberRoomHaloModifier(active = lit, bulbs = roomBulbs, effectsRunning = effectsRunning, observe = observeLight)
    val onLocalNetwork = LocalOnLocalNetwork.current
    val haptics = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }

    Box(contentAlignment = Alignment.Center) {
        // The halo sits behind the bulb's glass, outside the button (which clips to its circle).
        Box(Modifier.offset(y = HALO_OFFSET_Y).size(HALO_SIZE).then(halo))
        IconButton(onClick = { open = true }) {
            Icon(
                painterResource(if (lit) R.drawable.ic_lightbulb else R.drawable.ic_lightbulb_outline),
                contentDescription = stringResource(R.string.lights_menu),
                tint = if (lit) Color.White else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DARK_BULB_ALPHA),
            )
        }
        // A bulb isn't answering: a small warning dot on the bulb's shoulder.
        if (unreachable > 0) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-8).dp, y = 8.dp)
                    .size(WARNING_DOT)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (unreachable > 0) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (scanning) {
                                stringResource(R.string.lights_scanning)
                            } else {
                                pluralStringResource(R.plurals.lights_unreachable, unreachable, unreachable)
                            },
                        )
                    },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    trailingIcon = { Text(stringResource(R.string.lights_scan_again), color = MaterialTheme.colorScheme.primary) },
                    enabled = onLocalNetwork && !scanning,
                    onClick = viewModel::scanAgain,
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (room.lightsOff) R.string.lights_turn_on else R.string.lights_turn_off)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_power), contentDescription = null) },
                enabled = onLocalNetwork,
                onClick = {
                    haptics.performHapticFeedback(SceneHaptic)
                    if (room.lightsOff) viewModel.turnOn() else viewModel.turnOff()
                    open = false
                },
            )
            // On/off comes first: the default can't be switched while the lights are off.
            val canSwitchDefault = onLocalNetwork && !room.lightsOff
            DropdownMenuItem(
                text = { Text(stringResource(R.string.default_lighting)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_home), contentDescription = null) },
                trailingIcon = { Switch(checked = room.defaultOn, onCheckedChange = null, enabled = canSwitchDefault) },
                enabled = canSwitchDefault,
                onClick = {
                    haptics.performHapticFeedback(SceneHaptic)
                    viewModel.setDefaultLighting(!room.defaultOn)
                    open = false
                },
            )
            // Stays open while dragging, so the room can be dimmed by eye.
            DimmerRow(dimmerLevel, enabled = onLocalNetwork, onChange = viewModel::setDimmer)
        }
    }
}

/** The master dimmer: 5–100 % in 5 % steps, scaling everything the app sends. */
@Composable
private fun DimmerRow(level: Float, enabled: Boolean, onChange: (Float) -> Unit) {
    val percent = (level * 100f).roundToInt()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.lights_dimmer, percent),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SteadySlider(
            value = level,
            onValueChange = { value ->
                // Snap to whole 5 % steps; only a real change goes to the bulbs.
                val snapped = (value * DIMMER_STEPS).roundToInt() / DIMMER_STEPS
                if (snapped != level) onChange(snapped)
            },
            modifier = Modifier.width(DIMMER_WIDTH),
            valueRange = MasterDimmer.MIN..MasterDimmer.FULL,
            steps = DIMMER_SLIDER_STEPS,
            enabled = enabled,
        )
    }
}

/* Dimmer: 20 steps of 5 %; the slider's 5 %..100 % range has 18 stops between its ends. */
private const val DIMMER_STEPS = 20f
private const val DIMMER_SLIDER_STEPS = 18
private val DIMMER_WIDTH = 220.dp

/* The bulb's halo: a disk about the size of the icon's glass, centered on it (the glass sits above the icon's middle). */
private val HALO_SIZE = 16.dp
private val HALO_OFFSET_Y = (-3).dp

/** The warning dot on the bulb when some bulb isn't answering. */
private val WARNING_DOT = 8.dp

/** A dark bulb is dimmer than the other title-bar icons, so it reads as off. */
private const val DARK_BULB_ALPHA = 0.6f
