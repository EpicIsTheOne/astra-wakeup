package com.astra.wakeup.ui

import android.content.Context

data class OpenClawChatResult(val reply: String? = null, val error: String? = null)

class OpenClawChatClient {
    private fun bridgeStatus(config: OpenClawGatewayConfig): OpenClawBridgeStatus =
        OpenClawBridgeClient.status(config.httpBaseUrl)

    fun chat(context: Context, userText: String): OpenClawChatResult {
        val config = OpenClawGatewayConfig.fromContext(context)
        return chat(context, config, userText)
    }

    fun chat(config: OpenClawGatewayConfig, userText: String): OpenClawChatResult {
        return OpenClawChatResult(error = "Context-aware Gateway auth required for Android direct mode")
    }

    fun chat(context: Context, config: OpenClawGatewayConfig, userText: String): OpenClawChatResult {
        val status = bridgeStatus(config)
        if (!(status.ok && status.connected)) {
            return OpenClawChatResult(error = status.error ?: "Command Center bridge unavailable")
        }
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

    fun history(context: Context, sessionKey: String? = null): GatewayHistoryResult {
        return GatewayHistoryResult(error = "Bridge-backed history is not implemented yet")
    }

    fun probe(context: Context): Result<OpenClawGatewaySession> {
        val config = OpenClawGatewayConfig.fromContext(context)
        val status = bridgeStatus(config)
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

    fun probe(config: OpenClawGatewayConfig): Result<OpenClawGatewaySession> =
        Result.failure(IllegalStateException("Bridge-only mode requires Context-aware probe"))
}
