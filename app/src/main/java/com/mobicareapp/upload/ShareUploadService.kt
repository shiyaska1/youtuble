package com.mobicareapp.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.mobicareapp.ui.upload.ShareUploadActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Uploads files shared to the app ("Upload to my server" in another app's Share menu) as a foreground
 * service, so a big upload keeps going when the phone is locked or the app is left. Progress is in the
 * notification; when it is finished the address is copied to the clipboard and a "finished" notification is left.
 */
class ShareUploadService : Service() {
    private class Work(val uri: Uri, val index: Int, val source: ShareUploader.Source, val serverName: String)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ConcurrentLinkedQueue<Work>()
    private var worker: Job? = null
    @Volatile private var cancelRequested = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        if (intent?.action == ACTION_CANCEL) {
            cancelRequested = true
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, progressNotification("Preparing…", 0, true))

        val uris = uriList(intent)
        if (uris.isNotEmpty()) {
            ShareUploadState.startFresh()
            val described = uris.map { it to ShareUploader.describe(this, it) }
            val first = ShareUploadState.append(described.mapIndexed { i, (_, source) ->
                ShareUploadItem(source.name, ShareUploader.serverNameFor(source, i + 1), source.size)
            })
            described.forEachIndexed { i, (uri, source) ->
                queue.add(Work(uri, first + i, source, ShareUploader.serverNameFor(source, i + 1)))
            }
        }
        if (worker?.isActive != true) {
            cancelRequested = false
            worker = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    private suspend fun runQueue() {
        ShareUploadState.setRunning(true)
        acquireWakeLock()
        var failures = 0
        var lastName = ""
        try {
            while (true) {
                val work = queue.poll() ?: break
                lastName = work.source.name
                if (cancelRequested) {
                    ShareUploadState.update(work.index) { it.copy(status = UploadStatus.FAILED, error = "Cancelled") }
                    failures++
                    continue
                }
                ShareUploadState.update(work.index) { it.copy(status = UploadStatus.UPLOADING) }
                var lastNotified = 0L
                val result = runCatching {
                    ShareUploader.upload(
                        context = this@ShareUploadService,
                        uri = work.uri,
                        source = work.source,
                        serverName = work.serverName,
                        onProgress = { sent ->
                            ShareUploadState.update(work.index) { it.copy(sent = sent) }
                            val now = System.currentTimeMillis()
                            if (now - lastNotified > 700) {
                                lastNotified = now
                                val total = ShareUploadState.items.value.getOrNull(work.index)?.size ?: 0L
                                notify(progressNotification(work.source.name, if (total > 0) (sent * 100 / total).toInt() else 0, total <= 0))
                            }
                        },
                        cancelled = { cancelRequested }
                    )
                }
                result
                    .onSuccess { url ->
                        ShareUploadState.update(work.index) { it.copy(status = UploadStatus.DONE, url = url, sent = it.size) }
                    }
                    .onFailure { e ->
                        failures++
                        ShareUploadState.update(work.index) { it.copy(status = UploadStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
                    }
            }
        } finally {
            releaseWakeLock()
            ShareUploadState.setRunning(false)
        }

        val copied = ShareUploadState.copyUrls(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        val done = ShareUploadState.finishedUrls()
        val text = when {
            done.isNotEmpty() && failures == 0 -> (if (copied) "URL copied: " else "") + done.joinToString("  ")
            done.isNotEmpty() -> "${done.size} uploaded, $failures failed"
            else -> "Failed: " + (ShareUploadState.items.value.lastOrNull { it.error != null }?.error ?: lastName)
        }
        notify(finishedNotification(if (done.isNotEmpty() && failures == 0) "Upload finished" else "Upload did not finish", text), FINISHED_ID)
        stopSelf()
    }

    private fun uriList(intent: Intent?): List<Uri> {
        intent ?: return emptyList()
        val fromExtra = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableArrayListExtra(EXTRA_URIS, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)
        }
        return fromExtra.orEmpty()
    }

    private fun notify(notification: Notification, id: Int = NOTIFICATION_ID) {
        runCatching { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification) }
    }

    private fun openScreen(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, ShareUploadActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun progressNotification(name: String, percent: Int, indeterminate: Boolean): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, ShareUploadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Uploading $name")
            .setContentText(if (indeterminate) "Starting…" else "$percent%")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setProgress(100, percent.coerceIn(0, 100), indeterminate)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openScreen())
            .addAction(0, "Cancel", cancel)
            .build()
    }

    private fun finishedNotification(title: String, text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setAutoCancel(true)
            .setContentIntent(openScreen())
            .build()

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Uploads to my server", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun acquireWakeLock() {
        runCatching {
            val power = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytsaver:shareupload").apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "share_upload"
        private const val NOTIFICATION_ID = 7301
        private const val FINISHED_ID = 7302
        private const val ACTION_CANCEL = "com.mobicareapp.upload.CANCEL_SHARE_UPLOAD"
        private const val EXTRA_URIS = "uris"

        /** Starts uploading [uris]. The service is given read permission for them along with the intent. */
        fun start(context: Context, uris: List<Uri>) {
            val intent = Intent(context, ShareUploadService::class.java)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.isNotEmpty()) {
                intent.clipData = ClipData.newRawUri("shared", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ShareUploadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
