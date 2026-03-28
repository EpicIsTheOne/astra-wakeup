package com.astra.wakeup.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val reminderId = intent?.getStringExtra("reminder_id") ?: return
        val reminder = ReminderRepository.getReminder(context, reminderId) ?: return
        val normalized = ReminderScheduler.normalizeReminderIfNeeded(context, reminder)
        if (!normalized.enabled) return
        val active = normalized.copy(lastTriggeredAtMillis = System.currentTimeMillis(), followUpState = if (normalized.followUpState == "verification") "verification_active" else "firing")
        ReminderRepository.upsertReminder(context, active)
        ReminderForegroundService.start(context, reminderId)
    }
}
