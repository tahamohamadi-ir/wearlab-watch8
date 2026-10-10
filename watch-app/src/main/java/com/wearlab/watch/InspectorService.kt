package com.wearlab.watch

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/** Keeps the process alive (foreground) so streams survive screen-off; owns the sensor wake lock. */
class InspectorService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("inspector", "Sensor Inspector", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "inspector")
            .setContentTitle("WearLab")
            .setContentText("Recording")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
        // The location type needs the runtime permission at start time or startForeground throws.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        startForeground(1, n, type)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wearlab:sensors")
        setAwake(true)
    }

    private fun setAwake(on: Boolean) {
        val w = wakeLock ?: return
        if (on && !w.isHeld) w.acquire() else if (!on && w.isHeld) w.release()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A stop requested before the service finished starting is honoured here, after startForeground().
        if (!wanted) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        setAwake(false)
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_WINDOW = "com.wearlab.watch.WINDOW_ALARM"
        @Volatile private var instance: InspectorService? = null
        @Volatile private var wanted = false

        fun start(c: Context) {
            wanted = true
            c.applicationContext.startForegroundService(Intent(c.applicationContext, InspectorService::class.java))
        }

        /** Never stopService() directly: stopping before startForeground() crashes the app. */
        fun stop() {
            wanted = false
            instance?.stopSelf()
        }

        /** Between capture windows the CPU may sleep (battery); during a window it must not. */
        fun awake(on: Boolean) { instance?.setAwake(on) }

        /** Wakes the device at [elapsedRealtimeMs] so the next capture window starts on time. */
        fun scheduleWindow(c: Context, elapsedRealtimeMs: Long) {
            val am = c.getSystemService(AlarmManager::class.java)
            val pi = pending(c)
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedRealtimeMs, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, elapsedRealtimeMs, pi)
            }
        }

        fun cancelWindow(c: Context) = c.getSystemService(AlarmManager::class.java).cancel(pending(c))

        private fun pending(c: Context) = PendingIntent.getBroadcast(
            c, 1, Intent(ACTION_WINDOW).setPackage(c.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
