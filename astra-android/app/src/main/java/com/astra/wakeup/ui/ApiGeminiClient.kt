package com.astra.wakeup.ui

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeminiRuntimeConfig(
    val ok: Boolean,
    val hasApiKey: Boolean,
    val model: String,
    val thinkingLevel: String,
    val responseModalities: List<String>,
    val transport: String,
    val error: String? = null,
)

object ApiGeminiClient {
    private fun commandCenterBase(apiUrl: String): String {
        val trimmed = apiUrl.trim().trimEnd('/')
        return when {
            trimmed.isBlank() -> ""
            trimmed.contains("/commandcenter") -> trimmed.substringBefore("/commandcenter") + "/commandcenter"
            trimmed.contains("/missioncontrol") -> trimmed.substringBefore("/missioncontrol") + "/commandcenter"
            trimmed.contains("/aichat") -> trimmed.substringBefore("/aichat") + "/commandcenter"
            trimmed.contains("/api/") -> trimmed.substringBefore("/api/") + "/commandcenter"
            else -> "$trimmed/commandcenter"
        }
    }

    fun loadRuntimeConfig(apiUrl: String): GeminiRuntimeConfig {
        val base = commandCenterBase(apiUrl)
        if (base.isBlank()) {
            return GeminiRuntimeConfig(false, false, "gemini-3.1-flash-live-preview", "minimal", listOf("AUDIO"), "websocket-proxy-pending", "Missing API URL")
        }
        return runCatching {
            val conn = URL("$base/api/live/config").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                return GeminiRuntimeConfig(false, false, "gemini-3.1-flash-live-preview", "minimal", listOf("AUDIO"), "websocket-proxy-pending", "HTTP $code")
            }
            val json = JSONObject(body)
            val config = json.optJSONObject("config") ?: JSONObject()
            val modalitiesJson = config.optJSONArray("responseModalities")
            val modalities = buildList {
                if (modalitiesJson != null) {
                    for (i in 0 until modalitiesJson.length()) add(modalitiesJson.optString(i))
                }
            }.ifEmpty { listOf("AUDIO") }
            GeminiRuntimeConfig(
                ok = json.optBoolean("ok", true),
                hasApiKey = config.optBoolean("hasApiKey", false),
                model = config.optString("model").ifBlank { "gemini-3.1-flash-live-preview" },
                thinkingLevel = config.optString("thinkingLevel").ifBlank { "minimal" },
                responseModalities = modalities,
                transport = config.optString("transport").ifBlank { "websocket-proxy-pending" },
                error = json.optString("error").takeIf { it.isNotBlank() },
            )
        }.getOrElse {
            GeminiRuntimeConfig(false, false, "gemini-3.1-flash-live-preview", "minimal", listOf("AUDIO"), "websocket-proxy-pending", it.message ?: "network error")
        }
    }
}
