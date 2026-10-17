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
 * Where the sound goes depends on [OutputRouter.route]: call mode, for example,
 * forces speakerphone and turns on echo cancellation. The device the phone
 * actually picked is reported in [routedDevice] so the screen can show it.
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

    /** The output the phone is really using right now, if known. */
    @Volatile var routedDevice: AudioDeviceInfo? = null
        private set

    private var runningRoute = OutputRouter.Route.MEDIA

    /** Switches to the newly chosen speaker, restarting if the route changes. */
    fun reroute() {
        if (!running) return
        if (OutputRouter.route != runningRoute) {
            stop()
            start()
        } else {
            activeTrack?.let { OutputRouter.apply(it) }
        }
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

        val route = OutputRouter.route
        runningRoute = route
        if (route.callMode) enterSpeakerphone()

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            record = AudioRecord(
                if (route.callMode) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                inMin * 2,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(route.usage)
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

            if (!route.callMode) OutputRouter.apply(track)
            activeTrack = track

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "Microphone failed to start")
                return
            }

            val input = ShortArray(CHUNK)
            val output = ShortArray(CHUNK)
            record.startRecording()
            track.play()

            var chunks = 0
            while (running) {
                val n = record.read(input, 0, CHUNK)
                if (n <= 0) continue
                voice.process(input, output, n)
                track.write(output, 0, n)
                if (chunks++ % 50 == 0) routedDevice = track.routedDevice
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop failed", e)
        } finally {
            running = false
            activeTrack = null
            routedDevice = null
            runCatching { record?.stop() }
            runCatching { track?.stop() }
            record?.release()
            track?.release()
            if (route.callMode) leaveSpeakerphone()
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
