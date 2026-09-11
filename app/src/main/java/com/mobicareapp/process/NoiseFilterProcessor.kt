package com.mobicareapp.process

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.SavedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow

/**
 * Runs a saved recording's audio through a small on-device filter (a voice-range band-pass plus
 * a noise gate) and saves the result as a brand-new Library item, leaving the original untouched
 * so it's easy to compare and delete whichever copy isn't wanted.
 *
 * What this can and can't do: it cuts steady/continuous noise well — low-frequency engine rumble
 * (the band-pass), and quiet ambient hiss/rustle during pauses in speech (the gate). It can't
 * separate a bird call or a car horn that happens *while* someone is talking — that noise sits in
 * the same frequency range as speech itself, and telling the two apart takes a real ML source-
 * separation model, not a filter like this one. Treat it as "cleaner", not "studio quiet".
 */
object NoiseFilterProcessor {

    private const val TIMEOUT_US = 10_000L

    /** Center frequencies (Hz) of the manual filter's bands, lowest to highest — a UI slider per entry. */
    val MANUAL_BAND_FREQUENCIES_HZ = listOf(60, 150, 400, 1000, 2500, 6000, 10000, 16000)

    /** The automatic, one-tap filter: a voice-range band-pass plus a noise gate. */
    suspend fun process(context: Context, source: SavedMedia): Result<SavedMedia> =
        runPipeline(context, source, "(noise filtered)") { sampleRate, channelCount ->
            VoiceFilter(sampleRate, channelCount)
        }

    /**
     * The manual filter: [bandGainsDb] must have one entry per [MANUAL_BAND_FREQUENCIES_HZ], each
     * 0 (band left alone) down to negative (that band cut) — lets the user dial out exactly the
     * frequency range the unwanted sound sits in, rather than relying on [process]'s fixed guess.
     */
    suspend fun processManual(context: Context, source: SavedMedia, bandGainsDb: List<Double>): Result<SavedMedia> {
        require(bandGainsDb.size == MANUAL_BAND_FREQUENCIES_HZ.size)
        return runPipeline(context, source, "(manual EQ)") { sampleRate, channelCount ->
            ManualEqFilter(sampleRate, channelCount, bandGainsDb)
        }
    }

    private suspend fun runPipeline(
        context: Context,
        source: SavedMedia,
        captionSuffix: String,
        makeFilter: (sampleRate: Int, channelCount: Int) -> SampleFilter
    ): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceUri = MediaAccess.uri(source.filePath)

            val (hasVideo, videoMime) = probeVideoTrack(context, sourceUri)
            if (source.type == MediaType.VIDEO && hasVideo && videoMime != null && !isMp4CompatibleVideo(videoMime)) {
                throw IOException("This video's format ($videoMime) can't be carried over while filtering its audio.")
            }

            val audioTrack = findAudioTrack(context, sourceUri)
                ?: throw IOException("Couldn't find an audio track in that file.")

            val tempPcm = File.createTempFile("denoise_pcm_", ".raw", context.cacheDir)
            val decoded = try {
                decodeAndFilterAudio(context, sourceUri, audioTrack, tempPcm, makeFilter)
            } catch (e: Exception) {
                tempPcm.delete()
                throw e
            }

            val encoded = try {
                encodePcmToAac(tempPcm, decoded.sampleRate, decoded.channelCount, decoded.basePresentationTimeUs)
            } finally {
                tempPcm.delete()
            }

            val outFile = uniqueOutputFile(context, source)
            muxOutput(context, sourceUri, hasVideo, outFile, encoded)

            val sizeBytes = outFile.length()
            if (sizeBytes <= 0L) {
                outFile.delete()
                throw IOException("Filtering produced an empty file.")
            }

