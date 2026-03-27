package com.astra.wakeup.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import java.time.ZoneId
import java.time.ZonedDateTime

object AlarmScheduler {
    private const val REQ = 550

    fun canScheduleExactAlarms(context: Context): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    fun exactAlarmSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = android.net.Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun scheduleDaily(context: Context, hour: Int = 5, minute: Int = 50): Boolean {
        if (!canScheduleExactAlarms(context)) return false

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = wakeIntent(context)

        val zone = ZoneId.of("America/New_York")
        var next = ZonedDateTime.now(zone)
            .withHour(hour)
            .withMinute(minute)
            .withSecond(0)
            .withNano(0)
        if (next.isBefore(ZonedDateTime.now(zone))) next = next.plusDays(1)

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            next.toInstant().toEpochMilli(),
            pending
        )
        context.getSharedPreferences("astra", Context.MODE_PRIVATE).edit().putInt("wake_snooze_count", 0).apply()
        return true
    }

    fun scheduleFromPrefs(context: Context): Boolean {
        val prefs = context.getSharedPreferences("astra", Context.MODE_PRIVATE)
        val hour = prefs.getInt("wake_hour", 5)
        val minute = prefs.getInt("wake_minute", 50)
        return scheduleDaily(context, hour, minute)
    }

    fun scheduleSnooze(context: Context, minutes: Int = 10): Boolean {
        if (!canScheduleExactAlarms(context)) return false
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = wakeIntent(context)
        val at = System.currentTimeMillis() + minutes * 60_000L
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        return true
    }

    private fun wakeIntent(context: Context): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            context,
            REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
