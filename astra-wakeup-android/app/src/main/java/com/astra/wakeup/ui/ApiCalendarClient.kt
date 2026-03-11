package com.astra.wakeup.ui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CronJobView(
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
}
