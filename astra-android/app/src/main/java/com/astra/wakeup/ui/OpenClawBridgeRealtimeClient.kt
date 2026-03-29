package com.astra.wakeup.ui

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class OpenClawBridgeRealtimeResult(
    val reply: String? = null,
    val error: String? = null
)

object OpenClawBridgeRealtimeClient {
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()

    private fun wsUrl(apiUrl: String): String {
        val trimmed = apiUrl.trim().trimEnd('/')
        val base = when {
            trimmed.contains("/commandcenter") -> trimmed.substringBefore("/commandcenter") + "/commandcenter"
            trimmed.contains("/api/") -> trimmed.substringBefore("/api/") + "/commandcenter"
            else -> "$trimmed/commandcenter"
        }
        return when {
            base.startsWith("https://") -> base.replaceFirst("https://", "wss://") + "/ws"
            base.startsWith("http://") -> base.replaceFirst("http://", "ws://") + "/ws"
            else -> base + "/ws"
        }
    }

    fun sendAndAwaitReply(apiUrl: String, text: String, agent: String? = null, timeoutMs: Long = 45_000): OpenClawBridgeRealtimeResult {
        val socketUrl = wsUrl(apiUrl)
        val latch = CountDownLatch(1)
        val resultRef = AtomicReference(OpenClawBridgeRealtimeResult())
        val bridgeAgent = agent?.ifBlank { null }

        val ws = client.newWebSocket(Request.Builder().url(socketUrl).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val queued = OpenClawBridgeClient.directChat(apiUrl, text, bridgeAgent)
                if (!queued.ok) {
                    resultRef.set(OpenClawBridgeRealtimeResult(error = queued.error ?: "bridge send failed"))
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = json.optString("type")
                val data = json.optJSONObject("data") ?: JSONObject()
                val source = data.optString("source")
                val isChat = data.optBoolean("chat", false)
                val eventAgent = data.optString("agent").ifBlank { null }
                if (source != "direct-chat" || !isChat) return
                if (!bridgeAgent.isNullOrBlank() && eventAgent != null && eventAgent != bridgeAgent) return
                when (type) {
                    "agent:responding" -> {
                        resultRef.set(OpenClawBridgeRealtimeResult(reply = data.optString("message").ifBlank { "(empty reply)" }))
                        latch.countDown()
                    }
                    "agent:error" -> {
                        resultRef.set(OpenClawBridgeRealtimeResult(error = data.optString("message").ifBlank { "bridge agent error" }))
                        latch.countDown()
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                resultRef.compareAndSet(OpenClawBridgeRealtimeResult(), OpenClawBridgeRealtimeResult(error = t.message ?: "bridge websocket failed"))
                latch.countDown()
            }
        })

        val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        ws.close(1000, null)
        return if (completed) resultRef.get() else OpenClawBridgeRealtimeResult(error = "bridge reply timeout")
    }
}
