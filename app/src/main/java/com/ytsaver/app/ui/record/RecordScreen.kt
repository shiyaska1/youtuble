package com.ytsaver.app.ui.record

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.record.AudioRecordService
import com.ytsaver.app.record.RecordingStatus
import com.ytsaver.app.record.VideoRecordService
import kotlinx.coroutines.delay

@Composable
fun RecordScreen() {
    val context = LocalContext.current
    val active by RecordingStatus.active.collectAsState()
    val error by RecordingStatus.error.collectAsState()
    var mode by remember { mutableStateOf(MediaType.AUDIO) }
    var elapsedSeconds by remember { mutableLongStateOf(0L) }

    val recordAudioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AudioRecordService.start(context)
    }
    val videoPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) VideoRecordService.start(context)
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
                selected = mode == MediaType.AUDIO,
                enabled = active == null,
                onClick = { mode = MediaType.AUDIO },
                leadingIcon = { Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Audio") }
            )
            FilterChip(
                selected = mode == MediaType.VIDEO,
                enabled = active == null,
                onClick = { mode = MediaType.VIDEO },
                leadingIcon = { Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp)) },
                label = { Text("Video") }
            )
        }

        Spacer(Modifier.height(32.dp))

        if (active != null) {
            Text(formatElapsed(elapsedSeconds), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text(
                if (active?.type == MediaType.VIDEO) "Recording video…" else "Recording audio…",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Text(
                "Recording continues if you lock your phone or leave the app.\nStop it here or from the notification.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        Spacer(Modifier.height(32.dp))

        if (active != null) {
            Button(
                onClick = {
                    if (active?.type == MediaType.VIDEO) VideoRecordService.stop(context) else AudioRecordService.stop(context)
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text("  Stop")
            }
        } else {
            Button(onClick = {
                if (mode == MediaType.VIDEO) {
                    val hasCamera = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                    val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (hasCamera && hasAudio) {
                        VideoRecordService.start(context)
                    } else {
                        videoPermissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
                    }
                } else {
                    val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (hasAudio) {
                        AudioRecordService.start(context)
                    } else {
                        recordAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }) {
                Icon(Icons.Default.FiberManualRecord, contentDescription = null)
                Text("  Start recording")
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
}

private fun formatElapsed(totalSeconds: Long): String {
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
