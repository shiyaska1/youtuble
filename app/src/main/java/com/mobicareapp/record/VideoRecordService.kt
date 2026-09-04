package com.mobicareapp.record

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
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

/**
 * Records video via Camera2 straight into a MediaRecorder input surface. The
 * camera session is owned entirely by this service, not by any Activity, so
 * capture keeps running after the screen locks or the app is backgrounded —
 * an Activity-bound preview would get torn down as soon as it stops.
 */
class VideoRecordService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var recorder: MediaRecorder? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var outputPfd: ParcelFileDescriptor? = null
    private var target: RecordTarget? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopRecording() else beginRecording()
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        if (recorder != null) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification())

            val newTarget = RecordFileStore.createTarget(this, MediaType.VIDEO, RecordFileStore.timestampedName(MediaType.VIDEO))
            target = newTarget

            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            newRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            newRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setVideoSize(1280, 720)
            newRecorder.setVideoFrameRate(30)
            newRecorder.setVideoEncodingBitRate(8_000_000)
            outputPfd = RecordFileStore.setOutput(newRecorder, this, newTarget)
            newRecorder.prepare()
            recorder = newRecorder

            val thread = HandlerThread("VideoRecordCamera").apply { start() }
            cameraThread = thread
            val handler = Handler(thread.looper)
            cameraHandler = handler

            val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = backCameraId(cameraManager) ?: throw IllegalStateException("No camera available")

            @Suppress("MissingPermission")
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    openCaptureSession(device, newRecorder, handler)
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                    stopRecording()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (cameraDevice === device) cameraDevice = null
                    RecordingStatus.reportError("Camera error ($error)")
                    stopRecording()
                }
            }, handler)

            wakeLock = RecordFileStore.acquireWakeLock(this, "ytsaver:video-record")
            RecordingStatus.started(MediaType.VIDEO)
        } catch (e: Exception) {
            RecordingStatus.reportError(e.message ?: "Couldn't start video recording")
            cleanupAfterFailure()
        }
    }

    private fun openCaptureSession(device: CameraDevice, recorder: MediaRecorder, handler: Handler) {
        try {
            val surface = recorder.surface
            device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            addTarget(surface)
                        }.build()
                        session.setRepeatingRequest(request, null, handler)
                        recorder.start()
                    } catch (e: Exception) {
                        RecordingStatus.reportError(e.message ?: "Couldn't start camera session")
                        stopRecording()
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    RecordingStatus.reportError("Couldn't configure camera session")
                    stopRecording()
                }
            }, handler)
        } catch (e: Exception) {
            RecordingStatus.reportError(e.message ?: "Couldn't open camera session")
            stopRecording()
        }
    }

    private fun backCameraId(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    private fun stopRecording() {
        val finishedTarget = target
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching {
            recorder?.apply {
                runCatching { stop() }
                reset()
                release()
            }
        }
        runCatching { outputPfd?.close() }
        cameraThread?.quitSafely()

        captureSession = null
        cameraDevice = null
        recorder = null
        outputPfd = null
        cameraThread = null
        cameraHandler = null
        releaseWakeLock()

        if (finishedTarget != null) {
            val caption = RecordFileStore.timestampedName(MediaType.VIDEO)
            serviceScope.launch {
                RecordFileStore.finalizeAndSave(applicationContext, finishedTarget, MediaType.VIDEO, caption)
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
        runCatching { captureSession?.close() }
        captureSession = null
        runCatching { cameraDevice?.close() }
        cameraDevice = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
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
            this, 0, Intent(this, VideoRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, YtSaverApp.RECORD_CHANNEL_ID)
            .setContentTitle("Recording video")
            .setContentText("Tap Stop to finish and save")
            .setSmallIcon(android.R.drawable.presence_video_online)
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
        private const val NOTIFICATION_ID = 52
        private const val ACTION_STOP = "com.mobicareapp.record.STOP_VIDEO"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VideoRecordService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VideoRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}
