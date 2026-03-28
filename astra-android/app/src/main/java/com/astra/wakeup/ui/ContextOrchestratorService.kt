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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import com.astra.wakeup.R
import java.util.UUID

class ContextOrchestratorService : Service() {
    private lateinit var repo: ContextRuleRepository
    private lateinit var engine: ContextRuleEngine
    private lateinit var interventionRepo: InterventionRepository
    private var headphonesConnected: Boolean = false
    private val handler = Handler(Looper.getMainLooper())
    private val appUsageTick = object : Runnable {
        override fun run() {
            checkAppUsageIntervention()
            handler.postDelayed(this, 15_000)
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val event = when (action) {
                Intent.ACTION_USER_PRESENT -> ContextEvent(TriggerType.PHONE_UNLOCK)
                Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED -> ContextEvent(TriggerType.CHARGING_CHANGED)
                Intent.ACTION_HEADSET_PLUG -> {
                    headphonesConnected = intent.getIntExtra("state", 0) == 1
                    ContextEvent(TriggerType.HEADPHONE_CHANGED)
                }
                AudioManagerCompat.ACTION_AUDIO_BECOMING_NOISY -> {
                    headphonesConnected = false
                    ContextEvent(TriggerType.HEADPHONE_CHANGED)
                }
                Intent.ACTION_TIME_TICK -> ContextEvent(TriggerType.TIME_TICK)
                else -> null
            } ?: return

            engine.onEvent(event, snapshot())
        }
    }

    override fun onCreate() {
        super.onCreate()
        repo = ContextRuleRepository(this)
        interventionRepo = InterventionRepository(this)
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
        handler.post(appUsageTick)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun snapshot(): ContextSnapshot {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val personality = prefs.getString("personality_mode", "coach") ?: "coach"
        val mode = runCatching { PersonalityMode.valueOf(personality.uppercase()) }.getOrDefault(PersonalityMode.COACH)
        val zone = prefs.getString("context_location_zone", null)
        val lastTrig = prefs.getLong("last_alarm_triggered_at", 0L).takeIf { it > 0 }
        val lastDismiss = prefs.getLong("last_alarm_dismissed_at", 0L).takeIf { it > 0 }
        val nextEvent = nextCalendarEventMs()
        return ContextSnapshot(
            isCharging = charging,
            headphonesConnected = headphonesConnected,
            lastAlarmTriggeredAt = lastTrig,
            lastAlarmDismissedAt = lastDismiss,
            locationZoneId = zone,
            nextCalendarEventMs = nextEvent,
            currentPersonality = mode,
            foregroundAppPackage = AppUsageTracker.foregroundApp(this)
        )
    }

    private fun checkAppUsageIntervention() {
        val state = interventionRepo.getState()
        if (!state.enabled) return
        if (!AppUsageTracker.hasUsageAccess(this)) return

        val currentPackage = AppUsageTracker.foregroundApp(this) ?: return
        val tracked = state.trackedApps.firstOrNull { it.enabled && it.packageName == currentPackage } ?: return
        val usage = AppUsageTracker.usageInWindow(this, tracked.packageName, state.rollingWindowMinutes)
        val thresholdMs = tracked.thresholdMinutes * 60_000L
        if (usage.totalMs < thresholdMs) return

        val cooldownMs = state.cooldownMinutes * 60_000L
        val lastPopup = interventionRepo.getLastPopupAt(tracked.packageName)
        if (lastPopup > 0 && System.currentTimeMillis() - lastPopup < cooldownMs) return

        interventionRepo.saveLastPopupAt(tracked.packageName, System.currentTimeMillis())
        val minutes = usage.totalMs / 60_000L
        val opener = "You've spent $minutes minutes in ${tracked.label} lately. Get off and tell me what you're doing."
        InterventionPopupActivity.open(
            context = this,
            packageName = tracked.packageName,
            label = tracked.label,
            message = opener,
            sessionKey = "intervention-${tracked.packageName}-${UUID.randomUUID()}"
        )
    }

    private fun nextCalendarEventMs(): Long? {
        return runCatching {
            val now = System.currentTimeMillis()
            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(CalendarContract.Events.DTSTART)
            val sel = "${CalendarContract.Events.DTSTART}>=?"
            val args = arrayOf(now.toString())
            contentResolver.query(uri, projection, sel, args, "${CalendarContract.Events.DTSTART} ASC")?.use { c ->
                if (c.moveToFirst()) c.getLong(0) else null
            }
        }.getOrNull()
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
