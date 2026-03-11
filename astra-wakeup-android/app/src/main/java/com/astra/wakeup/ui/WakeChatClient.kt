package com.astra.wakeup.ui

import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

data class ChatResult(val reply: String? = null, val error: String? = null)

object WakeChatClient {
    private fun postJson(url: String, body: JSONObject): ChatResult {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 8000
            conn.readTimeout = 12000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(stream.reader()).use { it.readText() }
            if (code !in 200..299) return ChatResult(error = "HTTP $code: ${text.take(120)}")
            val reply = JSONObject(text).optString("reply").takeIf { it.isNotBlank() }
            ChatResult(reply = reply, error = if (reply == null) "Empty reply" else null)
        }.getOrElse { ChatResult(error = it.message ?: "Network error") }
    }

    fun wakeReply(apiUrl: String, userText: String): String? {
        return wakeReplyDetailed(apiUrl, userText).reply
    }

    fun wakeReplyDetailed(apiUrl: String, userText: String): ChatResult {
        if (apiUrl.isBlank() || userText.isBlank()) return ChatResult(error = "Missing API URL or text")
        val chatUrl = ApiEndpoints.wakeRespond(apiUrl)
        val body = JSONObject().apply {
            put("user", "Epic")
            put("text", userText)
        }
        return postJson(chatUrl, body)
    }

    fun chatReply(apiUrl: String, userText: String): String? {
        return chatReplyDetailed(apiUrl, userText).reply
    }

    fun chatReplyDetailed(apiUrl: String, userText: String): ChatResult {
        if (apiUrl.isBlank() || userText.isBlank()) return ChatResult(error = "Missing API URL or text")
        val chatUrl = ApiEndpoints.chatRespond(apiUrl)
        val body = JSONObject().apply {
            put("user", "Epic")
            put("text", userText)
        }
        return postJson(chatUrl, body)
    }
}
