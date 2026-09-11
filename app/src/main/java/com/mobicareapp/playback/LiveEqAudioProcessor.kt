package com.mobicareapp.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.mobicareapp.process.NoiseFilterProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * A live, real-time version of the same per-band cut the Library's "Manual noise filter" applies
 * offline — inserted into ExoPlayer's audio pipeline so moving a slider changes what you're
 * hearing immediately, instead of having to save a copy and replay it to check. Shares the same
 * band frequencies ([NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ]) so a setting that works
 * live can be reapplied as a permanent saved copy from the Library screen, and vice versa.
 */
class LiveEqAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var bandGainsDb: DoubleArray = DoubleArray(NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ.size)

    @Volatile
    private var bands: Array<Array<Biquad>> = emptyArray()

    private var channelCount = 0

    /** Safe to call from Compose (the UI thread) at any time, including mid-playback — coefficients update in place on the next processed buffer, no flush needed. */
    fun setBandGainsDb(newGains: DoubleArray) {
        bandGainsDb = newGains
        rebuildBands(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        rebuildBands(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    private fun rebuildBands(sampleRate: Int, channels: Int) {
        if (sampleRate <= 0 || channels <= 0) return
        bands = Array(channels) { _ ->
            Array(NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ.size) { i ->
                Biquad().apply {
                    setPeaking(sampleRate, NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ[i].toDouble(), bandGainsDb[i], q = 1.2)
                }
            }
        }
    }

    override fun isActive(): Boolean = channelCount > 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0 || bands.isEmpty()) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val inShorts = inputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val outShorts = ShortArray(inShorts.remaining())
        var index = 0
        while (inShorts.hasRemaining()) {
            val ch = index % channelCount
            var value = inShorts.get().toDouble()
            val channelBands = bands.getOrNull(ch)
            if (channelBands != null) {
                for (band in channelBands) value = band.process(value)
            }
            outShorts[index] = value.coerceIn(-32768.0, 32767.0).toInt().toShort()
            index++
        }
        outputBuffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(outShorts)
        outputBuffer.position(outputBuffer.position() + outShorts.size * 2)
        inputBuffer.position(inputBuffer.limit())
        outputBuffer.flip()
    }

    override fun onReset() {
        bands = emptyArray()
        channelCount = 0
    }
}

/** A second-order (biquad) peaking filter section, Direct Form I — RBJ Audio EQ Cookbook coefficients. Kept per-processor rather than shared with [com.mobicareapp.process.NoiseFilterProcessor]'s copy since that one is private to its own file. */
private class Biquad {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun setPeaking(sampleRate: Int, centerHz: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * Math.PI * centerHz / sampleRate
        val alpha = sin(w0) / (2 * q)
        val cosw0 = cos(w0)
        val a0 = 1 + alpha / a
        b0 = (1 + alpha * a) / a0
        b1 = (-2 * cosw0) / a0
        b2 = (1 - alpha * a) / a0
        a1 = (-2 * cosw0) / a0
        a2 = (1 - alpha / a) / a0
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }
}
