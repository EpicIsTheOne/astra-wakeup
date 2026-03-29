package com.astra.wakeup.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import java.util.concurrent.LinkedBlockingQueue

data class AudioChunk(
    val bytes: ByteArray,
    val sampleRateHz: Int,
)

class AudioPlaybackQueue(
    private val defaultSampleRateHz: Int = 24_000,
    private val onError: (String) -> Unit,
    private val onPlaybackStateChanged: ((Boolean) -> Unit)? = null,
    private val onPlaybackIdle: (() -> Unit)? = null,
) {
    private val queue = LinkedBlockingQueue<AudioChunk>()
    @Volatile private var running = false
    private var worker: Thread? = null
    private var audioTrack: AudioTrack? = null
    @Volatile private var activeSampleRateHz: Int = defaultSampleRateHz
    @Volatile private var playbackActive = false
    @Volatile private var idleSignalToken = 0L

    fun start() {
        if (running) return
        running = true
        worker = Thread {
            try {
                ensureAudioTrack(activeSampleRateHz)
                audioTrack?.play()
                while (running) {
                    val chunk = queue.take()
                    if (!running) break
                    if (chunk.sampleRateHz != activeSampleRateHz) {
                        ensureAudioTrack(chunk.sampleRateHz)
                        audioTrack?.play()
                    }
                    if (chunk.bytes.isNotEmpty()) {
                        setPlaybackActive(true)
                        audioTrack?.write(chunk.bytes, 0, chunk.bytes.size)
                        scheduleIdleSignal(chunk)
                    }
                }
            } catch (e: Throwable) {
                if (running) onError(e.message ?: "Audio playback failed")
            } finally {
                releaseTrack()
            }
        }.apply {
            name = "astra-audio-playback"
            start()
        }
    }

    fun enqueuePcm16Base64(base64: String, mimeType: String? = null) {
        if (!running) return
        val decoded = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return
        val sampleRate = parseSampleRateFromMimeType(mimeType) ?: defaultSampleRateHz
        queue.offer(AudioChunk(decoded, sampleRate))
    }

    fun interruptPlayback() {
        idleSignalToken += 1
        queue.clear()
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.play() }
        setPlaybackActive(false)
    }

    fun stop() {
        running = false
        idleSignalToken += 1
        worker?.interrupt()
        worker = null
        queue.clear()
        setPlaybackActive(false)
        releaseTrack()
    }

    private fun ensureAudioTrack(sampleRateHz: Int) {
        if (audioTrack != null && sampleRateHz == activeSampleRateHz) return
        releaseTrack()
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            throw IllegalStateException("AudioTrack init failed for $sampleRateHz Hz")
        }
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes((minBuffer * 3).coerceAtLeast(sampleRateHz / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        activeSampleRateHz = sampleRateHz
    }

    private fun releaseTrack() {
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }

    private fun scheduleIdleSignal(chunk: AudioChunk) {
        val token = idleSignalToken + 1
        idleSignalToken = token
        val durationMs = ((chunk.bytes.size / 2.0) / chunk.sampleRateHz.toDouble() * 1000.0).toLong().coerceAtLeast(80L)
        Thread {
            try {
                Thread.sleep((durationMs + 120L).coerceAtMost(1500L))
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (!running) return@Thread
            if (token != idleSignalToken) return@Thread
            if (queue.isNotEmpty()) return@Thread
            setPlaybackActive(false)
            onPlaybackIdle?.invoke()
        }.apply {
            name = "astra-audio-idle"
            isDaemon = true
            start()
        }
    }

    private fun setPlaybackActive(active: Boolean) {
        if (playbackActive == active) return
        playbackActive = active
        onPlaybackStateChanged?.invoke(active)
    }

    private fun parseSampleRateFromMimeType(mimeType: String?): Int? {
        val value = mimeType?.lowercase()?.trim() ?: return null
        val marker = "rate="
        val index = value.indexOf(marker)
        if (index == -1) return null
        val tail = value.substring(index + marker.length)
        val raw = tail.takeWhile { it.isDigit() }
        return raw.toIntOrNull()?.coerceIn(8000, 48000)
    }
}
