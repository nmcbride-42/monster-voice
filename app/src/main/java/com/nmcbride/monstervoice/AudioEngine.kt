package com.nmcbride.monstervoice

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

/**
 * Reads the microphone, runs it through [voice] and plays it straight back out,
 * on a dedicated high-priority audio thread.
 */
class AudioEngine(private val voice: MonsterVoice) {

    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "MonsterVoiceAudio").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(1000)
        thread = null
    }

    @SuppressLint("MissingPermission") // Checked by MainActivity before the service starts.
    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        val inMin = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val outMin = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                inMin * 2,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(outMin * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Microphone failed to start")
                return
            }

            val input = ShortArray(CHUNK)
            val output = ShortArray(CHUNK)
            record.startRecording()
            track.play()

            while (running) {
                val n = record.read(input, 0, CHUNK)
                if (n <= 0) continue
                voice.process(input, output, n)
                track.write(output, 0, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop failed", e)
        } finally {
            running = false
            runCatching { record?.stop() }
            runCatching { track?.stop() }
            record?.release()
            track?.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val CHUNK = 256
        private const val TAG = "AudioEngine"
    }
}
