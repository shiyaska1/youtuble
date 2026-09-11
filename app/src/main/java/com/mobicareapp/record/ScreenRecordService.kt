package com.mobicareapp.record

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/** Pixel margins (at full device resolution) to crop out of a screen recording — the default use is excluding the status bar (top) and navigation bar (wherever it sits). */
data class ScreenCropInsets(val top: Int = 0, val bottom: Int = 0, val left: Int = 0, val right: Int = 0)

/**
 * Records the whole screen (whatever's visible — a live stream playing in Browse, or in any
 * other app) plus whatever audio the device is playing, for content that has no downloadable
 * file to begin with. MediaRecorder can't do this on its own: its audio sources are the mic or
 * camera mic, never "what the device is currently playing" — that's only available through a raw
 * AudioRecord configured with an AudioPlaybackCaptureConfiguration (API 29+). So this encodes
 * video and audio through two independent MediaCodec encoders and muxes them together by hand,
 * the same way MediaRecorder would internally, just built from the pieces MediaRecorder doesn't
 * expose for this particular combination.
 */
class ScreenRecordService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var videoEncoder: MediaCodec? = null
    private var audioEncoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxerCoordinator: MuxerCoordinator? = null
    private var target: RecordTarget? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val stopSignal = AtomicBoolean(false)
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null
    private var captureThread: Thread? = null
    private var cropRenderer: ScreenCropRenderer? = null
    private var captureSurfaceTexture: SurfaceTexture? = null
    private var captureSurface: Surface? = null
    private var captureThreadHandler: Handler? = null
    private var captureHandlerThread: HandlerThread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRecording()
            return START_NOT_STICKY
        }
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: return START_NOT_STICKY
        val resultData = intent.parcelableExtraCompat<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
        val cropInsets = ScreenCropInsets(
            top = intent.getIntExtra(EXTRA_CROP_TOP, 0),
            bottom = intent.getIntExtra(EXTRA_CROP_BOTTOM, 0),
            left = intent.getIntExtra(EXTRA_CROP_LEFT, 0),
            right = intent.getIntExtra(EXTRA_CROP_RIGHT, 0)
        )
        // MediaProjection requires this service to already be in the foreground (and, on newer
        // Android, of type "mediaProjection") before the projection token is redeemed below.
        startForeground(NOTIFICATION_ID, buildNotification())
        beginRecording(resultCode, resultData, cropInsets)
        return START_NOT_STICKY
    }

    private fun beginRecording(resultCode: Int, resultData: Intent, cropInsets: ScreenCropInsets) {
        if (mediaProjection != null) return
        stopSignal.set(false)
        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
            mediaProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopRecording()
                }
            }, null)

            val newTarget = RecordFileStore.createTarget(this, MediaType.VIDEO, "SCR_" + RecordFileStore.timestampedName(MediaType.VIDEO).removePrefix("VID_"))
            target = newTarget
            val (outputPath, outputPfd) = openMuxerOutput(newTarget)

            val metrics = resources.displayMetrics
            val fullWidth = metrics.widthPixels
            val fullHeight = metrics.heightPixels
            val densityDpi = metrics.densityDpi

            // Crop margins as fractions of the full captured frame — excluding the status/nav
            // bar by default means the saved video is just the app's own content, not the system
            // chrome around it.
            val cropLeftFrac = (cropInsets.left.toFloat() / fullWidth).coerceIn(0f, 0.9f)
            val cropRightFrac = (1f - cropInsets.right.toFloat() / fullWidth).coerceIn(0.1f, 1f)
            val cropTopFrac = (cropInsets.top.toFloat() / fullHeight).coerceIn(0f, 0.9f)
            val cropBottomFrac = (1f - cropInsets.bottom.toFloat() / fullHeight).coerceIn(0.1f, 1f)
            val croppedWidth = (fullWidth * (cropRightFrac - cropLeftFrac)).toInt().coerceAtLeast(1)
            val croppedHeight = (fullHeight * (cropBottomFrac - cropTopFrac)).toInt().coerceAtLeast(1)

            // Scaled down proportionally so the shorter side lands around 360px — keeps files
            // small enough to share easily while still looking fine on a phone screen, the same
            // as the camera Video mode. MediaCodec encoders reject odd dimensions on some
            // devices, so the result is always rounded to an even number.
            val scale = 360.0 / minOf(croppedWidth, croppedHeight)
            val outputWidth = ((croppedWidth * scale).toInt() / 2) * 2
            val outputHeight = ((croppedHeight * scale).toInt() / 2) * 2

            val hasRecordAudio = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            val captureAudio = hasRecordAudio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

            val coordinator = MuxerCoordinator(outputPath, outputPfd, expectAudio = captureAudio)
            muxerCoordinator = coordinator

            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outputWidth, outputHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val newVideoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            newVideoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encoderInputSurface = newVideoEncoder.createInputSurface()
            newVideoEncoder.start()
            videoEncoder = newVideoEncoder

            // The whole physical screen is captured at full resolution into an off-screen GL
            // texture; a small renderer then samples just the crop sub-rectangle of it into the
            // encoder's own input surface, which is what actually excludes the status/nav bar
            // (MediaProjection itself has no concept of cropping — a smaller virtual display just
            // scales the whole screen down into it rather than cropping).
            val renderer = ScreenCropRenderer(encoderInputSurface)
            cropRenderer = renderer
            val texture = SurfaceTexture(renderer.textureId)
            texture.setDefaultBufferSize(fullWidth, fullHeight)
            captureSurfaceTexture = texture
            val captureSurfaceForDisplay = Surface(texture)
            captureSurface = captureSurfaceForDisplay

            val handlerThread = HandlerThread("ScreenRecordCapture").apply { start() }
            captureHandlerThread = handlerThread
            val captureHandler = Handler(handlerThread.looper)
            captureThreadHandler = captureHandler

            val frameLock = Object()
            var frameAvailable = false
            texture.setOnFrameAvailableListener({
                synchronized(frameLock) {
                    frameAvailable = true
                    frameLock.notifyAll()
                }
            }, captureHandler)

            captureThread = Thread({
                try {
                    while (!stopSignal.get()) {
                        synchronized(frameLock) {
                            if (!frameAvailable) frameLock.wait(500)
                            frameAvailable = false
                        }
                        if (stopSignal.get()) break
                        texture.updateTexImage()
                        val transform = FloatArray(16)
                        texture.getTransformMatrix(transform)
                        renderer.drawFrame(transform, cropLeftFrac, cropTopFrac, cropRightFrac, cropBottomFrac, texture.timestamp)
                    }
                } catch (_: Exception) {
                }
            }, "ScreenRecord-Capture").apply { start() }

            virtualDisplay = projection.createVirtualDisplay(
                "YtSaverScreenRecord", fullWidth, fullHeight, densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                captureSurfaceForDisplay, null, null
            )

            videoThread = Thread({ runVideoEncoderLoop(newVideoEncoder, coordinator) }, "ScreenRecord-Video").apply { start() }

            if (captureAudio) {
                val audioFormat = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val playbackCaptureConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()
                val minBufferSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val newAudioRecord = AudioRecord.Builder()
                    .setAudioFormat(audioFormat)
                    .setBufferSizeInBytes(minBufferSize * 2)
                    .setAudioPlaybackCaptureConfig(playbackCaptureConfig)
                    .build()
                // AudioPlaybackCaptureConfiguration is finicky enough that AudioRecord can come
                // back uninitialized without throwing — starting the thread anyway would have it
                // fail on record.startRecording() instead, but checking here avoids spinning up a
                // MediaCodec encoder that will never receive anything.
                if (newAudioRecord.state == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = newAudioRecord

                    val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    }
                    val newAudioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    newAudioEncoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    newAudioEncoder.start()
                    audioEncoder = newAudioEncoder

                    audioThread = Thread({ runAudioEncoderLoop(newAudioRecord, newAudioEncoder, coordinator) }, "ScreenRecord-Audio").apply { start() }
                } else {
                    newAudioRecord.release()
                    coordinator.audioFinished()
                }
            }

            wakeLock = RecordFileStore.acquireWakeLock(this, "ytsaver:screen-record")
            RecordingStatus.started(MediaType.VIDEO, RecordingSource.SCREEN)
        } catch (e: Exception) {
            RecordingStatus.reportError(e.message ?: "Couldn't start screen recording")
            cleanupAfterFailure()
        }
    }

    /** [MediaMuxer] needs either a file path or an open [android.os.ParcelFileDescriptor] — the latter only when the target is a MediaStore Uri (not used while [PublicMediaStore] stays disabled, but handled either way). */
    private fun openMuxerOutput(recordTarget: RecordTarget): Pair<String?, ParcelFileDescriptor?> = when (recordTarget) {
        is RecordTarget.LegacyFile -> recordTarget.file.absolutePath to null
        is RecordTarget.MediaStoreUri -> {
            val pfd = contentResolver.openFileDescriptor(recordTarget.uri, "rw") ?: error("Couldn't open output for screen recording")
            null to pfd
        }
    }

    private fun runVideoEncoderLoop(encoder: MediaCodec, coordinator: MuxerCoordinator) {
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        var eosSignaled = false
        try {
            while (true) {
                if (!eosSignaled && stopSignal.get()) {
                    runCatching { encoder.signalEndOfInputStream() }
                    eosSignaled = true
                }
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> trackIndex = coordinator.addVideoTrack(encoder.outputFormat)
                    outIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(outIndex)
                        if (encoded != null && bufferInfo.size != 0 && trackIndex != -1) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            coordinator.writeSample(trackIndex, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            coordinator.videoFinished()
        }
    }

    private fun runAudioEncoderLoop(record: AudioRecord, encoder: MediaCodec, coordinator: MuxerCoordinator) {
        val bufferInfo = MediaCodec.BufferInfo()
        var trackIndex = -1
        var eosSignaled = false
        val pcmBuffer = ByteArray(4096)
        try {
            record.startRecording()
            while (true) {
                if (!eosSignaled) {
                    val inIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = encoder.getInputBuffer(inIndex)
                        if (stopSignal.get()) {
                            encoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSignaled = true
                        } else if (inputBuffer != null) {
                            inputBuffer.clear()
                            val read = record.read(pcmBuffer, 0, minOf(pcmBuffer.size, inputBuffer.remaining()))
                            if (read > 0) {
                                inputBuffer.put(pcmBuffer, 0, read)
                                encoder.queueInputBuffer(inIndex, 0, read, System.nanoTime() / 1000, 0)
                            } else {
                                encoder.queueInputBuffer(inIndex, 0, 0, 0, 0)
                            }
                        }
                    }
                }
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, CODEC_TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> trackIndex = coordinator.addAudioTrack(encoder.outputFormat)
                    outIndex >= 0 -> {
                        val encoded = encoder.getOutputBuffer(outIndex)
                        if (encoded != null && bufferInfo.size != 0 && trackIndex != -1) {
                            encoded.position(bufferInfo.offset)
                            encoded.limit(bufferInfo.offset + bufferInfo.size)
                            coordinator.writeSample(trackIndex, encoded, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            runCatching { record.stop() }
        } catch (_: Exception) {
        } finally {
            coordinator.audioFinished()
        }
    }

    private fun stopRecording() {
        val finishedTarget = target
        stopSignal.set(true)
        // Stop feeding the encoder's input surface before draining its remaining output and
        // signaling end-of-stream — joined in that order (capture, then encode) so nothing is
        // still drawing to the surface once the encoder's been told no more input is coming.
        runCatching { captureThread?.join(THREAD_JOIN_TIMEOUT_MS) }
        captureThread = null
        runCatching { videoThread?.join(THREAD_JOIN_TIMEOUT_MS) }
        runCatching { audioThread?.join(THREAD_JOIN_TIMEOUT_MS) }
        videoThread = null
        audioThread = null

        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { captureSurface?.release() }
        captureSurface = null
        runCatching { captureSurfaceTexture?.release() }
        captureSurfaceTexture = null
        runCatching { cropRenderer?.release() }
        cropRenderer = null
        captureHandlerThread?.quitSafely()
        captureHandlerThread = null
        captureThreadHandler = null
        runCatching { videoEncoder?.stop(); videoEncoder?.release() }
        videoEncoder = null
        runCatching { audioEncoder?.stop(); audioEncoder?.release() }
        audioEncoder = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        val coordinator = muxerCoordinator
        muxerCoordinator = null
        runCatching { coordinator?.close() }
        releaseWakeLock()

        val sizeBytes = when (finishedTarget) {
            is RecordTarget.LegacyFile -> finishedTarget.file.length()
            else -> 0L
        }
        if (finishedTarget != null && sizeBytes > 0L) {
            val caption = RecordFileStore.timestampedName(MediaType.VIDEO)
            serviceScope.launch {
                RecordFileStore.finalizeAndSave(applicationContext, finishedTarget, MediaType.VIDEO, caption)
            }
        } else if (finishedTarget != null) {
            RecordFileStore.abandon(this, finishedTarget)
        }
        target = null
        RecordingStatus.stopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupAfterFailure() {
        target?.let { RecordFileStore.abandon(this, it) }
        target = null
        stopSignal.set(true)
        runCatching { captureThread?.join(THREAD_JOIN_TIMEOUT_MS) }
        captureThread = null
        runCatching { videoThread?.join(THREAD_JOIN_TIMEOUT_MS) }
        runCatching { audioThread?.join(THREAD_JOIN_TIMEOUT_MS) }
        videoThread = null
        audioThread = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { captureSurface?.release() }
        captureSurface = null
        runCatching { captureSurfaceTexture?.release() }
        captureSurfaceTexture = null
        runCatching { cropRenderer?.release() }
        cropRenderer = null
        captureHandlerThread?.quitSafely()
        captureHandlerThread = null
        captureThreadHandler = null
        runCatching { videoEncoder?.release() }
        videoEncoder = null
        runCatching { audioEncoder?.release() }
        audioEncoder = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        runCatching { muxerCoordinator?.close() }
        muxerCoordinator = null
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
            this, 0, Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, YtSaverApp.RECORD_CHANNEL_ID)
            .setContentTitle("Recording screen")
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
        private const val NOTIFICATION_ID = 53
        private const val ACTION_STOP = "com.mobicareapp.record.STOP_SCREEN"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"
        private const val EXTRA_CROP_TOP = "cropTop"
        private const val EXTRA_CROP_BOTTOM = "cropBottom"
        private const val EXTRA_CROP_LEFT = "cropLeft"
        private const val EXTRA_CROP_RIGHT = "cropRight"
        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val THREAD_JOIN_TIMEOUT_MS = 5_000L

        /** [resultData] is the Intent returned by [MediaProjectionManager.createScreenCaptureIntent]'s activity result — the one-time user consent to capture the screen. [cropInsets] excludes the status/nav bar by default (see [ScreenCropInsets]). */
        fun start(context: Context, resultCode: Int, resultData: Intent, cropInsets: ScreenCropInsets = ScreenCropInsets()) {
            val intent = Intent(context, ScreenRecordService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
                putExtra(EXTRA_CROP_TOP, cropInsets.top)
                putExtra(EXTRA_CROP_BOTTOM, cropInsets.bottom)
                putExtra(EXTRA_CROP_LEFT, cropInsets.left)
                putExtra(EXTRA_CROP_RIGHT, cropInsets.right)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP))
        }
    }
}

private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtraCompat(name: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION") getParcelableExtra(name)
    }

/**
 * Coordinates the video and audio encoder threads writing into one [MediaMuxer]: a muxer can't
 * accept samples for any track until *every* track it will ever have has been added via
 * addTrack() and start() has been called, so this holds writes from whichever thread finishes
 * configuring its track first until the other one catches up (or never arrives, if audio capture
 * isn't in play).
 */
private class MuxerCoordinator(path: String?, pfd: ParcelFileDescriptor?, expectAudio: Boolean) {
    private val ownedPfd = pfd
    private val muxer = if (path != null) {
        MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    } else {
        MediaMuxer(pfd!!.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }
    private val lock = Object()
    // Mutable: audio capture is best-effort (AudioPlaybackCaptureConfiguration is finicky and can
    // fail after recording's already begun) — if its thread ever finishes without having added a
    // track, this drops to false so the muxer can still start video-only instead of waiting
    // forever for an audio track that's never coming, which silently produced a 0-byte file.
    private var expectAudio = expectAudio
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var videoDone = false
    private var audioDone = !expectAudio
    private var stopped = false

    fun addVideoTrack(format: MediaFormat): Int = synchronized(lock) {
        videoTrack = muxer.addTrack(format)
        maybeStart()
        videoTrack
    }

    fun addAudioTrack(format: MediaFormat): Int = synchronized(lock) {
        audioTrack = muxer.addTrack(format)
        maybeStart()
        audioTrack
    }

    private fun maybeStart() {
        if (!started && videoTrack != -1 && (audioTrack != -1 || !expectAudio)) {
            muxer.start()
            started = true
        }
    }

    fun writeSample(track: Int, buffer: ByteBuffer, info: MediaCodec.BufferInfo) = synchronized(lock) {
        if (started && !stopped) runCatching { muxer.writeSampleData(track, buffer, info) }
    }

    fun videoFinished() = synchronized(lock) {
        videoDone = true
        maybeStop()
    }

    fun audioFinished() = synchronized(lock) {
        audioDone = true
        if (audioTrack == -1 && expectAudio) {
            expectAudio = false
            maybeStart()
        }
        maybeStop()
    }

    private fun maybeStop() {
        if (started && !stopped && videoDone && audioDone) {
            runCatching { muxer.stop() }
            stopped = true
        }
    }

    fun close() = synchronized(lock) {
        if (started && !stopped) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { ownedPfd?.close() }
    }
}
