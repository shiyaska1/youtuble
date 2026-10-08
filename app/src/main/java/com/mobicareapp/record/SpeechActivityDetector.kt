package com.mobicareapp.record

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Classifies a short PCM frame as speech-like or not. MediaRecorder's amplitude meter alone
 * (getMaxAmplitude()) can't do this — any loud sound trips it, fan noise and traffic included —
 * so this looks at the frame's frequency content instead: human speech concentrates most of its
 * energy in roughly 300-3400 Hz, while a lot of common background noise (fan/AC hum, traffic
 * rumble, wind, hiss) sits mostly outside that band. Comparing energy inside vs. outside that band
 * (via the Goertzel algorithm — a cheap way to get a single frequency's energy without a full FFT)
 * screens out most of that noise while still passing normal speech through.
 *
 * This is a lightweight heuristic, not a trained speech model: it can't tell whose voice it is, and
 * loud non-speech sounds that happen to have energy concentrated in the same band (a whistled tune,
 * a ringtone, another phone's voice call playing through a speaker) can still pass. The threshold
 * below is a reasonable starting point, not something tuned against real recordings — it may need
 * adjusting based on how it actually behaves on-device.
 */
object SpeechActivityDetector {

    private val VOICE_BAND_HZ = listOf(300.0, 600.0, 1000.0, 1500.0, 2000.0, 2600.0, 3400.0)
    private val OUT_OF_BAND_HZ = listOf(80.0, 150.0, 220.0, 4200.0, 5200.0, 6200.0)
    private const val MIN_RMS = 400.0
    private const val MIN_VOICE_BAND_RATIO = 0.6

    /** [samples] a frame of signed 16-bit PCM (mono) at [sampleRate] Hz. Returns true if it looks like speech rather than silence or background noise. */
    fun isSpeechFrame(samples: ShortArray, sampleRate: Int): Boolean {
        if (samples.isEmpty()) return false
        var sumSquares = 0.0
        for (s in samples) sumSquares += s.toDouble() * s.toDouble()
        val rms = sqrt(sumSquares / samples.size)
        if (rms < MIN_RMS) return false

        val nyquist = sampleRate / 2.0
        val voiceEnergy = bandEnergy(samples, sampleRate, VOICE_BAND_HZ.filter { it < nyquist })
        val outOfBandEnergy = bandEnergy(samples, sampleRate, OUT_OF_BAND_HZ.filter { it < nyquist })
        val total = voiceEnergy + outOfBandEnergy
        if (total <= 0.0) return false
        return voiceEnergy / total >= MIN_VOICE_BAND_RATIO
    }

    private fun bandEnergy(samples: ShortArray, sampleRate: Int, freqs: List<Double>): Double =
        freqs.sumOf { freq -> val magnitude = goertzelMagnitude(samples, sampleRate, freq); magnitude * magnitude }

    private fun goertzelMagnitude(samples: ShortArray, sampleRate: Int, targetFreq: Double): Double {
        val n = samples.size
        val k = (0.5 + n * targetFreq / sampleRate).toInt()
        val omega = 2.0 * PI * k / n
        val cosine = cos(omega)
        val sine = sin(omega)
        val coeff = 2.0 * cosine
        var q1 = 0.0
        var q2 = 0.0
        for (sample in samples) {
            val q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        val real = q1 - q2 * cosine
        val imag = q2 * sine
        return sqrt(real * real + imag * imag)
    }
}
