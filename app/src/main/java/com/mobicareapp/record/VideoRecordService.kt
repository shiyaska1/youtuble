package com.mobicareapp.record

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.SurfaceTexture
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
import android.view.Surface
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
    private var previewSurface: Surface? = null
    /** MediaRecorder.start() must happen exactly once, but the capture session gets rebuilt every time the preview comes or goes. */
    private var recorderStarted = false
    /** Stopping with the newest start id (rather than unconditionally) keeps a recording that was started moments ago from being killed by a stop meant for the previous one. */
    private var latestStartId = 0
    /** Bumped per [configureSession] call, so a session that's already been superseded can recognise its own callbacks as stale when they arrive late. */
    private var sessionGeneration = 0
    /** Set once at Start, reused at Stop so the file and its Library entry always agree on when the recording began. */
    private var pendingCaption: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == ACTION_STOP) stopRecording() else beginRecording()
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        if (recorder != null) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification())

            // Captured once, here, and reused for both the on-disk file and the eventual Library
            // caption/fileName — generating it again at Stop time meant the two could disagree
            // (the file named for when recording started, the caption for whenever Stop happened
            // to be tapped, which could be minutes later).
            val caption = RecordFileStore.timestampedName(MediaType.VIDEO)
            pendingCaption = caption
            val newTarget = RecordFileStore.createTarget(this, MediaType.VIDEO, caption)
            target = newTarget

            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            // CAMCORDER assumes the audio HAL can tie itself to a camera the MediaRecorder itself
            // opened (the old MediaRecorder + VideoSource.CAMERA pairing). This records from a
            // Camera2 session we manage ourselves and feed in through a surface, so that link
            // never exists and CAMCORDER ends up capturing nothing on some devices. MIC is what
            // Android's own Camera2 video sample uses for exactly this combination.
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioChannels(1)
            newRecorder.setAudioSamplingRate(44_100)
            newRecorder.setAudioEncodingBitRate(96_000)
            newRecorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
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
                    configureSession()
                }
                // A previous recording's camera can still deliver callbacks after we've moved on,
                // and acting on those would tear down the recording that's running now — leaving
                // the next start to fail against a device someone else already closed.
                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    if (cameraDevice !== device) return
                    cameraDevice = null
                    stopRecording()
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    if (cameraDevice !== device) return
                    cameraDevice = null
                    RecordingStatus.reportError("Camera error ($error)")
                    stopRecording()
                }
            }, handler)

            wakeLock = RecordFileStore.acquireWakeLock(this, "ytsaver:video-record")
            RecordingStatus.started(MediaType.VIDEO, RecordingSource.CAMERA)
        } catch (e: Exception) {
            RecordingStatus.reportError(e.message ?: "Couldn't start video recording")
            cleanupAfterFailure()
        }
    }

    /**
     * Builds (or rebuilds) the capture session against whatever surfaces exist right now: always
     * the recorder's, plus the Record screen's preview when the app happens to be open. A camera
     * session's targets are fixed once configured, so showing or hiding the preview means starting
     * a fresh session — the recording itself carries on either way, since MediaRecorder keeps
     * owning its own input surface across the swap.
     */
    private fun configureSession() {
        val device = cameraDevice ?: return
        val activeRecorder = recorder ?: return
        val handler = cameraHandler ?: return
        val recorderSurface = activeRecorder.surface
        val targets = listOfNotNull(recorderSurface, previewSurface)
        try {
            // Opening the camera and the preview surface arriving are independent events, so two
            // configure passes can easily be in flight at once — the earlier one's callbacks then
            // land after its session was closed, and using that session throws "session has been
            // closed". Only the newest generation is allowed to touch anything.
            val generation = ++sessionGeneration
            runCatching { captureSession?.close() }
            captureSession = null
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (generation != sessionGeneration || cameraDevice !== device) {
                        runCatching { session.close() }
                        return
                    }
                    captureSession = session
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                            targets.forEach { addTarget(it) }
                        }.build()
                        session.setRepeatingRequest(request, null, handler)
                        if (!recorderStarted) {
                            activeRecorder.start()
                            recorderStarted = true
                        }
                    } catch (e: Exception) {
                        onSessionFailure(e.message ?: "Couldn't start camera session")
                    }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    if (generation != sessionGeneration) return
                    onSessionFailure("Couldn't configure camera session")
                }
            }, handler)
        } catch (e: Exception) {
            onSessionFailure(e.message ?: "Couldn't open camera session")
        }
    }

    /**
     * Sessions get rebuilt whenever the preview comes or goes — which is exactly when the app is
     * being opened or minimized, the worst possible moment to lose a recording. Once the recorder
     * is running it owns its own input surface, so a failed rebuild costs at most some frames: the
     * file stays valid and still gets saved on Stop. Only a failure before recording ever started
     * is worth giving up on.
     */
    private fun onSessionFailure(message: String) {
        RecordingStatus.reportError(message)
        if (!recorderStarted) stopRecording()
    }

    private fun setPreview(surfaceTexture: SurfaceTexture) {
        surfaceTexture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        runCatching { previewSurface?.release() }
        previewSurface = Surface(surfaceTexture)
        configureSession()
    }

    private fun clearPreview() {
        val old = previewSurface ?: return
        previewSurface = null
        // Drop the session pointing at this surface before letting go of it, so the camera isn't
        // still trying to deliver frames into a surface the Record screen has already torn down.
        runCatching { captureSession?.stopRepeating() }
        configureSession()
        runCatching { old.release() }
    }

    private fun backCameraId(manager: CameraManager): String? {
        val ids = manager.cameraIdList
        return ids.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull()
    }

    private fun stopRecording() {
        val finishedTarget = target
        val finishedCaption = pendingCaption
        target = null
        pendingCaption = null

        // Stop the flow of new frames into the recorder before touching the recorder itself or
        // the camera — recorder.stop() racing against a capture session still actively delivering
        // frames is what made this fail intermittently instead of every time.
        runCatching { captureSession?.stopRepeating() }
        // stop() throws when the recorder never got enough frames to write a valid file (most
        // commonly Start immediately followed by Stop). Treating that as success used to save a
        // file that could only ever show up in Library as "Couldn't play this file".
        val stoppedCleanly = runCatching { recorder?.stop() }.isSuccess
        runCatching { recorder?.reset() }
        runCatching { recorder?.release() }
        runCatching { captureSession?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { outputPfd?.close() }
        cameraThread?.quitSafely()

        captureSession = null
        cameraDevice = null
        recorder = null
        recorderStarted = false
        outputPfd = null
        cameraThread = null
        cameraHandler = null
        runCatching { previewSurface?.release() }
        previewSurface = null
        releaseWakeLock()
        RecordingStatus.stopped()

        // The service must stay alive — and serviceScope uncancelled — until the file is actually
        // finalized and inserted into the Library. Calling stopSelf() right after merely launching
        // that work let Android destroy the service (cancelling serviceScope, via onDestroy) before
        // it finished, which is exactly why a recording would only sometimes fail to save.
        serviceScope.launch {
            if (finishedTarget != null) {
                if (stoppedCleanly) {
                    RecordFileStore.finalizeAndSave(
                        applicationContext, finishedTarget, MediaType.VIDEO,
                        finishedCaption ?: RecordFileStore.timestampedName(MediaType.VIDEO),
                        categoryName = "Manual Video"
                    )
                } else {
                    RecordFileStore.abandon(applicationContext, finishedTarget)
                    RecordingStatus.reportError("Recording was too short to save")
                }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(latestStartId)
        }
    }

    private fun cleanupAfterFailure() {
        target?.let { RecordFileStore.abandon(this, it) }
        target = null
        pendingCaption = null
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
        recorderStarted = false
        runCatching { previewSurface?.release() }
        previewSurface = null
        releaseWakeLock()
        RecordingStatus.stopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(latestStartId)
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
        if (instance === this) instance = null
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 52
        private const val ACTION_STOP = "com.mobicareapp.record.STOP_VIDEO"
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720

        /**
         * The running service, so the Record screen can hand it a preview surface while the app is
         * open. Same process either way, so this needs no binder — and the preview is deliberately
         * optional: whether anyone is watching has no bearing on the recording itself.
         */
        @Volatile
        private var instance: VideoRecordService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, VideoRecordService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, VideoRecordService::class.java).setAction(ACTION_STOP))
        }

        fun attachPreview(surfaceTexture: SurfaceTexture) {
            instance?.setPreview(surfaceTexture)
        }

        fun detachPreview() {
            instance?.clearPreview()
        }
    }
}
