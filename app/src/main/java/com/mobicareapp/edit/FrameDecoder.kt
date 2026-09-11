package com.mobicareapp.edit

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.view.Surface

/**
 * Drives one MediaCodec decoder frame-by-frame, rendering its output into a [SurfaceTexture]-
 * backed [Surface] (a GL texture the caller already created) rather than a byte buffer — the
 * standard way to get video frames into GPU memory for per-frame GL processing (compositing in
 * [PictureInPictureProcessor], cropping in [VideoCropProcessor]). [advance] pulls exactly one new
 * decoded frame (or reports end-of-stream); [transformMatrix] and [lastPresentationTimeUs]
 * describe whatever frame it last produced.
 */
internal class FrameDecoder(context: Context, uri: Uri, textureId: Int, callbackHandler: Handler) {
    private val extractor = MediaExtractor()
    private val decoder: MediaCodec
    private val surfaceTexture: SurfaceTexture
    private val surface: Surface
    private val frameLock = Object()
    private var frameAvailable = false
    private var trackIndex = -1
    private var eos = false
    private val bufferInfo = MediaCodec.BufferInfo()

    var lastPresentationTimeUs = 0L
        private set

    init {
        extractor.setDataSource(context, uri, null)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) {
                trackIndex = i
                break
            }
        }
        if (trackIndex == -1) error("No video track")
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        surfaceTexture = SurfaceTexture(textureId)
        surfaceTexture.setOnFrameAvailableListener({
            synchronized(frameLock) {
                frameAvailable = true
                frameLock.notifyAll()
            }
        }, callbackHandler)
        surface = Surface(surfaceTexture)

        decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, surface, null, 0)
        decoder.start()
    }

    /** Feeds input and drains output until exactly one new frame lands on the texture. Returns false once the stream is exhausted with nothing more to show. */
    fun advance(): Boolean {
        if (eos) return false
        while (true) {
            val inIndex = decoder.dequeueInputBuffer(10_000)
            if (inIndex >= 0) {
                val inputBuffer = decoder.getInputBuffer(inIndex)
                val size = if (inputBuffer != null) extractor.readSampleData(inputBuffer, 0) else -1
                if (size < 0) {
                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex >= 0) {
                val doRender = bufferInfo.size != 0
                if (doRender) lastPresentationTimeUs = bufferInfo.presentationTimeUs
                decoder.releaseOutputBuffer(outIndex, doRender)
                if (doRender) awaitNewImage()
                val isEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (isEos) eos = true
                if (doRender) return true
                if (isEos) return false
            }
        }
    }

    private fun awaitNewImage() {
        synchronized(frameLock) {
            while (!frameAvailable) frameLock.wait(2_500)
            frameAvailable = false
        }
        surfaceTexture.updateTexImage()
    }

    fun transformMatrix(): FloatArray {
        val matrix = FloatArray(16)
        surfaceTexture.getTransformMatrix(matrix)
        return matrix
    }

    fun release() {
        runCatching { decoder.stop() }
        runCatching { decoder.release() }
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        runCatching { extractor.release() }
    }
}
