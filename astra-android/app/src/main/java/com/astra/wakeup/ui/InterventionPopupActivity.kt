package com.astra.wakeup.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.astra.wakeup.R
import org.json.JSONObject
import java.util.Locale

class InterventionPopupActivity : AppCompatActivity() {
    private lateinit var phoneControl: PhoneControlExecutor
    private var recognizer: SpeechRecognizer? = null
    private lateinit var trackedPackage: String
    private lateinit var trackedLabel: String
    private lateinit var sessionKey: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intervention_popup)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        phoneControl = PhoneControlExecutor(this)
        trackedPackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        trackedLabel = intent.getStringExtra(EXTRA_LABEL).orEmpty().ifBlank { trackedPackage }
        sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY).orEmpty().ifBlank { "intervention-${System.currentTimeMillis()}" }

        val tvMessage = findViewById<TextView>(R.id.tvInterventionMessage)
        val etInput = findViewById<EditText>(R.id.etInterventionInput)
        val btnSend = findViewById<Button>(R.id.btnInterventionSend)
        val btnTalk = findViewById<Button>(R.id.btnInterventionTalk)
        val btnDismiss = findViewById<Button>(R.id.btnInterventionDismiss)

        val opener = intent.getStringExtra(EXTRA_MESSAGE).orEmpty().ifBlank {
            "You've spent too long in $trackedLabel. Get off and tell me what you're doing."
        }
        tvMessage.text = opener
        maybeSpeak(opener)

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isBlank()) return@setOnClickListener
            etInput.setText("")
            tvMessage.text = "Astra is thinking…"
            replyToUser(text)
        }

        btnTalk.setOnClickListener {
            startVoiceInput(etInput)
        }

        btnDismiss.setOnClickListener {
            finish()
        }
    }

    private fun replyToUser(userText: String) {
        Thread {
            val prompt = buildString {
                append("You are Astra interrupting Epic because of doomscrolling in ")
                append(trackedLabel)
                append(" (")
                append(trackedPackage)
                append("). Keep it short, direct, in-character, and helpful. Respond like a popup intervention. User said: ")
                append(JSONObject.quote(userText))
            }
            val reply = WakeChatClient.chatReply(this, getApiUrl(), prompt, sessionKey)
                ?: "Nice try. Close the app and get back to something real."
            runOnUiThread {
                findViewById<TextView>(R.id.tvInterventionMessage).text = reply
                maybeSpeak(reply)
            }
        }.start()
    }

    private fun maybeSpeak(text: String) {
        val repo = InterventionRepository(this)
        if (!repo.getState().ttsEnabled) return
        runCatching {
            phoneControl.execute("phone.tts.speak", JSONObject().put("text", text))
        }
    }

    private fun startVoiceInput(target: EditText) {
        val repo = InterventionRepository(this)
        if (!repo.getState().voiceEnabled) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 993)
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (heard.isNotBlank()) target.setText(heard)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }
        recognizer?.startListening(intent)
    }

    private fun getApiUrl(): String = getSharedPreferences("astra", MODE_PRIVATE).getString("api_url", "") ?: ""

    override fun onDestroy() {
        recognizer?.destroy()
        phoneControl.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_PACKAGE = "tracked_package"
        private const val EXTRA_LABEL = "tracked_label"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_SESSION_KEY = "session_key"

        fun open(context: Context, packageName: String, label: String, message: String, sessionKey: String) {
            val intent = Intent(context, InterventionPopupActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE, packageName)
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_MESSAGE, message)
                putExtra(EXTRA_SESSION_KEY, sessionKey)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            context.startActivity(intent)
        }
    }
}
