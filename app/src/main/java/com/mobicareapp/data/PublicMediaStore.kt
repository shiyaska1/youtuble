package com.mobicareapp.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * Can save into the phone's public Movies/YTSaver or Music/YTSaver folders (API 29+) so files
 * show up in Gallery, file managers, and music/video apps — not just this app's own Library tab.
 * Kept disabled: this app is meant to be private, so every caller of this object instead falls
 * back to its own app-scoped storage (not visible to Gallery or other apps) unconditionally.
 */
object PublicMediaStore {

    fun isSupported(): Boolean = false

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

        fun values(mime: String) = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        // MediaStore's Audio/Video collections only accept a limited, OS-version-dependent
        // whitelist of MIME types (e.g. "audio/webm" from WebM/Opus YouTube tracks is
        // rejected on many devices) and throw IllegalArgumentException instead of failing
        // gracefully. Retry once with a generic, always-accepted MIME type for that media kind.
        return runCatching { resolver.insert(collection, values(mimeType)) }
            .getOrNull()
            ?: resolver.insert(collection, values(if (type == MediaType.VIDEO) "video/mp4" else "audio/mp4"))
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
