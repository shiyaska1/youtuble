package com.mobicareapp.record

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes raw 16-bit PCM straight to a .wav file. Used for voice-gated recording (voice-activated /
 * always-listen), which needs direct access to samples to tell speech apart from noise — see
 * [SpeechActivityDetector] — unlike plain recording, which still goes through MediaRecorder's
 * AAC/MP4 encoder untouched. A .wav file's header carries the final byte counts, which aren't known
 * until recording stops, so this writes a placeholder header first and patches it in [finish].
 */
class WavFileWriter(file: File, private val sampleRate: Int, private val channels: Int = 1, private val bitsPerSample: Int = 16) {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0); write(ByteArray(HEADER_SIZE)) }
    private var dataBytesWritten = 0L

    fun write(buffer: ByteArray, length: Int) {
        raf.write(buffer, 0, length)
        dataBytesWritten += length
    }

    /** Patches the header with the real size now that it's known, then closes the file. Safe to call even if nothing was ever written (produces a valid, empty .wav). */
    fun finish() {
        raf.seek(0)
        raf.write(buildHeader())
        raf.close()
    }

    /** For a failed/aborted recording — closes without a valid header, since the file is about to be deleted anyway. */
    fun abortWithoutFinishing() {
        runCatching { raf.close() }
    }

    private fun buildHeader(): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt((36 + dataBytesWritten).toInt())
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytesWritten.toInt())
        return buffer.array()
    }

    companion object {
        private const val HEADER_SIZE = 44
    }
}
