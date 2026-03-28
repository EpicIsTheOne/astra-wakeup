package com.astra.wakeup.ui

import android.Manifest
import android.content.Intent
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
import java.util.Locale

class ChatActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private var ttsReady = false
    private var callMode = false
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var tvCall: TextView
    private lateinit var tvChat: TextView
    private lateinit var etInput: EditText
    private lateinit var layoutTyping: LinearLayout
    private lateinit var tvTyping: TextView
    private lateinit var chatScroll: ScrollView
    private var pendingResumeAfterTts = false
    private var typingDots = 0
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        tts = TextToSpeech(this, this)

        tvChat = findViewById(R.id.tvChat)
        etInput = findViewById(R.id.etChatInput)
        val btnCall = findViewById<Button>(R.id.btnCallToggle)
        val cbRemember = findViewById<CheckBox>(R.id.cbRemember)
        tvCall = findViewById(R.id.tvCallStatus)
        layoutTyping = findViewById(R.id.layoutTyping)
        tvTyping = findViewById(R.id.tvTyping)
        chatScroll = tvChat.parent as ScrollView

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

        btnCall.setOnClickListener {
            callMode = !callMode
            if (callMode) {
                btnCall.text = "End Call"
                setCallStatus("live 🎙️")
                appendMessage("Astra", "Call connected. Try not to mumble.", isAstra = true)
                speak("Call connected. Talk to me.")
                startService(Intent(this, CallForegroundService::class.java))
                startSpeechInput(singleShot = false)
            } else {
                btnCall.text = "Start Call"
                pendingResumeAfterTts = false
                setCallStatus("idle")
                recognizer?.cancel()
                stopTyping()
                stopService(Intent(this, CallForegroundService::class.java))
                speak("Call ended.")
            }
        }
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.US
            tts?.setPitch(1.1f)
            tts?.setSpeechRate(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) {
                    maybeResumeListeningAfterTts()
                }
                override fun onDone(utteranceId: String?) {
                    maybeResumeListeningAfterTts()
                }
            })
        }
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
            val reply = result.reply ?: "Network tantrum: ${result.error ?: "unknown"}"
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
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat-turn")
    }

    private fun maybeResumeListeningAfterTts() {
        runOnUiThread {
            if (!callMode || !pendingResumeAfterTts) return@runOnUiThread
            pendingResumeAfterTts = false
            setCallStatus("listening…")
            startSpeechInput(singleShot = false)
        }
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
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                etInput.setText(heard)
                if (heard.isNotBlank()) {
                    appendMessage("You", heard, isAstra = false)
                    askAstra(heard, fromCall = !singleShot, remember = false)
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

    override fun onDestroy() {
        callMode = false
        stopTyping()
        recognizer?.destroy()
        tts?.shutdown()
        stopService(Intent(this, CallForegroundService::class.java))
        super.onDestroy()
    }
}
