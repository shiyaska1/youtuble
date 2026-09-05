package com.mobicareapp.download

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
import com.mobicareapp.MainActivity
import com.mobicareapp.YtSaverApp
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.PublicMediaStore
import com.mobicareapp.data.SavedMedia
import com.mobicareapp.extract.BROWSER_USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
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
    val error: String? = null,
    val savedMedia: SavedMedia? = null
)

private sealed class DownloadTarget {
    data class LegacyFile(val file: File) : DownloadTarget()
    data class MediaStoreUri(val uri: Uri) : DownloadTarget()
}

/**
 * Tracks progress across retry attempts within one [DownloadService.runDownload] call, so a
 * retry after a transient failure (e.g. a brief network hiccup while the app is backgrounded)
 * can resume from where it left off instead of restarting the whole download and visibly
 * resetting progress back to 0%.
 */
private class DownloadState {
    var target: DownloadTarget? = null

    // Ranged/chunked path (downloadToTarget).
    var totalBytes: Long? = null
    var probeDone: Boolean = false
    var probedSize: Long = 0L
    // Range starts already written to disk. A set rather than a byte cursor because ranges are
    // fetched concurrently and can finish out of order — a cursor could skip a still-missing
    // range or re-fetch one that already landed.
    val completedRanges: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    // HLS path (downloadHlsToTarget). Segments are written strictly in order within each
    // concurrent batch, so a simple running counter is safe here.
    var completedSegments: Int = 0

    // Display-only estimate of bytes written so far; shown in the notification/progress UI.
    var bytesDone: Long = 0L