            SavedMedia(
                caption = "${source.caption} $captionSuffix",
                sourceUrl = source.sourceUrl,
                type = source.type,
                filePath = outFile.absolutePath,
                fileName = outFile.name,
                thumbnailUrl = source.thumbnailUrl,
                sizeBytes = sizeBytes,
                durationSeconds = source.durationSeconds,
                createdAt = System.currentTimeMillis(),
                categoryId = source.categoryId
            )
        }
    }

    private fun isMp4CompatibleVideo(mime: String) =
        mime == MediaFormat.MIMETYPE_VIDEO_AVC || mime == MediaFormat.MIMETYPE_VIDEO_HEVC

    private fun probeVideoTrack(context: Context, uri: android.net.Uri): Pair<Boolean, String?> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return true to mime
            }
            false to null
        } finally {
            extractor.release()
        }
    }

    private data class AudioTrackInfo(val index: Int, val format: MediaFormat, val mime: String)

    private fun findAudioTrack(context: Context, uri: android.net.Uri): AudioTrackInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return AudioTrackInfo(i, format, mime)
            }
            null
        } finally {
            extractor.release()
        }
    }

    private data class DecodedAudio(val sampleRate: Int, val channelCount: Int, val basePresentationTimeUs: Long)

    /** Decodes the audio track to raw 16-bit PCM, running it through the filter [makeFilter] builds as each chunk comes off the decoder, and appends the filtered bytes to [outPcm]. */
    private fun decodeAndFilterAudio(
        context: Context,
        uri: android.net.Uri,
        track: AudioTrackInfo,
        outPcm: File,
        makeFilter: (sampleRate: Int, channelCount: Int) -> SampleFilter
    ): DecodedAudio {
        val sampleRate = track.format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = track.format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val filter = makeFilter(sampleRate, channelCount)

        val extractor = MediaExtractor()
        val decoder = MediaCodec.createDecoderByType(track.mime)
        var basePresentationTimeUs = -1L
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(track.index)
            decoder.configure(track.format, null, null, 0)
            decoder.start()

            RandomAccessFile(outPcm, "rw").use { raf ->
                var inputDone = false
                var outputDone = false
                val bufferInfo = MediaCodec.BufferInfo()
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inIndex)
                            val sampleSize = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    if (outIndex >= 0) {
                        if (basePresentationTimeUs < 0 && bufferInfo.size > 0) basePresentationTimeUs = bufferInfo.presentationTimeUs
                        val outputBuffer = decoder.getOutputBuffer(outIndex)
                        if (outputBuffer != null && bufferInfo.size > 0) {
                            outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val shortBuffer = outputBuffer.asShortBuffer()
                            val samples = ShortArray(shortBuffer.remaining())
                            shortBuffer.get(samples)
                            filter.process(samples)
                            val bytes = ByteArray(samples.size * 2)
                            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(samples)
                            raf.write(bytes)
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }
        return DecodedAudio(sampleRate, channelCount, if (basePresentationTimeUs >= 0) basePresentationTimeUs else 0L)
    }

    private class EncodedSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)
    private class EncodedAudio(val samples: List<EncodedSample>, val format: MediaFormat)

    private fun encodePcmToAac(pcmFile: File, sampleRate: Int, channelCount: Int, basePresentationTimeUs: Long): EncodedAudio {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val samples = mutableListOf<EncodedSample>()
        var outputFormat: MediaFormat? = null
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val bytesPerSampleFrame = 2 * channelCount // 16-bit PCM
            RandomAccessFile(pcmFile, "r").use { raf ->
                val totalBytes = raf.length()
                var bytesRead = 0L
                var inputDone = false
                var outputDone = false
                val bufferInfo = MediaCodec.BufferInfo()
                val chunk = ByteArray(4096)
                while (!outputDone) {
                    if (!inputDone) {
                        val inIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIndex >= 0) {
                            val inputBuffer = encoder.getInputBuffer(inIndex)
                            if (bytesRead >= totalBytes || inputBuffer == null) {
                                encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                val toRead = min(chunk.size.toLong(), totalBytes - bytesRead).toInt()
                                val read = raf.read(chunk, 0, toRead)
                                if (read <= 0) {
                                    encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                } else {
                                    inputBuffer.clear()
                                    inputBuffer.put(chunk, 0, read)
                                    val sampleFramesSoFar = bytesRead / bytesPerSampleFrame
                                    val presentationTimeUs = basePresentationTimeUs + (sampleFramesSoFar * 1_000_000L / sampleRate)
                                    encoder.queueInputBuffer(inIndex, 0, read, presentationTimeUs, 0)
                                    bytesRead += read
                                }
                            }
                        }
                    }
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = encoder.outputFormat
                        outIndex >= 0 -> {
                            val encodedBuffer = encoder.getOutputBuffer(outIndex)
                            if (encodedBuffer != null && bufferInfo.size > 0) {
                                encodedBuffer.position(bufferInfo.offset)
                                encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val copy = ByteArray(bufferInfo.size)
                                encodedBuffer.get(copy)
                                samples.add(EncodedSample(copy, bufferInfo.presentationTimeUs, bufferInfo.flags))
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
        }
        return EncodedAudio(samples, outputFormat ?: format)
    }

    private fun muxOutput(context: Context, sourceUri: android.net.Uri, hasVideo: Boolean, outFile: File, encodedAudio: EncodedAudio) {
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrackIndex = -1
        var videoExtractor: MediaExtractor? = null
        try {
            if (hasVideo) {
                val extractor = MediaExtractor()
                extractor.setDataSource(context, sourceUri, null)
                var sourceVideoTrack = -1
                for (i in 0 until extractor.trackCount) {
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/")) {
                        sourceVideoTrack = i
                        break
                    }
                }
                if (sourceVideoTrack != -1) {
                    extractor.selectTrack(sourceVideoTrack)
                    videoTrackIndex = muxer.addTrack(extractor.getTrackFormat(sourceVideoTrack))
                    videoExtractor = extractor
                } else {
                    extractor.release()
                }
            }
            val audioTrackIndex = muxer.addTrack(encodedAudio.format)

            muxer.start()

            videoExtractor?.let { extractor ->
                val bufferInfo = MediaCodec.BufferInfo()
                val buffer = ByteBuffer.allocate(2 * 1024 * 1024)
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = size
                    bufferInfo.presentationTimeUs = extractor.sampleTime
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
            }

            val audioBufferInfo = MediaCodec.BufferInfo()
            for (sample in encodedAudio.samples) {
                audioBufferInfo.offset = 0
                audioBufferInfo.size = sample.data.size
                audioBufferInfo.presentationTimeUs = sample.presentationTimeUs
                audioBufferInfo.flags = sample.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv() // muxer doesn't want the EOS flag on a written sample
                muxer.writeSampleData(audioTrackIndex, ByteBuffer.wrap(sample.data), audioBufferInfo)
            }
        } finally {
            videoExtractor?.release()
            runCatching { muxer.stop() }
            muxer.release()
        }
    }

    private fun uniqueOutputFile(context: Context, source: SavedMedia): File {
        val subDir = if (source.type == MediaType.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        val dir = context.getExternalFilesDir(subDir) ?: context.filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var candidate = File(dir, "${sanitize(source.caption)}_denoised_$stamp.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${sanitize(source.caption)}_denoised_$stamp ($counter).mp4")
            counter++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "recording" }.take(60)
    }
}

