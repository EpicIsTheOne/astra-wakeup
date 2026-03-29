package com.astra.wakeup.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.astra.wakeup.AstraCrashStore
import com.astra.wakeup.ui.ContextOrchestratorService
import com.astra.wakeup.ui.InterventionRepository
import com.astra.wakeup.ui.OpenClawNodeService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("astra", Context.MODE_PRIVATE)
            if (prefs.getBoolean("wake_enabled", false)) {
                AlarmScheduler.scheduleFromPrefs(context)
            }
            com.astra.wakeup.ui.ReminderScheduler.rescheduleAll(context)
            runCatching { OpenClawNodeService.start(context) }
                .onFailure { err ->
                    Log.e("BootReceiver", "Failed to start OpenClawNodeService on boot", err)
                    AstraCrashStore.record(context, origin = "BootReceiver.OpenClawNodeService.start", throwable = err)
                }
            if (InterventionRepository(context).getState().enabled) {
                runCatching {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, ContextOrchestratorService::class.java)
                    )
                }.onFailure { err ->
                    Log.e("BootReceiver", "Failed to start ContextOrchestratorService on boot", err)
                    AstraCrashStore.record(context, origin = "BootReceiver.ContextOrchestratorService.start", throwable = err)
                }
            }
        }
    }
}
