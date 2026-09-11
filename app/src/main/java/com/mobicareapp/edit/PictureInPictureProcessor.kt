package com.mobicareapp.edit

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.SavedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Composites a small "overlay" video into a corner of a "background" video — a picture-in-
 * picture layout (e.g. a whiteboard/paper as the background, a presenter's own camera clip inset
 * in a corner), the way a recorded class or tutorial often looks. The background's own audio
 * passes through unchanged; the overlay's audio (if it has any) is dropped, on the assumption
 * that only one voice track is wanted.
 *
 * Unlike [VideoEditProcessor] (which copies compressed samples through untouched), this decodes
 * both videos to GPU textures, composites them with a small GL program, and re-encodes the
 * result — genuine per-frame video processing, so it takes roughly as long as the background
 * video's own duration to run (frames are submitted to the encoder at the source's original pace
 * so the output's timestamps come out correct), and produces a freshly re-encoded video track
 * rather than a lossless copy.
 */
object PictureInPictureProcessor {

    suspend fun compose(
        context: Context,
        background: SavedMedia,
        overlay: SavedMedia,
        corner: OverlayCorner,
        overlayScale: Float = 0.3f
    ): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val backgroundUri = MediaAccess.uri(background.filePath)
            val overlayUri = MediaAccess.uri(overlay.filePath)

            val bgVideoFormat = probeTrack(context, backgroundUri, isVideo = true)
                ?: throw IOException("Couldn't find a video track in the background video.")
            val bgMime = bgVideoFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (bgMime != MediaFormat.MIMETYPE_VIDEO_AVC && bgMime != MediaFormat.MIMETYPE_VIDEO_HEVC) {
                throw IOException("This video's format ($bgMime) isn't supported for editing yet.")
            }
            if (probeTrack(context, overlayUri, isVideo = true) == null) {
                throw IOException("Couldn't find a video track in the clip to overlay.")
            }
            val bgAudioFormat = probeTrack(context, backgroundUri, isVideo = false)

            val width = bgVideoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = bgVideoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            val outFile = uniqueOutputFile(context, background)
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            // Added up front (its format is already fully known from the source, no decoding
            // needed) so it's ready whenever the video encoder's own format becomes available —
            // every track has to be added before muxer.start() can be called.
            val audioTrackIndex = bgAudioFormat?.let { muxer.addTrack(it) } ?: -1

            val handlerThread = HandlerThread("PipFrameCallbacks").apply { start() }
            val callbackHandler = Handler(handlerThread.looper)

            val encoderFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = encoder.createInputSurface()
            encoder.start()

            val renderer = PipGlRenderer(encoderInputSurface)
            val backgroundDecoder = FrameDecoder(context, backgroundUri, renderer.backgroundTextureId, callbackHandler)
            val overlayDecoder = FrameDecoder(context, overlayUri, renderer.overlayTextureId, callbackHandler)

            var videoTrackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            fun drainEncoder(endOfStream: Boolean) {
                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            if (!endOfStream) return
                        }
                        outIndex >= 0 -> {
                            val encoded = encoder.getOutputBuffer(outIndex)
                            if (encoded != null && bufferInfo.size != 0 && muxerStarted) {
                                encoded.position(bufferInfo.offset)
                                encoded.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encoded, bufferInfo)
                            }
                            encoder.releaseOutputBuffer(outIndex, false)
                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                        }
                    }
                }
            }

            try {
                var overlayEos = !overlayDecoder.advance()
                var backgroundHasFrame = backgroundDecoder.advance()
                while (backgroundHasFrame) {
                    // Keeps the overlay roughly in step with the background's timeline —
                    // advances it until its frame is at least as new as the background's current
                    // one, so a shorter overlay clip just stops appearing once it runs out.
                    while (!overlayEos && overlayDecoder.lastPresentationTimeUs < backgroundDecoder.lastPresentationTimeUs) {
                        if (!overlayDecoder.advance()) overlayEos = true
                    }

                    renderer.drawFrame(
                        backgroundTransform = backgroundDecoder.transformMatrix(),
                        overlayTransform = if (!overlayEos) overlayDecoder.transformMatrix() else null,
                        overlayCorner = corner,
                        overlayScale = overlayScale,
                        presentationTimeNs = backgroundDecoder.lastPresentationTimeUs * 1000
                    )
                    drainEncoder(endOfStream = false)

                    backgroundHasFrame = backgroundDecoder.advance()
                }
                encoder.signalEndOfInputStream()
                drainEncoder(endOfStream = true)

                if (audioTrackIndex != -1) {
                    copyAudioTrack(context, backgroundUri, muxer, audioTrackIndex)
                }
            } finally {
                backgroundDecoder.release()
                overlayDecoder.release()
                renderer.release()
                runCatching { encoder.stop() }
                encoder.release()
                handlerThread.quitSafely()
                if (muxerStarted) {
                    runCatching { muxer.stop() }
                }
                muxer.release()
            }

            val sizeBytes = outFile.length()
            if (sizeBytes <= 0L) {
                outFile.delete()
                throw IOException("Compositing produced an empty file.")
            }

            SavedMedia(
                caption = "${background.caption} (with overlay)",
                sourceUrl = background.sourceUrl,
                type = MediaType.VIDEO,
                filePath = outFile.absolutePath,
                fileName = outFile.name,
                thumbnailUrl = null,
                sizeBytes = sizeBytes,
                durationSeconds = 0,
                createdAt = System.currentTimeMillis(),
                categoryId = background.categoryId
            )
        }
    }

    private fun probeTrack(context: Context, uri: Uri, isVideo: Boolean): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith(if (isVideo) "video/" else "audio/")) return format
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /** Copies the background's whole audio track through unchanged, same technique as [VideoEditProcessor]'s video passthrough. */
    private fun copyAudioTrack(context: Context, uri: Uri, muxer: MediaMuxer, muxerTrack: Int) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex == -1) return
            extractor.selectTrack(trackIndex)

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = extractor.sampleTime
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                extractor.advance()
            }
        } finally {
            extractor.release()
        }
    }

    private fun uniqueOutputFile(context: Context, template: SavedMedia): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var candidate = File(dir, "${sanitize(template.caption)}_overlay_$stamp.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${sanitize(template.caption)}_overlay_$stamp ($counter).mp4")
            counter++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "video" }.take(60)
    }
}
