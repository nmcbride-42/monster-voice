package com.nmcbride.monstervoice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
 *
 * Many phones send audio to the quiet earpiece while the mic is recording, so
 * when the loud speaker is wanted the engine uses speakerphone (communication)
 * mode, which forces the loudspeaker and also turns on echo cancellation.
 */
class AudioEngine(private val context: Context, private val voice: MonsterVoice) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile private var running = false
    @Volatile private var activeTrack: AudioTrack? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "MonsterVoiceAudio").also { it.start() }
    }

    /** Switches to the newly chosen speaker, restarting if speakerphone mode changes. */
    fun reroute() {
        if (!running) return
        if (OutputRouter.wantsSpeakerphone(context) != speakerphone) {
            stop()
            start()
        } else {
            activeTrack?.let { OutputRouter.apply(it) }
        }
    }

    @Volatile private var speakerphone = false

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

        val useSpeakerphone = OutputRouter.wantsSpeakerphone(context)
        speakerphone = useSpeakerphone
        if (useSpeakerphone) enterSpeakerphone()

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            record = AudioRecord(
                if (useSpeakerphone) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                inMin * 2,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(if (useSpeakerphone) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
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

            if (!useSpeakerphone) OutputRouter.apply(track)
            activeTrack = track

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
            activeTrack = null
            runCatching { record?.stop() }
            runCatching { track?.stop() }
            record?.release()
            track?.release()
            if (useSpeakerphone) leaveSpeakerphone()
        }
    }

    private fun enterSpeakerphone() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun leaveSpeakerphone() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    companion object {
        const val SAMPLE_RATE = 48000
        private const val CHUNK = 256
        private const val TAG = "AudioEngine"
    }
}
