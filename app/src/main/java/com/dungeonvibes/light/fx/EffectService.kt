package com.dungeonvibes.light.fx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.dungeonvibes.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Keeps effects playing with the screen off or another app in front (FX_REQUIREMENTS.md → Running
 * in the background). A foreground service is Android's way of saying "the user is relying on this
 * right now": it shows a notification and stops the system from freezing the app. It runs only
 * while effects do; [EffectServiceLauncher] starts and stops it.
 *
 * While running it holds a Wi-Fi lock (so the radio doesn't doze and delay packets) and a partial
 * wake lock (so the processor keeps ticking with the screen off). The notification's Stop and
 * swiping the app away both stop the effects, settling the bulbs on their scene's base.
 */
@AndroidEntryPoint
class EffectService : Service() {

    @Inject lateinit var runner: EffectRunner

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLocks: List<WifiManager.WifiLock> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEffectsThenSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
        } catch (e: RuntimeException) {
            // Android refused (e.g. started from the background); effects still run while the app is open.
            Log.w(TAG, "Couldn't start the effects service in the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        acquireLocks()
        // If the system kills the process, effects are gone too: nothing to restart.
        return START_NOT_STICKY
    }

    /** Swiping the app away from recents ends the session's effects. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopEffectsThenSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun stopEffectsThenSelf() {
        val settling = runner.stop()
        // Stay alive (and awake) until the bulbs have been told to settle.
        if (settling == null) stopSelf() else settling.invokeOnCompletion { stopSelf() }
    }

    /* Locks */

    @Suppress("DEPRECATION") // HIGH_PERF still keeps the radio awake with the screen off.
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DungeonVibes:effects")?.apply {
                setReferenceCounted(false)
                acquire(MAX_LOCK_MS)
            }
        }
        if (wifiLocks.isEmpty()) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return
            val locks = ArrayList<WifiManager.WifiLock>(2)
            locks.add(wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DungeonVibes:effects"))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Lowest latency while the screen is on; the system drops back to HIGH_PERF when it's off.
                locks.add(wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "DungeonVibes:effects-ll"))
            }
            for (lock in locks) {
                lock.setReferenceCounted(false)
                lock.acquire()
            }
            wifiLocks = locks
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        for (lock in wifiLocks) if (lock.isHeld) lock.release()
        wifiLocks = emptyList()
    }

    /* Notification */

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            // Low importance: it sits quietly in the shade, no sound or pop-up.
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fx_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, EffectService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.fx_notification_title))
            .setContentText(getString(R.string.fx_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.fx_notification_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "EffectService"
        private const val CHANNEL_ID = "effects"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.dungeonvibes.action.STOP_EFFECTS"

        /** Safety net so a lost wake lock can't drain the battery forever: a long session's worth. */
        private const val MAX_LOCK_MS = 8L * 60 * 60 * 1000

        fun intent(context: Context): Intent = Intent(context, EffectService::class.java)
    }
}
