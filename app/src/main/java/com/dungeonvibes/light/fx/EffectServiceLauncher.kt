package com.dungeonvibes.light.fx

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.dungeonvibes.light.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Runs [EffectService] exactly while effects run. Started once from the Application. Stopping waits
 * a few seconds, so tapping from one effect scene to the next (stop, fade, start) doesn't make the
 * notification blink away and back.
 */
@Singleton
class EffectServiceLauncher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val runner: EffectRunner,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            runner.isRunning.collectLatest { running ->
                if (running) {
                    startService()
                } else {
                    delay(STOP_GRACE_MS)
                    context.stopService(EffectService.intent(context))
                }
            }
        }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, EffectService.intent(context))
        } catch (e: RuntimeException) {
            // Android only lets an app start this from the foreground; effects run regardless.
            Log.w(TAG, "Couldn't start the effects service", e)
        }
    }

    private companion object {
        const val TAG = "EffectServiceLauncher"
        const val STOP_GRACE_MS = 3_000L
    }
}
