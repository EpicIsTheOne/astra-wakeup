package com.astra.wakeup.ui

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiOpsClient {
    fun releaseNotes(apiUrl: String): String {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/release-notes").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode !in 200..299) return "No release notes (HTTP ${conn.responseCode})"
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(txt).optString("notes", "No release notes")
        }.getOrDefault("No release notes")
    }

    fun metrics(apiUrl: String): String {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/metrics").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            if (conn.responseCode !in 200..299) return "metrics unavailable"
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(txt)
            "streak=${j.optInt("streak")}, profile=${j.optString("wakeProfile")}, memory=${j.optInt("memoryCount")}"
        }.getOrDefault("metrics unavailable")
    }

    fun log(apiUrl: String, level: String, message: String) {
        runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/log").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("level", level)
                put("message", message.take(400))
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            conn.responseCode
        }
    }
}
