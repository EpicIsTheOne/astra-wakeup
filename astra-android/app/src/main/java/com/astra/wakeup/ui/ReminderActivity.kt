package com.astra.wakeup.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class ReminderActivity : AppCompatActivity() {
    private var recognizer: SpeechRecognizer? = null
    private var isListening = false
    private lateinit var phoneControl: PhoneControlExecutor
    private var reminder: ReminderItem? = null
    private val sessionKey = "reminder-${UUID.randomUUID()}"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reminder)
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
        getSystemService(KeyguardManager::class.java)?.let { keyguard ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguard.isKeyguardLocked) keyguard.requestDismissKeyguard(this, null)
        }
        phoneControl = PhoneControlExecutor(this)
        reminder = ReminderRepository.getReminder(this, intent.getStringExtra("reminder_id").orEmpty())
        renderReminder()
        speakReminderIntro()

        findViewById<Button>(R.id.btnReminderTalk).setOnClickListener { startListening() }
        findViewById<Button>(R.id.btnRemindLater).setOnClickListener { remindLater() }
        findViewById<Button>(R.id.btnDone).setOnClickListener { markDone() }
    }

    private fun renderReminder() {
        val item = reminder ?: return finish()
        findViewById<TextView>(R.id.tvReminderTitle).text = item.title
        findViewById<TextView>(R.id.tvReminderMeta).text = buildString {
            append("${importanceLabel(item.importance)} · ${annoyanceLabel(item.annoyanceLevel)}")
            append(" · ${if (item.followUpState.contains("verification")) "Verification check" else "Scheduled reminder"}")
            append("\nDue ${formatTimestamp(item.scheduledTimeMillis)}")
            item.linkedTaskId?.let { linked ->
                ReminderRepository.getTask(this@ReminderActivity, linked)?.let { append("\nTask: ${it.title}") }
            }
        }
        findViewById<Button>(R.id.btnRemindLater).text = "Remind me later"
    }

    private fun speakReminderIntro() {
        val item = reminder ?: return
        val line = if (item.followUpState.contains("verification")) {
            "Verification check. Did you actually handle this: ${item.title}?"
        } else {
            "Reminder time. ${item.title}."
        }
        findViewById<TextView>(R.id.tvReminderLine).text = line
        phoneControl.execute("phone.tts.speak", JSONObject().put("text", line).put("volume", 0.85))
    }

    private fun remindLater() {
        val item = reminder ?: return
        val nextTime = ReminderScheduler.computeLaterTime(item)
        val updated = item.copy(scheduledTimeMillis = nextTime, snoozeCount = item.snoozeCount + 1, followUpState = "snoozed", enabled = true)
        ReminderRepository.upsertReminder(this, updated)
        ReminderNotifier.clear(this)
        ReminderForegroundService.stop(this)
        finish()
    }

    private fun markDone() {
        val item = reminder ?: return
        item.linkedTaskId?.let { taskId ->
            ReminderRepository.getTask(this, taskId)?.let { task ->
                ReminderRepository.upsertTask(this, task.copy(done = true, completedAtMillis = System.currentTimeMillis()))
            }
        }
        val followUp = ReminderScheduler.maybeCreateVerificationFollowUp(item)
        val updated = if (followUp != null) {
            followUp
        } else {
            val nextTime = when (item.repeatRule) {
                "daily" -> item.scheduledTimeMillis + 24 * 60 * 60 * 1000L
                "weekly" -> item.scheduledTimeMillis + 7 * 24 * 60 * 60 * 1000L
                else -> item.scheduledTimeMillis
            }
            item.copy(
                enabled = item.repeatRule != "once",
                scheduledTimeMillis = nextTime,
                followUpState = "done",
                snoozeCount = 0
            )
        }
        ReminderRepository.upsertReminder(this, updated)
        ReminderNotifier.clear(this)
        ReminderForegroundService.stop(this)
        finish()
    }

    private fun startListening() {
        if (isListening) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 441)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        isListening = true
        findViewById<TextView>(R.id.tvReminderTranscript).text = "You: listening..."
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) { isListening = false }
            override fun onResults(results: Bundle?) {
                isListening = false
                val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                findViewById<TextView>(R.id.tvReminderTranscript).text = "You: $heard"
                replyToSpeech(heard)
            }
        })
        recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
        })
    }

    private fun replyToSpeech(userText: String) {
        val item = reminder ?: return
        val prompt = "You are Astra responding to a live Android reminder screen. The reminder is ${JSONObject.quote(item.title)}. User said ${JSONObject.quote(userText)}. Reply with one concise sentence only, no markdown."
        Thread {
            val reply = runCatching { WakeChatClient.wakeReply(this, getSharedPreferences("astra", MODE_PRIVATE).getString("api_url", "") ?: "", prompt, sessionKey) }.getOrDefault("Handle the thing, then tap the button, menace.")
            runOnUiThread {
                findViewById<TextView>(R.id.tvReminderLine).text = reply
                phoneControl.execute("phone.tts.speak", JSONObject().put("text", reply).put("volume", 0.88))
            }
        }.start()
    }

    override fun onDestroy() {
        recognizer?.destroy()
        phoneControl.release()
        super.onDestroy()
    }
}
