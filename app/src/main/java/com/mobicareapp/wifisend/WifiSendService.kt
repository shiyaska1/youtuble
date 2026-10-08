package com.mobicareapp.wifisend

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mobicareapp.MainActivity
import com.mobicareapp.YtSaverApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hosts [WifiSendServer] independently of the "Send over Wi-Fi" dialog's Compose lifecycle. That
 * dialog is exactly what's open right as the user switches away to their laptop/another phone to
 * actually load the link — which backgrounds this app and, without this, would trip the app-lock
 * (it replaces the whole nav graph on backgrounding), disposing the dialog's DisposableEffect and
 * killing the server before the transfer could even happen. Running it as a foreground service
 * instead means it keeps serving the file regardless of what the app's own UI is doing.
 */
class WifiSendService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var server: WifiSendServer? = null
    private var fileName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val path = intent?.getStringExtra(EXTRA_FILE_PATH)
        if (path == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Only one transfer at a time — starting a new one (a different file) replaces whatever
        // was being served before rather than running two servers.
        server?.stop()

        val file = File(path)
        fileName = file.name
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"))

        val newServer = WifiSendServer(file)
        server = newServer
        serviceScope.launch {
            newServer.state.collect { transferState ->
                _state.value = transferState
                updateNotification(
                    when (transferState) {
                        is WifiSendServer.TransferState.Starting -> "Starting…"
                        is WifiSendServer.TransferState.Running ->
                            transferState.urls.firstOrNull() ?: "No Wi-Fi address found"
                        is WifiSendServer.TransferState.Transferring -> "Sending…"
                        is WifiSendServer.TransferState.Done -> "Sent — tap Stop when you're done"
                        is WifiSendServer.TransferState.Failed -> "Failed: ${transferState.message}"
                    }
                )
            }
        }
        newServer.start(serviceScope)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        server = null
        serviceScope.cancel()
        _state.value = WifiSendServer.TransferState.Starting
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, WifiSendService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, YtSaverApp.DOWNLOAD_CHANNEL_ID)
            .setContentTitle("Sending \"$fileName\" over Wi-Fi")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val NOTIFICATION_ID = 77
        private const val EXTRA_FILE_PATH = "filePath"
        private const val ACTION_STOP = "com.mobicareapp.wifisend.STOP"

        private val _state = MutableStateFlow<WifiSendServer.TransferState>(WifiSendServer.TransferState.Starting)
        val state = _state.asStateFlow()

        fun start(context: Context, file: File) {
            val intent = Intent(context, WifiSendService::class.java).putExtra(EXTRA_FILE_PATH, file.absolutePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WifiSendService::class.java).setAction(ACTION_STOP))
        }
    }
}
