package com.mobicareapp.ui.record

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mobicareapp.accessibility.VolumeTriggerService
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.data.MediaType
import com.mobicareapp.record.AlwaysListenSettings
import com.mobicareapp.record.AudioRecordService
import com.mobicareapp.record.KeepScreenOnSettings
import com.mobicareapp.record.RecordingSource
import com.mobicareapp.record.RecordingStatus
import com.mobicareapp.record.PendingQuickRecordAction
import com.mobicareapp.record.ScreenCropInsets
import com.mobicareapp.record.ScreenRecordService
import com.mobicareapp.record.VideoQuality
import com.mobicareapp.record.VideoRecordService
import com.mobicareapp.record.VoiceActivationSettings
import com.mobicareapp.ui.clipboard.ImageClipboardDialog
import com.mobicareapp.ui.translate.TranslateTextDialog
import com.mobicareapp.ui.upload.UploadWebScreen
import com.mobicareapp.ui.transcribe.TranscribeDialog
import com.mobicareapp.ui.transcribe.TranscribePickerDialog
import kotlinx.coroutines.delay
import java.io.File

private enum class RecordMode { AUDIO, VIDEO, SCREEN }

@Composable
fun RecordScreen() {
    val context = LocalContext.current
    val active by RecordingStatus.active.collectAsState()
    val error by RecordingStatus.error.collectAsState()
    val lastFinished by RecordingStatus.lastFinished.collectAsState()
    // Local, not part of RecordingStatus itself — dismissing this card is just a UI preference for
    // this screen, not something Library (which reacts to the same signal for its own reason)
    // should be affected by.
    var showFinishedCard by remember { mutableStateOf(false) }
    LaunchedEffect(lastFinished) { showFinishedCard = lastFinished != null }
    var transcribingFile by remember { mutableStateOf<File?>(null) }
    var showTranscribePicker by remember { mutableStateOf(false) }
    var showImageClipboard by remember { mutableStateOf(false) }
    var showTranslateText by remember { mutableStateOf(false) }
    var showUploadWeb by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(RecordMode.AUDIO) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }
    var showVideoQualityPicker by remember { mutableStateOf(false) }
    var keepScreenOn by remember { mutableStateOf(KeepScreenOnSettings.isEnabled(context)) }

    // Only actually keeps the screen awake while a video recording (the mode with a live preview
    // worth watching) is both active and the checkbox is on — cleared the moment either stops
    // being true, so this never keeps the screen on outside of the one situation it's meant for.
    DisposableEffect(active, keepScreenOn, mode) {
        val activity = context as? Activity
        val shouldKeepOn = keepScreenOn && active?.source == RecordingSource.CAMERA
        if (shouldKeepOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    // Chosen in the quality dialog, read back once the permission callback below actually starts
    // the recording — the dialog closes and the permission request (if needed) happens after it.
    var pendingVideoQuality by remember { mutableStateOf(VideoQuality.NORMAL) }

    // Read fresh each time rather than cached in a remembered state: a widget or shortcut tap can
    // start a recording without this screen ever having been composed since the setting changed.
    val recordAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AudioRecordService.start(context, VoiceActivationSettings.isEnabled(context))
    }
    val videoPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) VideoRecordService.start(context, pendingVideoQuality)
    }
    fun startVideoRecording(quality: VideoQuality) {
        pendingVideoQuality = quality
        val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasCamera && hasAudio) VideoRecordService.start(context, quality)
        else videoPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
    }
    fun startAudioRecording() {
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasAudio) AudioRecordService.start(context, VoiceActivationSettings.isEnabled(context))
        else recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    // A shortcut tap (long-press the app icon) lands here already switched to the Record tab by
    // MainActivity — this is what actually starts the recording, skipping the extra "pick a mode,
    // tap Start" steps a shortcut is supposed to save. Video always uses Normal quality from a
    // shortcut: a quality popup on top of an already-quick action would defeat the point of it —
    // the in-app Start button still offers the full picker for a deliberate choice.
    val pendingQuickAction by PendingQuickRecordAction.pending.collectAsState()
    LaunchedEffect(pendingQuickAction) {
        val action = pendingQuickAction ?: return@LaunchedEffect
        if (active == null) {
            when (action) {
                PendingQuickRecordAction.Action.AUDIO -> {
                    mode = RecordMode.AUDIO
                    startAudioRecording()
                }
                PendingQuickRecordAction.Action.VIDEO -> {
                    mode = RecordMode.VIDEO
                    startVideoRecording(VideoQuality.NORMAL)
                }
            }
        }
        PendingQuickRecordAction.consume()
    }
    // Screen recording captures the whole physical display; cropping these out by default means
    // the saved video is just the app's own content, not the status bar/nav bar chrome around it.
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val cropInsetsPx = ScreenCropInsets(
        top = WindowInsets.statusBars.getTop(density),
        bottom = WindowInsets.navigationBars.getBottom(density),
        left = WindowInsets.navigationBars.getLeft(density, layoutDirection),
        right = WindowInsets.navigationBars.getRight(density, layoutDirection)
    )
    // The system's "start recording or casting?" screen is its own Activity — without this, the
    // app-lock would treat that hand-off as backgrounding and re-lock while the request is still
    // pending (same issue as the document scanner and backup/restore pickers).
    val screenCapturePermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenRecordService.start(context, result.resultCode, data, cropInsetsPx)
        }
    }
    fun launchScreenCapture() {
        AppLockManager.suppressNextLock()
        val projectionManager = context.getSystemService(MediaProjectionManager::class.java)
        screenCapturePermission.launch(projectionManager.createScreenCaptureIntent())
    }
    val screenRecordAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Proceed either way — screen capture still works without the device's own audio, just
        // silently, if the permission is denied.
        launchScreenCapture()
    }

    LaunchedEffect(active) {
        val startedAt = active?.startedAtMillis
        if (startedAt == null) {
            elapsedSeconds = 0
            return@LaunchedEffect
        }
        while (true) {
            elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000
            delay(1000)
        }
    }

    @Composable
    fun StartStopButton() {
        if (active != null) {
            Button(
                onClick = {
                    when (active?.source) {
                        RecordingSource.SCREEN -> ScreenRecordService.stop(context)
                        RecordingSource.CAMERA -> VideoRecordService.stop(context)
                        else -> AudioRecordService.stop(context)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Text("  Stop")
            }
        } else {
            Button(
                onClick = {
                    when (mode) {
                        RecordMode.VIDEO -> showVideoQualityPicker = true
                        RecordMode.SCREEN -> {
                            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio) launchScreenCapture() else screenRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        RecordMode.AUDIO -> startAudioRecording()
                    }
                }
            ) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                Text("  Start recording")
            }
        }
    }

    // The Upload button's page replaces this whole screen (same pattern as the Scan tab's tools), so
    // its own Back button/gesture returns here.
    if (showUploadWeb) {
        UploadWebScreen(onBack = { showUploadWeb = false })
        return
    }

    // Plain Center arrangement with no scrolling used to work fine when this screen only had the
    // mode chips and the Start button — now that Audio mode also shows the Always-Listen,
    // Voice-Activation, Volume-trigger, and Google Drive cards, the content is often taller than
    // the screen, and Center arrangement with no scroll silently pushed the Start/Stop button (and
    // anything else past the fold) off-screen instead of just letting it scroll into view.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Record", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        // Left-aligned (not centered), with the Upload button at the end of the same row. Scrolls
        // sideways on a very narrow screen rather than squeezing the labels.
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            FilterChip(
                selected = mode == RecordMode.AUDIO,
                enabled = active == null,
                onClick = { mode = RecordMode.AUDIO },
                leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Audio") }
            )
            FilterChip(
                selected = mode == RecordMode.VIDEO,
                enabled = active == null,
                onClick = { mode = RecordMode.VIDEO },
                leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Video") }
            )
            FilterChip(
                selected = mode == RecordMode.SCREEN,
                enabled = active == null,
                onClick = { mode = RecordMode.SCREEN },
                leadingIcon = { Icon(Icons.Default.ScreenShare, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Screen") }
            )
            FilterChip(
                selected = false,
                enabled = active == null,
                onClick = { showUploadWeb = true },
                leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Upload") }
            )
        }

        if (active == null) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { showTranscribePicker = true }) {
                Text("Transcribe a recording")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showImageClipboard = true }) {
                Text("Copy images/videos to clipboard")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showTranslateText = true }) {
                Text("Speak / upload (English)")
            }
        }

        if (active == null && mode == RecordMode.SCREEN) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Captures the whole screen and whatever's playing — useful for a live stream with no downloadable file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        if (mode == RecordMode.VIDEO) {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Keep screen on while recording", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "So you can watch the preview the whole time without the screen locking. Uses more battery.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = keepScreenOn,
                        onCheckedChange = {
                            keepScreenOn = it
                            KeepScreenOnSettings.setEnabled(context, it)
                        }
                    )
                }
            }
        }

        if (active == null && mode == RecordMode.AUDIO) {
            Spacer(Modifier.height(12.dp))
            BatteryExemptionCard()
            Spacer(Modifier.height(12.dp))
            AlwaysListenCard()
            Spacer(Modifier.height(12.dp))
            VoiceActivationCard()
            Spacer(Modifier.height(12.dp))
            VolumeTriggerCard()
        }

        Spacer(Modifier.height(16.dp))
        if (active != null) {
            Text(formatElapsed(elapsedSeconds), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                when (active?.source) {
                    RecordingSource.SCREEN -> "Recording screen…"
                    RecordingSource.CAMERA -> "Recording video…"
                    else -> "Recording audio…"
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (active?.source == RecordingSource.MIC) {
                Spacer(Modifier.height(4.dp))
                if (AlwaysListenSettings.isEnabled(context)) {
                    Text(
                        "Always-listen mode: mic stays on in the background. Turn it off above to save battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else if (VoiceActivationSettings.isEnabled(context)) {
                    Text(
                        "Voice-activated: auto-saves once you stop speaking for a few seconds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                } else {
                    Text(
                        "One continuous recording - tap Stop to save it. Keep this phone charged for long meetings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
            if (active?.source == RecordingSource.CAMERA) {
                Spacer(Modifier.height(4.dp))
                val segmentSeconds = VideoRecordService.SEGMENT_DURATION_MS / 1000
                val secondsUntilAutoSave = segmentSeconds - (elapsedSeconds % segmentSeconds)
                Text(
                    "Auto-saves as a file in ${formatElapsed(secondsUntilAutoSave)}, then every 5 min after",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                // Shows exactly what's being written to the file — no rotation or mirroring — so
                // what's on screen is what the saved video will look like. It's only a window onto
                // a recording the service owns: closing this screen or locking the phone drops the
                // preview, and the recording carries on regardless.
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(12.dp)),
                    factory = { ctx ->
                        TextureView(ctx).apply {
                            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                    VideoRecordService.attachPreview(surface)
                                }

                                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

                                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                    VideoRecordService.detachPreview()
                                    return true
                                }

                                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                            }
                        }
                    }
                )
            }
        } else {
            Text(
                "Recording continues if you lock your phone or leave the app.\nStop it here or from the notification.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(16.dp))
        StartStopButton()

        val finished = lastFinished
        if (active == null && showFinishedCard && finished != null && finished.type == MediaType.AUDIO) {
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Recording saved.", modifier = Modifier.weight(1f))
                    TextButton(onClick = { transcribingFile = File(finished.filePath) }) {
                        Text("Transcribe")
                    }
                    IconButton(onClick = { showFinishedCard = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Dismiss")
                    }
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            LaunchedEffect(it) {
                delay(4000)
                RecordingStatus.errorShown()
            }
        }
    }

    transcribingFile?.let { file ->
        TranscribeDialog(file = file, onDismiss = { transcribingFile = null })
    }

    if (showImageClipboard) {
        ImageClipboardDialog(onDismiss = { showImageClipboard = false })
    }

    if (showTranslateText) {
        TranslateTextDialog(onDismiss = { showTranslateText = false })
    }

    if (showTranscribePicker) {
        TranscribePickerDialog(
            onDismiss = { showTranscribePicker = false },
            onPick = { file ->
                showTranscribePicker = false
                transcribingFile = file
            }
        )
    }

    if (showVideoQualityPicker) {
        VideoQualityPickerDialog(
            onDismiss = { showVideoQualityPicker = false },
            onSelect = { quality ->
                showVideoQualityPicker = false
                startVideoRecording(quality)
            }
        )
    }
}

@Composable
private fun VideoQualityPickerDialog(onDismiss: () -> Unit, onSelect: (VideoQuality) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Video quality") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                VideoQuality.entries.forEach { quality ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(quality) }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(quality.label, style = MaterialTheme.typography.titleSmall)
                        Text(
                            quality.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "Either way, recording auto-saves every 5 minutes as a separate file — if the phone dies or the app is closed mid-recording, only the last few minutes are at risk, not the whole thing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun formatElapsed(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

/**
 * Some phones (MIUI, and other heavily-customized Android skins) kill a recording's foreground
 * service when the app is swiped away from Recents, even though a properly-started foreground
 * service is supposed to survive that on stock Android — they do this via their own battery
 * management on top of the OS, unless the app is explicitly exempted from it. The app already asks
 * for this exemption once automatically on first launch, but never asks again if that got missed
 * or dismissed — this button re-triggers the same standard Android system dialog on demand, which
 * works the same way on every phone (unlike OEM-specific "Autostart"/"Lock in recents" menus,
 * which vary in wording and location by manufacturer and Android version).
 */
@Composable
private fun BatteryExemptionCard() {
    val context = LocalContext.current
    var isExempt by remember {
        mutableStateOf((context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName))
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isExempt = (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                if (isExempt) {
                    "Background battery restrictions are off for this app — recordings should keep running even after you swipe the app away."
                } else {
                    "This phone may stop a recording when the app is swiped away from Recents, unless it's exempted from battery restrictions."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!isExempt) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val intent = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                    runCatching { context.startActivity(intent) }
                }) {
                    Text("Allow running in background")
                }
            }
        }
    }
}

/**
 * Settings toggle for the 24/7 "always listen" mode: turning it on immediately starts a
 * background service that keeps the mic open, saving a clip whenever it hears speech and going
 * back to listening afterward, with no trigger needed at all. Turning it off immediately stops
 * that service — releasing the mic and removing its notification — which is the actual
 * battery-saving action, not just a preference flip for next time.
 */
@Composable
private fun AlwaysListenCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(AlwaysListenSettings.isEnabled(context)) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            AudioRecordService.start(context, voiceActivated = true, continuousListening = true)
        } else {
            enabled = false
            AlwaysListenSettings.setEnabled(context, false)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Always listen in background", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Mic stays on 24/7 and auto-saves whenever it hears you speak — no tap needed. Uses more battery; turn off here to save it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { checked ->
                        enabled = checked
                        AlwaysListenSettings.setEnabled(context, checked)
                        if (checked) {
                            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio) AudioRecordService.start(context, voiceActivated = true, continuousListening = true)
                            else permission.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            AudioRecordService.stop(context)
                        }
                    }
                )
            }
        }
    }
}

