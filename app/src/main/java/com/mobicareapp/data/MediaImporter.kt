package com.mobicareapp.data

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Brings a video already on the phone into the Library. */
object MediaImporter {

    /**
     * Copies [uri] into the app's own storage rather than saving a reference to it. The picker
     * hands back a permission-scoped URI that stops working once the app restarts, and the file
     * behind it can be moved or deleted from elsewhere — an imported video needs to still be there
     * later, and to be editable and playable like anything else in the Library.
     */
    suspend fun importVideo(context: Context, uri: Uri, categoryId: Long?): Result<SavedMedia> =
        withContext(Dispatchers.IO) {
            runCatching {
                val displayName = displayName(context, uri)
                val outFile = uniqueFile(context, displayName)
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Couldn't open the selected video")
                input.use { source -> outFile.outputStream().use { source.copyTo(it) } }

                val sizeBytes = outFile.length()
                if (sizeBytes <= 0L) {
                    outFile.delete()
                    throw IOException("The selected video was empty")
                }

                SavedMedia(
                    caption = displayName.substringBeforeLast('.'),
                    sourceUrl = "imported",
                    type = MediaType.VIDEO,
                    filePath = outFile.absolutePath,
                    fileName = outFile.name,
                    thumbnailUrl = null,
                    sizeBytes = sizeBytes,
                    durationSeconds = 0,
                    createdAt = System.currentTimeMillis(),
                    categoryId = categoryId
                )
            }
        }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Imported video"
    }

    private fun uniqueFile(context: Context, displayName: String): File {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        dir.mkdirs()
        val cleaned = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "video.mp4" }
        val base = cleaned.substringBeforeLast('.', cleaned).take(60)
        val extension = cleaned.substringAfterLast('.', "mp4")
        var candidate = File(dir, "$base.$extension")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($counter).$extension")
            counter++
        }
        return candidate
    }
}
