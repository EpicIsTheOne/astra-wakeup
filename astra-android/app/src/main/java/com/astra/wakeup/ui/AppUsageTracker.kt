package com.astra.wakeup.ui

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

data class AppUsageSummary(
    val packageName: String,
    val totalMs: Long,
    val windowStartMs: Long,
    val windowEndMs: Long
)

object AppUsageTracker {
    fun foregroundApp(context: Context, lookbackMs: Long = 15_000L): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val end = System.currentTimeMillis()
        val start = end - lookbackMs
        val events = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        var currentPackage: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                currentPackage = event.packageName
            }
        }
        return currentPackage
    }

    fun usageInWindow(context: Context, packageName: String, windowMinutes: Int): AppUsageSummary {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return AppUsageSummary(packageName, 0L, 0L, 0L)
        val end = System.currentTimeMillis()
        val start = end - windowMinutes * 60_000L
        val events = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        var total = 0L
        var activeSince: Long? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> activeSince = event.timeStamp
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val started = activeSince
                    if (started != null && event.timeStamp > started) {
                        total += event.timeStamp - started
                    }
                    activeSince = null
                }
            }
        }

        activeSince?.let { started ->
            if (end > started) total += end - started
        }

        return AppUsageSummary(packageName, total, start, end)
    }

    fun hasUsageAccess(context: Context): Boolean {
        return runCatching { foregroundApp(context) != null }.getOrDefault(false)
    }
}
