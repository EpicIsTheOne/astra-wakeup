package com.astra.wakeup.brain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.astra.wakeup.R
import com.astra.wakeup.brain.BrainEventLog
import com.astra.wakeup.brain.actions.ActionExecutor
import com.astra.wakeup.brain.automation.AutomationHub
import com.astra.wakeup.brain.memory.SharedPrefsMemoryStore
import com.astra.wakeup.brain.perception.EventBus
import com.astra.wakeup.brain.perception.SignalCollectors
import com.astra.wakeup.brain.reasoning.Reasoner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AstraBrainService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var collectors: SignalCollectors
    private var lastEvent: String = "-"
    private var lastDecision: String = "-"

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val notif: Notification = NotificationCompat.Builder(this, "astra_brain_service")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Astra Brain")
            .setContentText("Perceiving and reasoning")
            .setOngoing(true)
            .build()
        startForeground(7410, notif)

        collectors = SignalCollectors(this)
        collectors.start()

        val memory = SharedPrefsMemoryStore(this)
        val reasoner = Reasoner(memory)
        val executor = ActionExecutor(this)
        val hub = AutomationHub(this, executor)

        scope.launch {
            EventBus.events.collectLatest { event ->
                lastEvent = event.type
                BrainEventLog.append(this@AstraBrainService, "info", "event: ${event.type}")
                val decision = reasoner.decide(event)
                lastDecision = decision.reason
                BrainEventLog.append(this@AstraBrainService, "debug", "decision: ${decision.reason}")
                decision.actions.forEach { executor.execute(it) }
                hub.onEvent(event)
                val st = hub.state(lastEvent, lastDecision)
                getSharedPreferences("astra_brain", MODE_PRIVATE).edit()
                    .putString("last_event", st.lastEvent)
                    .putString("last_decision", st.lastDecision)
                    .putInt("total_rules", st.totalRules)
                    .putInt("context_rules", st.contextRules)
                    .putInt("task_rules", st.taskRules)
                    .putInt("cron_rules", st.cronRules)
                    .apply()
            }
        }
    }

    override fun onDestroy() {
        collectors.stop()
        scope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("astra_brain_service", "Astra Brain Service", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
