package com.mobicareapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.mobicareapp.R
import com.mobicareapp.record.AudioRecordService
import com.mobicareapp.record.RecordingSource
import com.mobicareapp.record.RecordingStatus
import com.mobicareapp.record.ScreenRecordService
import com.mobicareapp.record.VideoRecordService
import com.mobicareapp.record.VoiceActivationSettings

/**
 * Shared by [AudioRecordWidgetProvider] and [VideoRecordWidgetProvider] — home-screen widgets
 * that start/stop recording with one tap, added as a more reliable alternative to the
 * volume-button trigger, which depends on an accessibility service filtering hardware key events
 * that several OEMs (MIUI especially) silently block even when the accessibility toggle is
 * properly enabled. A widget tap goes through the normal app-widget broadcast path instead, which
 * isn't subject to that same restriction.
 *
 * Both widgets share the same "stop whatever's active, else start this widget's own kind" tap
 * behavior — tapping while ANY recording is active (audio, video, or screen, whichever was
 * actually started, in-app or from either widget) stops that one, so both widgets stay useful
 * together rather than only being able to stop what they themselves started.
 */
private object RecordWidgetSupport {
    fun handleToggle(context: Context, startWhenIdle: (Context) -> Unit) {
        val active = RecordingStatus.active.value
        if (active != null) {
            when (active.source) {
                RecordingSource.SCREEN -> ScreenRecordService.stop(context)
                RecordingSource.CAMERA -> VideoRecordService.stop(context)
                // Stopping from a widget means there's no Record screen open to show the usual
                // manual "Transcribe" option on — auto-transcribing in the background and
                // reporting back via notification is the only way this flow can offer it at all.
                RecordingSource.MIC -> AudioRecordService.stop(context, autoTranscribe = true)
            }
        } else {
            startWhenIdle(context)
        }
        // The service calls above are async (they just fire an Intent at the service), so the
        // widget can't reflect the new state synchronously here — updateWidgets() runs again once
        // RecordingStatus.active actually changes (see YtSaverApp's collector).
    }

    fun updateWidgets(
        context: Context,
        providerClass: Class<out AppWidgetProvider>,
        toggleAction: String,
        idleLabel: String,
        idleIcon: Int
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, providerClass))
        if (ids.isEmpty()) return
        val isRecording = RecordingStatus.active.value != null
        ids.forEach { widgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_record)
            views.setTextViewText(R.id.widget_label, if (isRecording) "Stop recording" else idleLabel)
            views.setImageViewResource(
                R.id.widget_icon,
                if (isRecording) android.R.drawable.presence_video_busy else idleIcon
            )
            val toggleIntent = Intent(context, providerClass).apply { action = toggleAction }
            val pendingIntent = PendingIntent.getBroadcast(
                context, providerClass.hashCode(), toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pendingIntent)
            manager.updateAppWidget(widgetId, views)
        }
    }

    fun refreshAll(context: Context) {
        updateWidgets(context, AudioRecordWidgetProvider::class.java, AudioRecordWidgetProvider.ACTION_TOGGLE, "Start recording", android.R.drawable.presence_audio_online)
        updateWidgets(context, VideoRecordWidgetProvider::class.java, VideoRecordWidgetProvider.ACTION_TOGGLE, "Start video", android.R.drawable.presence_video_online)
    }
}

class AudioRecordWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        RecordWidgetSupport.updateWidgets(context, AudioRecordWidgetProvider::class.java, ACTION_TOGGLE, "Start recording", android.R.drawable.presence_audio_online)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            RecordWidgetSupport.handleToggle(context) { ctx -> AudioRecordService.start(ctx, VoiceActivationSettings.isEnabled(ctx)) }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.mobicareapp.widget.TOGGLE_AUDIO"
    }
}

class VideoRecordWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        RecordWidgetSupport.updateWidgets(context, VideoRecordWidgetProvider::class.java, ACTION_TOGGLE, "Start video", android.R.drawable.presence_video_online)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) {
            RecordWidgetSupport.handleToggle(context) { ctx -> VideoRecordService.start(ctx) }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.mobicareapp.widget.TOGGLE_VIDEO"
    }
}

/** Called from [com.mobicareapp.YtSaverApp]'s RecordingStatus collector so both widgets stay in sync with whatever's actually recording, from any source. */
fun refreshAllRecordWidgets(context: Context) = RecordWidgetSupport.refreshAll(context)
