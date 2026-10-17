package com.nmcbride.monstervoice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper

/**
 * Plays a sound effect. While the live voice is on, the sound is mixed into it;
 * otherwise it plays on its own.
 */
object SoundPlayer {

    private var track: AudioTrack? = null
    private val handler = Handler(Looper.getMainLooper())

    fun play(sound: FloatArray, volume: Float) {
        if (VoiceService.isRunning) {
            VoiceService.voice.playSound(sound)
            return
        }
        stop()
        val pcm = ShortArray(sound.size) { (sound[it] * volume).coerceIn(-1f, 1f).times(32767f).toInt().toShort() }
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioEngine.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(pcm, 0, pcm.size)
        t.play()
        track = t
        val millis = pcm.size * 1000L / AudioEngine.SAMPLE_RATE + 300
        handler.postDelayed({ if (track === t) stop() }, millis)
    }

    fun stop() {
        track?.let {
            runCatching { it.stop() }
            it.release()
        }
        track = null
    }
}
