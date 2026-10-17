package com.nmcbride.monstervoice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log

/** Records raw microphone audio until [stop] is called (or [MAX_SECONDS] passes). */
class Recorder {

    @Volatile private var running = false
    private var thread: Thread? = null
    private var samples = ShortArray(0)
    private var length = 0

    @SuppressLint("MissingPermission") // MainActivity checks RECORD_AUDIO first.
    fun start() {
        if (running) return
        running = true
        length = 0
        samples = ShortArray(AudioEngine.SAMPLE_RATE * MAX_SECONDS)
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val min = AudioRecord.getMinBufferSize(
                AudioEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            var record: AudioRecord? = null
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC, AudioEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min * 2
                )
                record.startRecording()
                while (running && length < samples.size) {
                    val n = record.read(samples, length, minOf(1024, samples.size - length))
                    if (n > 0) length += n
                }
            } catch (e: Exception) {
                Log.e("Recorder", "Recording failed", e)
            } finally {
                runCatching { record?.stop() }
                record?.release()
                running = false
            }
        }, "MonsterVoiceRecorder").also { it.start() }
    }

    /** Stops recording and returns what was captured. */
    fun stop(): ShortArray {
        running = false
        thread?.join(1000)
        thread = null
        return samples.copyOf(length)
    }

    companion object {
        const val MAX_SECONDS = 10
    }
}
