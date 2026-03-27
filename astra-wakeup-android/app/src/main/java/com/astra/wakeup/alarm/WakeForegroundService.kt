package com.astra.wakeup.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.astra.wakeup.R
import com.astra.wakeup.ui.WakeActivity

class WakeForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWakeSession()
                return START_NOT_STICKY
            }
            else -> {
                acquireWakeLock()
                startForeground(NOTIFICATION_ID, buildNotification())
                AlarmNotifier.showWakeAlarm(this)
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val fullScreenIntent = Intent(this, WakeActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION
            )
        }
        val pending = PendingIntent.getActivity(
            this,
            7101,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Astra wake session")
            .setContentText("Wake up, Epic.")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .build()
    }

    private fun stopWakeSession() {
        AlarmNotifier.clearWakeAlarm(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld == true) return
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "astra:wake-foreground"
        ).apply {
            acquire(5 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.runCatching {
            if (isHeld) release()
        }
        wakeLock = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Astra Wake Session", NotificationManager.IMPORTANCE_HIGH).apply {
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    enableVibration(true)
                }
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "astra_wake_session"
        private const val NOTIFICATION_ID = 5502
        private const val ACTION_STOP = "com.astra.wakeup.action.STOP_WAKE_SESSION"

        fun start(context: Context) {
            val intent = Intent(context, WakeForegroundService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, WakeForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}
