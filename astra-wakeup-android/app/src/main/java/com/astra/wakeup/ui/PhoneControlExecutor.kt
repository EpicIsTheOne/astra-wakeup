package com.astra.wakeup.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class PhoneControlExecutor(private val context: Context) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null
    private var mediaPlayer: MediaPlayer? = null

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.US
            tts?.setPitch(1.08f)
            tts?.setSpeechRate(1.0f)
            pendingSpeak?.let {
                pendingSpeak = null
                tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "phone-control")
            }
        }
    }

    fun execute(command: String, params: JSONObject? = null): JSONObject {
        return when (command) {
            "phone.tts.speak" -> {
                val text = params?.optString("text").orEmpty()
                if (text.isBlank()) error("missing text")
                speak(text)
                JSONObject().put("spoken", true).put("textLength", text.length)
            }
            "phone.audio.play" -> {
                val sourceType = params?.optString("sourceType").orEmpty().ifBlank { "url" }
                val source = params?.optString("source").orEmpty()
                val loop = params?.optBoolean("loop", false) ?: false
                val volume = (params?.optDouble("volume", 1.0) ?: 1.0).toFloat()
                playAudio(sourceType, source, loop, volume)
                JSONObject().put("playing", true).put("sourceType", sourceType)
            }
            "phone.audio.stop" -> {
                stopPlayback()
                JSONObject().put("stopped", true)
            }
            "phone.vibrate" -> {
                val arr = params?.optJSONArray("patternMs")
                val pattern = if (arr != null && arr.length() > 0) LongArray(arr.length()) { idx -> arr.optLong(idx, 200L) } else longArrayOf(0, 300, 120, 500)
                vibrate(pattern)
                JSONObject().put("vibrated", true)
            }
            else -> error("unsupported command: $command")
        }
    }

    fun executePlan(plan: JSONObject): JSONObject {
        val results = JSONArray()
        val actions = plan.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until actions.length()) {
            val action = actions.optJSONObject(i) ?: continue
            val command = action.optString("command")
            val params = action.optJSONObject("params")
            val result = runCatching { execute(command, params) }
            results.put(JSONObject().apply {
                put("command", command)
                put("ok", result.isSuccess)
                result.getOrNull()?.let { put("result", it) }
                result.exceptionOrNull()?.let { put("error", it.message ?: "command failed") }
            })
        }
        return JSONObject().put("results", results)
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            pendingSpeak = text
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "phone-control")
    }

    private fun playAudio(sourceType: String, source: String, loop: Boolean, volume: Float) {
        stopPlayback()
        val player = MediaPlayer()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        when (sourceType) {
            "url" -> player.setDataSource(source)
            else -> throw IllegalArgumentException("unsupported sourceType: $sourceType")
        }
        player.isLooping = loop
        player.setVolume(volume, volume)
        player.setOnPreparedListener { it.start() }
        player.prepareAsync()
        mediaPlayer = player
    }

    private fun stopPlayback() {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            reset()
            release()
        }
        mediaPlayer = null
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun release() {
        stopPlayback()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
