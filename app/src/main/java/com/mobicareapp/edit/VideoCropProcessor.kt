package com.mobicareapp.edit

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Session
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.SavedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crops a video to a rectangle using FFmpeg's own `crop` filter, rather than the hand-rolled
 * GL/EGL renderer this used previously — that required correctly reasoning about a SurfaceTexture
 * transform matrix whose exact contents aren't consistent across devices/decoders, and twice
 * shipped wrong.
 *
 * The rectangle is expressed as fractions (0..1) of the video the way it's actually displayed —
 * matching what the crop UI shows — but ffmpeg's `crop` filter operates on the *raw* decoded
 * buffer, which for a rotated source (a portrait phone recording stored as a landscape buffer
 * plus a 90-degree hint, say) is not the same thing. [displayToRawRect] maps back to raw-buffer
 * coordinates, `-noautorotate` stops ffmpeg from also auto-applying the hint (which would then
 * double-rotate), and a `transpose`/`flip` stage is appended to the same filter chain to bake the
 * correct final orientation into the output pixels directly — no reliance on a rotation *hint*
 * on the output surviving whatever plays it, the way the first crop rewrite still implicitly did.
 */
object VideoCropProcessor {

    suspend fun crop(
        context: Context,
        source: SavedMedia,
        displayLeft: Float,
        displayTop: Float,
        displayRight: Float,
        displayBottom: Float
    ): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = MediaAccess.uri(source.filePath)
            val videoFormat = probeVideoTrack(context, uri) ?: throw IOException("Couldn't find a video track to crop.")
            val rawWidth = videoFormat.getInteger(MediaFormat.KEY_WIDTH)
            val rawHeight = videoFormat.getInteger(MediaFormat.KEY_HEIGHT)
            val rotation = rotationDegrees(context, uri)

            val raw = displayToRawRect(rotation, displayLeft, displayTop, displayRight, displayBottom)
            val cropLeftPx = (raw.left * rawWidth).toInt().coerceIn(0, rawWidth - 2)
            val cropTopPx = (raw.top * rawHeight).toInt().coerceIn(0, rawHeight - 2)
            val cropWidthPx = evenDimension((raw.right - raw.left) * rawWidth).coerceIn(2, rawWidth - cropLeftPx)
            val cropHeightPx = evenDimension((raw.bottom - raw.top) * rawHeight).coerceIn(2, rawHeight - cropTopPx)

            val rotateStage = when (((rotation % 360) + 360) % 360) {
                90 -> ",transpose=1"
                180 -> ",hflip,vflip"
                270 -> ",transpose=2"
                else -> ""
            }

            val input = ffmpegInputPath(context, source.filePath)
            val outFile = uniqueOutputFile(context, source)
            val session = FFmpegKit.execute(
                "-y -noautorotate -i \"$input\" " +
                    "-vf \"crop=$cropWidthPx:$cropHeightPx:$cropLeftPx:$cropTopPx$rotateStage\" " +
                    "${VideoEncoderSupport.encodeArgs()} -c:a copy \"${outFile.absolutePath}\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                outFile.delete()
                throw IOException(ffmpegErrorDetail(session))
            }

            val sizeBytes = outFile.length()
            if (sizeBytes <= 0L) {
                outFile.delete()
                throw IOException("Cropping produced an empty file.")
            }

            SavedMedia(
                caption = "${source.caption} (cropped)",
                sourceUrl = source.sourceUrl,
                type = MediaType.VIDEO,
                filePath = outFile.absolutePath,
                fileName = outFile.name,
                thumbnailUrl = null,
                sizeBytes = sizeBytes,
                durationSeconds = source.durationSeconds,
                createdAt = System.currentTimeMillis(),
                categoryId = source.categoryId
            )
        }
    }

    private data class RawRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * A rotation hint of 90/270 means the raw buffer is sideways relative to how it's displayed,
     * so a rectangle drawn in display space needs its coordinates rotated back before it can be
     * used to crop the raw buffer. 180 is a straight point-reflection. Derived and checked
     * corner-by-corner against the standard "rotate clockwise by the hint to display" convention.
     */
    private fun displayToRawRect(rotation: Int, dLeft: Float, dTop: Float, dRight: Float, dBottom: Float): RawRect =
        when (((rotation % 360) + 360) % 360) {
            90 -> RawRect(left = dTop, top = 1f - dRight, right = dBottom, bottom = 1f - dLeft)
            180 -> RawRect(left = 1f - dRight, top = 1f - dBottom, right = 1f - dLeft, bottom = 1f - dTop)
            270 -> RawRect(left = 1f - dBottom, top = dLeft, right = 1f - dTop, bottom = dRight)
            else -> RawRect(dLeft, dTop, dRight, dBottom)
        }

    private fun rotationDegrees(context: Context, uri: Uri): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        } finally {
            retriever.release()
        }
    }

    private fun probeVideoTrack(context: Context, uri: Uri): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return format
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    /** H.264 requires even width/height. */
    private fun evenDimension(value: Float): Int {
        val rounded = value.toInt()
        return if (rounded % 2 == 0) rounded else rounded - 1
    }

    /** FFmpeg needs a plain path or its own "saf:" scheme for a content:// URI — not a raw content:// string. */
    private fun ffmpegInputPath(context: Context, path: String): String =
        if (path.startsWith("content://")) {
            FFmpegKitConfig.getSafParameterForRead(context, Uri.parse(path))
        } else {
            path
        }

    private fun uniqueOutputFile(context: Context, source: SavedMedia): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = source.caption.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "video" }.take(60)
        var candidate = File(dir, "${base}_cropped_$stamp.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_cropped_$stamp ($counter).mp4")
            counter++
        }
        return candidate
    }
}

/** [Session.getFailStackTrace] is only set for a Java-level exception (e.g. the binary itself couldn't launch) — a normal ffmpeg command that just fails still returns a clean non-zero exit code with the real reason sitting in its own log output instead, which is what actually needs surfacing to be useful. */
internal fun ffmpegErrorDetail(session: Session): String {
    val logs = runCatching { session.allLogsAsString }.getOrNull()?.trim().orEmpty()
    return when {
        logs.isNotBlank() -> logs.takeLast(600)
        !session.failStackTrace.isNullOrBlank() -> session.failStackTrace
        else -> "FFmpeg failed (return code ${session.returnCode})"
    }
}
