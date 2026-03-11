package com.astra.wakeup.ui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ApiSuiteStatus(
    val ok: Boolean,
    val summary: String,
    val details: String
)

object ApiStatusClient {
    private fun get(url: String): Pair<Boolean, String> {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().use { it.readText() }
            if (code !in 200..299) return false to "HTTP $code"
            val ok = runCatching { JSONObject(body).optBoolean("ok", true) }.getOrDefault(true)
            ok to if (ok) "ok" else "bad body"
        }.getOrElse { false to (it.message ?: "network error") }
    }

    fun check(apiUrl: String): Pair<Boolean, String> {
        if (apiUrl.isBlank()) return false to "No API URL"
        val (ok, msg) = get(ApiEndpoints.health(apiUrl))
        return ok to msg
    }

    fun checkSuite(apiUrl: String): ApiSuiteStatus {
        if (apiUrl.isBlank()) return ApiSuiteStatus(false, "offline ❌", "No API URL")

        val (hOk, hMsg) = get(ApiEndpoints.health(apiUrl))
        val lineOk = WakeMessageClient.fetchLine(apiUrl, punishment = false) != null
        val chat = WakeChatClient.chatReplyDetailed(apiUrl, "ping")
        val chatOk = !chat.reply.isNullOrBlank()

        val allOk = hOk && lineOk && chatOk
        val details = "health=${if (hOk) "ok" else hMsg}, line=${if (lineOk) "ok" else "fail"}, chat=${if (chatOk) "ok" else (chat.error ?: "fail")}".take(180)
        val summary = if (allOk) "connected ✅" else "partial/offline ❌"
        return ApiSuiteStatus(allOk, summary, details)
    }
}
