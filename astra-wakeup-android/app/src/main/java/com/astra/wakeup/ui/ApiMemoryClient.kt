package com.astra.wakeup.ui

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object ApiMemoryClient {
    fun remember(apiUrl: String, text: String): Pair<Boolean, String> {
        if (text.isBlank()) return false to "blank"
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/memory").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply { put("text", text) }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) return false to "HTTP $code"
            true to "saved"
        }.getOrElse { false to (it.message ?: "network error") }
    }

    fun list(apiUrl: String): List<String> {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/memory").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            if (code !in 200..299) return emptyList()
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).optJSONArray("notes") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) add(arr.optString(i))
            }
        }.getOrElse { emptyList() }
    }

    fun clearAll(apiUrl: String): Boolean {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/memory").openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write("{\"all\":true}") }
            conn.responseCode in 200..299
        }.getOrElse { false }
    }
}
