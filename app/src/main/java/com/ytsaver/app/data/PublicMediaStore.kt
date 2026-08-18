package com.ytsaver.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore

/**
 * Saves into the phone's public Movies/YTSaver or Music/YTSaver folders (API
 * 29+) so files show up in Gallery, file managers, and music/video apps —
 * not just this app's own Library tab.
 */
object PublicMediaStore {

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    fun createPendingTarget(context: Context, type: MediaType, fileName: String, mimeType: String): Uri {
        val resolver = context.contentResolver
        val collection: Uri
        val relativePath: String
        if (type == MediaType.VIDEO) {
            collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            relativePath = "${Environment.DIRECTORY_MOVIES}/YTSaver"
        } else {
            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            relativePath = "${Environment.DIRECTORY_MUSIC}/YTSaver"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values)
            ?: error("Couldn't create \"$fileName\" in public storage")
    }

    /** Makes the finished file visible to other apps (Gallery, file managers, ...). */
    fun finalize(context: Context, uri: Uri) {
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null)
    }

    /** Removes a partially-written entry after a failed download. */
    fun abandon(context: Context, uri: Uri) {
        context.contentResolver.delete(uri, null, null)
    }
}
