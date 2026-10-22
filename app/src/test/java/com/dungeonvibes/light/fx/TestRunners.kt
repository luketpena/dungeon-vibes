package com.dungeonvibes.light.fx

import com.dungeonvibes.light.LightController
import com.dungeonvibes.light.MonotonicClock
import com.dungeonvibes.light.NetworkStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

/** An effect runner on real time and an always-online network, for the real-time database tests. */
fun realTimeEffectRunner(controller: LightController, scope: CoroutineScope): EffectRunner = EffectRunner(
    controller,
    object : NetworkStatus {
        override val isOnLocalNetwork = MutableStateFlow(true)
    },
    MonotonicClock { System.nanoTime() / 1_000_000 },
    scope,
)
