package com.astra.wakeup.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.graphics.Rect
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.astra.wakeup.R

class AstraOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var orbView: View? = null
    private var orbParams: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var panelController: AstraOverlayPanelController? = null
    private var callCompactView: View? = null
    private var callCompactParams: WindowManager.LayoutParams? = null
    private var tvCallCompactPhase: TextView? = null
    private var tvCallCompactTimer: TextView? = null
    private var btnCallCompactEnd: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private var unsubscribeCallState: (() -> Unit)? = null
    private var lastOutsideTapAtMs: Long = 0L
    private var currentCallState: CallState = CallState()
    private var overlayOwnedCall = false
    private var activeCallSessionId: String? = null
    private var liveCallSocket: AstraLiveCallSocket? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var audioRecordStreamer: AudioRecordStreamer? = null
    private var audioPlaybackQueue: AudioPlaybackQueue? = null
    private var pendingVoiceFallbackText: String? = null
    private var receivedAudioForCurrentTurn = false
    private var assistantPlaybackActive = false
    private var micGateUntilMs = 0L
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val callTimerTicker = object : Runnable {
        override fun run() {
            updateCompactCallUi(currentCallState)
            if (currentCallState.active) {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ensureChannel()
        tts = TextToSpeech(applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        assistantPlaybackActive = true
                    }
                    override fun onError(utteranceId: String?) {
                        handler.post { notifyAssistantPlaybackFinished() }
                    }
                    override fun onDone(utteranceId: String?) {
                        handler.post { notifyAssistantPlaybackFinished() }
                    }
                })
            }
        }
        unsubscribeCallState = CallStateRepository.subscribe { state ->
            handler.post { handleCallStateChanged(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!AstraOverlayController.isOverlayEnabled(this) && intent?.action != ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_EXPAND -> {
                startOverlayForeground()
                if (currentCallState.active) {
                    showCompactCallUiIfNeeded()
                } else {
                    showPanelIfNeeded()
                }
                return START_STICKY
            }
            else -> {
                startOverlayForeground()
                if (currentCallState.active) {
                    showCompactCallUiIfNeeded()
                } else {
                    showOrbIfNeeded()
                }
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        unsubscribeCallState?.invoke()
        unsubscribeCallState = null
        handler.removeCallbacks(callTimerTicker)
        endOverlayCall(announce = false, updateState = false)
        removePanel()
        removeCompactCallUi()
        removeOrb()
        tts?.shutdown()
        tts = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startOverlayForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val expandPending = PendingIntent.getService(
            this,
            7103,
            Intent(this, AstraOverlayService::class.java).apply { action = ACTION_EXPAND },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPending = PendingIntent.getService(
            this,
            7104,
            Intent(this, AstraOverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Astra overlay")
            .setContentText("Floating orb ready. Tap to summon Astra from anywhere.")
            .setOngoing(true)
            .setContentIntent(expandPending)
            .addAction(0, "Open panel", expandPending)
            .addAction(0, "Stop overlay", stopPending)
            .build()
    }

    private fun showOrbIfNeeded() {
        if (currentCallState.active) return
        if (orbView != null || !AstraOverlayController.isOverlayEnabled(this) || !AstraOverlayController.canDrawOverlays(this)) return
        val orb = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.bg_astra_orb)
            elevation = 18f
            alpha = 0.94f
            addView(TextView(context).apply {
                text = "A"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        val size = (AstraOverlayController.orbSizeDp(this) * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 24
            y = 220
        }

        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f

        orb.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX - (event.rawX - touchX)).toInt()
                    params.y = (initialY + (event.rawY - touchY)).toInt()
                    runCatching { windowManager.updateViewLayout(orb, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = kotlin.math.abs(event.rawX - touchX) > 10 || kotlin.math.abs(event.rawY - touchY) > 10
                    if (!moved) {
                        showPanelIfNeeded()
                    }
                    true
                }
                else -> false
            }
        }

        runCatching { windowManager.addView(orb, params) }
        orbView = orb
        orbParams = params
    }

    private fun showPanelIfNeeded() {
        if (currentCallState.active) {
            showCompactCallUiIfNeeded()
            return
        }
        if (panelView != null || !AstraOverlayController.isOverlayEnabled(this) || !AstraOverlayController.canDrawOverlays(this)) return
        removeOrb()
        removeCompactCallUi()
        lastOutsideTapAtMs = 0L
        val panel = LayoutInflater.from(this).inflate(R.layout.activity_astra_overlay, null)
        val panelCard = panel.findViewById<View>(R.id.overlayPanelCard)
        panel.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (isTouchOutsidePanelCard(panelCard, event)) {
                        handleOutsideTap()
                        true
                    } else {
                        false
                    }
                }
                MotionEvent.ACTION_OUTSIDE -> {
                    handleOutsideTap()
                    true
                }
                else -> false
            }
        }
        panelController = AstraOverlayPanelController(
            context = this,
            root = panel,
            requestMicPermission = {
                startActivity(AstraPanelLauncher.intent(this))
            },
            onCloseRequested = {
                collapseToOrb()
            },
            onCallRequested = {
                startOverlayCall()
            }
        )
        panelController?.onShow()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
        }
        runCatching { windowManager.addView(panel, params) }
        panelView = panel
        panelParams = params
    }

    private fun collapseToOrb() {
        val panel = panelView
        if (panel == null) {
            removePanel()
            showOrbIfNeeded()
            return
        }
        panel.animate()
            .alpha(0f)
            .setDuration(180)
            .withEndAction {
                removePanel()
                showOrbIfNeeded()
            }
            .start()
    }

    private fun handleOutsideTap() {
        val now = System.currentTimeMillis()
        if (now - lastOutsideTapAtMs <= OUTSIDE_DOUBLE_TAP_WINDOW_MS) {
            lastOutsideTapAtMs = 0L
            collapseToOrb()
        } else {
            lastOutsideTapAtMs = now
        }
    }

    private fun isTouchOutsidePanelCard(panelCard: View, event: MotionEvent): Boolean {
        val rect = Rect()
        panelCard.getGlobalVisibleRect(rect)
        return !rect.contains(event.rawX.toInt(), event.rawY.toInt())
    }

    private fun startOverlayCall() {
        if (overlayOwnedCall || currentCallState.active) return
        removePanel()
        removeOrb()
        val gatewayConfig = OpenClawGatewayConfig.fromContext(this)
        overlayOwnedCall = true
        updateOverlayCallState(active = true, phase = "connecting…")
        showCompactCallUiIfNeeded()
        Thread {
            val started = AstraCallSessionClient.startCall(gatewayConfig.httpBaseUrl)
            handler.post {
                if (!started.ok || started.session == null) {
                    overlayOwnedCall = false
                    updateOverlayCallState(active = false, phase = "call failed")
                    return@post
                }
                activeCallSessionId = started.session.id
                reconnectAttempts = 0
                connectOverlayLiveCallSocket(gatewayConfig.httpBaseUrl, started.session.id)
                audioRecordStreamer?.stop()
                audioPlaybackQueue?.stop()
                audioPlaybackQueue = AudioPlaybackQueue(
                    onError = { error -> handler.post { updateOverlayCallState(active = true, phase = "playback issue") } },
                    onPlaybackStateChanged = { active -> handler.post { markAssistantPlaybackActive(active) } },
                    onPlaybackIdle = { handler.post { notifyAssistantPlaybackFinished() } },
                ).also { it.start() }
                audioRecordStreamer = AudioRecordStreamer(
                    shouldStreamChunk = { pcm16 -> shouldUploadMicChunk(pcm16) },
                    onChunk = { chunk ->
                        val sessionId = activeCallSessionId ?: return@AudioRecordStreamer
                        AstraCallSessionClient.sendAudioChunk(gatewayConfig.httpBaseUrl, sessionId, chunk)
                    },
                    onError = { error -> handler.post { updateOverlayCallState(active = true, phase = "audio issue") } },
                    onDebug = { }
                ).also { it.start() }
                updateOverlayCallState(active = true, sessionId = started.session.id, phase = "live 🎙️")
                removePanel()
                removeOrb()
                showCompactCallUiIfNeeded()
            }
        }.start()
    }

    private fun endOverlayCall(announce: Boolean = false, updateState: Boolean = true) {
        if (overlayOwnedCall) {
            activeCallSessionId?.let { AstraCallSessionClient.endCall(OpenClawGatewayConfig.fromContext(this).httpBaseUrl, it) }
        }
        liveCallSocket?.close()
        liveCallSocket = null
        audioRecordStreamer?.stop()
        audioRecordStreamer = null
        audioPlaybackQueue?.stop()
        audioPlaybackQueue = null
        pendingVoiceFallbackText = null
        receivedAudioForCurrentTurn = false
        assistantPlaybackActive = false
        micGateUntilMs = 0L
        if (announce && ttsReady) {
            tts?.speak("Call ended.", TextToSpeech.QUEUE_FLUSH, null, "overlay-call-ended")
        }
        activeCallSessionId = null
        overlayOwnedCall = false
        if (updateState) {
            CallStateRepository.set(CallState())
        }
    }

    private fun connectOverlayLiveCallSocket(apiUrl: String, sessionId: String) {
        liveCallSocket?.close()
        liveCallSocket = AstraLiveCallSocket(
            apiUrl = apiUrl,
            sessionId = sessionId,
            onEvent = { type, data -> handler.post { handleOverlayLiveCallEvent(type, data) } },
            onFailure = { error -> handler.post { scheduleOverlayReconnect(apiUrl, sessionId) } }
        ).also { it.connect() }
    }

    private fun scheduleOverlayReconnect(apiUrl: String, sessionId: String) {
        if (!overlayOwnedCall || activeCallSessionId != sessionId) return
        reconnectAttempts += 1
        if (reconnectAttempts > maxReconnectAttempts) {
            endOverlayCall(updateState = true)
            return
        }
        updateOverlayCallState(active = true, sessionId = sessionId, phase = "reconnecting…")
        handler.postDelayed({
            if (overlayOwnedCall && activeCallSessionId == sessionId) {
                connectOverlayLiveCallSocket(apiUrl, sessionId)
            }
        }, (600L * reconnectAttempts).coerceAtMost(4000L))
    }

    private fun handleOverlayLiveCallEvent(type: String, data: org.json.JSONObject) {
        reconnectAttempts = 0
        when (type) {
            "call:session.started" -> updateOverlayCallState(active = true, sessionId = data.optString("id").ifBlank { activeCallSessionId }, phase = "ready")
            "call:session.state" -> updateOverlayCallState(active = true, sessionId = activeCallSessionId, phase = data.optString("state").ifBlank { "live" })
            "call:transcript.partial" -> updateOverlayCallState(active = true, sessionId = activeCallSessionId, phase = "listening…")
            "call:transcript.final" -> updateOverlayCallState(active = true, sessionId = activeCallSessionId, phase = "thinking…")
            "call:response.text" -> {
                val text = data.optString("text").trim()
                if (text.isNotBlank()) {
                    pendingVoiceFallbackText = text
                    receivedAudioForCurrentTurn = false
                    handler.removeCallbacks(voiceFallbackRunnable)
                    handler.postDelayed(voiceFallbackRunnable, 2500L)
                    CallStateRepository.update { current -> current.copy(lastAssistantText = text) }
                    updateOverlayCallState(active = true, sessionId = activeCallSessionId, phase = "speaking…")
                }
            }
            "call:response.audio" -> {
                val chunk = data.optString("pcm16Base64").trim()
                val mimeType = data.optString("mimeType").trim()
                if (chunk.isNotBlank()) {
                    receivedAudioForCurrentTurn = true
                    pendingVoiceFallbackText = null
                    handler.removeCallbacks(voiceFallbackRunnable)
                    markAssistantPlaybackActive(true)
                    audioPlaybackQueue?.enqueuePcm16Base64(chunk, mimeType)
                    updateOverlayCallState(active = true, sessionId = activeCallSessionId, phase = "speaking…")
                }
            }
            "call:error", "call:session.ended" -> endOverlayCall(updateState = true)
        }
    }

    private val voiceFallbackRunnable = Runnable {
        val fallback = pendingVoiceFallbackText
        if (!overlayOwnedCall || fallback.isNullOrBlank() || receivedAudioForCurrentTurn || !ttsReady) return@Runnable
        markAssistantPlaybackActive(true)
        tts?.speak(fallback, TextToSpeech.QUEUE_FLUSH, null, "overlay-call-fallback")
        pendingVoiceFallbackText = null
    }

    private fun notifyAssistantPlaybackFinished() {
        markAssistantPlaybackActive(false)
        val sessionId = activeCallSessionId ?: return
        AstraCallSessionClient.sendSessionEvent(OpenClawGatewayConfig.fromContext(this).httpBaseUrl, sessionId, "assistant.playback_finished")
        updateOverlayCallState(active = true, sessionId = sessionId, phase = "live 🎙️")
    }

    private fun markAssistantPlaybackActive(active: Boolean) {
        assistantPlaybackActive = active
        micGateUntilMs = if (active) System.currentTimeMillis() + 550L else System.currentTimeMillis() + 160L
    }

    private fun shouldUploadMicChunk(pcm16: ByteArray): Boolean {
        if (!overlayOwnedCall) return false
        if (!assistantPlaybackActive && System.currentTimeMillis() >= micGateUntilMs) return true
        val rms = estimatePcm16Rms(pcm16)
        val allowBargeIn = rms >= 1500
        if (allowBargeIn) {
            pendingVoiceFallbackText = null
            receivedAudioForCurrentTurn = false
            handler.removeCallbacks(voiceFallbackRunnable)
            tts?.stop()
            audioPlaybackQueue?.interruptPlayback()
            markAssistantPlaybackActive(false)
            updateOverlayCallState(active = true, sessionId = activeCallSessionId, phase = "listening…")
            return true
        }
        return false
    }

    private fun estimatePcm16Rms(pcm16: ByteArray): Int {
        if (pcm16.size < 2) return 0
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < pcm16.size) {
            val sample = ((pcm16[i + 1].toInt() shl 8) or (pcm16[i].toInt() and 0xFF)).toShort().toInt()
            sum += sample * sample.toDouble()
            count += 1
            i += 2
        }
        if (count == 0) return 0
        return kotlin.math.sqrt(sum / count).toInt()
    }

    private fun updateOverlayCallState(active: Boolean, sessionId: String? = activeCallSessionId, phase: String, startedAtMs: Long? = null) {
        if (!active) {
            CallStateRepository.set(CallState())
            return
        }
        CallStateRepository.update { current ->
            current.copy(
                active = true,
                sessionId = sessionId,
                phase = phase,
                callStartedAtMs = startedAtMs ?: current.callStartedAtMs ?: System.currentTimeMillis(),
            )
        }
    }

    private fun handleCallStateChanged(state: CallState) {
        currentCallState = state
        if (state.active) {
            removePanel()
            removeOrb()
            showCompactCallUiIfNeeded()
            updateCompactCallUi(state)
            handler.removeCallbacks(callTimerTicker)
            handler.post(callTimerTicker)
        } else {
            handler.removeCallbacks(callTimerTicker)
            removeCompactCallUi()
            if (AstraOverlayController.isOverlayEnabled(this) && AstraOverlayController.canDrawOverlays(this)) {
                showOrbIfNeeded()
            }
        }
    }

    private fun showCompactCallUiIfNeeded() {
        if (!currentCallState.active) return
        if (callCompactView != null || !AstraOverlayController.isOverlayEnabled(this) || !AstraOverlayController.canDrawOverlays(this)) return
        val view = LayoutInflater.from(this).inflate(R.layout.view_overlay_call_compact, null)
        tvCallCompactPhase = view.findViewById(R.id.tvOverlayCallPhase)
        tvCallCompactTimer = view.findViewById(R.id.tvOverlayCallTimer)
        btnCallCompactEnd = view.findViewById<Button>(R.id.btnOverlayCallEnd).apply {
            setOnClickListener {
                if (overlayOwnedCall) {
                    endOverlayCall()
                } else {
                    sendBroadcast(Intent(ChatActivity.ACTION_END_CALL))
                }
            }
        }
        val anchor = orbParams
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = anchor?.gravity ?: (Gravity.TOP or Gravity.END)
            x = anchor?.x ?: 24
            y = anchor?.y ?: 220
        }
        runCatching { windowManager.addView(view, params) }
        callCompactView = view
        callCompactParams = params
    }

    private fun updateCompactCallUi(state: CallState) {
        tvCallCompactPhase?.text = when {
            state.phase.isBlank() -> "Call live"
            state.phase.startsWith("Call:") -> state.phase.removePrefix("Call:").trim().replaceFirstChar { it.uppercase() }
            else -> state.phase.replaceFirstChar { it.uppercase() }
        }
        val startedAt = state.callStartedAtMs
        tvCallCompactTimer?.text = if (startedAt == null) {
            "00:00"
        } else {
            formatElapsed(System.currentTimeMillis() - startedAt)
        }
    }

    private fun formatElapsed(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun removePanel() {
        panelController?.release()
        panelController = null
        panelView?.let { view -> runCatching { windowManager.removeView(view) } }
        panelView = null
        panelParams = null
    }

    private fun removeCompactCallUi() {
        tvCallCompactPhase = null
        tvCallCompactTimer = null
        btnCallCompactEnd = null
        callCompactView?.let { view -> runCatching { windowManager.removeView(view) } }
        callCompactView = null
        callCompactParams = null
    }

    private fun removeOrb() {
        orbView?.let { view -> runCatching { windowManager.removeView(view) } }
        orbView = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Astra overlay", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    companion object {
        const val ACTION_STOP = "com.astra.wakeup.action.STOP_ASTRA_OVERLAY"
        const val ACTION_EXPAND = "com.astra.wakeup.action.EXPAND_ASTRA_OVERLAY"
        private const val CHANNEL_ID = "astra_overlay"
        private const val NOTIFICATION_ID = 7110
        private const val OUTSIDE_DOUBLE_TAP_WINDOW_MS = 450L
        private const val COMPACT_CALL_DOUBLE_TAP_WINDOW_MS = 450L
    }
}
