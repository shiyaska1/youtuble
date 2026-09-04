package com.ytsaver.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.ytsaver.app.data.AppDatabase
import com.ytsaver.app.extract.OkHttpNewPipeDownloader
import org.schabi.newpipe.extractor.NewPipe

class YtSaverApp : Application() {

    val database: AppDatabase by lazy { AppDatabase.build(this) }

    override fun onCreate() {
        super.onCreate()
        NewPipe.init(OkHttpNewPipeDownloader.instance)
        createDownloadNotificationChannel()
        createRecordNotificationChannel()
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
