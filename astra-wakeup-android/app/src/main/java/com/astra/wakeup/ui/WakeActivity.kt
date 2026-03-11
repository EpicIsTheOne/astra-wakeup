package com.astra.wakeup.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.astra.wakeup.R
import com.astra.wakeup.alarm.AlarmScheduler
import java.util.Locale

class WakeActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private var ringtone: Ringtone? = null
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var acknowledged = false
    private var ttsReady = false
    private var pendingSpeech: String? = null

    private val fallbackLines = listOf(
        "Wake up, Epic. Move now.",
        "Wake up, dumbass. Right now.",
        "Wake up~ now, sleepy chaos goblin."
    )

    private val punishmentShots = listOf(
        "Wake up dumbass.",
        "Wake up~",
        "Now.",
        "Up. Now."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wake)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        tts = TextToSpeech(this, this)

        playRandomSfx()
        loadDynamicLineAndSpeak(false)

        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        if (prefs.getBoolean("punish", true)) schedulePunishmentLoop()

        findViewById<Button>(R.id.btnTalk).setOnClickListener {
            startListeningWithVAD()
        }

        findViewById<Button>(R.id.btnAwake).setOnClickListener {
            acknowledged = true
            stopAudio()
            finish()
        }

        findViewById<Button>(R.id.btnSnooze).setOnClickListener {
            acknowledged = true
            stopAudio()
            AlarmScheduler.scheduleSnooze(this, 10)
            finish()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            val profile = getSharedPreferences("astra", MODE_PRIVATE).getString("wake_profile", "bully") ?: "bully"
            when (profile) {
                "gentle" -> { tts?.setPitch(1.0f); tts?.setSpeechRate(0.95f) }
                "normal" -> { tts?.setPitch(1.08f); tts?.setSpeechRate(1.0f) }
                "nuclear" -> { tts?.setPitch(1.25f); tts?.setSpeechRate(1.08f) }
                else -> { tts?.setPitch(1.15f); tts?.setSpeechRate(1.02f) }
            }
            val femaleVoice = tts?.voices?.firstOrNull { v: Voice ->
                val n = v.name.lowercase(Locale.US)
                n.contains("female") || n.contains("fem") || n.contains("woman") || n.contains("girl")
            }
            if (femaleVoice != null) tts?.voice = femaleVoice
            ttsReady = true
            pendingSpeech?.let {
                pendingSpeech = null
                speakNow(it)
            }
        }
    }

    private fun loadDynamicLineAndSpeak(punishment: Boolean) {
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "") ?: ""
        val wakeProfile = prefs.getString("wake_profile", "bully") ?: "bully"

        Thread {
            val result = WakeMessageClient.fetchLineResult(apiUrl, punishment, wakeProfile)
            val line = result?.line ?: if (punishment) punishmentShots.random() else fallbackLines.random()
            val mission = result?.mission

            runOnUiThread {
                val display = if (!mission.isNullOrBlank() && !punishment) "$line\n\nMission: $mission" else line
                findViewById<TextView>(R.id.tvLine).text = display
                speak(line)
            }
        }.start()
    }

    private fun startListeningWithVAD() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 991)
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition isn't available on this phone.")
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val transcriptView = findViewById<TextView>(R.id.tvTranscript)
        transcriptView.text = "You: listening..."

        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                transcriptView.text = "You: (didn't catch that)"
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
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
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        val apiUrl = prefs.getString("api_url", "") ?: ""

        Thread {
            val reply = WakeChatClient.wakeReply(apiUrl, userText)
                ?: "Nice try. You're still waking up now."

            runOnUiThread {
                findViewById<TextView>(R.id.tvLine).text = reply
                speak(reply)
            }
        }.start()
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            pendingSpeech = text
            return
        }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "wake-line")
    }

    private fun playRandomSfx() {
        val prefs = getSharedPreferences("astra", MODE_PRIVATE)
        if (!prefs.getBoolean("random_sfx", true)) return
        val profile = prefs.getString("wake_profile", "bully") ?: "bully"

        val typeChoices = when (profile) {
            "gentle" -> listOf(RingtoneManager.TYPE_NOTIFICATION)
            "normal" -> listOf(RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_ALARM)
            "nuclear" -> listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE)
            else -> listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_NOTIFICATION, RingtoneManager.TYPE_RINGTONE)
        }
        val type = typeChoices.random()

        val soundUri = RingtoneManager.getDefaultUri(type)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        ringtone = RingtoneManager.getRingtone(this, soundUri)
        ringtone?.streamType = AudioManager.STREAM_ALARM
        ringtone?.play()

        val vibePattern = when (profile) {
            "gentle" -> longArrayOf(0, 250, 150, 250)
            "normal" -> longArrayOf(0, 350, 120, 450)
            "nuclear" -> longArrayOf(0, 600, 80, 700, 80, 700)
            else -> longArrayOf(0, 400, 150, 600)
        }

        (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.let { v ->
            v.vibrate(VibrationEffect.createWaveform(vibePattern, -1))
        }
    }

    private fun schedulePunishmentLoop() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (acknowledged) return
                playRandomSfx()
                loadDynamicLineAndSpeak(true)
                handler.postDelayed(this, 20_000)
            }
        }, 20_000)
    }

    private fun stopAudio() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        ringtone?.stop()
        ringtone = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    override fun onDestroy() {
        stopAudio()
        super.onDestroy()
    }
}
