package com.nmcbride.monstervoice

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * The monster voice effect. Pure Kotlin with no Android dependencies, so it
 * can be unit tested on the JVM.
 *
 * Signal chain: mic boost -> noise gate -> pitch shift down -> growl (rumble + grit)
 * -> echo -> roar sound effect -> volume.
 *
 * The pitch shifter is a classic two-tap delay-line shifter: two read heads
 * sweep through a short delay buffer half a cycle apart and are crossfaded
 * with sin^2 windows (which always sum to 1), so the output stays smooth.
 */
class MonsterVoice(private val sampleRate: Int) {

    /** How many semitones to drop the voice (0 = normal, 12 = one octave down). */
    @Volatile var semitones: Float = 7f

    /** 0..1: how much rumble and grit to add. */
    @Volatile var growl: Float = 0.3f

    /** Output gain, 0..2. */
    @Volatile var volume: Float = 1f

    /** Microphone gain, 0..4. Turn up for a quiet voice or a mic far from the mouth. */
    @Volatile var micBoost: Float = 1f

    /** 0..1: how much cave-like echo to add. */
    @Volatile var echo: Float = 0f

    /** Mutes background hiss when she isn't talking, and helps stop feedback. */
    @Volatile var gateEnabled: Boolean = true

    private val window = (sampleRate * 0.05f).toInt()
    private val buffer = FloatArray(nextPowerOfTwo(window * 2 + 8))
    private val mask = buffer.size - 1
    private var writePos = 0
    private var phase = 0f

    private var rumblePhase = 0.0
    private val rumbleStep = 2.0 * PI * 38.0 / sampleRate

    private var lowpass = 0f
    private val lowpassCoef = 1f - exp(-2.0 * PI * 2200.0 / sampleRate).toFloat()

    private var envelope = 0f
    private var gateGain = 0f
    private val envAttack = 1f - exp(-1.0 / (0.005 * sampleRate)).toFloat()
    private val envRelease = 1f - exp(-1.0 / (0.15 * sampleRate)).toFloat()
    private val gateSmooth = 1f - exp(-1.0 / (0.01 * sampleRate)).toFloat()

    private val echoBuffer = FloatArray((sampleRate * 0.28f).toInt())
    private var echoPos = 0

    private val roarLength = (sampleRate * 1.4f).toInt()
    @Volatile private var roarRequested = false
    private var roarPos = -1
    private var roarSawPhase = 0f
    private var roarNoise = 0f
    private var rng = 22222

    /** Plays a synthesized monster roar over the live voice. */
    fun roar() {
        roarRequested = true
    }

    /** Processes [count] 16-bit samples from [input] into [output]. */
    fun process(input: ShortArray, output: ShortArray, count: Int) {
        val ratio = 2f.pow(-semitones / 12f)
        val g = growl.coerceIn(0f, 1f)
        val vol = volume.coerceIn(0f, 2f)
        val boost = micBoost.coerceIn(0f, 4f)
        val e = echo.coerceIn(0f, 1f)
        if (roarRequested) {
            roarRequested = false
            roarPos = 0
        }
        for (i in 0 until count) {
            val y = processSample(input[i] / 32768f * boost, ratio, g, vol, gateEnabled, e)
            output[i] = (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    internal fun processSample(x: Float, ratio: Float, g: Float, vol: Float, gate: Boolean, e: Float = 0f): Float {
        // Noise gate: follow the input level and fade out below a threshold.
        val level = if (x < 0) -x else x
        envelope += (level - envelope) * (if (level > envelope) envAttack else envRelease)
        val gateTarget = if (!gate || envelope > GATE_THRESHOLD) 1f else 0f
        gateGain += (gateTarget - gateGain) * gateSmooth

        // Pitch shift.
        buffer[writePos] = x
        phase += (1f - ratio) / window
        if (phase >= 1f) phase -= 1f
        if (phase < 0f) phase += 1f
        val phase2 = if (phase >= 0.5f) phase - 0.5f else phase + 0.5f
        val w1 = sin(PI.toFloat() * phase).let { it * it }
        val w2 = 1f - w1
        var y = readDelayed(phase * window) * w1 + readDelayed(phase2 * window) * w2
        writePos = (writePos + 1) and mask

        // Growl: darken, add a low rumble and some soft-clipped grit.
        if (g > 0f) {
            lowpass += (y - lowpass) * lowpassCoef
            y += (lowpass - y) * 0.6f * g
            rumblePhase += rumbleStep
            if (rumblePhase > 2.0 * PI) rumblePhase -= 2.0 * PI
            val rumble = 1f - 0.7f * g * (0.5f + 0.5f * sin(rumblePhase).toFloat())
            y *= rumble
            val drive = 1f + 6f * g
            y = tanh(y * drive) / sqrt(drive)
        }

        y *= gateGain

        // Echo: a single feedback delay, applied after the gate so the tail rings out.
        if (e > 0f) {
            val delayed = echoBuffer[echoPos]
            echoBuffer[echoPos] = y + delayed * (0.25f + 0.4f * e)
            echoPos = if (echoPos + 1 == echoBuffer.size) 0 else echoPos + 1
            y += delayed * 0.7f * e
        }

        if (roarPos >= 0) {
            y += nextRoarSample()
        }

        return y * vol
    }

    /** A falling, rough sawtooth plus breathy noise, shaped by a swell-and-fade envelope. */
    private fun nextRoarSample(): Float {
        val t = roarPos.toFloat() / roarLength
        val env = if (t < 0.15f) t / 0.15f else (1f - t) / 0.85f
        val freq = 95f - 40f * t + 6f * sin(2f * PI.toFloat() * 7f * t)
        roarSawPhase += freq / sampleRate
        if (roarSawPhase >= 1f) roarSawPhase -= 1f
        val saw = roarSawPhase * 2f - 1f
        rng = rng * 1103515245 + 12345
        val white = ((rng ushr 8) and 0xFFFF) / 32768f - 1f
        roarNoise += (white - roarNoise) * 0.15f
        val out = tanh((saw * 0.8f + roarNoise * 1.2f) * 2.5f) * 0.55f * env
        roarPos++
        if (roarPos >= roarLength) roarPos = -1
        return out
    }

    private fun readDelayed(delay: Float): Float {
        val pos = writePos - delay
        val base = kotlin.math.floor(pos).toInt()
        val frac = pos - base
        val a = buffer[base and mask]
        val b = buffer[(base + 1) and mask]
        return a + (b - a) * frac
    }

    companion object {
        const val GATE_THRESHOLD = 0.012f

        private fun nextPowerOfTwo(n: Int): Int {
            var p = 1
            while (p < n) p = p shl 1
            return p
        }
    }
}
