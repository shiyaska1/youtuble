package com.ytsaver.app.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where one scanned page ended up - a MediaStore row (API 29+) or a plain file (older devices). */
sealed class PageLocation {
    data class MediaStoreUri(val uri: Uri) : PageLocation()
    data class LegacyFile(val file: File) : PageLocation()
}

/** One scan session's folder: a set of page photos captured together. */
data class ScanFolder(
    /** The path segment under Pictures/YTSaver, e.g. "Scan 2026-09-03 143105". */
    val folderName: String,
    val imageCount: Int,
    val thumbnail: PageLocation?
)

/**
 * Saves and lists page-scan photos under the public Pictures/YTSaver folder -
 * shared by the camera capture flow and (previously) the video-based
 * extractor, and by the folder list / PDF / share screens.
 */
object ScanFileStore {

    private const val ROOT_SUBDIR = "YTSaver"

    fun newSessionFolderName(): String =
        "Scan " + SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())

    fun savePage(context: Context, bitmap: Bitmap, sessionFolder: String, pageNumber: Int): PageLocation {
        val fileName = "Page %03d.jpg".format(pageNumber)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, bitmap, sessionFolder, fileName)
        } else {
            saveToPublicDir(bitmap, sessionFolder, fileName)
        }
    }

    /** All scan-session folders that currently have at least one photo, newest first. */
    fun listFolders(context: Context): List<ScanFolder> {
        val perFolder = linkedMapOf<String, MutableList<PageLocation>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("${Environment.DIRECTORY_PICTURES}/$ROOT_SUBDIR/%")
            context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Images.Media.DATE_ADDED} DESC")
                ?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        val relativePath = cursor.getString(pathCol) ?: continue
                        val folder = relativePath
                            .removePrefix("${Environment.DIRECTORY_PICTURES}/$ROOT_SUBDIR/")
                            .trimEnd('/')
                        if (folder.isBlank() || folder.contains('/')) continue
                        val id = cursor.getLong(idCol)
                        val uri = android.content.ContentUris.withAppendedId(collection, id)
                        perFolder.getOrPut(folder) { mutableListOf() }.add(PageLocation.MediaStoreUri(uri))
                    }
                }
        } else {
            val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ROOT_SUBDIR)
            root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.lastModified() }?.forEach { dir ->
                val images = dir.listFiles { f -> f.isFile && f.extension.lowercase() == "jpg" }
                    ?.sortedBy { it.name }
                    ?.map { PageLocation.LegacyFile(it) }
                    .orEmpty()
                if (images.isNotEmpty()) perFolder[dir.name] = images.toMutableList()
            }
        }
        return perFolder.map { (name, images) -> ScanFolder(name, images.size, images.firstOrNull()) }
    }

    fun listImagesInFolder(context: Context, folderName: String): List<PageLocation> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
            val args = arrayOf("${Environment.DIRECTORY_PICTURES}/$ROOT_SUBDIR/$folderName/")
            val result = mutableListOf<Pair<String, PageLocation>>()
            context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val uri = android.content.ContentUris.withAppendedId(collection, id)
                    result += cursor.getString(nameCol).orEmpty() to PageLocation.MediaStoreUri(uri)
                }
            }
            return result.sortedBy { it.first }.map { it.second }
        }
        val dir = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ROOT_SUBDIR), folderName)
        return dir.listFiles { f -> f.isFile && f.extension.lowercase() == "jpg" }
            ?.sortedBy { it.name }
            ?.map { PageLocation.LegacyFile(it) }
            .orEmpty()
    }

    private fun saveViaMediaStore(context: Context, bitmap: Bitmap, sessionFolder: String, fileName: String): PageLocation {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ROOT_SUBDIR/$sessionFolder")
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
        val dir = File(File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ROOT_SUBDIR), sessionFolder)
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
