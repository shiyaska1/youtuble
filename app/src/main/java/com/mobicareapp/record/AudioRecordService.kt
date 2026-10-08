package com.mobicareapp.record

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
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
import com.mobicareapp.transcribe.AutoTranscribeRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Records microphone audio as a foreground service so it keeps running after the screen locks or the app is backgrounded. */
class AudioRecordService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    // Plain (manual, non-voice-gated) capture: MediaRecorder writing AAC/MP4 directly.
    private var recorder: MediaRecorder? = null
    private var outputPfd: ParcelFileDescriptor? = null
    // Watches the microphone level of the plain recording — see [startSilenceWatchdog].
    private var watchdogJob: Job? = null
    // True while the recording is running but no sound is reaching it (shown in the notification).
    @Volatile private var silenceWarning = false

    // Voice-gated capture (voice-activated / always-listen): raw PCM via AudioRecord instead of
    // MediaRecorder. Deciding "is this actually speech, not just loud" needs the frequency content
    // of the audio (see SpeechActivityDetector) — MediaRecorder only exposes a single loudness
    // number (getMaxAmplitude()), nothing that can tell speech apart from fan/traffic/wind noise.
    // Writes a plain .wav file, since raw PCM needs no separate encoder.
    private var audioRecord: AudioRecord? = null
    private var wavWriter: WavFileWriter? = null
    private var captureJob: Job? = null
    @Volatile private var stopRequested = false

    private var noiseSuppressor: NoiseSuppressor? = null
    private var target: RecordTarget? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoTranscribeOnStop = false
    /** Shared across every segment/chunk of one continuous recording, so its parts are recognizable as belonging together (e.g. "AUD_..._part2") rather than looking like unrelated clips. Reset to null to start a fresh, separately-named clip. */
    private var sessionBaseName: String? = null
    private var segmentIndex = 1

    private var voiceActivated = false
    private var everDetectedVoice = false
    private var lastVoiceAtMs = 0L
    private var recordingStartedAtMs = 0L
    // True for the 24/7 "always listen" mode: instead of stopping after one saved (or discarded)
    // clip, it loops straight back into listening for the next one, forever, until a real Stop.
    private var continuousListening = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            autoTranscribeOnStop = intent.getBooleanExtra(EXTRA_AUTO_TRANSCRIBE, false)
            if (continuousListening) AlwaysListenSettings.setEnabled(applicationContext, false)
            stopRecording()
        } else {
            // Only take the new intent's flags if nothing is running yet — a redundant Start while
            // already active (e.g. tapping the in-app button while always-listen is on) would
            // otherwise silently downgrade continuousListening back to false here.
            if (recorder == null && audioRecord == null) {
                voiceActivated = intent?.getBooleanExtra(EXTRA_VOICE_ACTIVATED, false) ?: false
                continuousListening = intent?.getBooleanExtra(EXTRA_CONTINUOUS, false) ?: false
            }
            if (voiceActivated || continuousListening) beginVoiceGatedRecording() else beginPlainRecording()
        }
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------------------------------
    // Plain (manual, non-voice-gated) recording — unchanged MediaRecorder/AAC path.
    // ---------------------------------------------------------------------------------------

    private fun beginPlainRecording(isNewSession: Boolean = true, sourceIndex: Int = 0) {
        if (recorder != null) return
        try {
            if (isNewSession) {
                silenceWarning = false
                startForeground(NOTIFICATION_ID, buildNotification())
                sessionBaseName = RecordFileStore.timestampedName(MediaType.AUDIO)
                segmentIndex = 1
            }
            val base = sessionBaseName ?: RecordFileStore.timestampedName(MediaType.AUDIO).also { sessionBaseName = it }
            val caption = if (segmentIndex == 1) base else "${base}_part$segmentIndex"
            val newTarget = RecordFileStore.createTarget(this, MediaType.AUDIO, caption)
            target = newTarget

            val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            // The normal microphone first. While a call or meeting (Google Meet, WhatsApp call…) is
            // using the mic, Android may hand this recording only silence; [startSilenceWatchdog]
            // notices that in the first seconds and restarts on the next source in [AUDIO_SOURCES]
            // (some phones let a different source through). VOICE_COMMUNICATION is the last resort,
            // since on its own it can record nothing usable on some devices.
            newRecorder.setAudioSource(AUDIO_SOURCES[sourceIndex.coerceIn(0, AUDIO_SOURCES.lastIndex)])
            newRecorder.setOnErrorListener { _, _, _ ->
                // The system ended the recording (mic taken away, storage trouble…) — keep what was captured.
                RecordingStatus.reportError("The recording was interrupted. What was captured so far has been saved.")
                if (recorder != null) stopPlainRecording()
            }
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
            if (isNewSession) {
                wakeLock = RecordFileStore.acquireWakeLock(this, "ytsaver:audio-record")
                RecordingStatus.started(MediaType.AUDIO, RecordingSource.MIC)
            }
            startSilenceWatchdog(sourceIndex)
        } catch (e: Exception) {
            RecordingStatus.reportError(e.message ?: "Couldn't start audio recording")
            cleanupPlainAfterFailure()
        }
    }

    /**
     * One recording is now ONE continuous file from Start to Stop — it used to be cut into a new
     * file every 5 minutes (so a crash would only lose the current stretch), which broke long
     * meetings into many parts. The trade-off: the file is only playable once Stop finishes it
     * cleanly, so if the phone dies or the app is force-killed mid-recording, that recording is lost.
     *
     * This watches the microphone level once a second. A level of exactly 0 means no sound is
     * reaching the recorder at all (a live mic always has a little noise) — typically because a call
     * or meeting app holds the microphone and Android feeds this recording silence. If that is the case
     * from the very start, it restarts on the next source in [AUDIO_SOURCES] (the silent first seconds
     * are thrown away, so nothing audible is cut). If it goes silent later, or no source works, the
     * recording carries on unbroken and the user is warned, instead of finding out afterwards.
     */
    private fun startSilenceWatchdog(sourceIndex: Int) {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            var silentSeconds = 0
            var heardSound = false
            runCatching { recorder?.maxAmplitude } // the first reading is always 0 — discard it
            while (true) {
                delay(1000)
                val current = recorder ?: return@launch
                val level = runCatching { current.maxAmplitude }.getOrNull() ?: return@launch
                if (level > 0) {
                    heardSound = true
                    silentSeconds = 0
                    if (silenceWarning) {
                        silenceWarning = false
                        updateNotification()
                    }
                } else {
                    silentSeconds++
                    if (!heardSound && silentSeconds >= SILENT_SECONDS_BEFORE_SWITCH && sourceIndex < AUDIO_SOURCES.lastIndex) {
                        withContext(Dispatchers.Main) { switchToNextSource(sourceIndex + 1) }
                        return@launch
                    }
                    if (silentSeconds >= SILENT_SECONDS_BEFORE_WARNING && !silenceWarning) {
                        silenceWarning = true
                        updateNotification()
                        RecordingStatus.reportError(
                            "No sound is reaching the recorder. Another app (such as a Google Meet or phone call) is probably using the microphone, " +
                                "and Android gives it priority. The recording keeps going."
                        )
                    }
                }
            }
        }
    }

    /** Throws away the silent attempt and starts a fresh recording on [sourceIndex]; same session name, so it's still one recording to the user. */
    private fun switchToNextSource(sourceIndex: Int) {
        if (recorder == null) return
        val silent = stopMediaRecorderOnly()
        silent.target?.let { RecordFileStore.abandon(this, it) }
        beginPlainRecording(isNewSession = false, sourceIndex = sourceIndex)
    }

    private data class PlainSegmentResult(val target: RecordTarget?, val stoppedCleanly: Boolean)

    private fun stopMediaRecorderOnly(): PlainSegmentResult {
        val finishedTarget = target
        target = null
        val stoppedCleanly = runCatching {
            recorder?.apply { stop(); reset(); release() }
        }.isSuccess
        if (!stoppedCleanly) runCatching { recorder?.release() }
        runCatching { outputPfd?.close() }
        recorder = null
        outputPfd = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        return PlainSegmentResult(finishedTarget, stoppedCleanly)
    }

    private suspend fun savePlainSegment(result: PlainSegmentResult, reportShortRecordingError: Boolean, autoTranscribe: Boolean = false) {
        val target = result.target ?: return
        if (result.stoppedCleanly) {
            val caption = RecordFileStore.timestampedName(MediaType.AUDIO)
            RecordFileStore.finalizeAndSave(applicationContext, target, MediaType.AUDIO, caption)
            if (autoTranscribe && target is RecordTarget.LegacyFile) {
                AutoTranscribeRunner.runInBackground(applicationContext, target.file.absolutePath)
            }
        } else {
            RecordFileStore.abandon(applicationContext, target)
            if (reportShortRecordingError) RecordingStatus.reportError("Recording was too short to save")
        }
    }

    private fun stopPlainRecording() {
        watchdogJob?.cancel()
        watchdogJob = null
        val result = stopMediaRecorderOnly()
        releaseWakeLock()
        RecordingStatus.stopped()

        // The service must stay alive — and serviceScope uncancelled — until the file is actually
        // finalized and inserted into the Library. Calling stopSelf() right after merely launching
        // that work let Android destroy the service (cancelling serviceScope, via onDestroy)
        // before it finished, which is exactly why a recording would only sometimes fail to save —
        // most easily triggered right after a fresh install, when the first-ever database write is
        // slower (cold Room/SQLite init) than usual, giving Android more of a window to win the race.
        val shouldAutoTranscribe = autoTranscribeOnStop
        autoTranscribeOnStop = false
        serviceScope.launch {
            savePlainSegment(result, reportShortRecordingError = true, autoTranscribe = shouldAutoTranscribe)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun cleanupPlainAfterFailure() {
        watchdogJob?.cancel()
        watchdogJob = null
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

    // ---------------------------------------------------------------------------------------
    // Voice-gated recording (voice-activated / always-listen) — raw AudioRecord + SpeechActivityDetector.
    // ---------------------------------------------------------------------------------------

    private fun beginVoiceGatedRecording() {
        if (audioRecord != null) return
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            wakeLock = RecordFileStore.acquireWakeLock(this, "ytsaver:audio-record")
            RecordingStatus.started(MediaType.AUDIO, RecordingSource.MIC)
            sessionBaseName = null
            segmentIndex = 1
            everDetectedVoice = false
            recordingStartedAtMs = System.currentTimeMillis()
            openNewCycleFile()

            val minBufferSize = AudioRecord.getMinBufferSize(VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val newRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC, VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBufferSize, FRAME_SIZE_SAMPLES * 2 * 8)
            )
            if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
                newRecord.release()
                error("Couldn't access the microphone")
            }
            newRecord.startRecording()
            audioRecord = newRecord
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = RecordFileStore.attachNoiseSuppressor(newRecord.audioSessionId)
            }
            startCaptureLoop()
        } catch (e: Exception) {
            RecordingStatus.reportError(e.message ?: "Couldn't start audio recording")
            cleanupVoiceGatedAfterFailure()
        }
    }

    private fun openNewCycleFile() {
        val base = sessionBaseName ?: RecordFileStore.timestampedName(MediaType.AUDIO).also { sessionBaseName = it }
        val caption = if (segmentIndex == 1) base else "${base}_part$segmentIndex"
        val newTarget = RecordFileStore.createTarget(this, MediaType.AUDIO, caption, fileExtension = "wav", mimeType = "audio/wav")
        target = newTarget
        wavWriter = WavFileWriter((newTarget as RecordTarget.LegacyFile).file, VOICE_SAMPLE_RATE)
    }

    /**
     * Runs the whole voice-gated lifecycle in one loop reading ~20ms frames: classifying each one
     * (see [SpeechActivityDetector]), buffering a short pre-roll before speech is confirmed so the
     * saved clip doesn't clip the first word, writing continuously once speech starts (so natural
     * pauses between words aren't cut out), and deciding when a clip is finished (silence after
     * speech, or giving up after a grace period with no speech at all). In always-listen mode, a
     * finished clip loops straight back into listening instead of stopping the service.
     */
    private fun startCaptureLoop() {
        stopRequested = false
        captureJob = serviceScope.launch {
            val frame = ShortArray(FRAME_SIZE_SAMPLES)
            var consecutiveSpeechFrames = 0
            var consecutiveSilenceFrames = 0
            val preroll = ArrayDeque<ShortArray>()

            suspend fun stopForGood() {
                releaseWakeLock()
                RecordingStatus.stopped()
                runCatching { audioRecord?.stop(); audioRecord?.release() }
                audioRecord = null
                runCatching { noiseSuppressor?.release() }
                noiseSuppressor = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            while (true) {
                if (stopRequested) {
                    val shouldAutoTranscribe = autoTranscribeOnStop
                    autoTranscribeOnStop = false
                    finishCurrentCycle(save = everDetectedVoice, autoTranscribe = shouldAutoTranscribe)
                    stopForGood()
                    return@launch
                }

                val record = audioRecord ?: return@launch
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) {
                    delay(5)
                    continue
                }
                val samples = if (read == frame.size) frame else frame.copyOf(read)
                val now = System.currentTimeMillis()
                val isSpeech = SpeechActivityDetector.isSpeechFrame(samples, VOICE_SAMPLE_RATE)

                if (!everDetectedVoice) {
                    preroll.addLast(samples.copyOf())
                    if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                    if (isSpeech) {
                        consecutiveSpeechFrames++
                        if (consecutiveSpeechFrames >= CONFIRM_FRAMES) {
                            everDetectedVoice = true
                            lastVoiceAtMs = now
                            consecutiveSilenceFrames = 0
                            updateNotification()
                            preroll.forEach { writeFrame(it) }
                            preroll.clear()
                        }
                    } else {
                        consecutiveSpeechFrames = 0
                        if (now - recordingStartedAtMs >= VOICE_GRACE_PERIOD_MS) {
                            finishCurrentCycle(save = false)
                            if (continuousListening) {
                                beginNextCycle()
                                consecutiveSpeechFrames = 0; consecutiveSilenceFrames = 0; preroll.clear()
                            } else {
                                stopForGood()
                                return@launch
                            }
                        }
                    }
                } else {
                    writeFrame(samples)
                    if (isSpeech) {
                        consecutiveSilenceFrames = 0
                        lastVoiceAtMs = now
                    } else {
                        consecutiveSilenceFrames++
                        if (now - lastVoiceAtMs >= SILENCE_TIMEOUT_MS) {
                            finishCurrentCycle(save = true)
                            if (continuousListening) {
                                beginNextCycle()
                                consecutiveSpeechFrames = 0; consecutiveSilenceFrames = 0; preroll.clear()
                            } else {
                                stopForGood()
                                return@launch
                            }
                        }
                    }
                }
            }
        }
    }

    private fun writeFrame(samples: ShortArray) {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) buffer.putShort(s)
        wavWriter?.write(buffer.array(), buffer.array().size)
    }

    /** Ends the current clip: saves it (if it actually captured speech) or discards it, and closes the .wav header either way. */
    private suspend fun finishCurrentCycle(save: Boolean, autoTranscribe: Boolean = false) {
        val writer = wavWriter
        val finishedTarget = target
        wavWriter = null
        target = null
        runCatching { writer?.finish() }
        if (save && finishedTarget != null) {
            val caption = RecordFileStore.timestampedName(MediaType.AUDIO)
            RecordFileStore.finalizeAndSave(applicationContext, finishedTarget, MediaType.AUDIO, caption, fileExtension = "wav")
            if (autoTranscribe && finishedTarget is RecordTarget.LegacyFile) {
                AutoTranscribeRunner.runInBackground(applicationContext, finishedTarget.file.absolutePath)
            }
        } else if (finishedTarget != null) {
            RecordFileStore.abandon(applicationContext, finishedTarget)
        }
    }

    /** Always-listen mode only: starts listening for the next clip with a fresh name (not a "_partN" continuation — unrelated speech events, possibly minutes or hours apart). */
    private fun beginNextCycle() {
        sessionBaseName = null
        segmentIndex = 1
        everDetectedVoice = false
        recordingStartedAtMs = System.currentTimeMillis()
        updateNotification()
        openNewCycleFile()
    }

    private fun cleanupVoiceGatedAfterFailure() {
        target?.let { RecordFileStore.abandon(this, it) }
        target = null
        runCatching { wavWriter?.abortWithoutFinishing() }
        wavWriter = null
        runCatching { audioRecord?.stop(); audioRecord?.release() }
        audioRecord = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        releaseWakeLock()
        RecordingStatus.stopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------------------------------
    // Shared
    // ---------------------------------------------------------------------------------------

    private fun stopRecording() {
        if (recorder != null) stopPlainRecording()
        else if (audioRecord != null) stopRequested = true
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
        val (title, text) = when {
            continuousListening && everDetectedVoice -> "Recording speech…" to "Always-listen mode — saves once you stop talking"
            continuousListening -> "Listening for voice…" to "Always-listen mode — tap Stop to turn off"
            voiceActivated && everDetectedVoice -> "Recording speech…" to "Auto-saves once you stop talking"
            voiceActivated -> "Listening for voice…" to "Discards automatically if no one speaks"
            silenceWarning -> "Recording — no sound heard" to "Another app (a call or meeting) may be using the microphone"
            else -> "Recording audio" to "One continuous file — tap Stop to finish and save"
        }
        return NotificationCompat.Builder(this, YtSaverApp.RECORD_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.presence_audio_online)
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val NOTIFICATION_ID = 51
        private const val ACTION_STOP = "com.mobicareapp.record.STOP_AUDIO"
        private const val EXTRA_AUTO_TRANSCRIBE = "autoTranscribe"
        private const val EXTRA_VOICE_ACTIVATED = "voiceActivated"
        private const val EXTRA_CONTINUOUS = "continuousListening"
        // Tried in order for a plain recording: the normal mic first, then the sources some phones
        // keep delivering sound on while a call/meeting app is using the microphone.
        private val AUDIO_SOURCES = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )
        private const val SILENT_SECONDS_BEFORE_SWITCH = 8
        private const val SILENT_SECONDS_BEFORE_WARNING = 12

        // Voice-gated capture tuning. Not empirically tuned against real recordings — reasonable
        // starting points that can be adjusted if they turn out too strict/loose in practice.
        private const val VOICE_SAMPLE_RATE = 16_000
        private const val FRAME_MS = 20
        private const val FRAME_SIZE_SAMPLES = VOICE_SAMPLE_RATE * FRAME_MS / 1000 // 320
        private const val CONFIRM_FRAMES = 8 // 160ms of sustained speech-like frames before a clip actually starts
        private const val PREROLL_FRAMES = 15 // ~300ms kept from just before speech is confirmed, so the first word isn't clipped
        private const val VOICE_GRACE_PERIOD_MS = 15_000L
        private const val SILENCE_TIMEOUT_MS = 4_000L

        /**
         * [voiceActivated] true discards the recording if no speech is heard within 15s, and
         * auto-stops/saves once speech has started and then stayed quiet for 4s. [continuousListening]
         * true (implies voice-activated) never stops after one clip — it loops back into listening
         * for the next one, forever, until a real Stop (notification action or the Always-Listen
         * switch turning off).
         */
        fun start(context: Context, voiceActivated: Boolean = false, continuousListening: Boolean = false) {
            val intent = Intent(context, AudioRecordService::class.java)
                .putExtra(EXTRA_VOICE_ACTIVATED, voiceActivated)
                .putExtra(EXTRA_CONTINUOUS, continuousListening)
            ContextCompat.startForegroundService(context, intent)
        }

        /** [autoTranscribe] true kicks off a background transcription (reported via notification, no dialog) once the file is saved — meant for a stop with no app screen open to show the usual manual "Transcribe" option on, e.g. from the home-screen widget or an app shortcut. */
        fun stop(context: Context, autoTranscribe: Boolean = false) {
            val intent = Intent(context, AudioRecordService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_AUTO_TRANSCRIBE, autoTranscribe)
            context.startService(intent)
        }
    }
}
