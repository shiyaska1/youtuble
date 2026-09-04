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
import com.ytsaver.app.extract.BROWSER_USER_AGENT
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
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", BROWSER_USER_AGENT).build())
        }
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
            _progress.value = DownloadProgress(request.caption, 0, 0)
            downloadToTarget(resolvedTarget, request.streamUrl, request.caption)

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
                    createdAt = System.currentTimeMillis()
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

    /**
     * Probes with a small ranged request first. If the server honors it (206 + Content-Range),
     * the rest is fanned out over parallel ranged connections like before. Some CDNs instead
     * ignore the Range header and just return the whole file from byte 0 on a 200 — issuing more
     * Range requests to a server like that would silently re-fetch byte 0 into every "chunk" and
     * corrupt the output, so that case (and the case where no size is reported at all) falls back
     * to streaming everything sequentially over the one open connection instead.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun downloadToTarget(target: DownloadTarget, url: String, caption: String) {
        val bytesDone = AtomicLong(0)
        val lastNotify = AtomicLong(0)

        fun notifyProgress(totalBytes: Long) {
            val done = bytesDone.get()
            _progress.value = DownloadProgress(caption, done, totalBytes)
            val now = System.currentTimeMillis()
            val prevNotify = lastNotify.get()
            if (now - prevNotify > 400 && lastNotify.compareAndSet(prevNotify, now)) {
                updateNotification(caption, done, totalBytes)
            }
        }

        val probeRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
            .build()
        client.newCall(probeRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned ${response.code}")
            val body = response.body ?: throw IOException("Empty response body")

            if (response.code == 206) {
                val totalBytes = parseTotalBytes(response.header("Content-Range"))
                    ?: throw IOException("Server didn't report a file size")
                val probed = body.bytes()
                writeAt(target, 0) { out -> out.write(probed) }
                bytesDone.set(probed.size.toLong())
                notifyProgress(totalBytes)

                buildRanges(probed.size.toLong(), totalBytes, CHUNK_SIZE_BYTES).asFlow()
                    .flatMapMerge(concurrency = PARALLEL_CONNECTIONS) { range ->
                        flow {
                            downloadRangeInto(target, url, range) { justRead ->
                                bytesDone.addAndGet(justRead.toLong())
                                notifyProgress(totalBytes)
                            }
                            emit(Unit)
                        }
                    }
                    .collect()
            } else {
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: 0L
                writeAt(target, 0) { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            bytesDone.addAndGet(read.toLong())
                            notifyProgress(totalBytes)
                        }
                    }
                }
            }
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

    private fun legacyMediaDir(type: MediaType): File {
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
            durationSeconds: Long
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
                durationSeconds = getLongExtra(EXTRA_DURATION, 0)
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
    val durationSeconds: Long
)
