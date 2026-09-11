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
 * Mutes a video, or swaps its audio for a different track from the Library — the video stream is
 * always passed through untouched (`-c:v copy`, fast and lossless); the new audio is re-encoded to
 * AAC regardless of its original format (MP3 included) since that's the one audio codec an MP4
 * container is guaranteed to hold. `-shortest` caps the output at whichever of the two streams is
 * shorter, rather than leaving a silent tail or a frozen final frame.
 */
object ReplaceAudioProcessor {

    suspend fun removeAudio(context: Context, video: SavedMedia): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val input = ffmpegInputPath(context, video.filePath)
            val outFile = uniqueOutputFile(context, video, "muted")

            val session = FFmpegKit.execute("-y -i \"$input\" -c:v copy -an \"${outFile.absolutePath}\"")
            if (!ReturnCode.isSuccess(session.returnCode)) {
                outFile.delete()
                throw IOException(ffmpegErrorDetail(session))
            }

            buildResult(outFile, video, "(muted)")
        }
    }

    suspend fun replaceAudio(context: Context, video: SavedMedia, newAudio: SavedMedia): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val videoInput = ffmpegInputPath(context, video.filePath)
            val audioInput = ffmpegInputPath(context, newAudio.filePath)
            val outFile = uniqueOutputFile(context, video, "dubbed")

            val session = FFmpegKit.execute(
                "-y -i \"$videoInput\" -i \"$audioInput\" -map 0:v:0 -map 1:a:0 " +
                    "-c:v copy -c:a aac -b:a 128k -shortest \"${outFile.absolutePath}\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                outFile.delete()
                throw IOException(ffmpegErrorDetail(session))
            }

            buildResult(outFile, video, "(dubbed)")
        }
    }

    private fun buildResult(outFile: File, video: SavedMedia, captionSuffix: String): SavedMedia {
        val sizeBytes = outFile.length()
        if (sizeBytes <= 0L) {
            outFile.delete()
            throw IOException("That produced an empty file.")
        }
        return SavedMedia(
            caption = "${video.caption} $captionSuffix",
            sourceUrl = video.sourceUrl,
            type = MediaType.VIDEO,
            filePath = outFile.absolutePath,
            fileName = outFile.name,
            thumbnailUrl = null,
            sizeBytes = sizeBytes,
            durationSeconds = video.durationSeconds,
            createdAt = System.currentTimeMillis(),
            categoryId = video.categoryId
        )
    }

    /** FFmpeg needs a plain path or its own "saf:" scheme for a content:// URI — not a raw content:// string. */
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
