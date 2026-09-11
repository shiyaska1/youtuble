package com.mobicareapp.record

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.mobicareapp.MainActivity
import com.mobicareapp.YtSaverApp
import com.mobicareapp.data.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Records microphone audio as a foreground service so it keeps running after the screen locks or the app is backgrounded. */
class AudioRecordService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var recorder: MediaRecorder? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var target: RecordTarget? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopRecording() else beginRecording()
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        if (recorder != null) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification())

            val newTarget = RecordFileStore.createTarget(this, MediaType.AUDIO, RecordFileStore.timestampedName(MediaType.AUDIO))
            target = newTarget

            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            // VOICE_COMMUNICATION was tried here for stronger built-in noise suppression, but on
            // several devices it silently records with no usable audio at all unless the app also
            // explicitly puts AudioManager into MODE_IN_COMMUNICATION — not worth that fragility
            // now that Library's manual/live EQ filters give real, working noise control instead.
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            // Mono + a lower bitrate — voice doesn't need stereo or music-grade quality, and this
            // cuts the saved file to a fraction of the previous size.
            newRecorder.setAudioChannels(1)
            newRecorder.setAudioEncodingBitRate(48_000)
            newRecorder.setAudioSamplingRate(44_100)
            outputPfd = RecordFileStore.setOutput(newRecorder, this, newTarget)
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            // Cuts down steady background noise (fans, traffic, hiss) in the captured audio.
            // MediaRecorder (unlike AudioRecord) doesn't expose a session id up front — it's
            // only readable via the active recording configuration once actually recording.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val sessionId = newRecorder.activeRecordingConfiguration?.clientAudioSessionId
                if (sessionId != null) noiseSuppressor = RecordFileStore.attachNoiseSuppressor(sessionId)
            }

            wakeLock = RecordFileStore.acquireWakeLock(this, "ytsaver:audio-record")
            RecordingStatus.started(MediaType.AUDIO, RecordingSource.MIC)
        } catch (e: Exception) {
            RecordingStatus.reportError(e.message ?: "Couldn't start audio recording")
            cleanupAfterFailure()
        }
    }

    private fun stopRecording() {
        val finishedTarget = target
        try {
            recorder?.apply {
                runCatching { stop() }
                reset()
                release()
            }
        } catch (_: Exception) {
        }
        runCatching { outputPfd?.close() }
        recorder = null
        outputPfd = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        releaseWakeLock()

        if (finishedTarget != null) {
            val caption = RecordFileStore.timestampedName(MediaType.AUDIO)
            serviceScope.launch {
                RecordFileStore.finalizeAndSave(applicationContext, finishedTarget, MediaType.AUDIO, caption)
            }
        }
        target = null
        RecordingStatus.stopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupAfterFailure() {
        target?.let { RecordFileStore.abandon(this, it) }
        target = null
        runCatching { recorder?.release() }
        recorder = null
        runCatching { outputPfd?.close() }
        outputPfd = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        releaseWakeLock()
        RecordingStatus.stopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, AudioRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, YtSaverApp.RECORD_CHANNEL_ID)
            .setContentTitle("Recording audio")
            .setContentText("Tap Stop to finish and save")
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 51
        private const val ACTION_STOP = "com.mobicareapp.record.STOP_AUDIO"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, AudioRecordService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AudioRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
