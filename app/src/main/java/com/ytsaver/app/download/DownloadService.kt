package com.ytsaver.app.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ytsaver.app.MainActivity
import com.ytsaver.app.YtSaverApp
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.data.SavedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class DownloadProgress(
    val caption: String,
    val bytesDone: Long,
    val totalBytes: Long,
    val done: Boolean = false,
    val error: String? = null
)

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.toDownloadRequest()
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification(request.caption, 0))

        serviceScope.launch {
            runDownload(request)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun runDownload(request: DownloadRequest) {
        val app = application as YtSaverApp
        val targetDir = mediaDir(request.type)
        val targetFile = uniqueFile(targetDir, sanitizeFileName(request.caption), request.fileExtension)

        try {
            val httpRequest = Request.Builder().url(request.streamUrl).build()
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Server returned ${response.code}")
                }
                val body = response.body ?: throw IOException("Empty response body")
                val totalBytes = body.contentLength()
                var bytesDone = 0L

                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var lastNotify = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            bytesDone += read

                            _progress.value = DownloadProgress(request.caption, bytesDone, totalBytes)
                            val now = System.currentTimeMillis()
                            if (now - lastNotify > 400) {
                                lastNotify = now
                                updateNotification(request.caption, bytesDone, totalBytes)
                            }
                        }
                    }
                }
            }

            app.database.savedMediaDao().insert(
                SavedMedia(
                    caption = request.caption,
                    sourceUrl = request.sourceUrl,
                    type = request.type,
                    filePath = targetFile.absolutePath,
                    thumbnailUrl = request.thumbnailUrl,
                    sizeBytes = targetFile.length(),
                    durationSeconds = request.durationSeconds,
                    createdAt = System.currentTimeMillis()
                )
            )
            _progress.value = DownloadProgress(request.caption, targetFile.length(), targetFile.length(), done = true)
        } catch (e: IOException) {
            targetFile.delete()
            _progress.value = DownloadProgress(request.caption, 0, 0, done = true, error = e.message ?: "Download failed")
        }
    }

    private fun mediaDir(type: MediaType): File {
        val publicSubDir = if (type == MediaType.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        val dir = getExternalFilesDir(publicSubDir) ?: filesDir
        dir.mkdirs()
        return dir
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

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return cleaned.ifBlank { "video" }.take(80)
    }

    private fun buildNotification(caption: String, percent: Int): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, YtSaverApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Saving \"$caption\"")
            .setContentText(if (percent in 1..100) "$percent%" else "Starting…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .build()
    }

    private fun updateNotification(caption: String, bytesDone: Long, totalBytes: Long) {
        val percent = if (totalBytes > 0) ((bytesDone * 100) / totalBytes).toInt() else 0
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(caption, percent))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 42

        private val _progress = MutableStateFlow<DownloadProgress?>(null)
        val progress = _progress.asStateFlow()

        fun clearProgress() {
            _progress.value = null
        }

        fun start(
            context: Context,
            caption: String,
            sourceUrl: String,
            streamUrl: String,
            type: MediaType,
            fileExtension: String,
            thumbnailUrl: String?,
            durationSeconds: Long
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_CAPTION, caption)
                putExtra(EXTRA_SOURCE_URL, sourceUrl)
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_TYPE, type.name)
                putExtra(EXTRA_EXTENSION, fileExtension)
                putExtra(EXTRA_THUMBNAIL, thumbnailUrl)
                putExtra(EXTRA_DURATION, durationSeconds)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private const val EXTRA_CAPTION = "caption"
        private const val EXTRA_SOURCE_URL = "sourceUrl"
        private const val EXTRA_STREAM_URL = "streamUrl"
        private const val EXTRA_TYPE = "type"
        private const val EXTRA_EXTENSION = "extension"
        private const val EXTRA_THUMBNAIL = "thumbnail"
        private const val EXTRA_DURATION = "duration"

        private fun Intent.toDownloadRequest(): DownloadRequest? {
            val caption = getStringExtra(EXTRA_CAPTION) ?: return null
            val sourceUrl = getStringExtra(EXTRA_SOURCE_URL) ?: return null
            val streamUrl = getStringExtra(EXTRA_STREAM_URL) ?: return null
            val type = getStringExtra(EXTRA_TYPE)?.let { MediaType.valueOf(it) } ?: return null
            val extension = getStringExtra(EXTRA_EXTENSION) ?: return null
            return DownloadRequest(
                caption = caption,
                sourceUrl = sourceUrl,
                streamUrl = streamUrl,
                type = type,
                fileExtension = extension,
                thumbnailUrl = getStringExtra(EXTRA_THUMBNAIL),
                durationSeconds = getLongExtra(EXTRA_DURATION, 0)
            )
        }
    }
}

private data class DownloadRequest(
    val caption: String,
    val sourceUrl: String,
    val streamUrl: String,
    val type: MediaType,
    val fileExtension: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long
)
