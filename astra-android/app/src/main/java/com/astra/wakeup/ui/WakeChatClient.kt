package com.astra.wakeup.ui

import android.content.Context

data class ChatResult(val reply: String? = null, val error: String? = null)

object WakeChatClient {
    private val openClawChatClient = OpenClawChatClient()

    fun wakeReply(context: Context, apiUrl: String, userText: String, sessionKey: String? = null): String? {
        return wakeReplyDetailed(context, apiUrl, userText, sessionKey).reply
    }

    fun wakeReplyDetailed(context: Context, apiUrl: String, userText: String, sessionKey: String? = null): ChatResult {
        return chatReplyDetailed(context, apiUrl, userText, sessionKey)
    }

    fun chatReply(context: Context, apiUrl: String, userText: String, sessionKey: String? = null): String? {
        return chatReplyDetailed(context, apiUrl, userText, sessionKey).reply
    }

    fun chatReplyDetailed(context: Context, apiUrl: String, userText: String, sessionKey: String? = null): ChatResult {
        if (apiUrl.isBlank() || userText.isBlank()) return ChatResult(error = "Missing API URL or text")
        val apiConfig = OpenClawGatewayConfig.fromPrefsApiUrl(apiUrl = apiUrl)
        val baseConfig = OpenClawGatewayConfig.fromContext(context)
        val config = baseConfig.copy(
            httpBaseUrl = apiConfig.httpBaseUrl,
            wsUrl = apiConfig.wsUrl,
            sessionKey = sessionKey?.ifBlank { baseConfig.sessionKey } ?: baseConfig.sessionKey
        )
        val result = openClawChatClient.chat(context, config, userText)
        return ChatResult(reply = result.reply, error = result.error)
    }

    @Deprecated("Use the Context-aware overload so device auth and token persistence work")
    fun chatReplyDetailed(apiUrl: String, userText: String): ChatResult {
        if (apiUrl.isBlank() || userText.isBlank()) return ChatResult(error = "Missing API URL or text")
        return ChatResult(error = "Context-aware Gateway auth required for Android direct mode")
    }
}
