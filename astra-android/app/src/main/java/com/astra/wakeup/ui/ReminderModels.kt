package com.astra.wakeup.ui

import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.UUID

data class ReminderItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val scheduledTimeMillis: Long,
    val importance: Int = 2,
    val annoyanceLevel: Int = 2,
    val verifyLater: Boolean = false,
    val repeatRule: String = "once",
    val enabled: Boolean = true,
    val snoozeCount: Int = 0,
    val followUpState: String = "none",
    val linkedTaskId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val lastTriggeredAtMillis: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("scheduledTimeMillis", scheduledTimeMillis)
        put("importance", importance)
        put("annoyanceLevel", annoyanceLevel)
        put("verifyLater", verifyLater)
        put("repeatRule", repeatRule)
        put("enabled", enabled)
        put("snoozeCount", snoozeCount)
        put("followUpState", followUpState)
        put("linkedTaskId", linkedTaskId ?: JSONObject.NULL)
        put("createdAtMillis", createdAtMillis)
        put("lastTriggeredAtMillis", lastTriggeredAtMillis)
    }

    fun summary(): String = "${title} · ${formatTimestamp(scheduledTimeMillis)}"

    companion object {
        fun fromJson(json: JSONObject): ReminderItem = ReminderItem(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = json.optString("title"),
            scheduledTimeMillis = json.optLong("scheduledTimeMillis"),
            importance = json.optInt("importance", 2),
            annoyanceLevel = json.optInt("annoyanceLevel", 2),
            verifyLater = json.optBoolean("verifyLater", false),
            repeatRule = json.optString("repeatRule", "once"),
            enabled = json.optBoolean("enabled", true),
            snoozeCount = json.optInt("snoozeCount", 0),
            followUpState = json.optString("followUpState", "none"),
            linkedTaskId = json.optString("linkedTaskId").takeIf { it.isNotBlank() && it != "null" },
            createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
            lastTriggeredAtMillis = json.optLong("lastTriggeredAtMillis", 0L)
        )
    }
}

data class TaskBoardItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val notes: String = "",
    val done: Boolean = false,
    val linkedReminderId: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("notes", notes)
        put("done", done)
        put("linkedReminderId", linkedReminderId ?: JSONObject.NULL)
        put("createdAtMillis", createdAtMillis)
        put("completedAtMillis", completedAtMillis)
    }

    companion object {
        fun fromJson(json: JSONObject): TaskBoardItem = TaskBoardItem(
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            title = json.optString("title"),
            notes = json.optString("notes"),
            done = json.optBoolean("done", false),
            linkedReminderId = json.optString("linkedReminderId").takeIf { it.isNotBlank() && it != "null" },
            createdAtMillis = json.optLong("createdAtMillis", System.currentTimeMillis()),
            completedAtMillis = json.optLong("completedAtMillis", 0L)
        )
    }
}

fun formatTimestamp(timestamp: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestamp))

fun importanceLabel(level: Int): String = when (level) {
    3 -> "Critical"
    2 -> "Important"
    else -> "Normal"
}

fun annoyanceLabel(level: Int): String = when (level) {
    3 -> "Chaotic"
    2 -> "Pushy"
    else -> "Gentle"
}