/** Applied in place to each decoded chunk of interleaved 16-bit PCM, keeping its own per-channel state across calls so it behaves as one continuous filter over the whole recording. */
private interface SampleFilter {
    fun process(samples: ShortArray)
}

/**
 * A light voice-isolation filter: a band-pass (high-pass ~150Hz cuts engine/handling rumble,
 * low-pass ~4000Hz cuts hiss and a lot of higher-pitched noise) cascaded with a noise gate that
 * attenuates (not mutes — avoids audible clicks) quiet stretches sitting near the ambient noise
 * floor. Operates in place on interleaved 16-bit PCM sample blocks, one call per decoded chunk,
 * keeping per-channel filter/gate state across calls so it behaves as one continuous filter over
 * the whole recording rather than resetting at each chunk boundary.
 */
private class VoiceFilter(private val sampleRate: Int, private val channelCount: Int) : SampleFilter {
    private val highPass = Array(channelCount) { Biquad().apply { setHighPass(sampleRate, 150.0) } }
    private val lowPass = Array(channelCount) { Biquad().apply { setLowPass(sampleRate, 4000.0) } }
    private val noiseFloor = DoubleArray(channelCount) { 200.0 }
    private val gain = DoubleArray(channelCount) { 1.0 }

    // A ~20ms analysis frame for the noise gate's level detection.
    private val frameSize = (sampleRate * 0.02).toInt().coerceAtLeast(1)

    override fun process(samples: ShortArray) {
        var frameStart = 0
        while (frameStart < samples.size) {
            val frameEnd = min(frameStart + frameSize * channelCount, samples.size)
            processFrame(samples, frameStart, frameEnd)
            frameStart = frameEnd
        }
    }

