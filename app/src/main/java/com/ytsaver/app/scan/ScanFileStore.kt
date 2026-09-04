package com.ytsaver.app.scan

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The scanner result's PDF/page Uris point at Play Services' own transient cache, so they
 * need to be copied into our own storage right away rather than referenced long-term.
 */
object ScanFileStore {

    fun newBaseName(): String =
        "Scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun copyPdf(context: Context, source: Uri, baseName: String): File {
        val file = uniqueFile(scansDir(context), baseName, "pdf")
        context.contentResolver.openInputStream(source).use { input ->
            file.outputStream().use { output -> input!!.copyTo(output) }
        }
        return file
    }

    fun copyThumbnail(context: Context, source: Uri, baseName: String): File {
        val file = uniqueFile(thumbsDir(context), baseName, "jpg")
        context.contentResolver.openInputStream(source).use { input ->
            file.outputStream().use { output -> input!!.copyTo(output) }
        }
        return file
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun delete(filePath: String, thumbnailPath: String?) {
        File(filePath).delete()
        thumbnailPath?.let { File(it).delete() }
    }

    private fun scansDir(context: Context): File =
        File(context.getExternalFilesDir(null), "Scans").apply { mkdirs() }

    private fun thumbsDir(context: Context): File =
        File(scansDir(context), "thumbs").apply { mkdirs() }

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
