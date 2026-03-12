package com.astra.wakeup.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.astra.wakeup.R
import java.util.Locale

class ContextActionExecutor(private val context: Context) {
    private var tts: TextToSpeech? = null

    fun execute(rule: ContextRule, event: ContextEvent, snapshot: ContextSnapshot) {
        val apiUrl = context.getSharedPreferences("astra", Context.MODE_PRIVATE).getString("api_url", "") ?: ""

        rule.actions.forEach { action ->
            when (action.type.lowercase()) {
                "speak" -> speak(action.p1)
                "change_personality" -> {
                    context.getSharedPreferences("astra", Context.MODE_PRIVATE)
                        .edit().putString("personality_mode", action.p1.lowercase()).apply()
                    showNotification("Astra mode → ${action.p1}")
                }
                "show_notification" -> showNotification(action.p1)
                "run_cron_job" -> ApiCalendarClient.runNow(apiUrl, action.p1)
                "open_app" -> {
                    val i = context.packageManager.getLaunchIntentForPackage(action.p1)
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                    }
                }
                "start_music" -> showNotification("Music action queued: ${action.p1}")
                "send_message" -> ApiOpsClient.log(apiUrl, "info", "send_message placeholder to ${action.p1}: ${action.p2}")
            }
        }
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "context")
                }
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "context")
        }
    }

    private fun showNotification(text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("astra_context", "Astra Context", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val n = NotificationCompat.Builder(context, "astra_context")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Astra Context")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
    }
}
