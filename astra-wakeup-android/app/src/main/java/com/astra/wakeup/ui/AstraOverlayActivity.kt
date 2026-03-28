package com.astra.wakeup.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.animation.ValueAnimator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.astra.wakeup.R
import org.json.JSONObject
import java.util.Locale

class AstraOverlayActivity : AppCompatActivity() {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private var waitingForReply = false
    private var autoRelistenEnabled = true
    private var shouldResumeAfterSpeech = false
    private var isAstraSpeaking = false
    private val handler = Handler(Looper.getMainLooper())
    private val openClawChatClient = OpenClawChatClient()
    private lateinit var phoneControl: PhoneControlExecutor
    private lateinit var tvTranscript: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvConversation: TextView
    private lateinit var orbView: View
    private lateinit var panelCard: View
    private lateinit var etInput: EditText
    private lateinit var scrollConversation: ScrollView
    private var orbAnimator: ValueAnimator? = null
    private lateinit var btnSend: Button
    private lateinit var btnRetryListen: Button
    private lateinit var btnClose: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_astra_overlay)
        window.setGravity(Gravity.BOTTOM)
        window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)

        phoneControl = PhoneControlExecutor(this)
        phoneControl.setSpeechStartedListener {
            runOnUiThread {
                isAstraSpeaking = true
                if (!waitingForReply) {
                    setListeningUi(status = "Astra is speaking… Tap Listen to interrupt.", listening = false)
                }
            }
        }
        phoneControl.setSpeechFinishedListener {
            runOnUiThread {
                isAstraSpeaking = false
                if (!isFinishing && !isDestroyed && shouldResumeAfterSpeech && autoRelistenEnabled) {
                    shouldResumeAfterSpeech = false
                    setListeningUi(status = "Listening again…", listening = false)
                    handler.postDelayed({ startSpeechInput(force = true) }, 500)
                }
            }
        }
        panelCard = findViewById(R.id.overlayPanelCard)
        orbView = findViewById(R.id.viewOverlayOrb)
        tvTranscript = findViewById(R.id.tvOverlayTranscript)
        tvStatus = findViewById(R.id.tvOverlayStatus)
        tvConversation = findViewById(R.id.tvOverlayConversation)
        etInput = findViewById(R.id.etOverlayInput)
        scrollConversation = findViewById(R.id.scrollOverlayConversation)
        btnSend = findViewById(R.id.btnOverlaySend)
        btnRetryListen = findViewById(R.id.btnOverlayListen)
        btnClose = findViewById(R.id.btnOverlayClose)

        panelCard.translationY = 60f
        panelCard.alpha = 0f
        panelCard.animate().translationY(0f).alpha(1f).setDuration(220).start()

        updateOrb(OrbMode.IDLE)
        appendMessage("Astra", "Panel ready. Summon me with a tap, not fake background hotword nonsense.", true)

        btnClose.setOnClickListener { finish() }
        btnRetryListen.setOnClickListener {
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

    override fun onStart() {
        super.onStart()
        handler.postDelayed({ startSpeechInput(force = true) }, 250)
    }

    private fun submitTypedInput() {
        val text = etInput.text.toString().trim()
        if (text.isBlank()) return
        interruptAstraSpeech()
        recognizer?.cancel()
        isListening = false
        shouldResumeAfterSpeech = false
        hideKeyboard()
        etInput.setText("")
        processUserInput(text, source = "typed")
    }

    private fun processUserInput(text: String, source: String) {
        appendMessage("You", text, false)
        tvTranscript.text = if (source == "voice") "Heard: $text" else "Typed: $text"
        askAstra(text)
    }

    private fun appendMessage(speaker: String, message: String, isAstra: Boolean) {
        val speakerColor = if (isAstra) Color.parseColor("#F472B6") else Color.parseColor("#60A5FA")
        val bodyColor = if (isAstra) Color.parseColor("#FCE7F3") else Color.parseColor("#E5E7EB")
        val prefix = "$speaker: "
        val line = SpannableStringBuilder()
        if (tvConversation.text.isNotEmpty()) line.append("\n\n")

        val prefixSpan = SpannableString(prefix).apply {
            setSpan(ForegroundColorSpan(speakerColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(android.graphics.Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val bodySpan = SpannableString(message).apply {
            setSpan(ForegroundColorSpan(bodyColor), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        line.append(prefixSpan)
        line.append(bodySpan)
        tvConversation.append(line)
        scrollConversation.post { scrollConversation.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun askAstra(text: String) {
        val gatewayConfig = OpenClawGatewayConfig.fromContext(this)
        waitingForReply = true
        setListeningUi(status = "Astra is thinking…", listening = false)

        Thread {
            val result = openClawChatClient.chat(this, gatewayConfig, text)
            val reply = result.reply ?: "Network tantrum: ${result.error ?: "unknown"}"
            runOnUiThread {
                waitingForReply = false
                appendMessage("Astra", reply, true)
                shouldResumeAfterSpeech = autoRelistenEnabled
                speak(reply)
                setListeningUi(status = if (autoRelistenEnabled) "Astra is speaking… then I’ll listen again." else "Tap Listen to talk again, or just type.", listening = false)
            }
        }.start()
    }

    private fun speak(text: String) {
        runCatching {
            phoneControl.execute(
                "phone.tts.speak",
                JSONObject().apply { put("text", text) }
            )
        }
    }

    private fun interruptAstraSpeech() {
        if (!isAstraSpeaking && !phoneControl.isSpeaking()) return
        shouldResumeAfterSpeech = false
        isAstraSpeaking = false
        phoneControl.stopSpeaking()
        setListeningUi(status = "Interrupted. Listening for you instead.", listening = false)
    }

    private fun startSpeechInput(force: Boolean = false) {
        if (waitingForReply || (isListening && !force)) return

        if (isAstraSpeaking || phoneControl.isSpeaking()) {
            interruptAstraSpeech()
        }

        shouldResumeAfterSpeech = false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 993)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setListeningUi(status = "Speech recognition unavailable on this device right now.", listening = false)
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        isListening = true
        setListeningUi(status = "Listening for Astra input…", listening = true)

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setListeningUi(status = "Listening…", listening = true)
            }

            override fun onBeginningOfSpeech() {
                if (isAstraSpeaking || phoneControl.isSpeaking()) {
                    interruptAstraSpeech()
                }
                setListeningUi(status = "Speak, darling.", listening = true)
            }

            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                setListeningUi(status = "Got it. Thinking…", listening = false)
            }

            override fun onError(error: Int) {
                isListening = false
                setListeningUi(status = "Didn’t catch that. Tap Listen and try again.", listening = false)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    tvTranscript.text = "Hearing: $partial"
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onResults(results: Bundle?) {
                isListening = false
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()

                if (heard.isBlank()) {
                    setListeningUi(status = "No speech caught. Tap Listen and try again.", listening = false)
                    return
                }

                processUserInput(heard, source = "voice")
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 700L)
        }
        recognizer?.startListening(intent)
    }

    private enum class OrbMode { IDLE, LISTENING, THINKING, SPEAKING }

    private fun setListeningUi(status: String, listening: Boolean) {
        tvStatus.text = status
        btnRetryListen.text = if (listening) "Listening…" else "Listen"
        btnRetryListen.isEnabled = !waitingForReply
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
                orbView.scaleX = 1f
                orbView.scaleY = 1f
                orbView.alpha = 0.85f
            }
            OrbMode.LISTENING -> {
                orbView.setBackgroundColor(Color.parseColor("#8B5CF6"))
                startOrbPulse(0.88f, 1.35f, 760L)
            }
            OrbMode.THINKING -> {
                orbView.setBackgroundColor(Color.parseColor("#F59E0B"))
                startOrbPulse(0.92f, 1.18f, 980L)
            }
            OrbMode.SPEAKING -> {
                orbView.setBackgroundColor(Color.parseColor("#EC4899"))
                startOrbPulse(0.96f, 1.24f, 520L)
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

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(etInput.windowToken, 0)
        etInput.clearFocus()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 993) {
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (granted) {
                startSpeechInput(force = true)
            } else {
                Toast.makeText(this, "Microphone permission is required for voice input", Toast.LENGTH_SHORT).show()
                setListeningUi(status = "Mic permission denied. You can still type.", listening = false)
            }
        }
    }

    override fun onStop() {
        shouldResumeAfterSpeech = false
        isAstraSpeaking = false
        recognizer?.cancel()
        isListening = false
        super.onStop()
    }

    override fun onDestroy() {
        orbAnimator?.cancel()
        orbAnimator = null
        recognizer?.destroy()
        recognizer = null
        phoneControl.release()
        super.onDestroy()
    }
}
