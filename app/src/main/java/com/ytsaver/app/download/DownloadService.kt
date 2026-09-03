package com.ytsaver.app.download

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ytsaver.app.MainActivity
import com.ytsaver.app.YtSaverApp
import com.ytsaver.app.data.MediaAccess
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.data.PublicMediaStore
import com.ytsaver.app.data.SavedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class DownloadProgress(
    val caption: String,
    val bytesDone: Long,
    val totalBytes: Long,
    val done: Boolean = false,
    val error: String? = null
)

private sealed class DownloadTarget {
    data class LegacyFile(val file: File) : DownloadTarget()
    data class MediaStoreUri(val uri: Uri) : DownloadTarget()
}

/**
 * Downloads run through an internal queue (not straight in onStartCommand)
 * so pasting several links in a row while on Wi-Fi queues them all up and
 * they save one after another, instead of racing each other over one shared
 * notification/progress state.
 */
class DownloadService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val queueChannel = Channel<DownloadRequest>(Channel.UNLIMITED)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        serviceScope.launch {
            for (request in queueChannel) {
                val job = launch { runDownload(request) }
                currentJob = job
                job.join()
                currentJob = null

                val remaining = _queueSize.updateAndGet { (it - 1).coerceAtLeast(0) }
                if (remaining == 0) {
                    stopSelf()
                }
            }
        }
    }

    private var currentJob: Job? = null

    private fun cancelCurrentDownload() {
        client.dispatcher.cancelAll()
        currentJob?.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = intent?.toDownloadRequest() ?: return START_NOT_STICKY

        _queueSize.update { it + 1 }
        startForeground(NOTIFICATION_ID, buildNotification(request.caption, 0))
        serviceScope.launch { queueChannel.send(request) }
        return START_NOT_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runDownload(request: DownloadRequest) {
        val app = application as YtSaverApp
        val fileName = "${sanitizeFileName(request.caption)}.${request.fileExtension}"
        var target: DownloadTarget? = null

        try {
            target = if (PublicMediaStore.isSupported()) {
                DownloadTarget.MediaStoreUri(
                    PublicMediaStore.createPendingTarget(this, request.type, fileName, request.mimeType)
                )
            } else {
                val dir = legacyMediaDir(request.type)
                DownloadTarget.LegacyFile(uniqueFile(dir, sanitizeFileName(request.caption), request.fileExtension))
            }

            val resolvedTarget = target

            // A small synchronous probe learns the real file size (from Content-Range)
            // before splitting the rest into chunks fetched concurrently.
            val (totalBytes, probedBytes) = probeAndWriteFirstBytes(resolvedTarget, request.streamUrl)
            val bytesDone = AtomicLong(probedBytes)
            val lastNotify = AtomicLong(0)
            _progress.value = DownloadProgress(request.caption, bytesDone.get(), totalBytes)

            buildRanges(probedBytes, totalBytes, CHUNK_SIZE_BYTES).asFlow()
                .flatMapMerge(concurrency = PARALLEL_CONNECTIONS) { range ->
                    flow {
                        downloadRangeInto(resolvedTarget, request.streamUrl, range) { justRead ->
                            val done = bytesDone.addAndGet(justRead.toLong())
                            _progress.value = DownloadProgress(request.caption, done, totalBytes)
                            val now = System.currentTimeMillis()
                            val prevNotify = lastNotify.get()
                            if (now - prevNotify > 400 && lastNotify.compareAndSet(prevNotify, now)) {
                                updateNotification(request.caption, done, totalBytes)
                            }
                        }
                        emit(Unit)
                    }
                }
                .collect()

            val storedPath: String
            val sizeBytes: Long
            when (resolvedTarget) {
                is DownloadTarget.MediaStoreUri -> {
                    PublicMediaStore.finalize(this, resolvedTarget.uri)
                    storedPath = resolvedTarget.uri.toString()
                    sizeBytes = MediaAccess.length(this, storedPath)
                }
                is DownloadTarget.LegacyFile -> {
                    storedPath = resolvedTarget.file.absolutePath
                    sizeBytes = resolvedTarget.file.length()
                    scanLegacyFile(resolvedTarget.file, request.mimeType)
                }
            }

            app.database.savedMediaDao().insert(
                SavedMedia(
                    caption = request.caption,
                    sourceUrl = request.sourceUrl,
                    type = request.type,
                    filePath = storedPath,
                    fileName = fileName,
                    thumbnailUrl = request.thumbnailUrl,
                    sizeBytes = sizeBytes,
                    durationSeconds = request.durationSeconds,
                    createdAt = System.currentTimeMillis(),
                    categoryId = request.categoryId
                )
            )
            _progress.value = DownloadProgress(request.caption, sizeBytes, sizeBytes, done = true)
        } catch (e: Exception) {
            // Catches cancellation too (Cancel button) so the partial file/MediaStore
            // entry is always cleaned up and the queue always moves on to the next item.
            when (val t = target) {
                is DownloadTarget.LegacyFile -> t.file.delete()
                is DownloadTarget.MediaStoreUri -> PublicMediaStore.abandon(this, t.uri)
                null -> Unit
            }
            _progress.value = DownloadProgress(
                request.caption, 0, 0, done = true,
                error = e.message ?: "Download failed"
            )
        }
    }

    /** Fetches the first chunk synchronously so we learn the real file size before parallelizing. */
    private fun probeAndWriteFirstBytes(target: DownloadTarget, url: String): Pair<Long, Long> {
        val httpRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
            .build()
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            val totalBytes = parseTotalBytes(response.header("Content-Range"))
                ?: response.body?.contentLength()?.takeIf { it > 0 }
                ?: throw IOException("Server didn't report a file size")
            val bytes = response.body?.bytes() ?: throw IOException("Empty response body")
            writeAt(target, 0) { out -> out.write(bytes) }
            return totalBytes to bytes.size.toLong()
        }
    }

    private fun downloadRangeInto(target: DownloadTarget, url: String, range: LongRange, onBytes: (Int) -> Unit) {
        val httpRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=${range.first}-${range.last}")
            .build()
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")
            writeAt(target, range.first) { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        onBytes(read)
                    }
                }
            }
        }
    }

    private fun writeAt(target: DownloadTarget, offset: Long, block: (OutputStream) -> Unit) {
        when (target) {
            is DownloadTarget.LegacyFile -> {
                RandomAccessFile(target.file, "rw").use { raf ->
                    raf.seek(offset)
                    block(RandomAccessFileOutputStream(raf))
                }
            }
            is DownloadTarget.MediaStoreUri -> {
                contentResolver.openFileDescriptor(target.uri, "rw")!!.use { pfd ->
                    FileOutputStream(pfd.fileDescriptor).use { fos ->
                        fos.channel.position(offset)
                        block(fos)
                    }
                }
            }
        }
    }

    private fun buildRanges(start: Long, total: Long, chunkSize: Long): List<LongRange> {
        val ranges = mutableListOf<LongRange>()
        var offset = start
        while (offset < total) {
            val end = minOf(offset + chunkSize - 1, total - 1)
            ranges.add(offset..end)
            offset = end + 1
        }
        return ranges
    }

    /**
     * Only used below API 29, where [PublicMediaStore] isn't available. Saves to the real
     * public Movies/YTSaver or Music/YTSaver folder (visible to Gallery, file managers, etc.)
     * rather than the app's private external-files dir, which nothing else can see. Requires
     * WRITE_EXTERNAL_STORAGE, requested at startup for these older API levels.
     */
    private fun legacyMediaDir(type: MediaType): File {
        val publicSubDir = if (type == MediaType.VIDEO) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_MUSIC
        val publicRoot = Environment.getExternalStoragePublicDirectory(publicSubDir)
        val dir = File(publicRoot, "YTSaver")
        if (dir.mkdirs() || dir.isDirectory) return dir
        // Fall back to the private dir only if the public one truly can't be created
        // (e.g. permission missing/denied).
        val fallback = getExternalFilesDir(publicSubDir) ?: filesDir
        fallback.mkdirs()
        return fallback
    }

    /** Makes a legacy-path file show up in Gallery/file managers immediately instead of
     *  waiting for the next full media scan. */
    private fun scanLegacyFile(file: File, mimeType: String) {
        android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf(mimeType), null)
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

    /** Parses the total size out of a "bytes 0-10485759/104857600" Content-Range header. */
    private fun parseTotalBytes(contentRange: String?): Long? =
        contentRange?.substringAfterLast('/')?.toLongOrNull()

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
        if (activeInstance === this) activeInstance = null
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private var activeInstance: DownloadService? = null

        /** Cancels whatever is currently downloading; the rest of the queue keeps going. */
        fun cancelCurrent() {
            activeInstance?.cancelCurrentDownload()
        }

        // A tiny first request just to read the file's total size off Content-Range.
        private const val PROBE_BYTES = 256L * 1024

        // YouTube's CDN throttles one long-lived connection down toward real-time
        // playback speed; re-requesting in ranged chunks resets that throttle window,
        // which is the same workaround yt-dlp/NewPipe use.
        private const val CHUNK_SIZE_BYTES = 5L * 1024 * 1024
        private const val PARALLEL_CONNECTIONS = 4

        private val _progress = MutableStateFlow<DownloadProgress?>(null)
        val progress = _progress.asStateFlow()

        private val _queueSize = MutableStateFlow(0)
        val queueSize = _queueSize.asStateFlow()

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
            mimeType: String,
            thumbnailUrl: String?,
            durationSeconds: Long,
            categoryId: Long? = null
        ) {
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_CAPTION, caption)
                putExtra(EXTRA_SOURCE_URL, sourceUrl)
                putExtra(EXTRA_STREAM_URL, streamUrl)
                putExtra(EXTRA_TYPE, type.name)
                putExtra(EXTRA_EXTENSION, fileExtension)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_THUMBNAIL, thumbnailUrl)
                putExtra(EXTRA_DURATION, durationSeconds)
                if (categoryId != null) putExtra(EXTRA_CATEGORY_ID, categoryId)
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
        private const val EXTRA_MIME_TYPE = "mimeType"
        private const val EXTRA_THUMBNAIL = "thumbnail"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_CATEGORY_ID = "categoryId"

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
                mimeType = getStringExtra(EXTRA_MIME_TYPE) ?: if (type == MediaType.VIDEO) "video/mp4" else "audio/mp4",
                thumbnailUrl = getStringExtra(EXTRA_THUMBNAIL),
                durationSeconds = getLongExtra(EXTRA_DURATION, 0),
                categoryId = if (hasExtra(EXTRA_CATEGORY_ID)) getLongExtra(EXTRA_CATEGORY_ID, 0) else null
            )
        }
    }
}

private class RandomAccessFileOutputStream(private val raf: RandomAccessFile) : OutputStream() {
    override fun write(b: Int) = raf.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = raf.write(b, off, len)
}

private data class DownloadRequest(
    val caption: String,
    val sourceUrl: String,
    val streamUrl: String,
    val type: MediaType,
    val fileExtension: String,
    val mimeType: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val categoryId: Long? = null
)
