package com.mobicareapp

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.mobicareapp.data.AppDatabase
import com.mobicareapp.extract.OkHttpNewPipeDownloader
import com.mobicareapp.record.RecordingStatus
import com.mobicareapp.widget.RecordQuickTileService
import com.mobicareapp.widget.refreshAllRecordWidgets
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.NewPipe

class YtSaverApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(OkHttpNewPipeDownloader.instance)
        // Required once before any PdfBox-Android call (font/resource loading) — skipping this
        // doesn't fail here, it fails later at first real use with a much less obvious error.
        PDFBoxResourceLoader.init(applicationContext)
        createDownloadNotificationChannel()
        createRecordNotificationChannel()
        // Keeps the home-screen record widgets' icon/label in sync with whatever's actually
        // recording, from any source (widget tap, in-app button, notification Stop action) —
        // centralized here instead of duplicated into each recording service.
        applicationScope.launch {
            RecordingStatus.active.collect {
                refreshAllRecordWidgets(this@YtSaverApp)
                RecordQuickTileService.requestUpdate(this@YtSaverApp)
            }
        }
    }

    private fun createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress while a video or audio file is being saved"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createRecordNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RECORD_CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows an ongoing notice while audio or video is being recorded"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val DOWNLOAD_CHANNEL_ID = "downloads"
        const val RECORD_CHANNEL_ID = "recording"
    }
}
