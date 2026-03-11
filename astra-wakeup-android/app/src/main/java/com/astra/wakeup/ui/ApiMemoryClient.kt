package com.astra.wakeup.ui

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class MemoryNote(
    val text: String,
    val category: String = "misc",
    val at: String = ""
)

object ApiMemoryClient {
    fun remember(apiUrl: String, text: String, category: String = "preference"): Pair<Boolean, String> {
        if (text.isBlank()) return false to "blank"
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/memory").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("text", text)
                put("category", category)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            if (code !in 200..299) return false to "HTTP $code"
            true to "saved"
        }.getOrElse { false to (it.message ?: "network error") }
    }

    fun list(apiUrl: String, category: String? = null): List<MemoryNote> {
        return runCatching {
            val suffix = if (category.isNullOrBlank()) "" else "?category=$category"
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/memory$suffix").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            val code = conn.responseCode
            if (code !in 200..299) return emptyList()
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(txt).optJSONArray("notes") ?: return emptyList()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i)
                    if (obj != null) {
                        add(MemoryNote(
                            text = obj.optString("text"),
                            category = obj.optString("category", "misc"),
                            at = obj.optString("at", "")
                        ))
                    } else {
                        val s = arr.optString(i)
                        if (s.isNotBlank()) add(MemoryNote(text = s))
                    }
                }
            }
        }.getOrElse { emptyList() }
    }

    fun deleteIndex(apiUrl: String, index: Int): Boolean {
        return runCatching {
            val conn = URL(ApiEndpoints.normalizeBase(apiUrl) + "/memory").openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write("{\"index\":$index}") }
            conn.responseCode in 200..299
        }.getOrElse { false }
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
