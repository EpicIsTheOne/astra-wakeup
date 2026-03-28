package com.astra.wakeup.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.app.PendingIntent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.astra.wakeup.R
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenClawNodeService : Service() {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var ws: WebSocket? = null
    private var controlExecutor: PhoneControlExecutor? = null
    private var reconnectAttempts = 0
    private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        controlExecutor = PhoneControlExecutor(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Connecting to OpenClaw node control…"))
        connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shuttingDown = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (ws == null) connect()
        return START_STICKY
    }

    override fun onDestroy() {
        shuttingDown = true
        ws?.close(1000, "service stopping")
        ws = null
        controlExecutor?.release()
        controlExecutor = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun connect() {
        val config = OpenClawGatewayConfig.fromContext(this)
        if (config.wsUrl.isBlank()) {
            updateNotification("Node control idle: missing Gateway URL")
            return
        }

        val request = Request.Builder().url(config.wsUrl).build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempts = 0
                updateNotification("OpenClaw node connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "node websocket failure", t)
                updateNotification("Node control reconnecting…")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                updateNotification("Node control disconnected")
                if (!shuttingDown) scheduleReconnect()
            }
        })
    }

    private fun handleFrame(webSocket: WebSocket, text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        when (json.optString("type")) {
            "event" -> when (json.optString("event")) {
                "connect.challenge" -> sendConnectFrame(webSocket, json.optJSONObject("payload"))
                "node.invoke.request" -> handleNodeInvokeRequest(json.optJSONObject("payload"))
                "voicewake.changed" -> Unit
            }
        }
    }

    private fun sendConnectFrame(webSocket: WebSocket, payload: JSONObject?) {
        val nonce = payload?.optString("nonce").orEmpty()
        val config = OpenClawGatewayConfig.fromContext(this)
        val signatureToken = config.gatewayToken ?: config.bootstrapToken ?: config.deviceToken
        val signed = OpenClawGatewayCrypto.signConnectChallenge(
            context = this,
            clientId = NODE_CLIENT_ID,
            clientMode = "node",
            role = "node",
            scopes = emptyList(),
            nonce = nonce,
            platform = "android",
            deviceFamily = Build.MODEL ?: "android",
            signatureToken = signatureToken
        ).getOrNull()

        val params = JSONObject().apply {
            put("minProtocol", 3)
            put("maxProtocol", 3)
            put("client", JSONObject().apply {
                put("id", NODE_CLIENT_ID)
                put("displayName", "Astra Wakeup Android")
                put("version", "0.3.0")
                put("platform", "android")
                put("deviceFamily", Build.MODEL ?: "android")
                put("mode", "node")
                put("instanceId", NodeIdentity.getNodeInstanceId(this@OpenClawNodeService))
            })
            put("role", "node")
            put("caps", org.json.JSONArray().put("audio").put("tts").put("vibrate"))
            put("commands", org.json.JSONArray()
                .put("phone.tts.speak")
                .put("phone.audio.play")
                .put("phone.audio.stop")
                .put("phone.vibrate")
            )
            put("permissions", JSONObject())
            put("locale", Locale.getDefault().toLanguageTag())
            put("userAgent", "astra-android/0.3.0-node")
        }

        buildAuthPayload(config)?.let { params.put("auth", it) }
        signed?.let {
            params.put("device", JSONObject().apply {
                put("id", it.deviceId)
                put("publicKey", it.publicKey)
                put("signature", it.signature)
                put("signedAt", it.signedAtMs)
                put("nonce", it.nonce)
            })
        }

        webSocket.send(JSONObject().apply {
            put("type", "req")
            put("id", CONNECT_REQ_ID)
            put("method", "connect")
            put("params", params)
        }.toString())
    }

    private fun handleNodeInvokeRequest(payload: JSONObject?) {
        val id = payload?.optString("id").orEmpty()
        val nodeId = payload?.optString("nodeId").orEmpty()
        val command = payload?.optString("command").orEmpty()
        val params = payload?.optString("paramsJSON")?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }

        val result = runCatching {
            val executor = controlExecutor ?: error("phone control unavailable")
            executor.execute(command, params)
        }

        val wsRef = ws ?: return
        val res = JSONObject().apply {
            put("type", "req")
            put("id", "node-result-${UUID.randomUUID()}")
            put("method", "node.invoke.result")
            put("params", JSONObject().apply {
                put("id", id)
                put("nodeId", nodeId)
                put("ok", result.isSuccess)
                result.getOrNull()?.let { put("payloadJSON", it.toString()) }
                result.exceptionOrNull()?.let { err ->
                    put("error", JSONObject().apply {
                        put("code", "COMMAND_FAILED")
                        put("message", err.message ?: "command failed")
                    })
                }
            })
        }
        wsRef.send(res.toString())
    }


    private fun scheduleReconnect() {
        if (shuttingDown) return
        reconnectAttempts += 1
        val delayMs = (2_000L * reconnectAttempts).coerceAtMost(15_000L)
        android.os.Handler(mainLooper).postDelayed({
            if (!shuttingDown) connect()
        }, delayMs)
    }

    private fun buildAuthPayload(config: OpenClawGatewayConfig): JSONObject? {
        val auth = JSONObject()
        config.gatewayToken?.takeIf { it.isNotBlank() }?.let { auth.put("token", it) }
        config.bootstrapToken?.takeIf { it.isNotBlank() }?.let { auth.put("bootstrapToken", it) }
        config.deviceToken?.takeIf { it.isNotBlank() }?.let { auth.put("deviceToken", it) }
        return auth.takeIf { it.length() > 0 }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Astra Node Control", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val openPanel = AstraPanelLauncher.pendingIntent(this, requestCode = 4104)
        val openApp = PendingIntent.getActivity(
            this,
            4105,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Astra node control")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openApp)
            .addAction(0, "Talk to Astra", openPanel)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "OpenClawNodeService"
        private const val CHANNEL_ID = "astra_node_control"
        private const val NOTIFICATION_ID = 4103
        private const val CONNECT_REQ_ID = "node-connect"
        private const val NODE_CLIENT_ID = "openclaw-android"
        const val ACTION_STOP = "com.astra.wakeup.action.STOP_NODE_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, OpenClawNodeService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OpenClawNodeService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

    }
}
