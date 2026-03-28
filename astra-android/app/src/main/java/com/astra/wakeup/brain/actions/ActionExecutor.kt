package com.astra.wakeup.brain.actions

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.astra.wakeup.R
import com.astra.wakeup.ui.ApiOpsClient
import java.util.Locale

class ActionExecutor(private val context: Context) {
    private var tts: TextToSpeech? = null

    fun execute(action: Action) {
        when (action) {
            is Action.Speak -> speak(action.text)
            is Action.ShowNotification -> notify(action.text)
            is Action.ChangePersonality -> context.getSharedPreferences("astra", Context.MODE_PRIVATE)
                .edit().putString("personality_mode", action.mode).apply()
            is Action.Log -> {
                val api = context.getSharedPreferences("astra", Context.MODE_PRIVATE).getString("api_url", "") ?: ""
                ApiOpsClient.log(api, action.level, action.message)
            }
        }
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TextToSpeech(context) { s ->
                if (s == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brain")
                }
            }
        } else tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brain")
    }

    private fun notify(text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel("astra_brain", "Astra Brain", NotificationManager.IMPORTANCE_DEFAULT))
        }
        nm.notify(
            (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
            NotificationCompat.Builder(context, "astra_brain")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Astra Brain")
                .setContentText(text)
                .build()
        )
    }
}
