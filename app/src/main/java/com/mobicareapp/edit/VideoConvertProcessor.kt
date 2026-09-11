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
 * Rewrites a video the built-in player can't handle into a plain H.264/AAC MP4. Android's own
 * MediaCodec only decodes what the device's OEM shipped a decoder for, which is a much narrower
 * set than what a player like MX Player can open — MX Player gets its wider format support by
 * bundling FFmpeg's own decoders instead of relying on the OS. This uses the same FFmpeg engine
 * (via FFmpegKit) for the same reason: it can decode formats the platform simply has no decoder
 * for at all, which no amount of MediaCodec-based code could ever work around.
 */
object VideoConvertProcessor {

    suspend fun toMp4(context: Context, source: SavedMedia): Result<SavedMedia> = withContext(Dispatchers.IO) {
        runCatching {
            val input = ffmpegInputPath(context, source.filePath)
            val outFile = uniqueOutputFile(context, source)

            // Stream copy first: if the codecs inside are already ones an MP4 container can hold
            // (common when the only problem is the wrapper, e.g. .mkv/.avi), this just repackages
            // the existing compressed data — fast and lossless, no quality lost to re-encoding.
            var session = FFmpegKit.execute(
                "-y -i \"$input\" -c:v copy -c:a copy -movflags +faststart \"${outFile.absolutePath}\""
            )
            if (!ReturnCode.isSuccess(session.returnCode)) {
                outFile.delete()
                // Falls back to a full decode + re-encode, needed whenever the video's actual
                // codec (not just its container) is what the device can't play.
                session = FFmpegKit.execute(
                    "-y -i \"$input\" ${VideoEncoderSupport.encodeArgs()} -pix_fmt yuv420p " +
                        "-c:a aac -b:a 128k -movflags +faststart \"${outFile.absolutePath}\""
                )
                if (!ReturnCode.isSuccess(session.returnCode)) {
                    outFile.delete()
                    throw IOException(ffmpegErrorDetail(session))
                }
            }

            val sizeBytes = outFile.length()
            if (sizeBytes <= 0L) {
                outFile.delete()
                throw IOException("Converting produced an empty file.")
            }

            SavedMedia(
                caption = "${source.caption} (converted)",
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
        var candidate = File(dir, "${base}_converted_$stamp.mp4")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "${base}_converted_$stamp ($counter).mp4")
            counter++
        }
        return candidate
    }
}
