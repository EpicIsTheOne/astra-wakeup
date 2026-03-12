package com.astra.wakeup.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.astra.wakeup.R

class ContextOrchestratorService : Service() {
    private lateinit var repo: ContextRuleRepository
    private lateinit var engine: ContextRuleEngine

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val event = when (action) {
                Intent.ACTION_USER_PRESENT -> ContextEvent(TriggerType.PHONE_UNLOCK)
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> ContextEvent(TriggerType.CHARGING_CHANGED)
                Intent.ACTION_HEADSET_PLUG, AudioManagerCompat.ACTION_AUDIO_BECOMING_NOISY -> ContextEvent(TriggerType.HEADPHONE_CHANGED)
                Intent.ACTION_TIME_TICK -> ContextEvent(TriggerType.TIME_TICK)
                else -> null
            } ?: return

            engine.onEvent(event, snapshot())
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = ContextRuleRepository(this)
        engine = ContextRuleEngine(ContextConditionEvaluator(), ContextActionExecutor(this), repo)
        ensureChannel()
        val n: Notification = NotificationCompat.Builder(this, "astra_context_engine")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Astra Context Engine")
            .setContentText("Watching context signals")
            .setOngoing(true)
            .build()
        startForeground(7310, n)

        val f = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction(AudioManagerCompat.ACTION_AUDIO_BECOMING_NOISY)
            addAction(Intent.ACTION_TIME_TICK)
        }
        registerReceiver(receiver, f)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun snapshot(): ContextSnapshot {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val personality = getSharedPreferences("astra", MODE_PRIVATE).getString("personality_mode", "coach") ?: "coach"
        val mode = runCatching { PersonalityMode.valueOf(personality.uppercase()) }.getOrDefault(PersonalityMode.COACH)
        return ContextSnapshot(isCharging = charging, currentPersonality = mode)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel("astra_context_engine", "Astra Context Engine", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }
    }
}

object AudioManagerCompat {
    const val ACTION_AUDIO_BECOMING_NOISY: String = "android.media.AUDIO_BECOMING_NOISY"
}
