package com.astra.wakeup.ui

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64

class AudioRecordStreamer(
    private val sampleRateHz: Int = 16_000,
    private val shouldStreamChunk: ((pcm16: ByteArray) -> Boolean)? = null,
    private val onChunk: (pcm16Base64: String) -> Unit,
    private val onError: (String) -> Unit,
    private val onDebug: (String) -> Unit = {},
) {
    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var recorder: AudioRecord? = null

    fun start() {
        if (running) return
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            onError("AudioRecord buffer init failed")
            return
        }
        onDebug("AudioRecord minBuffer=$minBuffer sampleRate=$sampleRateHz")
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        recorder = audioRecord
        val sessionId = audioRecord.audioSessionId
        val aec = runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            } else null
        }.getOrNull()
        val ns = runCatching {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            } else null
        }.getOrNull()
        val agc = runCatching {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            } else null
        }.getOrNull()
        onDebug("AudioRecord effects aec=${aec != null} ns=${ns != null} agc=${agc != null}")
        running = true
        thread = Thread {
            var readCount = 0
            try {
                audioRecord.startRecording()
                onDebug("AudioRecord started state=${audioRecord.recordingState}")
                val buffer = ByteArray(minBuffer)
                while (running) {
                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        readCount += 1
                        if (readCount <= 3 || readCount % 25 == 0) {
                            onDebug("AudioRecord read #$readCount bytes=$read")
                        }
                        val pcm16 = buffer.copyOf(read)
                        val shouldStream = shouldStreamChunk?.invoke(pcm16) ?: true
                        if (!shouldStream) {
                            if (readCount <= 3 || readCount % 25 == 0) {
                                onDebug("AudioRecord gated local chunk #$readCount bytes=$read")
                            }
                            continue
                        }
                        val b64 = Base64.encodeToString(pcm16, Base64.NO_WRAP)
                        onChunk(b64)
                    } else if (read < 0) {
                        onDebug("AudioRecord read error code=$read")
                    }
                }
            } catch (e: Throwable) {
                onError(e.message ?: "Audio stream failed")
            } finally {
                runCatching { aec?.release() }
                runCatching { ns?.release() }
                runCatching { agc?.release() }
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
            }
        }.apply {
            name = "astra-audio-streamer"
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        recorder?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        recorder = null
    }
}
