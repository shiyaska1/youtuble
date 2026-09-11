package com.mobicareapp.edit

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
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
 * Cuts, joins, and splices video files without re-encoding — each segment's compressed
 * video/audio samples are copied through as-is (like the video track in
 * [com.mobicareapp.process.NoiseFilterProcessor]), just re-timed to sit back-to-back in one
 * output file. That's fast and lossless, but it does mean every segment being joined together
 * must already share the same video codec/resolution and audio codec/sample-rate/channel-count —
 * mixing, say, a 640x360 recording with a differently-sized downloaded video isn't supported yet
 * (that would need decoding and re-encoding every frame to a common format, a much bigger job).
 * Trim start points snap to the nearest preceding keyframe rather than being frame-exact, the
 * same trade-off most non-re-encoding trim tools make.
 */
object VideoEditProcessor {

    data class Segment(val sourceUri: Uri, val startMs: Long? = null, val endMs: Long? = null)

    suspend fun trim(context: Context, source: SavedMedia, startMs: Long, endMs: Long): Result<SavedMedia> =
        compose(context, listOf(Segment(MediaAccess.uri(source.filePath), startMs, endMs)), source, "(trimmed)")

    suspend fun merge(context: Context, sources: List<SavedMedia>): Result<SavedMedia> {
        if (sources.size < 2) return Result.failure(IOException("Pick at least two videos to merge."))
        return compose(context, sources.map { Segment(MediaAccess.uri(it.filePath)) }, sources.first(), "(merged)")
    }

    /** Splices [clip] into [base] at [insertAtMs] — equivalent to base[0, insertAtMs] + clip + base[insertAtMs, end]. */
    suspend fun insertClip(context: Context, base: SavedMedia, insertAtMs: Long, clip: SavedMedia): Result<SavedMedia> {
        val baseUri = MediaAccess.uri(base.filePath)
        val segments = listOf(
            Segment(baseUri, 0, insertAtMs),
            Segment(MediaAccess.uri(clip.filePath)),
            Segment(baseUri, insertAtMs, null)
        )
        return compose(context, segments, base, "(with insert)")
    }

    private suspend fun compose(
        context: Context,
        segments: List<Segment>,
        templateSource: SavedMedia,
        captionSuffix: String
    ): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val videoFormat = probeTrack(context, segments.first().sourceUri, isVideo = true)
                ?: throw IOException("Couldn't find a video track to work with.")
            val mime = videoFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime != MediaFormat.MIMETYPE_VIDEO_AVC && mime != MediaFormat.MIMETYPE_VIDEO_HEVC) {
                throw IOException("This video's format ($mime) isn't supported for editing yet.")
            }
            val width = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val height = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)

            for ((index, segment) in segments.withIndex()) {
                val format = probeTrack(context, segment.sourceUri, isVideo = true)
                    ?: throw IOException("Clip ${index + 1} has no video track.")
                val segMime = format.getString(MediaFormat.KEY_MIME)
                val segWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                val segHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (segMime != mime || segWidth != width || segHeight != height) {
                    throw IOException(
                        "Clip ${index + 1} doesn't match the others (different video format/resolution) — " +
                            "merging clips with different formats isn't supported yet."
                    )
                }
            }

            val audioFormat = probeTrack(context, segments.first().sourceUri, isVideo = false)
            val includeAudio = audioFormat != null && segments.all { seg ->
                val f = probeTrack(context, seg.sourceUri, isVideo = false) ?: return@all false
                f.getString(MediaFormat.KEY_MIME) == audioFormat.getString(MediaFormat.KEY_MIME) &&
                    f.getInteger(MediaFormat.KEY_SAMPLE_RATE) == audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) &&
                    f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }

            val outFile = uniqueOutputFile(context, templateSource)
            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoTrack = muxer.addTrack(videoFormat)
            val audioTrack = if (includeAudio) muxer.addTrack(audioFormat!!) else -1

            muxer.start()
            try {
                var videoOffsetUs = 0L
                var audioOffsetUs = 0L
                for (segment in segments) {
                    videoOffsetUs += copySegment(context, segment, isVideo = true, muxer, videoTrack, videoOffsetUs)
                    if (includeAudio) {
                        audioOffsetUs += copySegment(context, segment, isVideo = false, muxer, audioTrack, audioOffsetUs)
                    }
                }
            } finally {
                muxer.stop()
                muxer.release()
            }

            val sizeBytes = outFile.length()
            if (sizeBytes <= 0L) {
                outFile.delete()
                throw IOException("Editing produced an empty file.")
            }

            SavedMedia(
                caption = "${templateSource.caption} $captionSuffix",
                sourceUrl = templateSource.sourceUrl,
                type = MediaType.VIDEO,
                filePath = outFile.absolutePath,
                fileName = outFile.name,
                thumbnailUrl = null,
                sizeBytes = sizeBytes,
                durationSeconds = 0,
                createdAt = System.currentTimeMillis(),
                categoryId = templateSource.categoryId
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

    /** Copies one segment's track into [muxer], shifting every sample's timestamp so it lands right after [offsetUs]. Returns how much timeline duration this segment added, so the caller can offset the next one. */
    private fun copySegment(
        context: Context,
        segment: Segment,
        isVideo: Boolean,
        muxer: MediaMuxer,
        muxerTrack: Int,
        offsetUs: Long
    ): Long {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, segment.sourceUri, null)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith(if (isVideo) "video/" else "audio/")) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex == -1) return 0L
            extractor.selectTrack(trackIndex)

            val startUs = (segment.startMs ?: 0L) * 1000
            val endUs = segment.endMs?.let { it * 1000 }
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val bufferInfo = android.media.MediaCodec.BufferInfo()
            var firstSampleTimeUs = -1L
            var lastSampleTimeUs = startUs

            while (true) {
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs < 0) break
                if (endUs != null && sampleTimeUs >= endUs) break

                if (firstSampleTimeUs < 0) firstSampleTimeUs = sampleTimeUs
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                bufferInfo.offset = 0
                bufferInfo.size = size
                bufferInfo.presentationTimeUs = offsetUs + (sampleTimeUs - firstSampleTimeUs)
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                lastSampleTimeUs = sampleTimeUs
                extractor.advance()
            }

            val nominalEndUs = endUs ?: run {
                // No explicit end: use the track's full duration if the format reports one.
                val durationUs = extractor.getTrackFormat(trackIndex).let { f ->
                    if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else null
                }
                durationUs ?: (lastSampleTimeUs + 1)
            }
            return (nominalEndUs - (if (firstSampleTimeUs >= 0) firstSampleTimeUs else startUs)).coerceAtLeast(0L)
        } finally {
            extractor.release()
        }
    }

    private fun uniqueOutputFile(context: Context, template: SavedMedia): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var candidate = File(dir, "${sanitize(template.caption)}_edited_$stamp.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${sanitize(template.caption)}_edited_$stamp ($counter).mp4")
            counter++
        }
        return candidate
    }

    private fun sanitize(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "video" }.take(60)
    }
}
