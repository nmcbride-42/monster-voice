package com.nmcbride.monstervoice

import android.media.AudioRecord
import android.media.audiofx.NoiseSuppressor

/**
 * The phone's own built-in noise suppressor (the one used for calls), switched
 * on when the Noise filter slider is past a third. Not every phone has one.
 */
class PhoneNoiseSuppressor private constructor(private val effect: NoiseSuppressor) {

    fun update(noiseFilter: Float) {
        runCatching { effect.enabled = noiseFilter > ON_ABOVE }
    }

    fun release() {
        runCatching { effect.release() }
    }

    companion object {
        private const val ON_ABOVE = 0.33f

        fun attach(record: AudioRecord, noiseFilter: Float): PhoneNoiseSuppressor? {
            if (!NoiseSuppressor.isAvailable()) return null
            val effect = runCatching { NoiseSuppressor.create(record.audioSessionId) }.getOrNull() ?: return null
            return PhoneNoiseSuppressor(effect).also { it.update(noiseFilter) }
        }
    }
}