/**
 * Settings toggle for voice-activated recording: while on, a Start tap (in-app, widget, or
 * shortcut) begins listening in the background, discards the file if no one speaks within 15s,
 * and auto-stops/saves a few seconds after speech stops — no manual Stop tap needed. Persisted so
 * the widget/shortcut paths (which have no screen to ask on) pick up whatever was last chosen here.
 */
@Composable
private fun VoiceActivationCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(VoiceActivationSettings.isEnabled(context)) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Voice-activated recording", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Starts listening, discards silence, auto-saves once you stop talking.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    VoiceActivationSettings.setEnabled(context, it)
                }
            )
        }
    }
}

/**
 * Volume Up x3 starts or stops audio recording, even with the screen off — but only an
 * Accessibility Service can see volume-button presses system-wide, and Android requires the user
 * to flip that toggle on themselves in Settings (no app, this one included, can enable it for
 * them). This just gets them there and shows whether it's already on, re-checking it whenever the
 * user comes back to this screen (there's no callback for "the user changed this setting").
 */
@Composable
private fun VolumeTriggerCard() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(VolumeTriggerService.isEnabled(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = VolumeTriggerService.isEnabled(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (enabled) {
                "Volume-button shortcut is on: press Volume Up 3 times quickly to start or stop recording, even with the screen off."
            } else {
                "Tip: you can start or stop recording by pressing Volume Up 3 times quickly, even with the screen off — needs a one-time permission."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (!enabled) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                Text("Enable in Settings")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "In the list that opens, find \"YT Saver\" (it may be under \"Downloaded apps\"), tap it, then turn it on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
