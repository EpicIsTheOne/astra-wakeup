package com.astra.wakeup.ui

import android.content.Context
import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val GATEWAY_PROTOCOL_VERSION = 3
private const val ANDROID_GATEWAY_CLIENT_ID = "openclaw-android"
private const val ANDROID_GATEWAY_CLIENT_MODE = "ui"
private const val ANDROID_GATEWAY_ROLE = "operator"
private val ANDROID_GATEWAY_SCOPES = listOf("operator.read", "operator.write")

fun gatewayGrantedScopes(helloPayload: JSONObject?): Set<String> {
    if (helloPayload == null) return emptySet()
    val auth = helloPayload.optJSONObject("auth") ?: return emptySet()
    val scopes = auth.optJSONArray("scopes") ?: return emptySet()
    return buildSet {
        for (i in 0 until scopes.length()) {
            scopes.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

data class OpenClawGatewaySession(
    val protocol: Int,
    val helloPayload: JSONObject
)

data class GatewaySendAck(
    val runId: String? = null,
    val status: String? = null
)

data class GatewayChatResult(
    val ack: GatewaySendAck? = null,
    val reply: String? = null,
    val error: String? = null
)

data class GatewayHistoryResult(
    val messages: List<String> = emptyList(),
    val error: String? = null
)

class OpenClawGatewayTransport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
) {
    fun connect(
        config: OpenClawGatewayConfig,
        timeoutMs: Long = 12_000,
        onHello: ((JSONObject) -> Unit)? = null
    ): Result<OpenClawGatewaySession> = connectInternal(null, config, timeoutMs, onHello)

    fun connect(
        context: Context,
        config: OpenClawGatewayConfig,
        timeoutMs: Long = 12_000,
        onHello: ((JSONObject) -> Unit)? = null
    ): Result<OpenClawGatewaySession> = connectInternal(context, config, timeoutMs, onHello)

    fun sendChat(
        context: Context,
        config: OpenClawGatewayConfig,
        userText: String,
        timeoutMs: Long = 45_000,
        onHello: ((JSONObject) -> Unit)? = null
    ): GatewayChatResult {
        if (config.wsUrl.isBlank() || userText.isBlank()) return GatewayChatResult(error = "Missing Gateway URL or text")

        val resultRef = AtomicReference(GatewayChatResult())
        val replyBuffer = StringBuilder()
        val sendReqIdRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        val ws = client.newWebSocket(Request.Builder().url(config.wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "event" -> when (json.optString("event")) {
                        "connect.challenge" -> sendConnectFrame(context, webSocket, config, json.optJSONObject("payload")) { error ->
                            resultRef.set(resultRef.get().copy(error = error))
                            latch.countDown()
                        }
                        "chat" -> {
                            val payload = json.optJSONObject("payload") ?: return
                            extractChatText(payload)?.let { snapshot ->
                                if (snapshot.isNotBlank() && snapshot.length >= replyBuffer.length) {
                                    replyBuffer.clear()
                                    replyBuffer.append(snapshot)
                                    resultRef.set(resultRef.get().copy(reply = replyBuffer.toString()))
                                }
                            }
                            if (isChatTerminal(payload)) latch.countDown()
                        }
                    }
                    "res" -> {
                        when (json.optString("id")) {
                            CONNECT_REQ_ID -> {
                                if (json.optBoolean("ok")) {
                                    val payload = json.optJSONObject("payload") ?: JSONObject()
                                    onHello?.invoke(payload)
                                    val sendReqId = randomReqId("chat-send")
                                    sendReqIdRef.set(sendReqId)
                                    webSocket.send(chatSendFrame(sendReqId, config.sessionKey, userText).toString())
                                } else {
                                    resultRef.set(resultRef.get().copy(error = errorMessage(json, "Gateway connect failed")))
                                    latch.countDown()
                                }
                            }
                            sendReqIdRef.get() -> {
                                if (json.optBoolean("ok")) {
                                    val payload = json.optJSONObject("payload") ?: JSONObject()
                                    resultRef.set(resultRef.get().copy(
                                        ack = GatewaySendAck(
                                            runId = payload.optString("runId").takeIf { it.isNotBlank() },
                                            status = payload.optString("status").takeIf { it.isNotBlank() }
                                        )
                                    ))
                                } else {
                                    resultRef.set(resultRef.get().copy(error = errorMessage(json, "chat.send failed")))
                                    latch.countDown()
                                }
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                resultRef.set(resultRef.get().copy(error = t.message ?: "Gateway connection failed"))
                latch.countDown()
            }
        })

        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                resultRef.get().copy(error = resultRef.get().error ?: "Timed out waiting for chat reply")
            } else {
                resultRef.get().let { current ->
                    if (current.reply == null && current.error == null) current.copy(error = "Empty chat reply") else current
                }
            }
        } finally {
            ws.close(1000, "chat complete")
        }
    }

    fun fetchHistory(
        context: Context,
        config: OpenClawGatewayConfig,
        timeoutMs: Long = 15_000,
        onHello: ((JSONObject) -> Unit)? = null
    ): GatewayHistoryResult {
        if (config.wsUrl.isBlank()) return GatewayHistoryResult(error = "Missing Gateway URL")

        val resultRef = AtomicReference(GatewayHistoryResult())
        val historyReqIdRef = AtomicReference<String?>(null)
        val latch = CountDownLatch(1)

        val ws = client.newWebSocket(Request.Builder().url(config.wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "event" -> if (json.optString("event") == "connect.challenge") {
                        sendConnectFrame(context, webSocket, config, json.optJSONObject("payload")) { error ->
                            resultRef.set(GatewayHistoryResult(error = error))
                            latch.countDown()
                        }
                    }
                    "res" -> {
                        when (json.optString("id")) {
                            CONNECT_REQ_ID -> {
                                if (json.optBoolean("ok")) {
                                    val payload = json.optJSONObject("payload") ?: JSONObject()
                                    onHello?.invoke(payload)
                                    val historyReqId = randomReqId("chat-history")
                                    historyReqIdRef.set(historyReqId)
                                    webSocket.send(chatHistoryFrame(historyReqId, config.sessionKey).toString())
                                } else {
                                    resultRef.set(GatewayHistoryResult(error = errorMessage(json, "Gateway connect failed")))
                                    latch.countDown()
                                }
                            }
                            historyReqIdRef.get() -> {
                                if (json.optBoolean("ok")) {
                                    val payload = json.optJSONObject("payload") ?: JSONObject()
                                    resultRef.set(GatewayHistoryResult(messages = extractHistoryMessages(payload)))
                                } else {
                                    resultRef.set(GatewayHistoryResult(error = errorMessage(json, "chat.history failed")))
                                }
                                latch.countDown()
                            }
                        }
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                resultRef.set(GatewayHistoryResult(error = t.message ?: "Gateway connection failed"))
                latch.countDown()
            }
        })

        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                resultRef.get().copy(error = resultRef.get().error ?: "Timed out waiting for chat history")
            } else {
                resultRef.get()
            }
        } finally {
            ws.close(1000, "history complete")
        }
    }

    private fun connectInternal(
        context: Context?,
        config: OpenClawGatewayConfig,
        timeoutMs: Long,
        onHello: ((JSONObject) -> Unit)?
    ): Result<OpenClawGatewaySession> {
        if (config.wsUrl.isBlank()) return Result.failure(IllegalArgumentException("Missing Gateway URL"))

        val resultRef = AtomicReference<Result<OpenClawGatewaySession>?>(null)
        val latch = CountDownLatch(1)

        val ws = client.newWebSocket(Request.Builder().url(config.wsUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("type")) {
                    "event" -> if (json.optString("event") == "connect.challenge") {
                        sendConnectFrame(context, webSocket, config, json.optJSONObject("payload")) { error ->
                            resultRef.set(Result.failure(IllegalStateException(error)))
                            latch.countDown()
                        }
                    }
                    "res" -> if (json.optString("id") == CONNECT_REQ_ID) {
                        if (json.optBoolean("ok")) {
                            val payload = json.optJSONObject("payload") ?: JSONObject()
                            context?.let {
                                OpenClawGatewayDiagnostics.recordHandshake(
                                    context = it,
                                    stage = "connect_hello_ok",
                                    config = config,
                                    helloPayload = payload
                                )
                            }
                            onHello?.invoke(payload)
                            resultRef.set(Result.success(OpenClawGatewaySession(payload.optInt("protocol", GATEWAY_PROTOCOL_VERSION), payload)))
                        } else {
                            val message = errorMessage(json, "Gateway connect failed")
                            context?.let {
                                OpenClawGatewayDiagnostics.recordHandshake(
                                    context = it,
                                    stage = "connect_res_error",
                                    config = config,
                                    error = message,
                                    extra = JSONObject().put("frame", json.toString().take(600))
                                )
                            }
                            resultRef.set(Result.failure(IllegalStateException(message)))
                        }
                        latch.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                context?.let {
                    OpenClawGatewayDiagnostics.recordHandshake(
                        context = it,
                        stage = "socket_failure",
                        config = config,
                        error = t.message ?: "Gateway connection failed",
                        extra = JSONObject().put("responseCode", response?.code ?: -1)
                    )
                }
                resultRef.compareAndSet(null, Result.failure(t))
                latch.countDown()
            }
        })

        return try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Result.failure(IllegalStateException("Timed out waiting for Gateway handshake"))
            } else {
                resultRef.get() ?: Result.failure(IllegalStateException("Gateway handshake aborted"))
            }
        } finally {
            ws.close(1000, "connect complete")
        }
    }

    private fun sendConnectFrame(
        context: Context?,
        webSocket: WebSocket,
        config: OpenClawGatewayConfig,
        challengePayload: JSONObject?,
        onError: (String) -> Unit
    ) {
        val nonce = challengePayload?.optString("nonce").orEmpty()
        val frame = buildConnectFrame(context, config, nonce).getOrElse { err ->
            context?.let { appContext ->
                OpenClawGatewayDiagnostics.recordHandshake(
                    context = appContext,
                    stage = "build_connect_failed",
                    config = config,
                    nonce = nonce,
                    error = err.message ?: "Failed to build connect frame"
                )
            }
            onError(err.message ?: "Failed to build connect frame")
            return
        }
        context?.let {
            OpenClawGatewayDiagnostics.recordHandshake(
                context = it,
                stage = "connect_frame_sent",
                config = config,
                nonce = nonce,
                connectParams = frame.optJSONObject("params")
            )
        }
        val sent = webSocket.send(frame.toString())
        if (!sent) {
            context?.let {
                OpenClawGatewayDiagnostics.recordHandshake(
                    context = it,
                    stage = "connect_frame_send_failed",
                    config = config,
                    nonce = nonce,
                    connectParams = frame.optJSONObject("params"),
                    error = "Failed to send connect frame"
                )
            }
            onError("Failed to send connect frame")
        }
    }

    private fun buildConnectFrame(context: Context?, config: OpenClawGatewayConfig, nonce: String): Result<JSONObject> {
        return runCatching {
            val resolvedAuth = config.resolvedAuth()
            val params = JSONObject().apply {
                put("minProtocol", GATEWAY_PROTOCOL_VERSION)
                put("maxProtocol", GATEWAY_PROTOCOL_VERSION)
                put("client", JSONObject().apply {
                    put("id", ANDROID_GATEWAY_CLIENT_ID)
                    put("displayName", "Astra Wakeup Android")
                    put("version", "0.2.0")
                    put("platform", "android")
                    put("deviceFamily", Build.MODEL ?: "android")
                    put("mode", ANDROID_GATEWAY_CLIENT_MODE)
                })
                put("role", ANDROID_GATEWAY_ROLE)
                put("scopes", JSONArray(ANDROID_GATEWAY_SCOPES))
                put("caps", JSONArray())
                put("commands", JSONArray())
                put("permissions", JSONObject())
                put("locale", java.util.Locale.getDefault().toLanguageTag())
                put("userAgent", "astra-android/0.2.0")
            }

            resolvedAuth.payload?.let { params.put("auth", it) }
            context?.let {
                buildDevicePayload(it, resolvedAuth, nonce)?.let { device -> params.put("device", device) }
            }

            JSONObject().apply {
                put("type", "req")
                put("id", CONNECT_REQ_ID)
                put("method", "connect")
                put("params", params)
            }
        }
    }

    private fun buildDevicePayload(context: Context, resolvedAuth: GatewayResolvedAuth, nonce: String): JSONObject? {
        val signatureToken = resolvedAuth.signatureToken
        val signatureVersion = if (resolvedAuth.mode == GatewayAuthMode.SHARED_TOKEN) {
            OpenClawDeviceSignatureVersion.V2
        } else {
            OpenClawDeviceSignatureVersion.V3
        }
        val signed = OpenClawGatewayCrypto.signConnectChallenge(
            context = context,
            clientId = ANDROID_GATEWAY_CLIENT_ID,
            clientMode = ANDROID_GATEWAY_CLIENT_MODE,
            role = ANDROID_GATEWAY_ROLE,
            scopes = ANDROID_GATEWAY_SCOPES,
            nonce = nonce,
            platform = "android",
            deviceFamily = Build.MODEL ?: "android",
            signatureToken = signatureToken,
            signatureVersion = signatureVersion
        ).getOrElse { return null }

        return JSONObject().apply {
            put("id", signed.deviceId)
            put("publicKey", signed.publicKey)
            put("signature", signed.signature)
            put("signedAt", signed.signedAtMs)
            put("nonce", signed.nonce)
            put("signatureVersion", signed.version.name)
        }
    }

    private fun chatSendFrame(reqId: String, sessionKey: String, userText: String): JSONObject {
        return JSONObject().apply {
            put("type", "req")
            put("id", reqId)
            put("method", "chat.send")
            put("params", JSONObject().apply {
                put("sessionKey", sessionKey)
                put("message", userText)
                put("idempotencyKey", UUID.randomUUID().toString())
            })
        }
    }

    private fun chatHistoryFrame(reqId: String, sessionKey: String): JSONObject {
        return JSONObject().apply {
            put("type", "req")
            put("id", reqId)
            put("method", "chat.history")
            put("params", JSONObject().apply {
                put("sessionKey", sessionKey)
            })
        }
    }

    private fun extractChatText(payload: JSONObject): String? {
        val message = payload.opt("message")
        extractRenderableText(message)?.let { return it }
        return payload.optString("errorMessage").takeIf { it.isNotBlank() }
    }

    private fun extractRenderableText(message: Any?): String? {
        val obj = message as? JSONObject ?: return null
        obj.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        obj.optJSONArray("content")?.let { content ->
            val parts = mutableListOf<String>()
            for (i in 0 until content.length()) {
                val entry = content.optJSONObject(i) ?: continue
                when (entry.optString("type")) {
                    "text", "output_text" -> entry.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
                    "thinking" -> entry.optString("thinking").takeIf { it.isNotBlank() }?.let(parts::add)
                    "input_text" -> entry.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
                }
            }
            if (parts.isNotEmpty()) return parts.joinToString(separator = "")
        }
        obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun extractHistoryMessages(payload: JSONObject): List<String> {
        val items = mutableListOf<String>()
        val arrays = listOfNotNull(
            payload.optJSONArray("items"),
            payload.optJSONArray("messages"),
            payload.optJSONArray("entries")
        )
        for (array in arrays) {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val role = item.optString("role").ifBlank { item.optString("type") }.ifBlank { "message" }
                val text = item.optString("text")
                    .ifBlank { extractRenderableText(item.opt("message")).orEmpty() }
                    .ifBlank { item.optString("content") }
                if (text.isNotBlank()) items += "$role: $text"
            }
            if (items.isNotEmpty()) break
        }
        return items
    }

    private fun isChatTerminal(payload: JSONObject): Boolean {
        return when (payload.optString("state")) {
            "final", "aborted", "error" -> true
            else -> payload.optBoolean("done", false)
        }
    }

    private fun errorMessage(frame: JSONObject, fallback: String): String {
        val error = frame.optJSONObject("error")
        return error?.optString("message")?.takeIf { it.isNotBlank() }
            ?: frame.optString("error").takeIf { it.isNotBlank() }
            ?: error?.optJSONObject("details")?.optString("code")?.takeIf { it.isNotBlank() }
            ?: fallback
    }

    private fun randomReqId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

    companion object {
        private const val CONNECT_REQ_ID = "connect-bootstrap"
    }
}
