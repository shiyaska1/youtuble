package com.mobicareapp.record

import android.content.Context
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import com.mobicareapp.YtSaverApp
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.MediaCategory
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.PublicMediaStore
import com.mobicareapp.data.SavedMedia
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class RecordTarget {
    data class LegacyFile(val file: File) : RecordTarget()
    data class MediaStoreUri(val uri: Uri) : RecordTarget()
}

/** Shared helpers for AudioRecordService/VideoRecordService: where to write, and how to file the finished capture into the Library. */
object RecordFileStore {

    private const val MAX_WAKE_LOCK_MS = 6L * 60 * 60 * 1000

    fun timestampedName(type: MediaType): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val prefix = if (type == MediaType.VIDEO) "VID" else "AUD"
        return "${prefix}_$stamp"
    }

    fun createTarget(
        context: Context,
        type: MediaType,
        baseName: String,
        fileExtension: String = "mp4",
        mimeType: String = if (type == MediaType.VIDEO) "video/mp4" else "audio/mp4"
    ): RecordTarget {
        return if (PublicMediaStore.isSupported()) {
            RecordTarget.MediaStoreUri(PublicMediaStore.createPendingTarget(context, type, "$baseName.$fileExtension", mimeType))
        } else {
            val subDir = if (type == MediaType.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
            val dir = context.getExternalFilesDir(subDir) ?: context.filesDir
            dir.mkdirs()
            RecordTarget.LegacyFile(File(dir, "$baseName.$fileExtension"))
        }
    }

    /** Points [recorder]'s output at [target]. For a MediaStore target, the returned descriptor must be kept open (and closed by the caller) for the whole recording. */
    fun setOutput(recorder: MediaRecorder, context: Context, target: RecordTarget): ParcelFileDescriptor? =
        when (target) {
            is RecordTarget.LegacyFile -> {
                recorder.setOutputFile(target.file.absolutePath)
                null
            }
            is RecordTarget.MediaStoreUri -> {
                val pfd = context.contentResolver.openFileDescriptor(target.uri, "rw")
                    ?: error("Couldn't open output for recording")
                recorder.setOutputFile(pfd.fileDescriptor)
                pfd
            }
        }

    /** Turns on the device's built-in noise-suppression effect for a recording session, when available — not every device ships (or enables) one, so callers should treat a null result as "no suppression, that's fine". */
    fun attachNoiseSuppressor(audioSessionId: Int): NoiseSuppressor? {
        if (!NoiseSuppressor.isAvailable()) return null
        return runCatching { NoiseSuppressor.create(audioSessionId)?.apply { enabled = true } }.getOrNull()
    }

    fun abandon(context: Context, target: RecordTarget) {
        when (target) {
            is RecordTarget.MediaStoreUri -> PublicMediaStore.abandon(context, target.uri)
            is RecordTarget.LegacyFile -> target.file.delete()
        }
    }

    fun acquireWakeLock(context: Context, tag: String): PowerManager.WakeLock {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            acquire(MAX_WAKE_LOCK_MS)
        }
    }

    /** Finalizes the written file and, if anything was actually captured, files it into the Library under [categoryName] (defaulting to a plain "Audio"/"Video" category). */
    suspend fun finalizeAndSave(
        context: Context,
        target: RecordTarget,
        type: MediaType,
        caption: String,
        categoryName: String = if (type == MediaType.VIDEO) "Video" else "Audio",
        fileExtension: String = "mp4",
        // Library hides audio tagged "recording" behind the 7-tap gesture (see LibraryViewModel).
        // A caller producing audio that isn't a background recording — e.g. text-to-speech output
        // the user explicitly asked to generate — passes a different value so it stays visible.
        sourceUrl: String = "recording"
    ) {
        val storedPath: String
        val sizeBytes: Long
        when (target) {
            is RecordTarget.MediaStoreUri -> {
                PublicMediaStore.finalize(context, target.uri)
                storedPath = target.uri.toString()
                sizeBytes = MediaAccess.length(context, storedPath)
            }
            is RecordTarget.LegacyFile -> {
                storedPath = target.file.absolutePath
                sizeBytes = target.file.length()
            }
        }
        if (sizeBytes <= 0L) {
            abandon(context, target)
            return
        }

        val app = context.applicationContext as YtSaverApp
        val categoryDao = app.database.mediaCategoryDao()
        val categoryId = categoryDao.findByName(categoryName)?.id
            ?: categoryDao.insert(MediaCategory(name = categoryName))

        app.database.savedMediaDao().insert(
            SavedMedia(
                caption = caption,
                sourceUrl = sourceUrl,
                type = type,
                filePath = storedPath,
                fileName = "$caption.$fileExtension",
                thumbnailUrl = null,
                sizeBytes = sizeBytes,
                durationSeconds = 0,
                createdAt = System.currentTimeMillis(),
                categoryId = categoryId
            )
        )
        RecordingStatus.finished(type, storedPath)
    }
}
