package com.astra.wakeup.ui

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
import androidx.core.content.ContextCompat
import com.astra.wakeup.R

class ReminderForegroundService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ReminderNotifier.clear(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val reminderId = intent?.getStringExtra("reminder_id") ?: return START_NOT_STICKY
        val reminder = ReminderRepository.getReminder(this, reminderId) ?: return START_NOT_STICKY
        acquireWakeLock()
        startForeground(NOTIFICATION_ID, buildNotification(reminder))
        ReminderNotifier.show(this, reminder)
        startActivity(Intent(this, ReminderActivity::class.java).apply {
            putExtra("reminder_id", reminderId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        })
        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.runCatching { if (isHeld) release() }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(reminder: ReminderItem): Notification {
        val intent = Intent(this, ReminderActivity::class.java).putExtra("reminder_id", reminder.id)
        val pending = PendingIntent.getActivity(this, 6611, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Astra reminder session")
            .setContentText(reminder.title)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Astra reminder session", NotificationManager.IMPORTANCE_HIGH).apply {
                    enableVibration(true)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld == true) return
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "astra:reminder-foreground").apply {
            acquire(4 * 60 * 1000L)
        }
    }

    companion object {
        private const val CHANNEL_ID = "astra_reminder_session"
        private const val NOTIFICATION_ID = 6610
        private const val ACTION_STOP = "com.astra.wakeup.action.STOP_REMINDER_SESSION"

        fun start(context: Context, reminderId: String) {
            ContextCompat.startForegroundService(context, Intent(context, ReminderForegroundService::class.java).putExtra("reminder_id", reminderId))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReminderForegroundService::class.java).apply { action = ACTION_STOP })
        }
    }
}
