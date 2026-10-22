package com.dungeonvibes.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dungeonvibes.R
import com.dungeonvibes.light.RoomLights
import com.dungeonvibes.light.ScenePlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class LightsMenuViewModel @Inject constructor(private val player: ScenePlayer) : ViewModel() {
    val room: StateFlow<RoomLights> = player.room

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

/**
 * The lights menu in every screen's title bar: turn every light off (or back on to what was
 * showing), and switch the default lighting on or off. The bulb icon is filled while the app has
 * the lights on.
 */
@Composable
fun LightsMenuButton(viewModel: LightsMenuViewModel = hiltViewModel()) {
    val room by viewModel.room.collectAsStateWithLifecycle()
    val onLocalNetwork = LocalOnLocalNetwork.current
    val haptics = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                painterResource(if (room.lightsOff) R.drawable.ic_lightbulb_outline else R.drawable.ic_lightbulb),
                contentDescription = stringResource(R.string.lights_menu),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
        }
    }
}
