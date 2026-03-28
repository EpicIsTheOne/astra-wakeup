package com.astra.wakeup.ui

import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class WakeLineResult(val line: String? = null, val mission: String? = null)
data class WakeFmResult(val script: String? = null)

object WakeMessageClient {
    fun fetchLineResult(apiUrl: String, punishment: Boolean): WakeLineResult? {
        if (apiUrl.isBlank()) return null
        return runCatching {
            val conn = URL(ApiEndpoints.line(apiUrl)).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("punishment", punishment)
                put("user", "Epic")
            }

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val text = BufferedReader(conn.inputStream.reader()).use { it.readText() }
            val json = JSONObject(text)
            WakeLineResult(
                line = json.optString("line").takeIf { it.isNotBlank() },
                mission = json.optString("mission").takeIf { it.isNotBlank() }
            )
        }.getOrNull()
    }

    fun fetchFmResult(apiUrl: String): WakeFmResult? {
        if (apiUrl.isBlank()) return null
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/fm").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 9000
            conn.readTimeout = 12000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("user", "Epic")
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val text = BufferedReader(conn.inputStream.reader()).use { it.readText() }
            val json = JSONObject(text)
            WakeFmResult(script = json.optString("script").takeIf { it.isNotBlank() })
        }.getOrNull()
    }

    fun fetchLine(apiUrl: String, punishment: Boolean): String? {
        return fetchLineResult(apiUrl, punishment)?.line
    }
}
