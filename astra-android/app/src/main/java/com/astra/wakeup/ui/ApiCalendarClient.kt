package com.astra.wakeup.ui

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CronJobView(
    val id: String,
    val name: String,
    val schedule: String,
    val tz: String,
    val enabled: Boolean,
    val nextRun: String,
    val status: String
)

object ApiCalendarClient {
    private fun fmt(ms: Long?): String {
        if (ms == null || ms <= 0) return "-"
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
    }

    fun fetch(apiUrl: String): Pair<String, List<CronJobView>> {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/calendar").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            if (conn.responseCode !in 200..299) return "HTTP ${conn.responseCode}" to emptyList()
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(txt)
            val now = j.optString("now", "-")
            val arr = j.optJSONArray("jobs")
            val items = mutableListOf<CronJobView>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val it = arr.getJSONObject(i)
                    val sch = it.optJSONObject("schedule")
                    items += CronJobView(
                        id = it.optString("id", ""),
                        name = it.optString("name", "unnamed"),
                        schedule = sch?.optString("expr", "-") ?: "-",
                        tz = sch?.optString("tz", "-") ?: "-",
                        enabled = it.optBoolean("enabled", false),
                        nextRun = fmt(it.optLong("nextRunAtMs", 0L)),
                        status = it.optString("lastStatus", "-")
                    )
                }
            }
            now to items
        }.getOrElse { (it.message ?: "network error") to emptyList() }
    }

    fun create(apiUrl: String, name: String, cron: String, tz: String, message: String): Pair<Boolean, String> {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/cron/create").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("name", name)
                put("cron", cron)
                put("tz", tz)
                put("message", message)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) return false to "HTTP $code"
            true to "created"
        }.getOrElse { false to (it.message ?: "network error") }
    }

    fun edit(apiUrl: String, id: String, name: String?, cron: String?, tz: String?, message: String?): Pair<Boolean, String> {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/cron/edit").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("id", id)
                if (!name.isNullOrBlank()) put("name", name)
                if (!cron.isNullOrBlank()) put("cron", cron)
                if (!tz.isNullOrBlank()) put("tz", tz)
                if (!message.isNullOrBlank()) put("message", message)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) return false to "HTTP $code"
            true to "edited"
        }.getOrElse { false to (it.message ?: "network error") }
    }

    fun delete(apiUrl: String, id: String): Pair<Boolean, String> {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/cron/delete").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply { put("id", id) }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) return false to "HTTP $code"
            true to "deleted"
        }.getOrElse { false to (it.message ?: "network error") }
    }

    fun toggle(apiUrl: String, id: String, enabled: Boolean): Pair<Boolean, String> {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/cron/toggle").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("id", id)
                put("enabled", enabled)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) return false to "HTTP $code"
            true to if (enabled) "enabled" else "disabled"
        }.getOrElse { false to (it.message ?: "network error") }
    }

    fun runNow(apiUrl: String, id: String): Pair<Boolean, String> {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/cron/run").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 7000
            conn.readTimeout = 12000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply { put("id", id) }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) return false to "HTTP $code"
            true to "ran"
        }.getOrElse { false to (it.message ?: "network error") }
    }
}
