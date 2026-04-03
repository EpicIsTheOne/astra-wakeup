package com.astra.wakeup.ui

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class OrganizerSnapshot(
    val reminders: List<ReminderItem>,
    val tasks: List<TaskBoardItem>
)

object ApiOrganizerClient {
    private fun organizerBase(apiUrl: String): String = ApiEndpoints.normalizeBase(apiUrl) + "/organizer"

    private fun parseSnapshot(text: String): OrganizerSnapshot {
        val json = JSONObject(text)
        val reminders = buildList {
            val arr = json.optJSONArray("reminders") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(ReminderItem.fromJson(item))
            }
        }
        val tasks = buildList {
            val arr = json.optJSONArray("tasks") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                add(TaskBoardItem.fromJson(item))
            }
        }
        return OrganizerSnapshot(reminders.sortedBy { it.scheduledTimeMillis }, tasks.sortedWith(compareBy<TaskBoardItem> { it.done }.thenBy { it.createdAtMillis }))
    }

    fun fetch(apiUrl: String): Result<OrganizerSnapshot> = runCatching {
        val conn = URL(organizerBase(apiUrl)).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error(if (body.isBlank()) "HTTP $code" else body)
        parseSnapshot(body)
    }

    fun upsertReminder(apiUrl: String, reminder: ReminderItem): Result<OrganizerSnapshot> = runCatching {
        val conn = URL(organizerBase(apiUrl) + "/reminders").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(reminder.toJson().toString()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error(if (body.isBlank()) "HTTP $code" else body)
        parseSnapshot(body)
    }

    fun deleteReminder(apiUrl: String, reminderId: String): Result<OrganizerSnapshot> = runCatching {
        val conn = URL(organizerBase(apiUrl) + "/reminders/" + reminderId).openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error(if (body.isBlank()) "HTTP $code" else body)
        parseSnapshot(body)
    }

    fun upsertTask(apiUrl: String, task: TaskBoardItem): Result<OrganizerSnapshot> = runCatching {
        val conn = URL(organizerBase(apiUrl) + "/tasks").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.doOutput = true
        OutputStreamWriter(conn.outputStream).use { it.write(task.toJson().toString()) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error(if (body.isBlank()) "HTTP $code" else body)
        parseSnapshot(body)
    }

    fun deleteTask(apiUrl: String, taskId: String): Result<OrganizerSnapshot> = runCatching {
        val conn = URL(organizerBase(apiUrl) + "/tasks/" + taskId).openConnection() as HttpURLConnection
        conn.requestMethod = "DELETE"
        conn.connectTimeout = 7000
        conn.readTimeout = 7000
        conn.setRequestProperty("Accept", "application/json")
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) error(if (body.isBlank()) "HTTP $code" else body)
        parseSnapshot(body)
    }
}
