package com.astra.wakeup.ui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ApiAnalyticsClient {
    fun fetch(apiUrl: String): String {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/analytics").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            if (conn.responseCode !in 200..299) return "analytics unavailable (HTTP ${conn.responseCode})"
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(txt)
            val totals = j.getJSONObject("totals")
            val recent = j.optJSONArray("recent")
            val lines = mutableListOf<String>()
            lines += "Wake events: ${totals.optInt("wakeEvents")}" 
            lines += "Ack events: ${totals.optInt("ackEvents")}" 
            lines += "Ack rate: ${totals.optDouble("ackRate")}" 
            lines += ""
            lines += "Recent events:"
            if (recent != null) {
                for (i in 0 until recent.length()) {
                    val e = recent.getJSONObject(i)
                    lines += "- ${e.optString("at")} [${e.optString("type")}] ${e.optString("reason", "")}".trim()
                }
            }
            lines.joinToString("\n")
        }.getOrDefault("analytics unavailable")
    }
}
