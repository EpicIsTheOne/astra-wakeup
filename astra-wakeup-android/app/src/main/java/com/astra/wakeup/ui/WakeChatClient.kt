package com.astra.wakeup.ui

import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object WakeChatClient {
    fun reply(apiUrl: String, userText: String): String? {
        if (apiUrl.isBlank() || userText.isBlank()) return null
        val base = apiUrl.substringBefore("/api/wakeup/line")
        val chatUrl = if (base == apiUrl) "$apiUrl" else "$base/api/wakeup/respond"

        return runCatching {
            val conn = URL(chatUrl).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val body = JSONObject().apply {
                put("user", "Epic")
                put("text", userText)
            }
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

            val text = BufferedReader(conn.inputStream.reader()).use { it.readText() }
            JSONObject(text).optString("reply").takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
