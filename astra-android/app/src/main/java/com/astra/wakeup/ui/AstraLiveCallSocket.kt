package com.astra.wakeup.ui

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

class AstraLiveCallSocket(
    apiUrl: String,
    private val sessionId: String,
    private val onEvent: (type: String, data: JSONObject) -> Unit,
    private val onFailure: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder().retryOnConnectionFailure(true).build()
    private var socket: WebSocket? = null
    @Volatile private var intentionallyClosed = false
    private val url = AstraCallSessionClient.websocketUrl(apiUrl)

    fun connect() {
        intentionallyClosed = false
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = Unit

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = json.optString("type")
                val data = json.optJSONObject("data") ?: JSONObject()
                val eventSessionId = data.optString("sessionId").ifBlank { data.optString("id") }
                if (eventSessionId.isNotBlank() && eventSessionId != sessionId) return
                if (type.startsWith("call:") || type == "live_task:update") {
                    onEvent(type, data)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!intentionallyClosed) {
                    onFailure(t.message ?: "call socket failed")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!intentionallyClosed) {
                    onFailure("call socket closed ($code)${if (reason.isBlank()) "" else ": $reason"}")
                }
            }
        })
    }

    fun close() {
        intentionallyClosed = true
        socket?.close(1000, null)
        socket = null
    }
}
