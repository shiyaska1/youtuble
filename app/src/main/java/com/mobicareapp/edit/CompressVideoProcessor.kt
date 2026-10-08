package com.mobicareapp.edit

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
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

/** A quality tier to shrink a video down to — an explicit bitrate per tier, not just a resolution, so the reduction in file size is predictable regardless of the source's own bitrate. */
enum class VideoQuality(val label: String, val targetHeight: Int, val bitrateBps: Int) {
    P240("240p (smallest)", 240, 400_000),
    P360("360p", 360, 700_000),
    P480("480p", 480, 1_200_000),
    P720("720p", 720, 2_500_000)
}

/**
 * Saves a smaller copy of a video for easier sharing/storage — scales down to [VideoQuality]'s
 * resolution (skipped if the source is already at or below that height, since upscaling would
 * only make the file bigger) and re-encodes at that tier's bitrate, which is what actually
 * controls the resulting file size rather than resolution alone.
 */
object CompressVideoProcessor {

    suspend fun compress(context: Context, source: SavedMedia, quality: VideoQuality): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val uri = MediaAccess.uri(source.filePath)
            val sourceHeight = probeVideoHeight(context, uri)
            val input = ffmpegInputPath(context, source.filePath)
            val outFile = uniqueOutputFile(context, source, quality.label)

            val scaleFilter = if (sourceHeight != null && sourceHeight <= quality.targetHeight) {
                null
            } else {
                "-vf \"scale=-2:${quality.targetHeight}\""
            }

            val session = FFmpegKit.execute(
                "-y -i \"$input\" ${scaleFilter.orEmpty()} " +
                    "${VideoEncoderSupport.encodeArgsWithBitrate(quality.bitrateBps)} " +
                    "-c:a aac -b:a 96k -movflags +faststart \"${outFile.absolutePath}\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                outFile.delete()
                throw IOException(ffmpegErrorDetail(session))
            }

            val sizeBytes = outFile.length()
            if (sizeBytes <= 0L) {
                outFile.delete()
                throw IOException("Compressing produced an empty file.")
            }

            SavedMedia(
                caption = "${source.caption} (${quality.label})",
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

    private fun probeVideoHeight(context: Context, uri: Uri): Int? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return format.getInteger(MediaFormat.KEY_HEIGHT)
            }
            null
        } catch (e: Exception) {
            null
        } finally {
            extractor.release()
        }
    }

    private fun ffmpegInputPath(context: Context, path: String): String =
        if (path.startsWith("content://")) {
            FFmpegKitConfig.getSafParameterForRead(context, Uri.parse(path))
        } else {
            path
        }

    private fun uniqueOutputFile(context: Context, source: SavedMedia, suffix: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = source.caption.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "video" }.take(60)
        var candidate = File(dir, "${base}_${suffix}_$stamp.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_${suffix}_$stamp ($counter).mp4")
            counter++
        }
        return candidate
    }
}
