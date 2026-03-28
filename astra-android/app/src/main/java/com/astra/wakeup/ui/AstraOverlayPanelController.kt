package com.astra.wakeup.ui

import android.Manifest
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.astra.wakeup.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class AstraOverlayPanelController(
    private val context: Context,
    private val root: View,
    private val requestMicPermission: (() -> Unit)? = null,
    private val onCloseRequested: (() -> Unit)? = null
) {
    private data class ConversationTurn(
        val speaker: String,
        val message: String,
        val isAstra: Boolean,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING }

    private val handler = Handler(Looper.getMainLooper())
    private val openClawChatClient = OpenClawChatClient()
    private val phoneControl = PhoneControlExecutor(context)
    private val conversationTurns = mutableListOf<ConversationTurn>()

    private val panelCard: View = root.findViewById(R.id.overlayPanelCard)
    private val dragHandle: View = root.findViewById(R.id.viewOverlayDragHandle)
    private val orbView: View = root.findViewById(R.id.viewOverlayOrb)
    private val wave1: View = root.findViewById(R.id.viewWave1)
    private val wave2: View = root.findViewById(R.id.viewWave2)
    private val wave3: View = root.findViewById(R.id.viewWave3)
    private val tvLatestReply: TextView = root.findViewById(R.id.tvOverlayTranscript)
    private val tvStatus: TextView = root.findViewById(R.id.tvOverlayStatus)
    private val tvConnectionBanner: TextView = root.findViewById(R.id.tvOverlayConnectionBanner)
    private val etInput: EditText = root.findViewById(R.id.etOverlayInput)
    private val btnSend: Button = root.findViewById(R.id.btnOverlaySend)
    private val btnListen: Button = root.findViewById(R.id.btnOverlayListen)

    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var waitingForReply = false
    private var autoRelistenEnabled = true
    private var shouldResumeAfterSpeech = false
    private var isAstraSpeaking = false
    private var orbAnimator: ValueAnimator? = null
    private var waveformAnimator: ValueAnimator? = null
    private var latestReplyExpanded = false

    init {
        phoneControl.setSpeechStartedListener {
            handler.post {
                isAstraSpeaking = true
                if (!waitingForReply) {
                    setListeningUi("Astra is speaking… Tap Listen to interrupt.", false)
                }
            }
        }
        phoneControl.setSpeechFinishedListener {
            handler.post {
                isAstraSpeaking = false
                if (shouldResumeAfterSpeech && autoRelistenEnabled) {
                    shouldResumeAfterSpeech = false
                    setListeningUi("Listening again…", false)
                    handler.postDelayed({ startSpeechInput(force = true) }, 500)
                }
            }
        }

        panelCard.translationY = 60f
        panelCard.alpha = 0f
        panelCard.animate().translationY(0f).alpha(1f).setDuration(220).start()
        installSwipeToDismiss()

        loadConversationHistory()
        updateConnectionBanner()
        updateOrb(OrbMode.IDLE)
        showLatestAstraReply()
        tvLatestReply.setOnClickListener {
            latestReplyExpanded = !latestReplyExpanded
            applyLatestReplyExpansion()
        }

        btnListen.setOnClickListener {
            interruptAstraSpeech()
            shouldResumeAfterSpeech = false
            startSpeechInput(force = true)
        }
        btnSend.setOnClickListener { submitTypedInput() }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitTypedInput()
                true
            } else {
                false
            }
        }
    }

    fun onShow() {
        updateConnectionBanner()
        handler.postDelayed({ startSpeechInput(force = true) }, 250)
    }

    private fun installSwipeToDismiss() {
        var downY = 0f
        var dragging = false
        dragHandle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (event.rawY - downY).coerceAtLeast(0f)
                    if (deltaY > 6f) dragging = true
                    panelCard.translationY = deltaY
                    panelCard.alpha = (1f - (deltaY / 600f)).coerceIn(0.72f, 1f)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val deltaY = (event.rawY - downY).coerceAtLeast(0f)
                    if (dragging && deltaY > 120f) {
                        interruptAstraSpeech()
                        panelCard.animate().translationY(panelCard.height.toFloat() + 120f).alpha(0f).setDuration(160).withEndAction {
                            onCloseRequested?.invoke()
                        }.start()
                    } else {
                        panelCard.animate().translationY(0f).alpha(1f).setDuration(180).start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun submitTypedInput() {
        val text = etInput.text.toString().trim()
        if (text.isBlank()) return
        interruptAstraSpeech()
        recognizer?.cancel()
        isListening = false
        shouldResumeAfterSpeech = false
        etInput.setText("")
        processUserInput(text)
    }

    private fun processUserInput(text: String) {
        appendMessage("You", text, false)
        askAstra(text)
    }

    private fun askAstra(text: String) {
        val gatewayConfig = OpenClawGatewayConfig.fromContext(context)
        val connected = context.getSharedPreferences("astra", Context.MODE_PRIVATE).getBoolean("gateway_connected", false)
        if (!connected) {
            val reply = "Overlay test mode only right now. Connect this phone to OpenClaw and then I can actually answer instead of freeloading on my own UI."
            appendMessage("Astra", reply, true)
            shouldResumeAfterSpeech = false
            speak(reply)
            setListeningUi("Overlay test mode: connect this phone for live replies.", false)
            return
        }
        waitingForReply = true
        setListeningUi("Astra is thinking…", false)

        Thread {
            val result = openClawChatClient.chat(context, gatewayConfig, text)
            val reply = result.reply ?: "Network tantrum: ${result.error ?: "unknown"}"
            handler.post {
                waitingForReply = false
                appendMessage("Astra", reply, true)
                shouldResumeAfterSpeech = autoRelistenEnabled
                speak(reply)
                setListeningUi(if (autoRelistenEnabled) "Astra is speaking… then I’ll listen again." else "Tap Listen to talk again, or just type.", false)
            }
        }.start()
    }

    private fun speak(text: String) {
        runCatching {
            phoneControl.execute("phone.tts.speak", JSONObject().put("text", text))
        }
    }

    private fun appendMessage(speaker: String, message: String, isAstra: Boolean) {
        val turn = ConversationTurn(speaker, message, isAstra)
        conversationTurns += turn
        trimConversationHistory()
        if (isAstra) {
            latestReplyExpanded = false
            tvLatestReply.text = message
            applyLatestReplyExpansion()
        }
        saveConversationHistory()
    }

    private fun showLatestAstraReply() {
        val latestAstra = conversationTurns.lastOrNull { it.isAstra }?.message
        tvLatestReply.text = latestAstra ?: "Waiting for Astra to say something interesting…"
        applyLatestReplyExpansion()
    }

    private fun applyLatestReplyExpansion() {
        TransitionManager.beginDelayedTransition(panelCard as android.view.ViewGroup, AutoTransition().apply { duration = 180 })
        tvLatestReply.maxLines = if (latestReplyExpanded) Int.MAX_VALUE else 8
        tvLatestReply.ellipsize = if (latestReplyExpanded) null else android.text.TextUtils.TruncateAt.END
        tvLatestReply.alpha = if (latestReplyExpanded) 1f else 0.96f
    }

    private fun trimConversationHistory(maxTurns: Int = 32) {
        while (conversationTurns.size > maxTurns) conversationTurns.removeAt(0)
    }

    private fun loadConversationHistory() {
        conversationTurns.clear()
        val raw = context.getSharedPreferences("astra", Context.MODE_PRIVATE).getString("astra_overlay_conversation", null).orEmpty()
        if (raw.isBlank()) return
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val speaker = obj.optString("speaker").trim()
                val message = obj.optString("message").trim()
                if (speaker.isBlank() || message.isBlank()) continue
                conversationTurns += ConversationTurn(
                    speaker = speaker,
                    message = message,
                    isAstra = obj.optBoolean("isAstra", false),
                    timestampMs = obj.optLong("timestampMs", System.currentTimeMillis())
                )
            }
            trimConversationHistory()
        }
    }

    private fun saveConversationHistory() {
        val arr = JSONArray()
        conversationTurns.forEach { turn ->
            arr.put(JSONObject().apply {
                put("speaker", turn.speaker)
                put("message", turn.message)
                put("isAstra", turn.isAstra)
                put("timestampMs", turn.timestampMs)
            })
        }
        context.getSharedPreferences("astra", Context.MODE_PRIVATE).edit().putString("astra_overlay_conversation", arr.toString()).apply()
    }

    private fun startSpeechInput(force: Boolean = false) {
        if (waitingForReply || (isListening && !force)) return
        if (isAstraSpeaking || phoneControl.isSpeaking()) interruptAstraSpeech()
        shouldResumeAfterSpeech = false

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMicPermission?.invoke()
            setListeningUi("Mic permission needed. Use the full panel once to grant it.", false)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            setListeningUi("Speech recognition unavailable on this device right now.", false)
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        isListening = true
        setListeningUi("Listening for Astra input…", true)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: android.os.Bundle?) { setListeningUi("Listening…", true) }
            override fun onBeginningOfSpeech() {
                if (isAstraSpeaking || phoneControl.isSpeaking()) interruptAstraSpeech()
                setListeningUi("Speak, darling.", true)
            }
            override fun onRmsChanged(rmsdB: Float) { updateWaveformFromRms(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { setListeningUi("Got it. Thinking…", false) }
            override fun onPartialResults(partialResults: android.os.Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (partial.isNotBlank()) {
                    etInput.setText(partial)
                    etInput.setSelection(partial.length)
                }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            override fun onError(error: Int) {
                isListening = false
                setListeningUi("Didn’t catch that. Tap Listen and try again.", false)
            }
            override fun onResults(results: android.os.Bundle?) {
                isListening = false
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (heard.isBlank()) {
                    setListeningUi("No speech caught. Tap Listen and try again.", false)
                    return
                }
                etInput.setText(heard)
                etInput.setSelection(heard.length)
                processUserInput(heard)
                etInput.setText("")
            }
        })
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
        })
    }

    private fun interruptAstraSpeech() {
        if (!isAstraSpeaking && !phoneControl.isSpeaking()) return
        shouldResumeAfterSpeech = false
        isAstraSpeaking = false
        phoneControl.stopSpeaking()
        setListeningUi("Interrupted. Listening for you instead.", false)
    }

    private fun setListeningUi(status: String, listening: Boolean) {
        tvStatus.text = status
        btnListen.text = if (listening) "Listening…" else "Listen"
        btnListen.isEnabled = !waitingForReply
        btnSend.isEnabled = !waitingForReply
        val orbMode = when {
            listening -> OrbMode.LISTENING
            waitingForReply -> OrbMode.THINKING
            isAstraSpeaking || phoneControl.isSpeaking() -> OrbMode.SPEAKING
            else -> OrbMode.IDLE
        }
        updateOrb(orbMode)
    }

    private fun updateOrb(mode: OrbMode) {
        orbAnimator?.cancel()
        orbAnimator = null
        when (mode) {
            OrbMode.IDLE -> {
                orbView.setBackgroundColor(Color.parseColor("#64748B"))
                orbView.scaleX = 1f; orbView.scaleY = 1f; orbView.alpha = 0.85f
                stopWaveformAnimation(); setWaveHeights(8,14,10); setWaveAlpha(0.45f)
            }
            OrbMode.LISTENING -> {
                orbView.setBackgroundColor(Color.parseColor("#8B5CF6"))
                startOrbPulse(0.88f,1.35f,760L); startWaveformIdleAnimation(); setWaveAlpha(1f)
            }
            OrbMode.THINKING -> {
                orbView.setBackgroundColor(Color.parseColor("#F59E0B"))
                startOrbPulse(0.92f,1.18f,980L); stopWaveformAnimation(); setWaveHeights(6,10,6); setWaveAlpha(0.7f)
            }
            OrbMode.SPEAKING -> {
                orbView.setBackgroundColor(Color.parseColor("#EC4899"))
                startOrbPulse(0.96f,1.24f,520L); stopWaveformAnimation(); setWaveHeights(9,15,12); setWaveAlpha(0.9f)
            }
        }
    }

    private fun startOrbPulse(minScale: Float, maxScale: Float, durationMs: Long) {
        orbAnimator = ValueAnimator.ofFloat(minScale, maxScale).apply {
            duration = durationMs
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                orbView.scaleX = value
                orbView.scaleY = value
                orbView.alpha = (0.72f + ((value - minScale) / (maxScale - minScale).coerceAtLeast(0.01f)) * 0.28f).coerceIn(0.72f, 1f)
            }
            start()
        }
    }

    private fun updateWaveformFromRms(rmsdB: Float) {
        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        stopWaveformAnimation()
        setWaveHeights((6 + normalized * 12).toInt(), (8 + normalized * 18).toInt(), (7 + normalized * 15).toInt())
        setWaveAlpha((0.78f + normalized * 0.22f).coerceIn(0.78f,1f))
    }

    private fun startWaveformIdleAnimation() {
        if (waveformAnimator?.isRunning == true) return
        waveformAnimator = ValueAnimator.ofFloat(0f,1f).apply {
            duration = 720L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animator ->
                val v = animator.animatedValue as Float
                setWaveHeights((8 + v * 8).toInt(), (11 + (1f - v) * 10).toInt(), (7 + v * 12).toInt())
            }
            start()
        }
    }

    private fun stopWaveformAnimation() {
        waveformAnimator?.cancel(); waveformAnimator = null
    }

    private fun setWaveHeights(h1Dp: Int, h2Dp: Int, h3Dp: Int) {
        setWaveHeight(wave1, h1Dp)
        setWaveHeight(wave2, h2Dp)
        setWaveHeight(wave3, h3Dp)
    }

    private fun setWaveHeight(view: View, heightDp: Int) {
        val px = (heightDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(4)
        val lp = view.layoutParams
        if (lp.height != px) { lp.height = px; view.layoutParams = lp }
    }

    private fun setWaveAlpha(alpha: Float) {
        wave1.alpha = alpha; wave2.alpha = alpha; wave3.alpha = alpha
    }

    private fun updateConnectionBanner() {
        val connected = context.getSharedPreferences("astra", Context.MODE_PRIVATE).getBoolean("gateway_connected", false)
        if (connected) {
            tvConnectionBanner.visibility = View.GONE
        } else {
            tvConnectionBanner.visibility = View.VISIBLE
            tvConnectionBanner.text = "Astra overlay test mode: live replies need this phone connected to OpenClaw."
        }
    }

    fun release() {
        shouldResumeAfterSpeech = false
        isAstraSpeaking = false
        orbAnimator?.cancel(); orbAnimator = null
        stopWaveformAnimation()
        recognizer?.destroy(); recognizer = null
        phoneControl.release()
    }
}