    // Set once the target has been finalized (MediaStore) or fully written (legacy file) and
    // the post-download size checks are running — a failure past this point means the target
    // itself is suspect, so the next attempt starts over with a fresh one instead of resuming.
    var finishedWriting: Boolean = false
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
        // OkHttp defaults to 5 concurrent requests per host, which would otherwise silently cap
        // both the ranged-chunk and HLS-segment parallelism below.
        .dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 8 })
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

    /**
     * Retries a download that came back truncated (some CDNs cut a long-lived connection early
     * under load, which the sequential fallback path can't tell apart from a real EOF) up to a
     * few times before giving up. Progress carries over between attempts via [DownloadState] —
     * a retry resumes from the last segment/range that made it to disk instead of restarting
     * from 0%, which used to make backgrounding the app during a transient network hiccup look
     * like the whole download had started over.
     */
    private suspend fun runDownload(request: DownloadRequest) {
        val state = DownloadState()
        var lastError: Exception? = null
        var succeeded = false
        try {
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    attemptDownload(request, state)
                    succeeded = true
                    break
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The Cancel button surfaces here as a plain IOException ("Canceled") from OkHttp,
                    // not a CancellationException, since it comes from client.dispatcher.cancelAll()
                    // rather than coroutine cancellation alone — check the job explicitly so a
                    // deliberate cancel doesn't get treated as a transient failure worth retrying.
                    kotlin.coroutines.coroutineContext.ensureActive()
                    lastError = e
                    if (state.finishedWriting) {
                        // The failure happened after this target was already finalized/fully
                        // written (e.g. the post-download truncation check) — resuming into an
                        // already-finalized target doesn't make sense, so start clean next time.
                        discardTarget(state.target)
                        state.target = null
                        state.totalBytes = null
                        state.probeDone = false
                        state.probedSize = 0L
                        state.completedRanges.clear()
                        state.completedSegments = 0
                        state.bytesDone = 0L
                        state.finishedWriting = false
                    }
                }
            }
        } finally {
            if (!succeeded) discardTarget(state.target)
        }
        if (!succeeded) {
            _progress.value = DownloadProgress(
                request.caption, 0, 0, done = true,
                error = lastError?.message ?: "Download failed"
            )
        }
    }

    private fun discardTarget(target: DownloadTarget?) {
        when (target) {
            is DownloadTarget.LegacyFile -> target.file.delete()
            is DownloadTarget.MediaStoreUri -> PublicMediaStore.abandon(this, target.uri)
            null -> Unit
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun attemptDownload(request: DownloadRequest, state: DownloadState) {
        val app = application as YtSaverApp
        val fileName = "${sanitizeFileName(request.caption)}.${request.fileExtension}"

        if (state.target == null) {
            state.target = if (PublicMediaStore.isSupported()) {
                DownloadTarget.MediaStoreUri(
                    PublicMediaStore.createPendingTarget(this, request.type, fileName, request.mimeType)
                )
            } else {
                val dir = legacyMediaDir(request.type)
                DownloadTarget.LegacyFile(uniqueFile(dir, sanitizeFileName(request.caption), request.fileExtension))
            }
        }
        val target = state.target!!

        _progress.value = DownloadProgress(request.caption, state.bytesDone, state.totalBytes ?: 0)
        val expectedBytes = if (request.isHls) {
            downloadHlsToTarget(target, request.streamUrl, request.caption, request.cookie, request.referer, state)
            null
        } else {
            downloadToTarget(target, request.streamUrl, request.caption, request.cookie, request.referer, state)
        }

        val storedPath: String
        val sizeBytes: Long
        when (target) {
            is DownloadTarget.MediaStoreUri -> {
                PublicMediaStore.finalize(this, target.uri)
                state.finishedWriting = true
                storedPath = target.uri.toString()
                sizeBytes = MediaAccess.length(this, storedPath)
            }
            is DownloadTarget.LegacyFile -> {
                state.finishedWriting = true
                storedPath = target.file.absolutePath
                sizeBytes = target.file.length()
            }
        }

        // An empty result usually means the link needed something we didn't send (session
        // cookies, a specific referer) and the server answered with nothing rather than an
        // error — treat that as a real failure instead of "saving" an unplayable 0-byte file.
        if (sizeBytes <= 0L) {
            throw IOException("Downloaded file was empty — the link may need you to be signed in, or may have expired")
        }
        // Some CDNs close a long-lived connection early under load instead of erroring out,
        // which used to silently save a truncated file (e.g. 2MB of a much larger video).
        if (expectedBytes != null && sizeBytes < expectedBytes) {
            throw IOException("Connection was cut short (got ${formatMegabytes(sizeBytes)} of ${formatMegabytes(expectedBytes)} MB)")
        }

        val savedMedia = SavedMedia(
            caption = request.caption,
            sourceUrl = request.sourceUrl,
            type = request.type,
            filePath = storedPath,
            fileName = fileName,
            thumbnailUrl = request.thumbnailUrl,
            sizeBytes = sizeBytes,
            durationSeconds = request.durationSeconds,
            createdAt = System.currentTimeMillis()
        ).let { it.copy(id = app.database.savedMediaDao().insert(it)) }
        _progress.value = DownloadProgress(request.caption, sizeBytes, sizeBytes, done = true, savedMedia = savedMedia)
    }

    private fun formatMegabytes(bytes: Long): String = "%.1f".format(bytes / (1024.0 * 1024))

    /**
     * Resolves the playlist to its ordered segment URLs and fetches them several at a time (one
     * request per segment at a time was far too slow — mostly spent waiting on per-request
     * latency rather than actual transfer), writing each to the target strictly in order once
     * fetched regardless of which finishes first within a batch. Progress counts segments, not
     * bytes, since the total byte size of an HLS stream isn't known ahead of time. Since writes
     * within a batch happen strictly in order, [DownloadState.completedSegments] is always an
     * accurate count of what's actually on disk, so a retry can pick up from there via `drop`.
     */
    private suspend fun downloadHlsToTarget(
        target: DownloadTarget,
        playlistUrl: String,
        caption: String,
        cookie: String?,
        referer: String?,
        state: DownloadState
    ) = coroutineScope {
        val playlist = HlsResolver.resolve(playlistUrl, cookie, referer)
        if (playlist.isEncrypted) {
            throw IOException("This video stream is encrypted and can't be downloaded")
        }
        if (playlist.segmentUrls.isEmpty()) {
            throw IOException("Couldn't find any video segments in that stream")
        }

        val total = playlist.segmentUrls.size
        var offset = state.bytesDone
        var completed = state.completedSegments
        if (completed > 0) {
            _progress.value = DownloadProgress(caption, completed.toLong(), total.toLong())
        }

        playlist.segmentUrls.withIndex().drop(completed).chunked(HLS_PARALLEL_SEGMENTS).forEach { batch ->
            val fetches = batch.map { (index, segmentUrl) ->
                async(Dispatchers.IO) { index to fetchSegment(segmentUrl, index, total, cookie, referer) }
            }
            // .await() in original list order, not completion order, so segments land on disk in
            // the right sequence even though several were fetched concurrently.
            fetches.forEach { deferred ->
                val (_, bytes) = deferred.await()
                writeAt(target, offset) { out -> out.write(bytes) }
                offset += bytes.size
                completed++
                state.bytesDone = offset
                state.completedSegments = completed
                _progress.value = DownloadProgress(caption, completed.toLong(), total.toLong())
                updateNotification(caption, completed.toLong(), total.toLong())
            }
        }
    }

    private fun fetchSegment(segmentUrl: String, index: Int, total: Int, cookie: String?, referer: String?): ByteArray {
        val segmentRequest = Request.Builder().url(segmentUrl).withSessionHeaders(cookie, referer).build()
        client.newCall(segmentRequest).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Segment ${index + 1} of $total failed (server returned ${response.code})")
            return response.body?.bytes() ?: throw IOException("Segment ${index + 1} of $total was empty")
        }
    }

    /**
     * Probes with a small ranged request first. If the server honors it (206 + Content-Range),
     * the rest is fanned out over parallel ranged connections like before. Some CDNs instead
     * ignore the Range header and just return the whole file from byte 0 on a 200 — issuing more
     * Range requests to a server like that would silently re-fetch byte 0 into every "chunk" and
     * corrupt the output, so that case (and the case where no size is reported at all) falls back
     * to streaming everything sequentially over the one open connection instead (which can't be
     * resumed — a retry of that path always restarts from byte 0).
     *
     * A retry that already got past the probe resumes by re-requesting only the ranges in
     * [DownloadState.completedRanges] that are still missing, rather than everything after some
     * byte cursor — ranges are fetched concurrently and can land out of order, so a cursor could
     * wrongly skip a range that's still missing or re-fetch one that already made it to disk.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun downloadToTarget(
        target: DownloadTarget,
        url: String,
        caption: String,
        cookie: String?,
        referer: String?,
        state: DownloadState
    ): Long? {
        val bytesDone = AtomicLong(state.bytesDone)
        val lastNotify = AtomicLong(0)

        fun notifyProgress(totalBytes: Long) {
            val done = bytesDone.get()
            state.bytesDone = done
            _progress.value = DownloadProgress(caption, done, totalBytes)
            val now = System.currentTimeMillis()
            val prevNotify = lastNotify.get()
            if (now - prevNotify > 400 && lastNotify.compareAndSet(prevNotify, now)) {
                updateNotification(caption, done, totalBytes)
            }
        }

        suspend fun fetchRanges(ranges: List<LongRange>, totalBytes: Long) {
            ranges.asFlow()
                .flatMapMerge(concurrency = PARALLEL_CONNECTIONS) { range ->
                    flow {
                        downloadRangeInto(target, url, range, cookie, referer) { justRead ->
                            bytesDone.addAndGet(justRead.toLong())
                            notifyProgress(totalBytes)
                        }
                        state.completedRanges += range.first
                        emit(Unit)
                    }
                }
                .collect()
        }

        val resumeTotal = state.totalBytes
        if (resumeTotal != null && state.probeDone) {
            val remaining = buildRanges(state.probedSize, resumeTotal, CHUNK_SIZE_BYTES)
                .filter { it.first !in state.completedRanges }
            fetchRanges(remaining, resumeTotal)
            return resumeTotal
        }

        val probeRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
            .withSessionHeaders(cookie, referer)
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
                state.totalBytes = totalBytes
                state.probeDone = true
                state.probedSize = probed.size.toLong()
                notifyProgress(totalBytes)

                fetchRanges(buildRanges(probed.size.toLong(), totalBytes, CHUNK_SIZE_BYTES), totalBytes)
                return totalBytes
            } else {
                val totalBytes = body.contentLength().takeIf { it > 0 }
                writeAt(target, 0) { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            bytesDone.addAndGet(read.toLong())
                            notifyProgress(totalBytes ?: 0L)
                        }
                    }
                }
                return totalBytes
            }
        }
    }

    private fun downloadRangeInto(
        target: DownloadTarget,
        url: String,
        range: LongRange,
        cookie: String?,
        referer: String?,
        onBytes: (Int) -> Unit
    ) {
        val httpRequest = Request.Builder()
            .url(url)
            .header("Range", "bytes=${range.first}-${range.last}")
            .withSessionHeaders(cookie, referer)
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

    /** Carries the Browse screen's WebView session along to the actual byte-fetching requests — some sites only serve the file to a signed-in session or a matching referer. */
    private fun Request.Builder.withSessionHeaders(cookie: String?, referer: String?): Request.Builder = apply {
        if (!cookie.isNullOrBlank()) header("Cookie", cookie)
        if (!referer.isNullOrBlank()) header("Referer", referer)
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

        // A connection getting cut early is usually transient (CDN load-shedding), so it's
        // worth a couple of clean retries before surfacing an error to the user.
        private const val MAX_ATTEMPTS = 3

        // Fetching one HLS segment at a time spent most of its time waiting on per-request
        // latency rather than actual transfer, since segments are small; a handful in flight at
        // once hides that latency without overwhelming the server.
        private const val HLS_PARALLEL_SEGMENTS = 6

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
            cookie: String? = null,
            referer: String? = null,
            isHls: Boolean = false
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
                putExtra(EXTRA_COOKIE, cookie)
                putExtra(EXTRA_REFERER, referer)
                putExtra(EXTRA_IS_HLS, isHls)
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
        private const val EXTRA_COOKIE = "cookie"
        private const val EXTRA_REFERER = "referer"
        private const val EXTRA_IS_HLS = "isHls"

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
                cookie = getStringExtra(EXTRA_COOKIE),
                referer = getStringExtra(EXTRA_REFERER),
                isHls = getBooleanExtra(EXTRA_IS_HLS, false)
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
    val cookie: String? = null,
    val referer: String? = null,
    val isHls: Boolean = false
)