    private fun processFrame(samples: ShortArray, start: Int, end: Int) {
        for (ch in 0 until channelCount) {
            var sumSquares = 0.0
            var i = start + ch
            while (i < end) {
                val filtered = lowPass[ch].process(highPass[ch].process(samples[i].toDouble()))
                samples[i] = filtered.coerceIn(-32768.0, 32767.0).toInt().toShort()
                sumSquares += filtered * filtered
                i += channelCount
            }
            val count = ((end - start) / channelCount).coerceAtLeast(1)
            val rms = kotlin.math.sqrt(sumSquares / count)

            if (rms < noiseFloor[ch]) {
                noiseFloor[ch] += (rms - noiseFloor[ch]) * FLOOR_FALL_RATE
            } else {
                noiseFloor[ch] += (rms - noiseFloor[ch]) * FLOOR_RISE_RATE
            }
            val floorDb = 20 * log10(noiseFloor[ch].coerceAtLeast(1.0))
            val rmsDb = 20 * log10(rms.coerceAtLeast(1.0))
            val target = if (rmsDb - floorDb > GATE_RATIO_DB) 1.0 else dbToLinear(GATED_GAIN_DB)
            val rate = if (target < gain[ch]) ATTACK else RELEASE
            gain[ch] += (target - gain[ch]) * rate

            i = start + ch
            while (i < end) {
                samples[i] = (samples[i] * gain[ch]).coerceIn(-32768.0, 32767.0).toInt().toShort()
                i += channelCount
            }
        }
    }

    private fun dbToLinear(db: Double) = 10.0.pow(db / 20.0)

    private companion object {
        const val FLOOR_RISE_RATE = 0.01
        const val FLOOR_FALL_RATE = 0.15
        const val GATE_RATIO_DB = 10.0
        const val GATED_GAIN_DB = -20.0
        const val ATTACK = 0.5
        const val RELEASE = 0.08
    }
}

/**
 * A manual graphic-EQ-style filter: one peaking (bell-curve) biquad per entry in
 * [NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ], cascaded in series. [bandGainsDb] is 0 (band
 * left alone) down to negative (that band cut) — moving a slider to, say, -30dB on the band that
 * covers a bird call's pitch attenuates just that range rather than the whole recording, unlike
 * [VoiceFilter]'s fixed automatic guess.
 */
private class ManualEqFilter(sampleRate: Int, private val channelCount: Int, bandGainsDb: List<Double>) : SampleFilter {
    private val bands = Array(channelCount) { ch ->
        NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ.mapIndexed { i, freq ->
            Biquad().apply { setPeaking(sampleRate, freq.toDouble(), bandGainsDb[i], q = 1.2) }
        }
    }

    override fun process(samples: ShortArray) {
        for (i in samples.indices) {
            val ch = i % channelCount
            var value = samples[i].toDouble()
            for (band in bands[ch]) value = band.process(value)
            samples[i] = value.coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }
}

/** A second-order (biquad) filter section, Direct Form I — coefficients from the RBJ Audio EQ Cookbook. */
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

    fun setHighPass(sampleRate: Int, cutoffHz: Double, q: Double = 0.707) {
        val w0 = 2 * Math.PI * cutoffHz / sampleRate
        val alpha = kotlin.math.sin(w0) / (2 * q)
        val cosw0 = kotlin.math.cos(w0)
        val a0 = 1 + alpha
        b0 = ((1 + cosw0) / 2) / a0
        b1 = (-(1 + cosw0)) / a0
        b2 = ((1 + cosw0) / 2) / a0
        a1 = (-2 * cosw0) / a0
        a2 = (1 - alpha) / a0
    }

    fun setLowPass(sampleRate: Int, cutoffHz: Double, q: Double = 0.707) {
        val w0 = 2 * Math.PI * cutoffHz / sampleRate
        val alpha = kotlin.math.sin(w0) / (2 * q)
        val cosw0 = kotlin.math.cos(w0)
        val a0 = 1 + alpha
        b0 = ((1 - cosw0) / 2) / a0
        b1 = (1 - cosw0) / a0
        b2 = ((1 - cosw0) / 2) / a0
        a1 = (-2 * cosw0) / a0
        a2 = (1 - alpha) / a0
    }

    /** Boosts/cuts a bell-shaped range around [centerHz] by [gainDb]; [q] narrows (higher) or widens (lower) that range. */
    fun setPeaking(sampleRate: Int, centerHz: Double, gainDb: Double, q: Double = 1.0) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * Math.PI * centerHz / sampleRate
        val alpha = kotlin.math.sin(w0) / (2 * q)
        val cosw0 = kotlin.math.cos(w0)
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
