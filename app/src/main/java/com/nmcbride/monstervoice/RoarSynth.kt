package com.nmcbride.monstervoice

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Builds a monster roar the way a throat makes one: an uneven, buzzing pulse
 * train (the vocal cords) plus breath noise, shaped by vowel formants that
 * open from "r" to "aah" (the mouth), then roughened and soft-clipped.
 * Each seed gives a slightly different roar.
 */
object RoarSynth {

    fun create(sampleRate: Int, seed: Int, seconds: Float = 1.7f): FloatArray {
        val n = (sampleRate * seconds).toInt()
        val out = FloatArray(n)
        val rng = Random(seed.toLong())

        val f1 = Resonator(sampleRate)
        val f2 = Resonator(sampleRate)
        val f3 = Resonator(sampleRate)
        var body = 0f
        val bodyCoef = 1f - kotlin.math.exp(-2.0 * PI * 350.0 / sampleRate).toFloat()

        var phase = 0f
        var cycleJitter = 1f
        var cycleGain = 1f
        var oddCycle = false
        var prevPulse = 0f
        var flutterPhase = 0.0
        var flutterRate = 24.0
        val baseScale = 0.9f + 0.2f * rng.nextFloat()

        for (i in 0 until n) {
            val t = i.toFloat() / n

            // Pitch contour: swell up, then sag down as the roar runs out of breath.
            val pitch = baseScale * if (t < 0.3f) {
                60f + 55f * smooth(t / 0.3f)
            } else {
                115f - 65f * smooth((t - 0.3f) / 0.7f)
            }

            // Vocal cords: one pulse per cycle, with per-cycle jitter and
            // alternating loud/soft cycles (period doubling gives the growl).
            phase += pitch * cycleJitter / sampleRate
            if (phase >= 1f) {
                phase -= 1f
                cycleJitter = 1f + 0.18f * (rng.nextFloat() - 0.5f)
                oddCycle = !oddCycle
                cycleGain = (if (oddCycle) 0.5f else 1f) * (0.8f + 0.4f * rng.nextFloat())
            }
            val pulse = if (phase < 0.45f) sin(PI.toFloat() * phase / 0.45f).let { it * it } * cycleGain else 0f
            val buzz = (pulse - prevPulse) * 12f // differentiate for a brighter, raspier buzz
            prevPulse = pulse

            val breath = (rng.nextFloat() * 2f - 1f) * (0.35f + 0.25f * t)
            val excitation = buzz + breath

            // Mouth: formants glide from a tight "rr" to a wide "aah".
            val open = smooth((t / 0.35f).coerceAtMost(1f))
            val y1 = f1.process(excitation, 380f + 320f * open, 4f)
            val y2 = f2.process(excitation, 850f + 300f * open, 6f)
            val y3 = f3.process(excitation, 2100f + 300f * open, 8f)
            body += (excitation - body) * bodyCoef
            var y = y1 + 0.6f * y2 + 0.25f * y3 + 0.5f * body

            // Throat flutter: a wobbling ~20-30 Hz tremble.
            flutterRate += (rng.nextDouble() - 0.5) * 0.8
            flutterRate = flutterRate.coerceIn(18.0, 32.0)
            flutterPhase += 2.0 * PI * flutterRate / sampleRate
            y *= 1f - 0.45f * (0.5f + 0.5f * cos(flutterPhase).toFloat())

            // Swell in, hold, and fade out.
            val env = smooth((t / 0.12f).coerceAtMost(1f)) * smooth(((1f - t) / 0.45f).coerceAtMost(1f))
            out[i] = tanh(y * 3f) * env
        }

        var peak = 1e-6f
        for (v in out) peak = maxOf(peak, abs(v))
        val gain = 0.8f / peak
        for (i in out.indices) out[i] *= gain
        return out
    }

    private fun smooth(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** A band-pass biquad (constant 0 dB peak) whose center can glide every sample. */
    private class Resonator(private val sampleRate: Int) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun process(x: Float, freq: Float, q: Float): Float {
            val w = 2.0 * PI * freq / sampleRate
            val alpha = sin(w) / (2.0 * q)
            val a0 = 1.0 + alpha
            val b0 = (alpha / a0).toFloat()
            val a1 = (-2.0 * cos(w) / a0).toFloat()
            val a2 = ((1.0 - alpha) / a0).toFloat()
            val y = b0 * x - b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }
    }
}
