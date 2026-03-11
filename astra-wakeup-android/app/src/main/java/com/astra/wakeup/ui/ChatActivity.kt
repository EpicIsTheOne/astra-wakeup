package com.astra.wakeup.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        tts = TextToSpeech(this, this)

        val tvChat = findViewById<TextView>(R.id.tvChat)
        val etInput = findViewById<EditText>(R.id.etChatInput)
        val btnCall = findViewById<Button>(R.id.btnCallToggle)
        val tvCall = findViewById<TextView>(R.id.tvCallStatus)

        findViewById<Button>(R.id.btnChatSend).setOnClickListener {
            val msg = etInput.text.toString().trim()
            if (msg.isNotBlank()) {
                tvChat.append("\nYou: $msg")
                etInput.setText("")
                askAstra(msg, tvChat, fromCall = false)
            }
        }

        findViewById<Button>(R.id.btnChatTalk).setOnClickListener {
            startSpeechInput(etInput, tvChat, singleShot = true)
        }

        btnCall.setOnClickListener {
            callMode = !callMode
            if (callMode) {
                btnCall.text = "End Call"
                tvCall.text = "Call: live 🎙️"
                tvChat.append("\nAstra: Call connected. Try not to mumble.")
                speak("Call connected. Talk to me.")
                startSpeechInput(etInput, tvChat, singleShot = false)
            } else {
                btnCall.text = "Start Call"
                tvCall.text = "Call: idle"
                recognizer?.cancel()
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
        }
    }

    private fun askAstra(text: String, tv: TextView, fromCall: Boolean) {
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "") ?: ""
        Thread {
            val reply = WakeChatClient.chatReply(apiUrl, text) ?: "Network tantrum. Try again."
            runOnUiThread {
                tv.append("\nAstra: $reply")
                speak(reply)
                if (fromCall && callMode) {
                    handler.postDelayed({
                        val input = findViewById<EditText>(R.id.etChatInput)
                        startSpeechInput(input, tv, singleShot = false)
                    }, 800)
                }
            }
        }.start()
    }

    private fun speak(text: String) {
        if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "chat")
    }

    private fun startSpeechInput(etInput: EditText, tvChat: TextView, singleShot: Boolean) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 992)
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
            override fun onError(error: Int) {
                if (!singleShot && callMode) {
                    handler.postDelayed({ startSpeechInput(etInput, tvChat, singleShot = false) }, 1000)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                etInput.setText(heard)
                if (heard.isNotBlank()) {
                    tvChat.append("\nYou: $heard")
                    askAstra(heard, tvChat, fromCall = !singleShot)
                } else if (!singleShot && callMode) {
                    handler.postDelayed({ startSpeechInput(etInput, tvChat, singleShot = false) }, 800)
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
        recognizer?.destroy()
        tts?.shutdown()
        super.onDestroy()
    }
}
