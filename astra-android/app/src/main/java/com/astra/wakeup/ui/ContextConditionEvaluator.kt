package com.astra.wakeup.ui

import java.util.Calendar

class ContextConditionEvaluator {
    fun evaluate(condition: RuleCondition, snapshot: ContextSnapshot, event: ContextEvent): Boolean {
        return when (condition.type.lowercase()) {
            "is_charging" -> snapshot.isCharging == condition.value.toBoolean()
            "headphones_connected" -> snapshot.headphonesConnected == condition.value.toBoolean()
            "after_alarm" -> {
                val mins = condition.value.toIntOrNull() ?: return false
                val last = snapshot.lastAlarmTriggeredAt ?: return false
                (event.timestampMs - last) <= mins * 60_000L
            }
            "time_range" -> inTimeRange(condition.value)
            "location_zone" -> snapshot.locationZoneId == condition.value
            "calendar_within_minutes" -> {
                val mins = condition.value.toIntOrNull() ?: return false
                val next = snapshot.nextCalendarEventMs ?: return false
                (next - event.timestampMs) in 0..(mins * 60_000L)
            }
            "alarm_dismissed_recent" -> {
                val mins = condition.value.toIntOrNull() ?: return false
                val last = snapshot.lastAlarmDismissedAt ?: return false
                (event.timestampMs - last) <= mins * 60_000L
            }
            else -> true
        }
    }

    private fun inTimeRange(raw: String): Boolean {
        val parts = raw.split("-")
        if (parts.size != 2) return true
        val now = Calendar.getInstance()
        val cur = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        fun parse(t: String): Int {
            val p = t.trim().split(":")
            val h = p.getOrNull(0)?.toIntOrNull() ?: 0
            val m = p.getOrNull(1)?.toIntOrNull() ?: 0
            return h * 60 + m
        }
        val start = parse(parts[0])
        val end = parse(parts[1])
        return if (start <= end) cur in start..end else (cur >= start || cur <= end)
    }
}
