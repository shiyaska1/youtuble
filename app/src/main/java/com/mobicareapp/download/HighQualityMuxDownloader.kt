package com.mobicareapp.download

import android.content.Context
import android.os.Environment
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.mobicareapp.YtSaverApp
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.SavedMedia
import com.mobicareapp.edit.ffmpegErrorDetail
import com.mobicareapp.extract.MediaOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Downloads a YouTube video-only DASH stream and its matching audio stream, then muxes them into
 * one MP4 with ffmpeg — for resolutions beyond what a "progressive" stream (video+audio already
 * combined, which [DownloadService] normally saves directly) ever offers. FFmpeg is handed the
 * two remote URLs directly as inputs rather than downloading each to a temp file first: it can
 * fetch over HTTP(S) itself, so `-c:v copy -c:a copy` becomes one step that streams, remuxes, and
 * writes the output without re-encoding anything.
 *
 * Bypasses [DownloadService] entirely rather than extending it to support two source URLs per
 * item — that would be a much larger change to a part of the app that already works reliably for
 * every other kind of download.
 */
object HighQualityMuxDownloader {

    suspend fun downloadAndMux(
        context: Context,
        videoOption: MediaOption,
        audioOption: MediaOption,
        caption: String,
        sourceUrl: String,
        thumbnailUrl: String?,
        durationSeconds: Long
    ): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
            dir.mkdirs()
            val baseName = sanitizeFileName(caption)
            val outFile = uniqueFile(dir, baseName, "mp4")

            val session = FFmpegKit.execute(
                "-y ${headerArgs(videoOption)}-i \"${videoOption.streamUrl}\" " +
                    "${headerArgs(audioOption)}-i \"${audioOption.streamUrl}\" " +
                    "-map 0:v:0 -map 1:a:0 -c:v copy -c:a copy -movflags +faststart \"${outFile.absolutePath}\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                outFile.delete()
                throw IOException(ffmpegErrorDetail(session))
            }

            val sizeBytes = outFile.length()
            if (sizeBytes <= 0L) {
                outFile.delete()
                throw IOException("Download produced an empty file — the link may have expired, try fetching it again")
            }

            val app = context.applicationContext as YtSaverApp
            SavedMedia(
                caption = caption,
                sourceUrl = sourceUrl,
                type = MediaType.VIDEO,
                filePath = outFile.absolutePath,
                fileName = outFile.name,
                thumbnailUrl = thumbnailUrl,
                sizeBytes = sizeBytes,
                durationSeconds = durationSeconds,
                createdAt = System.currentTimeMillis()
            ).let { it.copy(id = app.database.savedMediaDao().insert(it)) }
        }
    }

    /** Trailing space included when non-empty, so it can be spliced straight before the `-i`. Rarely needed in practice — YouTube's own CDN URLs generally don't require a cookie or referer, unlike the sites GenericVideoFetcher/DirectLinkFetcher scrape. */
    private fun headerArgs(option: MediaOption): String {
        val headerLines = buildString {
            if (!option.referer.isNullOrBlank()) append("Referer: ${option.referer}\r\n")
            if (!option.cookie.isNullOrBlank()) append("Cookie: ${option.cookie}\r\n")
        }
        return if (headerLines.isEmpty()) "" else "-headers \"$headerLines\" "
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "video" }.take(60)
    }

    private fun uniqueFile(dir: File, baseName: String, extension: String): File {
        var candidate = File(dir, "$baseName.$extension")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($counter).$extension")
            counter++
        }
        return candidate
    }
}
