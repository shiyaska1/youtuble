package com.ytsaver.app.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScanProgress(val sampledMs: Long, val totalMs: Long, val pagesFound: Int)

/** Where one scanned page ended up - a MediaStore row (API 29+) or a plain file (older devices). */
sealed class PageLocation {
    data class MediaStoreUri(val uri: Uri) : PageLocation()
    data class LegacyFile(val file: File) : PageLocation()
}

/**
 * Turns a "flip through a book, one page at a time" video into one clean
 * JPEG per page - samples frames at a fixed interval, detects the
 * still/stable stretches between page turns (skipping the blurry motion in
 * between), and saves one frame per stable stretch. No manual per-page
 * photography needed.
 */
object PageScanExtractor {

    private const val SAMPLE_INTERVAL_MS = 400L
    private const val DIFF_THUMB_SIZE = 48
    // Fraction of max possible per-pixel luminance difference (0..255) that counts as "changed".
    // Frame-to-frame noise on a still page is normally well under this; an in-progress page
    // turn (motion blur, hand passing through frame) is well above it.
    private const val STABLE_THRESHOLD = 10.0
    // Consecutive stable samples required before a stretch counts as "a page", so a brief
    // pause mid-flip isn't mistaken for one (3 samples * 400ms = 1.2s of stillness).
    private const val MIN_STABLE_SAMPLES = 3

    suspend fun extractPages(
        context: Context,
        videoUri: Uri,
        onProgress: (ScanProgress) -> Unit
    ): Result<List<PageLocation>> = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)
            try {
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: error("Couldn't read the video's duration")

                val sessionFolder = "Scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val pages = mutableListOf<PageLocation>()
                var stableCount = 0
                var lastSavedSampleTimeMs = -1L
                var prevThumb: FloatArray? = null
                var lastStableSampleTimeMs = -1L

                var timeMs = 0L
                while (timeMs <= durationMs) {
                    val frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) {
                        val thumb = luminanceThumbnail(frame)
                        val diff = prevThumb?.let { diffScore(it, thumb) } ?: Double.MAX_VALUE

                        if (diff < STABLE_THRESHOLD) {
                            stableCount++
                            lastStableSampleTimeMs = timeMs
                        } else {
                            if (stableCount >= MIN_STABLE_SAMPLES &&
                                lastStableSampleTimeMs - lastSavedSampleTimeMs > SAMPLE_INTERVAL_MS
                            ) {
                                pages += savePage(context, retriever, lastStableSampleTimeMs, sessionFolder, pages.size + 1)
                                lastSavedSampleTimeMs = lastStableSampleTimeMs
                            }
                            stableCount = 0
                        }
                        prevThumb = thumb
                        frame.recycle()
                    }

                    onProgress(ScanProgress(timeMs, durationMs, pages.size))
                    timeMs += SAMPLE_INTERVAL_MS
                }

                // Trailing page still on screen when the video ends.
                if (stableCount >= MIN_STABLE_SAMPLES &&
                    lastStableSampleTimeMs - lastSavedSampleTimeMs > SAMPLE_INTERVAL_MS
                ) {
                    pages += savePage(context, retriever, lastStableSampleTimeMs, sessionFolder, pages.size + 1)
                }

                if (pages.isEmpty()) {
                    error("Couldn't find any still pages in that video - try flipping more slowly and holding each page steady for a moment.")
                }

                pages
            } finally {
                retriever.release()
            }
        }
    }

    /** Downscaled grayscale-luminance samples of the frame, as a flat array, for cheap diffing. */
    private fun luminanceThumbnail(frame: Bitmap): FloatArray {
        val scaled = Bitmap.createScaledBitmap(frame, DIFF_THUMB_SIZE, DIFF_THUMB_SIZE, true)
        val pixels = IntArray(DIFF_THUMB_SIZE * DIFF_THUMB_SIZE)
        scaled.getPixels(pixels, 0, DIFF_THUMB_SIZE, 0, 0, DIFF_THUMB_SIZE, DIFF_THUMB_SIZE)
        if (scaled !== frame) scaled.recycle()
        return FloatArray(pixels.size) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            0.299f * r + 0.587f * g + 0.114f * b
        }
    }

    private fun diffScore(a: FloatArray, b: FloatArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += kotlin.math.abs(a[i] - b[i])
        return sum / a.size
    }

    private fun savePage(
        context: Context,
        retriever: MediaMetadataRetriever,
        atTimeMs: Long,
        sessionFolder: String,
        pageNumber: Int
    ): PageLocation {
        val frame = retriever.getFrameAtTime(atTimeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            ?: error("Couldn't read frame for page $pageNumber")
        try {
            val fileName = "Page %03d.jpg".format(pageNumber)
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, frame, sessionFolder, fileName)
            } else {
                saveToPublicDir(frame, sessionFolder, fileName)
            }
        } finally {
            frame.recycle()
        }
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, sessionFolder: String, fileName: String): PageLocation {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/YTSaver/$sessionFolder")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: error("Couldn't save $fileName")
        context.contentResolver.openOutputStream(uri)?.use { out -> writeJpeg(bitmap, out) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return PageLocation.MediaStoreUri(uri)
    }

    private fun saveToPublicDir(bitmap: Bitmap, sessionFolder: String, fileName: String): PageLocation {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "YTSaver/$sessionFolder")
        dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { out -> writeJpeg(bitmap, out) }
        return PageLocation.LegacyFile(file)
    }

    private fun writeJpeg(bitmap: Bitmap, out: OutputStream) {
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
}

/** A URI safe to hand to another app (e.g. WhatsApp) via ACTION_SEND. */
fun PageLocation.shareUri(context: Context): Uri = when (this) {
    is PageLocation.MediaStoreUri -> uri
    is PageLocation.LegacyFile -> FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
