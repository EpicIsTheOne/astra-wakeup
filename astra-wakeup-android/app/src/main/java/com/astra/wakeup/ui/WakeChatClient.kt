package com.astra.wakeup.ui

import android.content.Context

data class ChatResult(val reply: String? = null, val error: String? = null)

object WakeChatClient {
    private val openClawChatClient = OpenClawChatClient()

    fun wakeReply(context: Context, apiUrl: String, userText: String): String? {
        return wakeReplyDetailed(context, apiUrl, userText).reply
    }

    fun wakeReplyDetailed(context: Context, apiUrl: String, userText: String): ChatResult {
        return chatReplyDetailed(context, apiUrl, userText)
    }

    fun chatReply(context: Context, apiUrl: String, userText: String): String? {
        return chatReplyDetailed(context, apiUrl, userText).reply
    }

    fun chatReplyDetailed(context: Context, apiUrl: String, userText: String): ChatResult {
        if (apiUrl.isBlank() || userText.isBlank()) return ChatResult(error = "Missing API URL or text")
        val apiConfig = OpenClawGatewayConfig.fromPrefsApiUrl(apiUrl = apiUrl)
        val config = OpenClawGatewayConfig.fromContext(context).copy(
            httpBaseUrl = apiConfig.httpBaseUrl,
            wsUrl = apiConfig.wsUrl
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
