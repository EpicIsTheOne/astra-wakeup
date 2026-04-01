package com.astra.wakeup.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.ScrollingMovementMethod
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.astra.wakeup.R
import org.json.JSONObject
import java.util.Locale

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var callMode = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvCall: TextView
    private lateinit var tvChat: TextView
    private lateinit var btnCallToggle: Button
    private lateinit var etInput: EditText
    private lateinit var layoutTyping: LinearLayout
    private lateinit var tvTyping: TextView
    private lateinit var chatScroll: ScrollView
    private var pendingResumeAfterTts = false
    private var activeCallSessionId: String? = null
    private var liveCallSocket: AstraLiveCallSocket? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private var audioRecordStreamer: AudioRecordStreamer? = null
    private var audioPlaybackQueue: AudioPlaybackQueue? = null
    private var pendingVoiceFallbackText: String? = null
    private var receivedAudioForCurrentTurn = false
    private var uplinkChunkDebugCount = 0
    private var debugMessagesEnabled = true
    private var typingDots = 0
    private var assistantPlaybackActive = false
    private var micGateUntilMs = 0L
    private var reconnectNoticeShown = false
    private var bargeInVoiceChunkStreak = 0
    private var pendingAssistantTextTurn: String? = null
    private val voiceFallbackRunnable = Runnable {
        val fallback = pendingVoiceFallbackText
        if (!callMode || fallback.isNullOrBlank() || receivedAudioForCurrentTurn) return@Runnable
        appendDebugMessage("No Gemini audio returned; using TTS fallback.")
        markAssistantPlaybackActive(true)
        speak(fallback)
        pendingResumeAfterTts = true
        pendingVoiceFallbackText = null
    }
    private val typingTicker = object : Runnable {
        override fun run() {
            if (layoutTyping.visibility != android.view.View.VISIBLE) return
            typingDots = (typingDots + 1) % 4
            val dots = ".".repeat(typingDots).ifBlank { "…" }
            tvTyping.text = "Astra is thinking$dots"
            handler.postDelayed(this, 400)
        }
    }
    private val openClawChatClient = OpenClawChatClient()
    private val endCallReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_END_CALL && callMode) {
                endCall(announce = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        tts = TextToSpeech(this, this)

        tvChat = findViewById(R.id.tvChat)
        etInput = findViewById(R.id.etChatInput)
        btnCallToggle = findViewById(R.id.btnCallToggle)
        val cbRemember = findViewById<CheckBox>(R.id.cbRemember)
        val cbShowDebug = findViewById<CheckBox>(R.id.cbShowDebug)
        tvCall = findViewById(R.id.tvCallStatus)
        layoutTyping = findViewById(R.id.layoutTyping)
        tvTyping = findViewById(R.id.tvTyping)
        chatScroll = tvChat.parent as ScrollView

        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        debugMessagesEnabled = prefs.getBoolean("chat_debug_enabled", true)
        cbShowDebug.isChecked = debugMessagesEnabled
        cbShowDebug.setOnCheckedChangeListener { _, isChecked ->
            debugMessagesEnabled = isChecked
            prefs.edit().putBoolean("chat_debug_enabled", isChecked).apply()
            appendMessage(
                "Astra",
                if (isChecked) "Fine. Debug spam is back on, since apparently you enjoy chaos." else "Debug spam is off now. You're welcome, Epic.",
                isAstra = true
            )
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(endCallReceiver, IntentFilter(ACTION_END_CALL), RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(endCallReceiver, IntentFilter(ACTION_END_CALL))
        }

        tvChat.movementMethod = ScrollingMovementMethod()
        appendMessage("Astra", "All right, I'm here. Try to keep up.", isAstra = true)

        findViewById<Button>(R.id.btnChatSend).setOnClickListener {
            val msg = etInput.text.toString().trim()
            if (msg.isNotBlank()) {
                hideKeyboard()
                appendMessage("You", msg, isAstra = false)
                etInput.setText("")
                askAstra(msg, fromCall = false, remember = cbRemember.isChecked)
                cbRemember.isChecked = false
            }
        }

        findViewById<Button>(R.id.btnChatTalk).setOnClickListener {
            startSpeechInput(singleShot = true)
        }

        btnCallToggle.setOnClickListener {
            if (callMode) {
                endCall()
            } else {
                startCall()
            }
        }

        handleIntent(intent)
    }

    private fun startCall() {
        callMode = true
        val gatewayConfig = OpenClawGatewayConfig.fromContext(this)
        btnCallToggle.isEnabled = false
        setCallStatus("connecting…")
        Thread {
            val started = AstraCallSessionClient.startCall(gatewayConfig.httpBaseUrl)
            runOnUiThread {
                btnCallToggle.isEnabled = true
                if (!callMode) return@runOnUiThread
                if (!started.ok || started.session == null) {
                    callMode = false
                    btnCallToggle.text = "Start Call"
                    val reason = started.error ?: "unknown error"
                    val message = if (reason.contains("Gemini API key", ignoreCase = true)) {
                        "Mission Control still isn't exposing your Gemini key to the live call backend yet. ${reason.trim()}"
                    } else {
                        "Couldn't start the live call session: $reason"
                    }
                    appendMessage("Astra", message, isAstra = true)
                    started.debug?.takeIf { it.isNotBlank() }?.let {
                        appendDebugMessage(it)
                    }
                    setCallStatus("call failed")
                    return@runOnUiThread
                }
                activeCallSessionId = started.session.id
                val startedAtMs = System.currentTimeMillis()
                CallStateRepository.update { current ->
                    current.copy(active = true, sessionId = started.session.id, phase = "connecting", callStartedAtMs = startedAtMs)
                }
                reconnectAttempts = 0
                reconnectNoticeShown = false
                connectLiveCallSocket(gatewayConfig.httpBaseUrl, started.session.id)
                btnCallToggle.text = "End Call"
                setCallStatus("live 🎙️")
                appendMessage("Astra", "Call connected. Try not to mumble.", isAstra = true)
                speak("Call connected. Talk to me.")
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, CallForegroundService::class.java).apply {
                        putExtra("call_session_id", started.session.id)
                    }
                )
                audioRecordStreamer?.stop()
                audioPlaybackQueue?.stop()
                audioPlaybackQueue = AudioPlaybackQueue(
                    onError = { error -> runOnUiThread { appendMessage("Astra", "Playback issue: $error", isAstra = true) } },
                    onPlaybackStateChanged = { active -> runOnUiThread { markAssistantPlaybackActive(active) } },
                    onPlaybackIdle = { runOnUiThread { notifyAssistantPlaybackFinished("pcm-drain") } },
                ).also { it.start() }
                uplinkChunkDebugCount = 0
                audioRecordStreamer = AudioRecordStreamer(
                    shouldStreamChunk = { pcm16 -> shouldUploadMicChunk(pcm16) },
                    onChunk = { chunk ->
                        val sessionId = activeCallSessionId
                        if (sessionId.isNullOrBlank()) {
                            runOnUiThread { appendDebugMessage("Skipping audio upload because session id is missing.") }
                            return@AudioRecordStreamer
                        }
                        uplinkChunkDebugCount += 1
                        if (uplinkChunkDebugCount <= 3 || uplinkChunkDebugCount % 25 == 0) {
                            runOnUiThread { appendDebugMessage("Posting uplink chunk #$uplinkChunkDebugCount len=${chunk.length}") }
                        }
                        val upload = AstraCallSessionClient.sendAudioChunk(gatewayConfig.httpBaseUrl, sessionId, chunk)
                        upload.onFailure { err ->
                            runOnUiThread { appendDebugMessage("Audio upload failed #$uplinkChunkDebugCount: ${err.message}") }
                        }
                    },
                    onError = { error -> runOnUiThread { setCallStatus("audio issue"); appendMessage("Astra", "Audio stream issue: $error", isAstra = true) } },
                    onDebug = { msg -> runOnUiThread { appendDebugMessage(msg) } },
                ).also { it.start() }
                appendDebugMessage("Live mode active: streaming mic directly to Gemini backend (SpeechRecognizer loop disabled).")
            }
        }.start()
    }

    private fun endCall(announce: Boolean = true) {
        btnCallToggle.text = "Start Call"
        pendingResumeAfterTts = false
        activeCallSessionId?.let { AstraCallSessionClient.endCall(OpenClawGatewayConfig.fromContext(this).httpBaseUrl, it) }
        activeCallSessionId = null
        liveCallSocket?.close()
        liveCallSocket = null
        audioRecordStreamer?.stop()
        audioRecordStreamer = null
        audioPlaybackQueue?.stop()
        audioPlaybackQueue = null
        pendingVoiceFallbackText = null
        pendingAssistantTextTurn = null
        receivedAudioForCurrentTurn = false
        handler.removeCallbacks(voiceFallbackRunnable)
        assistantPlaybackActive = false
        micGateUntilMs = 0L
        reconnectNoticeShown = false
        callMode = false
        setCallStatus("idle")
        recognizer?.cancel()
        stopTyping()
        stopService(Intent(this, CallForegroundService::class.java))
        CallStateRepository.set(CallState())
        if (announce) {
            speak("Call ended.")
        }
    }

    private fun handleIntent(intent: Intent?) {
        val shouldAutoStart = intent?.getBooleanExtra(EXTRA_AUTO_START_CALL, false) == true
        if (shouldAutoStart && !callMode) {
            handler.post { startCall() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.US
            tts?.setPitch(1.1f)
            tts?.setSpeechRate(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    markAssistantPlaybackActive(true)
                }
                override fun onError(utteranceId: String?) {
                    maybeResumeListeningAfterTts()
                }
                override fun onDone(utteranceId: String?) {
                    maybeResumeListeningAfterTts()
                }
            })
        }
    }

    private fun appendDebugMessage(message: String) {
        if (!debugMessagesEnabled || message.isBlank()) return
        appendMessage("Debug", message, isAstra = true)
    }

    private fun appendMessage(speaker: String, message: String, isAstra: Boolean) {
        val speakerColor = if (isAstra) Color.parseColor("#F472B6") else Color.parseColor("#60A5FA")
        val bodyColor = if (isAstra) Color.parseColor("#FCE7F3") else Color.parseColor("#E5E7EB")
        val prefix = "$speaker: "
        val line = SpannableStringBuilder()
        if (tvChat.text.isNotEmpty()) line.append("\n\n")

        val prefixSpan = SpannableString(prefix).apply {
            setSpan(ForegroundColorSpan(speakerColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val bodySpan = SpannableString(message).apply {
            setSpan(ForegroundColorSpan(bodyColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        line.append(prefixSpan)
        line.append(bodySpan)
        tvChat.append(line)
        chatScroll.post { chatScroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(etInput.windowToken, 0)
        etInput.clearFocus()
    }

    private fun setCallStatus(state: String) {
        tvCall.text = "Call: $state"
        CallStateRepository.update { current ->
            current.copy(
                active = callMode,
                sessionId = activeCallSessionId,
                phase = state,
            )
        }
    }

    private fun startTyping() {
        typingDots = 0
        layoutTyping.visibility = android.view.View.VISIBLE
        handler.removeCallbacks(typingTicker)
        handler.post(typingTicker)
    }

    private fun stopTyping() {
        handler.removeCallbacks(typingTicker)
        layoutTyping.visibility = android.view.View.GONE
    }

    private fun askAstra(text: String, fromCall: Boolean, remember: Boolean = false) {
        val gatewayConfig = OpenClawGatewayConfig.fromContext(this)
        if (fromCall) setCallStatus("thinking…")
        startTyping()

        Thread {
            if (remember) {
                ApiMemoryClient.remember(gatewayConfig.httpBaseUrl, text, category = "preference")
            }
            val result = openClawChatClient.chat(this, gatewayConfig, text)
            if (result.error != null) {
                ApiOpsClient.log(gatewayConfig.httpBaseUrl, "warn", "chatReply error: ${result.error}")
            }
            val reply = result.reply ?: if (result.error?.contains("missing scope: operator.write", ignoreCase = true) == true) {
                "Network tantrum: this OpenClaw auth can connect, but it can't send chat yet because it lacks operator.write. Use a write-capable shared token or pair this device so Astra can get a device token."
            } else {
                "Network tantrum: ${result.error ?: "unknown"}"
            }
            runOnUiThread {
                stopTyping()
                appendMessage("Astra", reply, isAstra = true)
                if (fromCall) setCallStatus("speaking…")
                speak(reply)
                if (fromCall && callMode) {
                    pendingResumeAfterTts = true
                }
            }
        }.start()
    }

    private fun speak(text: String) {
        if (!ttsReady) return
        markAssistantPlaybackActive(true)
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat-turn")
    }

    private fun maybeResumeListeningAfterTts() {
        runOnUiThread {
            notifyAssistantPlaybackFinished("tts")
        }
    }

    private fun notifyAssistantPlaybackFinished(source: String) {
        markAssistantPlaybackActive(false)
        if (!callMode || !pendingResumeAfterTts) return
        pendingResumeAfterTts = false
        appendDebugMessage("Assistant playback finished via $source.")
        activeCallSessionId?.let {
            AstraCallSessionClient.sendSessionEvent(
                OpenClawGatewayConfig.fromContext(this).httpBaseUrl,
                it,
                "assistant.playback_finished"
            )
        }
        setCallStatus("live 🎙️")
    }

    private fun startSpeechInput(singleShot: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 992)
            return
        }

        tts?.stop()

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        if (!singleShot && callMode) setCallStatus("listening…")

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (!singleShot && callMode) {
                    setCallStatus("reconnecting…")
                    handler.postDelayed({ startSpeechInput(singleShot = false) }, 1000)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val heard = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (!singleShot && callMode && heard.isNotBlank()) {
                    activeCallSessionId?.let {
                        AstraCallSessionClient.sendSessionEvent(
                            OpenClawGatewayConfig.fromContext(this@ChatActivity).httpBaseUrl,
                            it,
                            "transcript.partial",
                            heard,
                        )
                    }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                etInput.setText(heard)
                if (heard.isNotBlank()) {
                    appendMessage("You", heard, isAstra = false)
                    CallStateRepository.update { current -> current.copy(lastUserText = heard) }
                    if (!singleShot && callMode && activeCallSessionId != null) {
                        AstraCallSessionClient.sendSessionEvent(
                            OpenClawGatewayConfig.fromContext(this@ChatActivity).httpBaseUrl,
                            activeCallSessionId.orEmpty(),
                            "transcript.final",
                            heard,
                        )
                        setCallStatus("thinking…")
                    } else {
                        askAstra(heard, fromCall = !singleShot, remember = false)
                    }
                } else if (!singleShot && callMode) {
                    setCallStatus("listening…")
                    handler.postDelayed({ startSpeechInput(singleShot = false) }, 800)
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        }
        recognizer?.startListening(intent)
    }

    private fun scheduleVoiceFallbackIfNeeded(text: String) {
        pendingVoiceFallbackText = text
        receivedAudioForCurrentTurn = false
        handler.removeCallbacks(voiceFallbackRunnable)
        handler.postDelayed(voiceFallbackRunnable, 2500L)
    }

    private fun markAssistantPlaybackActive(active: Boolean) {
        assistantPlaybackActive = active
        if (active) {
            micGateUntilMs = System.currentTimeMillis() + 120L
            if (callMode) setCallStatus("live 🎙️")
        } else {
            micGateUntilMs = System.currentTimeMillis() + 80L
            bargeInVoiceChunkStreak = 0
        }
    }

    private fun shouldUploadMicChunk(pcm16: ByteArray): Boolean {
        if (!callMode) return false

        val now = System.currentTimeMillis()
        if (!assistantPlaybackActive) {
            if (now < micGateUntilMs) {
                return false
            }
            return true
        }

        val rms = estimatePcm16Rms(pcm16)
        val allowBargeIn = rms >= 1800
        if (allowBargeIn) {
            bargeInVoiceChunkStreak += 1
        } else {
            bargeInVoiceChunkStreak = 0
        }
        if (bargeInVoiceChunkStreak >= 3) {
            bargeInVoiceChunkStreak = 0
            handler.post {
                appendDebugMessage("Sustained user speech detected while Astra was talking; allowing barge-in.")
                pendingVoiceFallbackText = null
                pendingResumeAfterTts = false
                handler.removeCallbacks(voiceFallbackRunnable)
                tts?.stop()
                audioPlaybackQueue?.interruptPlayback()
                markAssistantPlaybackActive(false)
                setCallStatus("listening…")
            }
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

    private fun connectLiveCallSocket(apiUrl: String, sessionId: String) {
        liveCallSocket?.close()
        liveCallSocket = AstraLiveCallSocket(
            apiUrl = apiUrl,
            sessionId = sessionId,
            onOpen = {
                runOnUiThread {
                    if (reconnectAttempts > 0) {
                        appendDebugMessage("Live socket reconnected; resyncing call session state.")
                        syncCallSessionState(apiUrl, sessionId)
                    }
                }
            },
            onEvent = { type, data ->
                reconnectAttempts = 0
                reconnectNoticeShown = false
                handleLiveCallEvent(type, data)
            },
            onFailure = { error -> runOnUiThread { scheduleLiveSocketReconnect(apiUrl, sessionId, error) } }
        ).also { it.connect() }
    }

    private fun scheduleLiveSocketReconnect(apiUrl: String, sessionId: String, reason: String) {
        if (!callMode || activeCallSessionId != sessionId) return
        reconnectAttempts += 1
        if (reconnectAttempts > maxReconnectAttempts) {
            setCallStatus("reconnect failed")
            appendMessage("Astra", "Live call connection dropped and couldn't recover. End and restart the call.", isAstra = true)
            appendDebugMessage("Reconnect failed after $maxReconnectAttempts tries: $reason")
            return
        }
        val delayMs = (600L * reconnectAttempts).coerceAtMost(4000L)
        setCallStatus("reconnecting…")
        if (!reconnectNoticeShown) {
            reconnectNoticeShown = true
            appendMessage("Astra", "Call link hiccup. Reconnecting before you can complain.", isAstra = true)
        }
        appendDebugMessage("Socket reconnect #$reconnectAttempts in ${delayMs}ms: $reason")
        handler.postDelayed({
            if (!callMode || activeCallSessionId != sessionId) return@postDelayed
            connectLiveCallSocket(apiUrl, sessionId)
        }, delayMs)
    }

    private fun syncCallSessionState(apiUrl: String, sessionId: String) {
        Thread {
            val lookup = AstraCallSessionClient.getCallSession(apiUrl, sessionId)
            runOnUiThread {
                if (!callMode || activeCallSessionId != sessionId) return@runOnUiThread
                if (!lookup.ok || lookup.session == null) {
                    val error = lookup.error ?: "unknown error"
                    appendDebugMessage("Call session resync failed: $error")
                    if (error.contains("not found", ignoreCase = true)) {
                        appendMessage("Astra", "The backend lost our live session during reconnect. Start the call again, genius.", isAstra = true)
                        endCall(announce = false)
                    }
                    return@runOnUiThread
                }
                val state = lookup.session.state.ifBlank { "live" }
                setCallStatus(state)
                appendDebugMessage("Call session resynced after reconnect: state=$state")
            }
        }.start()
    }

    private fun handleLiveCallEvent(type: String, data: JSONObject) {
        runOnUiThread {
            when (type) {
                "call:session.started" -> setCallStatus("ready")
                "call:session.state" -> setCallStatus(data.optString("state").ifBlank { "live" })
                "call:transcript.partial" -> {
                    pendingVoiceFallbackText = null
                    pendingAssistantTextTurn = null
                    handler.removeCallbacks(voiceFallbackRunnable)
                    setCallStatus("listening…")
                }
                "call:transcript.final" -> {
                    pendingAssistantTextTurn = null
                    setCallStatus("thinking…")
                }
                "call:response.text" -> {
                    val text = data.optString("text").trim()
                    val done = data.optBoolean("done", false)
                    if (text.isNotBlank()) {
                        handler.removeCallbacks(voiceFallbackRunnable)
                        pendingVoiceFallbackText = null
                        pendingAssistantTextTurn = text
                        if (done || audioPlaybackQueue == null) {
                            appendMessage("Astra", text, isAstra = true)
                            CallStateRepository.update { current -> current.copy(lastAssistantText = text) }
                        }
                        setCallStatus("live 🎙️")
                        if (audioPlaybackQueue == null) {
                            speak(text)
                            if (callMode) pendingResumeAfterTts = true
                        } else if (done) {
                            pendingResumeAfterTts = true
                            scheduleVoiceFallbackIfNeeded(text)
                        }
                    }
                }
                "call:response.audio" -> {
                    val chunk = data.optString("pcm16Base64").trim()
                    val mimeType = data.optString("mimeType").trim()
                    if (chunk.isNotBlank()) {
                        receivedAudioForCurrentTurn = true
                        pendingVoiceFallbackText = null
                        handler.removeCallbacks(voiceFallbackRunnable)
                        pendingResumeAfterTts = true
                        pendingAssistantTextTurn?.takeIf { it.isNotBlank() }?.let { latestText ->
                            CallStateRepository.update { current -> current.copy(lastAssistantText = latestText) }
                        }
                        markAssistantPlaybackActive(true)
                        setCallStatus("live 🎙️")
                        audioPlaybackQueue?.enqueuePcm16Base64(chunk, mimeType)
                    }
                }
                "call:debug" -> {
                    val message = data.optString("message").trim()
                    if (message.isNotBlank()) {
                        appendDebugMessage(message)
                    }
                }
                "call:error" -> {
                    val message = data.optString("message").trim().ifBlank { "Unknown live call error" }
                    pendingVoiceFallbackText = null
                    pendingAssistantTextTurn = null
                    handler.removeCallbacks(voiceFallbackRunnable)
                    setCallStatus("call issue")
                    appendMessage("Astra", "Live call issue: $message", isAstra = true)
                    appendDebugMessage("Call error for session ${data.optString("sessionId")}: $message")
                }
                "call:session.ended" -> {
                    callMode = false
                    btnCallToggle.text = "Start Call"
                    activeCallSessionId = null
                    liveCallSocket?.close()
                    liveCallSocket = null
                    audioRecordStreamer?.stop()
                    audioRecordStreamer = null
                    audioPlaybackQueue?.stop()
                    audioPlaybackQueue = null
                    pendingVoiceFallbackText = null
                    pendingAssistantTextTurn = null
                    receivedAudioForCurrentTurn = false
                    handler.removeCallbacks(voiceFallbackRunnable)
                    assistantPlaybackActive = false
                    micGateUntilMs = 0L
                    reconnectNoticeShown = false
                    setCallStatus("ended")
                    CallStateRepository.set(CallState())
                }
                "live_task:update" -> {
                    val taskId = data.optString("id").ifBlank { null }
                    val status = data.optString("status")
                    val summary = data.optString("summary")
                    CallStateRepository.update { current -> current.copy(lastTaskId = taskId ?: current.lastTaskId) }
                    if (status == "completed" && summary.isNotBlank()) {
                        appendMessage("Astra", "Background update: $summary", isAstra = true)
                        CallStateRepository.update { current -> current.copy(lastAssistantText = summary) }
                    } else if (status == "needs_input" && summary.isNotBlank()) {
                        appendMessage("Astra", "I need your input to continue: $summary", isAstra = true)
                        CallStateRepository.update { current -> current.copy(lastAssistantText = summary) }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        callMode = false
        stopTyping()
        handler.removeCallbacks(voiceFallbackRunnable)
        runCatching { unregisterReceiver(endCallReceiver) }
        liveCallSocket?.close()
        audioRecordStreamer?.stop()
        audioPlaybackQueue?.stop()
        recognizer?.destroy()
        tts?.shutdown()
        stopService(Intent(this, CallForegroundService::class.java))
        super.onDestroy()
    }

    companion object {
        const val EXTRA_AUTO_START_CALL = "com.astra.wakeup.extra.AUTO_START_CALL"
        const val ACTION_END_CALL = "com.astra.wakeup.action.END_CALL"
    }
}
