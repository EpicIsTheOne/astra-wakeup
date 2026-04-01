package com.astra.wakeup.ui

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class AstraCallSession(
    val id: String,
    val state: String,
    val agent: String,
)

data class AstraCallStartResult(
    val ok: Boolean,
    val session: AstraCallSession? = null,
    val error: String? = null,
    val debug: String? = null,
)

data class AstraCallSessionLookupResult(
    val ok: Boolean,
    val session: AstraCallSession? = null,
    val error: String? = null,
)

object AstraCallSessionClient {
    private val httpClient = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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

    private fun wsUrl(apiUrl: String): String {
        val base = commandCenterBase(apiUrl)
        return when {
            base.startsWith("https://") -> base.replaceFirst("https://", "wss://") + "/ws"
            base.startsWith("http://") -> base.replaceFirst("http://", "ws://") + "/ws"
            else -> "$base/ws"
        }
    }

    fun startCall(apiUrl: String, agent: String? = null): AstraCallStartResult {
        val base = commandCenterBase(apiUrl)
        val url = if (base.isBlank()) "" else "$base/api/call/start"
        if (base.isBlank()) return AstraCallStartResult(false, error = "Missing API URL", debug = "apiUrl=$apiUrl | base=<blank>")
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.bufferedWriter().use { writer ->
                writer.write(JSONObject().apply {
                    if (!agent.isNullOrBlank()) put("agent", agent)
                }.toString())
            }
            val code = conn.responseCode
            val finalUrl = conn.url?.toString().orEmpty()
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            val debug = buildString {
                append("apiUrl=").append(apiUrl)
                append(" | base=").append(base)
                append(" | url=").append(url)
                append(" | finalUrl=").append(finalUrl)
                append(" | http=").append(code)
                if (body.isNotBlank()) append(" | body=").append(body.take(240).replace('\n', ' '))
            }
            if (code !in 200..299) {
                return AstraCallStartResult(false, error = json.optString("error").ifBlank { "HTTP $code" }, debug = debug)
            }
            val session = json.optJSONObject("session") ?: JSONObject()
            AstraCallStartResult(
                ok = true,
                session = AstraCallSession(
                    id = session.optString("id"),
                    state = session.optString("state").ifBlank { "ready" },
                    agent = session.optString("agent").ifBlank { agent ?: "orchestrator" },
                ),
                debug = debug,
            )
        }.getOrElse {
            AstraCallStartResult(
                false,
                error = it.message ?: "network error",
                debug = "apiUrl=$apiUrl | base=$base | url=$url | exception=${it::class.java.simpleName}: ${it.message}"
            )
        }
    }

    fun getCallSession(apiUrl: String, sessionId: String): AstraCallSessionLookupResult {
        val base = commandCenterBase(apiUrl)
        if (base.isBlank() || sessionId.isBlank()) {
            return AstraCallSessionLookupResult(false, error = "Missing call session lookup inputs")
        }
        val request = Request.Builder()
            .url("$base/api/call/$sessionId")
            .get()
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
                if (!response.isSuccessful) {
                    return AstraCallSessionLookupResult(false, error = json.optString("error").ifBlank { "HTTP ${response.code}" })
                }
                val session = json.optJSONObject("session") ?: JSONObject()
                AstraCallSessionLookupResult(
                    ok = true,
                    session = AstraCallSession(
                        id = session.optString("id"),
                        state = session.optString("state").ifBlank { "ready" },
                        agent = session.optString("agent").ifBlank { "orchestrator" },
                    ),
                )
            }
        }.getOrElse {
            AstraCallSessionLookupResult(false, error = it.message ?: "network error")
        }
    }

    fun sendSessionEvent(apiUrl: String, sessionId: String, type: String, text: String? = null) {
        val base = commandCenterBase(apiUrl)
        if (base.isBlank() || sessionId.isBlank()) return
        val body = JSONObject().apply {
            put("type", type)
            if (!text.isNullOrBlank()) put("text", text)
        }
        val request = Request.Builder()
            .url("$base/api/call/$sessionId/event")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        runCatching { httpClient.newCall(request).execute().close() }
    }

    fun sendAudioChunk(
        apiUrl: String,
        sessionId: String,
        pcm16Base64: String,
        mimeType: String = "audio/pcm;rate=16000",
    ): Result<String> {
        val base = commandCenterBase(apiUrl)
        if (base.isBlank() || sessionId.isBlank() || pcm16Base64.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing audio upload inputs"))
        }
        val body = JSONObject().apply {
            put("pcm16Base64", pcm16Base64)
            put("mimeType", mimeType)
        }
        val request = Request.Builder()
            .url("$base/api/call/$sessionId/audio")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: ${text.take(160)}")
                }
                text
            }
        }
    }

    fun sendScreenFrame(
        apiUrl: String,
        sessionId: String,
        jpegBase64: String,
        mimeType: String = "image/jpeg",
    ): Result<String> {
        val base = commandCenterBase(apiUrl)
        if (base.isBlank() || sessionId.isBlank() || jpegBase64.isBlank()) {
            return Result.failure(IllegalArgumentException("Missing screen upload inputs"))
        }
        val body = JSONObject().apply {
            put("jpegBase64", jpegBase64)
            put("mimeType", mimeType)
        }
        val request = Request.Builder()
            .url("$base/api/call/$sessionId/screen")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IllegalStateException("HTTP ${response.code}: ${text.take(160)}")
                }
                text
            }
        }
    }

    fun endCall(apiUrl: String, sessionId: String) {
        val base = commandCenterBase(apiUrl)
        if (base.isBlank() || sessionId.isBlank()) return
        val request = Request.Builder()
            .url("$base/api/call/$sessionId/end")
            .post("{}".toRequestBody(jsonMediaType))
            .build()
        runCatching { httpClient.newCall(request).execute().close() }
    }

    fun websocketUrl(apiUrl: String): String = wsUrl(apiUrl)
}
