package com.nmcbride.monstervoice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class MonsterVoiceTest {

    private val rate = 48000

    private fun sine(freq: Double, seconds: Double, amp: Double = 0.5): ShortArray =
        ShortArray((rate * seconds).toInt()) { i -> (sin(2 * PI * freq * i / rate) * amp * 32767).toInt().toShort() }

    /** Estimates frequency by counting upward zero crossings, skipping the warm-up. */
    private fun frequencyOf(samples: ShortArray, skip: Int): Double {
        var crossings = 0
        for (i in skip + 1 until samples.size) {
            if (samples[i - 1] < 0 && samples[i] >= 0) crossings++
        }
        return crossings * rate.toDouble() / (samples.size - skip)
    }

    private fun run(voice: MonsterVoice, input: ShortArray): ShortArray {
        val out = ShortArray(input.size)
        voice.process(input, out, input.size)
        return out
    }

    @Test
    fun oneOctaveDownHalvesThePitch() {
        val voice = MonsterVoice(rate).apply { semitones = 12f; growl = 0f; gateEnabled = false }
        val out = run(voice, sine(440.0, 2.0))
        val freq = frequencyOf(out, rate / 2)
        assertEquals(220.0, freq, 20.0)
    }

    @Test
    fun zeroDeepnessKeepsThePitch() {
        val voice = MonsterVoice(rate).apply { semitones = 0f; growl = 0f; gateEnabled = false }
        val out = run(voice, sine(300.0, 1.0))
        assertEquals(300.0, frequencyOf(out, rate / 4), 10.0)
    }

    @Test
    fun gateSilencesQuietHiss() {
        val voice = MonsterVoice(rate).apply { semitones = 7f; growl = 0.5f }
        val out = run(voice, sine(1000.0, 1.0, amp = 0.002))
        val tail = out.copyOfRange(rate / 2, out.size)
        assertTrue(tail.all { abs(it.toInt()) < 50 })
    }

    @Test
    fun loudGrowlStillMakesSound() {
        val voice = MonsterVoice(rate).apply { semitones = 12f; growl = 1f; volume = 2f }
        val out = run(voice, sine(200.0, 1.0, amp = 1.0))
        assertTrue(out.any { abs(it.toInt()) > 1000 })
    }

    @Test
    fun roarPlaysOverSilence() {
        val voice = MonsterVoice(rate)
        voice.playSound(RoarSynth.create(rate, 1))
        val out = run(voice, ShortArray(rate))
        assertTrue(out.any { abs(it.toInt()) > 3000 })
    }

    @Test
    fun echoRepeatsAfterTheVoiceStops() {
        val voice = MonsterVoice(rate).apply { semitones = 0f; growl = 0f; echo = 1f; gateEnabled = false }
        val input = ShortArray(rate)
        sine(300.0, 0.1).copyInto(input)
        val out = run(voice, input)
        // The direct sound ends after 0.1 s plus the shifter's short delay; the echo arrives ~0.28 s in.
        val echoWindow = out.copyOfRange((rate * 0.3).toInt(), (rate * 0.36).toInt())
        assertTrue(echoWindow.any { abs(it.toInt()) > 2000 })
    }

    @Test
    fun micBoostMakesAQuietVoiceLouder() {
        fun peak(boost: Float): Int {
            val voice = MonsterVoice(rate).apply { semitones = 0f; growl = 0f; gateEnabled = false; micBoost = boost }
            return run(voice, sine(300.0, 0.5, amp = 0.05)).maxOf { abs(it.toInt()) }
        }
        assertTrue(peak(3f) > peak(1f) * 2)
    }

    @Test
    fun renderedClipIsDeepAndNormalized() {
        val voice = MonsterVoice(rate).apply { semitones = 12f; growl = 0f; gateEnabled = false }
        val clip = voice.render(sine(400.0, 1.0, amp = 0.2))
        assertTrue(clip.maxOf { abs(it) } > 0.85f)
        val asShorts = ShortArray(rate) { (clip[it] * 32767).toInt().toShort() }
        assertEquals(200.0, frequencyOf(asShorts, rate / 2), 20.0)
    }

    @Test
    fun roarIsLoudRoughAndEndsQuietly() {
        val roar = RoarSynth.create(rate, 7)
        assertTrue(roar.maxOf { abs(it) } > 0.7f)
        val end = roar.copyOfRange(roar.size - rate / 100, roar.size)
        assertTrue(end.all { abs(it) < 0.05f })
    }
}
