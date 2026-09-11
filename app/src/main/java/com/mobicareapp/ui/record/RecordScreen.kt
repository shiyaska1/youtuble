package com.mobicareapp.ui.record

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.ScreenShare
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import com.mobicareapp.record.AudioRecordService
import com.mobicareapp.record.RecordingSource
import com.mobicareapp.record.RecordingStatus
import com.mobicareapp.record.ScreenCropInsets
import com.mobicareapp.record.ScreenRecordService
import com.mobicareapp.record.VideoRecordService
import kotlinx.coroutines.delay

private enum class RecordMode { AUDIO, VIDEO, SCREEN }

@Composable
fun RecordScreen() {
    val context = LocalContext.current
    val active by RecordingStatus.active.collectAsState()
    val error by RecordingStatus.error.collectAsState()
    var mode by remember { mutableStateOf(RecordMode.AUDIO) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    val recordAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AudioRecordService.start(context)
    }
    val videoPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) VideoRecordService.start(context)
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
                        RecordMode.VIDEO -> {
                            val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasCamera && hasAudio) VideoRecordService.start(context)
                            else videoPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                        }
                        RecordMode.SCREEN -> {
                            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio) launchScreenCapture() else screenRecordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        RecordMode.AUDIO -> {
                            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            if (hasAudio) AudioRecordService.start(context) else recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
            ) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                Text("  Start recording")
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Record", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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

        if (active == null && mode == RecordMode.AUDIO) {
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
            if (active?.source == RecordingSource.CAMERA) {
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

        error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            LaunchedEffect(it) {
                delay(4000)
                RecordingStatus.errorShown()
            }
        }
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
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
