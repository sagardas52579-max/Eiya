package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.sqrt

class AudioTrackPlayer {

    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 24000
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private var playbackJob: Job? = null
    private var isPlaying = false

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null
    var onAmplitude: ((Float) -> Unit)? = null

    private fun initAudioTrack(sampleRate: Int) {
        if (audioTrack != null && currentSampleRate == sampleRate) return

        releaseAudioTrack()
        currentSampleRate = sampleRate

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.play()
    }

    fun startPlaybackWorker(scope: CoroutineScope) {
        if (playbackJob != null) return

        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val chunk = audioQueue.take()
                    if (chunk.isEmpty()) continue

                    if (!isPlaying) {
                        isPlaying = true
                        onSpeakingStateChanged?.invoke(true)
                    }

                    initAudioTrack(currentSampleRate)
                    audioTrack?.write(chunk, 0, chunk.size)

                    // Compute amplitude for speaking orb visualization
                    var sum = 0.0
                    var i = 0
                    while (i < chunk.size - 1) {
                        val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                        sum += sample * sample
                        i += 2
                    }
                    val rms = sqrt(sum / (chunk.size / 2.0)).toFloat()
                    val normalized = (rms / 8000f).coerceIn(0f, 1f)
                    onAmplitude?.invoke(normalized)

                    if (audioQueue.isEmpty()) {
                        // Brief pause to check if more chunks arrive
                        kotlinx.coroutines.delay(80)
                        if (audioQueue.isEmpty()) {
                            isPlaying = false
                            onSpeakingStateChanged?.invoke(false)
                            onAmplitude?.invoke(0f)
                        }
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            isPlaying = false
            onSpeakingStateChanged?.invoke(false)
            onAmplitude?.invoke(0f)
        }
    }

    /**
     * Enqueue a PCM chunk for playback
     */
    fun enqueueChunk(pcmData: ByteArray, sampleRate: Int = 24000) {
        currentSampleRate = sampleRate
        audioQueue.offer(pcmData)
    }

    /**
     * Stop and flush immediately when user interrupts
     */
    fun interrupt() {
        audioQueue.clear()
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (_: Exception) {}
        isPlaying = false
        onSpeakingStateChanged?.invoke(false)
        onAmplitude?.invoke(0f)
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying || !audioQueue.isEmpty()

    private fun releaseAudioTrack() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (_: Exception) {}
        audioTrack = null
    }

    fun release() {
        interrupt()
        playbackJob?.cancel()
        playbackJob = null
        releaseAudioTrack()
    }
}
