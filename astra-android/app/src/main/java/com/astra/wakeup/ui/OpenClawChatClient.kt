package com.astra.wakeup.ui

import android.content.Context

data class OpenClawChatResult(val reply: String? = null, val error: String? = null)

class OpenClawChatClient(
    private val transport: OpenClawGatewayTransport = OpenClawGatewayTransport()
) {
    private fun shouldUseBridge(config: OpenClawGatewayConfig): Boolean {
        val status = OpenClawBridgeClient.status(config.httpBaseUrl)
        return status.ok && status.connected
    }
    private val staleDeviceTokenMarkers = listOf(
        "device token mismatch",
        "AUTH_DEVICE_TOKEN_MISMATCH"
    )

    fun chat(context: Context, userText: String): OpenClawChatResult {
        val config = OpenClawGatewayConfig.fromContext(context)
        return chat(context, config, userText)
    }

    fun chat(config: OpenClawGatewayConfig, userText: String): OpenClawChatResult {
        return OpenClawChatResult(error = "Context-aware Gateway auth required for Android direct mode")
    }

    fun chat(context: Context, config: OpenClawGatewayConfig, userText: String): OpenClawChatResult {
        if (shouldUseBridge(config)) {
            val bridged = OpenClawBridgeRealtimeClient.sendAndAwaitReply(
                apiUrl = config.httpBaseUrl,
                text = userText,
                agent = config.sessionKey
            )
            return OpenClawChatResult(
                reply = bridged.reply,
                error = bridged.error
            )
        }

        val first = transport.sendChat(context, config, userText) { helloPayload ->
            OpenClawGatewayAuthStore.persistHelloAuth(context, helloPayload)
        }
        if (shouldClearStaleDeviceToken(first.error)) {
            OpenClawGatewayAuthStore.clearDeviceToken(context)
            val retryConfig = OpenClawGatewayConfig.fromContext(context)
            val retried = transport.sendChat(context, retryConfig, userText) { helloPayload ->
                OpenClawGatewayAuthStore.persistHelloAuth(context, helloPayload)
            }
            return OpenClawChatResult(
                reply = retried.reply,
                error = retried.error?.let { "$it (after stale device-token reset)" } ?: retried.error
            )
        }
        return OpenClawChatResult(
            reply = first.reply,
            error = first.error
        )
    }

    fun history(context: Context, sessionKey: String? = null): GatewayHistoryResult {
        val baseConfig = OpenClawGatewayConfig.fromContext(context)
        val config = if (sessionKey.isNullOrBlank()) baseConfig else baseConfig.copy(sessionKey = sessionKey)
        val first = transport.fetchHistory(context, config) { helloPayload ->
            OpenClawGatewayAuthStore.persistHelloAuth(context, helloPayload)
        }
        if (shouldClearStaleDeviceToken(first.error)) {
            OpenClawGatewayAuthStore.clearDeviceToken(context)
            val retryConfig = OpenClawGatewayConfig.fromContext(context)
            return transport.fetchHistory(context, retryConfig) { helloPayload ->
                OpenClawGatewayAuthStore.persistHelloAuth(context, helloPayload)
            }
        }
        return first
    }

    fun probe(context: Context): Result<OpenClawGatewaySession> {
        val config = OpenClawGatewayConfig.fromContext(context)
        if (shouldUseBridge(config)) {
            val status = OpenClawBridgeClient.status(config.httpBaseUrl)
            return if (status.ok && status.connected) {
                Result.success(OpenClawGatewaySession(3, org.json.JSONObject().apply {
                    put("type", "hello-ok")
                    put("protocol", 3)
                    put("auth", org.json.JSONObject().put("mode", "bridge"))
                    put("server", org.json.JSONObject().put("version", status.mode).put("connId", "commandcenter-bridge"))
                }))
            } else {
                Result.failure(IllegalStateException(status.error ?: "Command Center bridge unavailable"))
            }
        }
        val first = transport.connect(context, config) { helloPayload ->
            OpenClawGatewayAuthStore.persistHelloAuth(context, helloPayload)
        }
        if (first.isFailure && shouldClearStaleDeviceToken(first.exceptionOrNull()?.message)) {
            OpenClawGatewayAuthStore.clearDeviceToken(context)
            val retryConfig = OpenClawGatewayConfig.fromContext(context)
            return transport.connect(context, retryConfig) { helloPayload ->
                OpenClawGatewayAuthStore.persistHelloAuth(context, helloPayload)
            }
        }
        return first
    }

    fun probe(config: OpenClawGatewayConfig): Result<OpenClawGatewaySession> = transport.connect(config)

    private fun shouldClearStaleDeviceToken(error: String?): Boolean {
        val normalized = error?.lowercase().orEmpty()
        return staleDeviceTokenMarkers.any { marker -> normalized.contains(marker.lowercase()) }
    }
}
