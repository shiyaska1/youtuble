package com.mobicareapp.edit

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
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
 * Rotates a video by a fixed amount the user picks — a manual escape hatch for whenever a video
 * (a crop output, an import, anything) ends up sideways or upside down and needs a direct fix
 * rather than relying on this app's own orientation handling being right.
 */
object RotateVideoProcessor {

    suspend fun rotate(context: Context, source: SavedMedia, degreesClockwise: Int): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val filter = when (((degreesClockwise % 360) + 360) % 360) {
                90 -> "transpose=1"
                180 -> "hflip,vflip"
                270 -> "transpose=2"
                else -> throw IOException("Only 90, 180, or 270 degrees is supported.")
            }

            val input = ffmpegInputPath(context, source.filePath)
            val outFile = uniqueOutputFile(context, source)
            val session = FFmpegKit.execute(
                "-y -i \"$input\" -vf \"$filter\" ${VideoEncoderSupport.encodeArgs()} -c:a copy \"${outFile.absolutePath}\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                outFile.delete()
                throw IOException(ffmpegErrorDetail(session))
            }

            val sizeBytes = outFile.length()
            if (sizeBytes <= 0L) {
                outFile.delete()
                throw IOException("Rotating produced an empty file.")
            }

            SavedMedia(
                caption = "${source.caption} (rotated)",
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
        var candidate = File(dir, "${base}_rotated_$stamp.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_rotated_$stamp ($counter).mp4")
            counter++
        }
        return candidate
    }
}
