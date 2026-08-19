package com.ytsaver.app.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.InputStream

/**
 * Reads/deletes a saved item regardless of whether it lives at a plain file
 * path (legacy storage, or API<29 devices) or a content:// MediaStore URI
 * (public Movies/Music storage on API 29+).
 */
object MediaAccess {

    private fun isContentPath(path: String) = path.startsWith("content://")

    fun uri(path: String): Uri =
        if (isContentPath(path)) Uri.parse(path) else Uri.fromFile(File(path))

    /**
     * A URI safe to hand to another app (e.g. via ACTION_SEND). content://
     * MediaStore URIs are already shareable as-is; plain file paths need
     * wrapping in a FileProvider URI since a raw file:// URI would crash
     * with FileUriExposedException on API 24+.
     */
    fun shareUri(context: Context, path: String): Uri =
        if (isContentPath(path)) Uri.parse(path)
        else FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))

    fun playableUriString(path: String): String =
        if (isContentPath(path)) path else File(path).toURI().toString()

    fun openInputStream(context: Context, path: String): InputStream? =
        if (isContentPath(path)) context.contentResolver.openInputStream(Uri.parse(path))
        else File(path).inputStream()

    fun delete(context: Context, path: String): Boolean =
        if (isContentPath(path)) context.contentResolver.delete(Uri.parse(path), null, null) > 0
        else File(path).delete()

    fun length(context: Context, path: String): Long {
        if (!isContentPath(path)) return File(path).length()
        context.contentResolver.query(
            Uri.parse(path), arrayOf(MediaStore.MediaColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }
}
