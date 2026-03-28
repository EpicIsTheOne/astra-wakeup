package com.astra.wakeup.ui

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import kotlin.math.abs

object ReminderScheduler {
    private fun pendingIntent(context: Context, reminderId: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra("reminder_id", reminderId)
        return PendingIntent.getBroadcast(
            context,
            abs(reminderId.hashCode()),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun scheduleReminder(context: Context, reminder: ReminderItem) {
        cancelReminder(context, reminder.id)
        if (!reminder.enabled || reminder.scheduledTimeMillis <= 0L) return
        if (!com.astra.wakeup.alarm.AlarmScheduler.canScheduleExactAlarms(context)) return
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.scheduledTimeMillis,
            pendingIntent(context, reminder.id)
        )
    }

    fun cancelReminder(context: Context, reminderId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent(context, reminderId))
    }

    fun rescheduleAll(context: Context) {
        ReminderRepository.listReminders(context).forEach { scheduleReminder(context, normalizedReminder(it)) }
    }

    fun normalizeReminderIfNeeded(context: Context, reminder: ReminderItem): ReminderItem {
        val normalized = normalizedReminder(reminder)
        if (normalized != reminder) ReminderRepository.saveReminders(
            context,
            ReminderRepository.listReminders(context).map { if (it.id == normalized.id) normalized else it }
        )
        return normalized
    }

    private fun normalizedReminder(reminder: ReminderItem): ReminderItem {
        val now = System.currentTimeMillis()
        if (reminder.scheduledTimeMillis > now || !reminder.enabled) return reminder
        return when (reminder.repeatRule) {
            "daily" -> reminder.copy(scheduledTimeMillis = nextByDays(reminder.scheduledTimeMillis, 1), snoozeCount = 0, followUpState = "scheduled")
            "weekly" -> reminder.copy(scheduledTimeMillis = nextByDays(reminder.scheduledTimeMillis, 7), snoozeCount = 0, followUpState = "scheduled")
            else -> reminder
        }
    }

    private fun nextByDays(start: Long, days: Int): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = start
            while (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, days)
        }
        return calendar.timeInMillis
    }

    fun computeLaterTime(reminder: ReminderItem): Long {
        val baseMinutes = when (reminder.importance + reminder.annoyanceLevel) {
            in 5..6 -> 7
            4 -> 12
            else -> 18
        }
        val extra = reminder.snoozeCount.coerceAtMost(3) * 4
        return System.currentTimeMillis() + (baseMinutes + extra) * 60_000L
    }

    fun maybeCreateVerificationFollowUp(reminder: ReminderItem): ReminderItem? {
        val shouldFollowUp = reminder.verifyLater || reminder.importance >= 3 || reminder.annoyanceLevel >= 3
        if (!shouldFollowUp) return null
        val delayMinutes = when {
            reminder.importance >= 3 && reminder.annoyanceLevel >= 3 -> 4
            reminder.importance >= 3 -> 6
            else -> 9
        }
        return reminder.copy(
            scheduledTimeMillis = System.currentTimeMillis() + delayMinutes * 60_000L,
            followUpState = "verification",
            snoozeCount = 0,
            enabled = true,
            repeatRule = "once"
        )
    }
}
