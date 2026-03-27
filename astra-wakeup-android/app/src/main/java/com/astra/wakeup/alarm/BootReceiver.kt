package com.astra.wakeup.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
            OpenClawNodeService.start(context)
            if (InterventionRepository(context).getState().enabled) {
                context.startService(Intent(context, ContextOrchestratorService::class.java))
            }
        }
    }
}
