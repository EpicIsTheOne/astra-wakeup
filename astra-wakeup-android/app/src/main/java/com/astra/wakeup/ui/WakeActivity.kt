package com.astra.wakeup.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import com.astra.wakeup.alarm.AlarmNotifier
import com.astra.wakeup.alarm.AlarmScheduler
import com.astra.wakeup.alarm.WakeForegroundService
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class WakeActivity : AppCompatActivity() {
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var acknowledged = false
    private var isListening = false
    private var wakeTurn = 0
    private var outputsStopped = false
    private var wakeMediaCatalog = "Loading wake-ready Media Center assets..."
    private var wakeMusicAssets: List<MediaCenterAsset> = emptyList()
    private var wakeSfxAssets: List<MediaCenterAsset> = emptyList()
    private var currentMusicAsset: MediaCenterAsset? = null
    private val wakeSessionKey = "wake-${UUID.randomUUID()}"
    private lateinit var phoneControl: PhoneControlExecutor

    private fun getVoiceVolume(): Double = getSharedPreferences("astra", MODE_PRIVATE).getInt("wake_voice_volume", 70) / 100.0
    private fun getMusicVolume(): Double = getSharedPreferences("astra", MODE_PRIVATE).getInt("wake_music_volume", 35) / 100.0
    private fun getSfxVolume(): Double = getSharedPreferences("astra", MODE_PRIVATE).getInt("wake_sfx_volume", 90) / 100.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager?.isKeyguardLocked == true) {
            keyguardManager.requestDismissKeyguard(this, null)
        }

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
            AlarmNotifier.clearWakeAlarm(this)
            finish()
        }

        findViewById<Button>(R.id.btnSnooze).setOnClickListener {
            acknowledged = true
            getSharedPreferences("astra", MODE_PRIVATE).edit().putLong("last_alarm_dismissed_at", System.currentTimeMillis()).apply()
            stopWakeOutputs()
            AlarmNotifier.clearWakeAlarm(this)
            WakeForegroundService.stop(this)
            val snoozed = AlarmScheduler.scheduleSnooze(this, 10)
            getSharedPreferences("astra", MODE_PRIVATE).edit().putBoolean("wake_enabled", snoozed).apply()
            finish()
        }
    }

    private fun preloadWakeMediaCatalog() {
        Thread {
            val result = runCatching { MediaCenterClient.fetchWakeAssets(this) }
            val assets = result.getOrNull()
            if (assets != null) {
                wakeMusicAssets = assets.filter { it.collection == "wake-music" }
                wakeSfxAssets = assets.filter { it.collection == "wake-sfx" }
                currentMusicAsset = wakeMusicAssets.randomOrNull()
            }
            wakeMediaCatalog = assets?.let { loaded ->
                MediaCenterClient.assetCatalogText(loaded, limit = 12)
            } ?: "Media Center assets could not be loaded right now."
            runOnUiThread {
                announceChosenWakeSong()
                startWakeMusicIfAvailable()
            }
        }.start()
    }

    private fun currentMusicSummary(): String {
        val current = currentMusicAsset
        return if (current == null) {
            "No app-selected wake song is currently available."
        } else {
            "Current app-selected wake song: title=${JSONObject.quote(current.title)} url=${current.publicUrl}"
        }
    }

    private fun announceChosenWakeSong() {
        val transcriptView = findViewById<TextView>(R.id.tvTranscript)
        val current = currentMusicAsset
        transcriptView.text = if (current == null) {
            "Wake song: none"
        } else {
            "Wake song: ${current.title}"
        }
    }

    private fun startWakeMusicIfAvailable() {
        val current = currentMusicAsset ?: return
        runCatching {
            phoneControl.execute(
                "phone.audio.play",
                JSONObject().apply {
                    put("sourceType", "url")
                    put("source", current.publicUrl)
                    put("loop", true)
                    put("channel", "music")
                    put("volume", getMusicVolume())
                }
            )
        }
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
            append("Prefer speech first and sound effects as occasional accents. Use phone.vibrate rarely and only if you genuinely need emphasis, not by default. ")
            append("The app itself has already picked and started a wake song if one was available. Do not choose the initial music yourself. ")
            append("If you later want to change songs, sourceType must be url and source must be one of the wake-ready Media Center URLs listed below. ")
            append("On later turns, you may keep the current song, switch to another listed wake-music URL, or stop/change music if it helps. ")
            append("Promote sound effects sometimes, but do not overdo them or stack noise constantly. ")
            append("Use actions only when you actually want the phone to do something. ")
            append("The app itself will execute the actions. ")
            append(currentMusicSummary())
            append("\nWake-ready media catalog:\n")
            append(wakeMediaCatalog)
            append("\n")
            append("Epic is not awake yet. Reason for this turn: $reason. Wake turn number: $wakeTurn. ")
            append("Stable target info if you need to refer to the phone later: nodeId=")
            append(NodeIdentity.getStableNodeId(this@WakeActivity))
            append(", instanceId=")
            append(NodeIdentity.getNodeInstanceId(this@WakeActivity))
            append(", wakeSessionKey=")
            append(wakeSessionKey)
            append(". This wake session is fresh for this alarm and should not rely on old session context.")
        }

        Thread {
            val raw = WakeChatClient.chatReply(this, getApiUrl(), prompt, wakeSessionKey)
            val plan = WakePlanParser.parse(raw)
            runOnUiThread {
                renderPlan(plan)
            }
        }.start()
    }

    private fun renderPlan(plan: WakePlan) {
        val lineView = findViewById<TextView>(R.id.tvLine)
        lineView.text = plan.speech

        for (i in 0 until plan.actions.length()) {
            val action = plan.actions.optJSONObject(i) ?: continue
            if (action.optString("command") == "phone.audio.play") {
                val params = action.optJSONObject("params")
                val chosenChannel = params?.optString("channel").orEmpty().ifBlank {
                    val source = params?.optString("source").orEmpty()
                    if (wakeMusicAssets.any { it.publicUrl == source }) "music" else "sfx"
                }
                params?.put("channel", chosenChannel)
                if (chosenChannel == "music") {
                    params?.put("volume", getMusicVolume())
                } else {
                    params?.put("volume", getSfxVolume())
                }
                val source = params?.optString("source").orEmpty()
                if (source.isNotBlank()) {
                    currentMusicAsset = wakeMusicAssets.firstOrNull { it.publicUrl == source } ?: currentMusicAsset
                    announceChosenWakeSong()
                }
            }
        }

        val actions = JSONArray()
        if (plan.speech.isNotBlank()) {
            actions.put(JSONObject().apply {
                put("command", "phone.tts.speak")
                put("params", JSONObject().put("text", plan.speech).put("volume", getVoiceVolume()))
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
            append("The app already chose and started a wake song if one was available. ")
            append("Keep music going during the wake flow unless there is a good reason to stop or switch it. ")
            append("You may change the song mid-wake if it helps, using another listed wake-music URL. ")
            append("Promote sound effects sometimes, but do not overdo them. Use phone.vibrate rarely and only if truly needed. ")
            append(currentMusicSummary())
            append("\nIf you use phone.audio.play, sourceType must be url and source must be one of these wake-ready Media Center URLs:\n")
            append(wakeMediaCatalog)
            append("\nKeep it short, direct, and in-character. No markdown.")
        }

        Thread {
            val raw = WakeChatClient.wakeReply(this, getApiUrl(), prompt, wakeSessionKey)
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
