package com.astra.wakeup.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class PhoneControlExecutor(private val context: Context) : TextToSpeech.OnInitListener {
    private data class PendingSpeech(val text: String, val volume: Float)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: PendingSpeech? = null
    private var musicPlayer: MediaPlayer? = null
    private var sfxPlayer: MediaPlayer? = null
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                stopPlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                val duckedMusic = (defaultMusicVolume() * 0.35f).coerceAtLeast(0.03f)
                val duckedSfx = (defaultSfxVolume() * 0.35f).coerceAtLeast(0.08f)
                musicPlayer?.setVolume(duckedMusic, duckedMusic)
                sfxPlayer?.setVolume(duckedSfx, duckedSfx)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val music = defaultMusicVolume()
                val sfx = defaultSfxVolume()
                musicPlayer?.setVolume(music, music)
                sfxPlayer?.setVolume(sfx, sfx)
            }
        }
    }
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    init {
        tts = TextToSpeech(context.applicationContext, this)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language = Locale.US
            tts?.setPitch(1.08f)
            tts?.setSpeechRate(1.0f)
            tts?.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            pendingSpeak?.let {
                pendingSpeak = null
                speakNow(it.text, it.volume)
            }
        }
    }

    fun execute(command: String, params: JSONObject? = null): JSONObject {
        return when (command) {
            "phone.tts.speak" -> {
                val text = params?.optString("text").orEmpty()
                if (text.isBlank()) error("missing text")
                val volume = (params?.optDouble("volume", defaultVoiceVolume().toDouble()) ?: defaultVoiceVolume().toDouble()).toFloat()
                speak(text, volume)
                JSONObject().put("spoken", true).put("textLength", text.length).put("volume", volume)
            }
            "phone.audio.play" -> {
                val sourceType = params?.optString("sourceType").orEmpty().ifBlank { "url" }
                val source = params?.optString("source").orEmpty()
                val loop = params?.optBoolean("loop", false) ?: false
                val volume = (params?.optDouble("volume", 1.0) ?: 1.0).toFloat()
                val channel = params?.optString("channel").orEmpty().ifBlank { if (loop) "music" else "sfx" }
                playAudio(channel, sourceType, source, loop, volume)
                JSONObject().put("playing", true).put("sourceType", sourceType).put("channel", channel)
            }
            "phone.audio.stop" -> {
                val channel = params?.optString("channel").orEmpty()
                stopPlayback(channel)
                JSONObject().put("stopped", true).put("channel", channel.ifBlank { "all" })
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

    private fun speak(text: String, volume: Float = defaultVoiceVolume()) {
        requestAlarmAudioFocus()
        if (!ttsReady) {
            pendingSpeak = PendingSpeech(text, volume)
            return
        }
        speakNow(text, volume)
    }

    private fun speakNow(text: String, volume: Float) {
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume.coerceIn(0f, 1f))
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "phone-control")
    }

    private fun playAudio(channel: String, sourceType: String, source: String, loop: Boolean, volume: Float) {
        requestAlarmAudioFocus()
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

        when (channel) {
            "music" -> {
                stopPlayer(musicPlayer)
                musicPlayer = player
            }
            else -> {
                stopPlayer(sfxPlayer)
                sfxPlayer = player
            }
        }
    }

    private fun stopPlayback(channel: String = "") {
        when (channel) {
            "music" -> {
                stopPlayer(musicPlayer)
                musicPlayer = null
            }
            "sfx" -> {
                stopPlayer(sfxPlayer)
                sfxPlayer = null
            }
            else -> {
                stopPlayer(musicPlayer)
                stopPlayer(sfxPlayer)
                musicPlayer = null
                sfxPlayer = null
            }
        }
        if (musicPlayer == null && sfxPlayer == null) {
            abandonAlarmAudioFocus()
        }
    }

    private fun stopPlayer(player: MediaPlayer?) {
        player?.runCatching {
            if (isPlaying) stop()
            reset()
            release()
        }
    }

    private fun defaultVoiceVolume(): Float = prefVolume("wake_voice_volume", 70)

    private fun defaultMusicVolume(): Float = prefVolume("wake_music_volume", 35)

    private fun defaultSfxVolume(): Float = prefVolume("wake_sfx_volume", 90)

    private fun prefVolume(key: String, defaultPercent: Int): Float {
        val prefs = context.getSharedPreferences("astra", Context.MODE_PRIVATE)
        val percent = prefs.getInt(key, defaultPercent).coerceIn(0, 100)
        return (percent / 100f).coerceIn(0f, 1f)
    }

    private fun vibrate(pattern: LongArray) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    private fun requestAlarmAudioFocus() {
        if (hasAudioFocus) return
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(false)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_ALARM,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAlarmAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
        hasAudioFocus = false
    }

    fun release() {
        stopPlayback()
        tts?.stop()
        tts?.shutdown()
        tts = null
        abandonAlarmAudioFocus()
    }
}
