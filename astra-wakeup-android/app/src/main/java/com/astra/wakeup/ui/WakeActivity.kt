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
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.astra.wakeup.R
import com.astra.wakeup.alarm.AlarmScheduler
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class WakeActivity : AppCompatActivity() {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var acknowledged = false
    private var isListening = false
    private var wakeTurn = 0
    private var outputsStopped = false
    private var wakeMediaCatalog = "Loading wake-ready Media Center assets..."
    private lateinit var phoneControl: PhoneControlExecutor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        phoneControl = PhoneControlExecutor(this)
        getSharedPreferences("astra", MODE_PRIVATE).edit().putLong("last_alarm_triggered_at", System.currentTimeMillis()).apply()

        preloadWakeMediaCatalog()
        requestWakeTurn(reason = "initial")

        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        if (prefs.getBoolean("punish", true)) scheduleWakeLoop()

        findViewById<Button>(R.id.btnTalk).setOnClickListener {
            startListeningWithVAD(force = true)
        }

        findViewById<Button>(R.id.btnAwake).setOnClickListener {
            acknowledged = true
            getSharedPreferences("astra", MODE_PRIVATE).edit().putLong("last_alarm_dismissed_at", System.currentTimeMillis()).apply()
            stopWakeOutputs()
            finish()
        }

        findViewById<Button>(R.id.btnSnooze).setOnClickListener {
            acknowledged = true
            getSharedPreferences("astra", MODE_PRIVATE).edit().putLong("last_alarm_dismissed_at", System.currentTimeMillis()).apply()
            stopWakeOutputs()
            AlarmScheduler.scheduleSnooze(this, 10)
            finish()
        }
    }

    private fun preloadWakeMediaCatalog() {
        Thread {
            val result = runCatching { MediaCenterClient.fetchWakeAssets(this) }
            wakeMediaCatalog = result.getOrNull()?.let { assets ->
                MediaCenterClient.assetCatalogText(assets, limit = 12)
            } ?: "Media Center assets could not be loaded right now."
        }.start()
    }

    private fun requestWakeTurn(reason: String) {
        if (acknowledged) return
        wakeTurn += 1
        val lineView = findViewById<TextView>(R.id.tvLine)
        lineView.text = "Astra is waking you up…"

        val prompt = buildString {
            append("You are Astra waking Epic up through an Android alarm screen. ")
            append("Return ONLY valid compact JSON with this exact shape: ")
            append("{\"speech\":string,\"actions\":[{\"command\":string,\"params\":object}]}. ")
            append("The speech must be Astra talking directly to Epic in 1-3 short sentences. ")
            append("No markdown. No prose outside JSON. No code fences. ")
            append("Allowed commands are phone.tts.speak, phone.audio.play, phone.audio.stop, phone.vibrate. ")
            append("If you use phone.audio.play, sourceType must be url and source must be one of the wake-ready Media Center URLs listed below. ")
            append("Use actions only when you actually want the phone to do something. ")
            append("The app itself will execute the actions. ")
            append("Wake-ready media catalog:\n")
            append(wakeMediaCatalog)
            append("\n")
            append("Epic is not awake yet. Reason for this turn: $reason. Wake turn number: $wakeTurn. ")
            append("Stable target info if you need to refer to the phone later: nodeId=")
            append(NodeIdentity.getStableNodeId(this@WakeActivity))
            append(", instanceId=")
            append(NodeIdentity.getNodeInstanceId(this@WakeActivity))
            append(".")
        }

        Thread {
            val raw = WakeChatClient.chatReply(this, getApiUrl(), prompt)
            val plan = WakePlanParser.parse(raw)
            runOnUiThread {
                renderPlan(plan)
            }
        }.start()
    }

    private fun renderPlan(plan: WakePlan) {
        val lineView = findViewById<TextView>(R.id.tvLine)
        lineView.text = plan.speech

        val actions = JSONArray()
        if (plan.speech.isNotBlank()) {
            actions.put(JSONObject().apply {
                put("command", "phone.tts.speak")
                put("params", JSONObject().put("text", plan.speech))
            })
        }
        for (i in 0 until plan.actions.length()) {
            actions.put(plan.actions.opt(i))
        }
        phoneControl.executePlan(JSONObject().put("actions", actions))
    }

    private fun startListeningWithVAD(force: Boolean = false) {
        if (acknowledged || (!force && isListening)) return

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 991)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            val transcriptView = findViewById<TextView>(R.id.tvTranscript)
            transcriptView.text = "You: speech recognition unavailable"
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val transcriptView = findViewById<TextView>(R.id.tvTranscript)
        transcriptView.text = "You: listening..."
        isListening = true

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                isListening = false
                transcriptView.text = "You: (didn't catch that)"
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                isListening = false
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()

                if (heard.isBlank()) {
                    transcriptView.text = "You: ..."
                    return
                }

                transcriptView.text = "You: $heard"
                respondToUserSpeech(heard)
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

    private fun respondToUserSpeech(userText: String) {
        val lineView = findViewById<TextView>(R.id.tvLine)
        lineView.text = "Astra is thinking…"

        val prompt = buildString {
            append("You are Astra in an Android wake alarm conversation with Epic. ")
            append("Epic just said: ")
            append(JSONObject.quote(userText))
            append(". Return ONLY valid compact JSON with this exact shape: ")
            append("{\"speech\":string,\"actions\":[{\"command\":string,\"params\":object}]}. ")
            append("Allowed commands are phone.tts.speak, phone.audio.play, phone.audio.stop, phone.vibrate. ")
            append("If you use phone.audio.play, sourceType must be url and source must be one of these wake-ready Media Center URLs:\n")
            append(wakeMediaCatalog)
            append("\nKeep it short, direct, and in-character. No markdown.")
        }

        Thread {
            val raw = WakeChatClient.wakeReply(this, getApiUrl(), prompt)
            val plan = WakePlanParser.parse(raw)
            runOnUiThread {
                renderPlan(plan)
            }
        }.start()
    }

    private fun scheduleWakeLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (acknowledged) return
                if (!isListening) {
                    requestWakeTurn(reason = "follow-up")
                }
                handler.postDelayed(this, 20_000)
            }
        }, 20_000)
    }

    private fun stopWakeOutputs() {
        if (outputsStopped) return
        outputsStopped = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        isListening = false
        if (::phoneControl.isInitialized) {
            runCatching { phoneControl.execute("phone.audio.stop", JSONObject()) }
            phoneControl.release()
        }
    }

    private fun getApiUrl(): String {
        return getSharedPreferences("astra", MODE_PRIVATE).getString("api_url", "") ?: ""
    }

    override fun onDestroy() {
        stopWakeOutputs()
        super.onDestroy()
    }
}
