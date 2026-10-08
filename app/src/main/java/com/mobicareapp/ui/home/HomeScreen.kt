package com.mobicareapp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mobicareapp.extract.MediaOption
import com.mobicareapp.ui.browser.SignInWebViewDialog

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel(), onOpenBrowser: (String?) -> Unit = {}) {
    val state by viewModel.uiState.collectAsState()
    val progress by viewModel.downloadProgress.collectAsState()
    val queuedDownloads by viewModel.queuedDownloads.collectAsState()
    val muxingIds by viewModel.muxingIds.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Paste YouTube links — or direct video/audio file links", style = MaterialTheme.typography.titleMedium)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.urlText,
                    onValueChange = viewModel::onUrlChanged,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("https://youtube.com/watch?v=...") }
                )
                IconButton(onClick = viewModel::addToQueue) {
                    Icon(Icons.Default.Add, contentDescription = "Add to queue")
                }
            }

            TextButton(onClick = { onOpenBrowser(null) }) {
                Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Can't paste a direct link? Browse the site and find it here")
            }

            progress?.let { p ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = if (p.done) {
                                    if (p.error != null) "Failed: ${p.error}" else "Saved \"${p.caption}\""
                                } else {
                                    "Saving \"${p.caption}\"…" + if (queuedDownloads > 1) " (${queuedDownloads - 1} more queued)" else ""
                                }
                            )
                            if (p.done) {
                                IconButton(onClick = viewModel::dismissProgress) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                                }
                            } else {
                                TextButton(onClick = viewModel::cancelCurrentDownload) { Text("Cancel") }
                            }
                        }
                        if (!p.done) {
                            Spacer(modifier = Modifier.height(8.dp))
                            val fraction = if (p.totalBytes > 0) p.bytesDone.toFloat() / p.totalBytes else 0f
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.queue, key = { it.id }) { queued ->
                QueueItemCard(
                    queued = queued,
                    isMuxing = queued.id in muxingIds,
                    onCaptionChanged = { viewModel.onCaptionChanged(queued.id, it) },
                    onRemove = { viewModel.removeFromQueue(queued.id) },
                    onSaveVideo = { option -> viewModel.saveVideo(queued.id, option) },
                    onSaveAudio = { viewModel.saveAudio(queued.id) },
                    onRetry = { viewModel.retry(queued.id) },
                    onOpenInBrowse = { onOpenBrowser(normalizeUrlForSignIn(queued.urlText)) }
                )
            }
        }
    }
}

@Composable
private fun QueueItemCard(
    queued: QueuedLink,
    isMuxing: Boolean,
    onCaptionChanged: (String) -> Unit,
    onRemove: () -> Unit,
    onSaveVideo: (MediaOption) -> Unit,
    onSaveAudio: () -> Unit,
    onRetry: () -> Unit,
    onOpenInBrowse: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (val status = queued.status) {
                is QueueStatus.Loading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(queued.urlText, maxLines = 1, modifier = Modifier.weight(1f))
                        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                    }
                }
                is QueueStatus.Error -> {
                    var showSignIn by remember { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.Top) {
                        Text(status.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                    }
                    Row {
                        // Some sites only serve their video to a signed-in session — this opens a
                        // real WebView so the user can log in with their own credentials, then
                        // retries with whatever session cookie that produced.
                        TextButton(onClick = { showSignIn = true }) { Text("Sign in") }
                        // For sites blocked by a bot/JS check that no plain HTTP request (signed
                        // in or not) can get past — Browse uses a real WebView instead, which can.
                        TextButton(onClick = onOpenInBrowse) { Text("Open in Browse") }
                    }
                    if (showSignIn) {
                        SignInWebViewDialog(
                            url = normalizeUrlForSignIn(queued.urlText),
                            onDismiss = {
                                showSignIn = false
                                onRetry()
                            }
                        )
                    }
                }
                is QueueStatus.Ready -> {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            status.stream.thumbnailUrl?.let { thumb ->
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            OutlinedTextField(
                                value = status.caption,
                                onValueChange = onCaptionChanged,
                                label = { Text("Save as (name)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
                    }

                    var showQualityPicker by remember { mutableStateOf(false) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showQualityPicker = true },
                            enabled = status.stream.videoOptions.isNotEmpty() && !isMuxing,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (isMuxing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Merging…")
                            } else {
                                Text(
                                    status.stream.videoOptions.firstOrNull()?.let { "Save Video (${it.label})" }
                                        ?: "No video stream"
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onSaveAudio,
                            enabled = status.stream.audioOption != null && !isMuxing,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(status.stream.audioOption?.let { "Save Audio (${it.label})" } ?: "No audio stream")
                        }
                    }
                    if (showQualityPicker) {
                        VideoQualityPickerDialog(
                            options = status.stream.videoOptions,
                            onPick = { option ->
                                showQualityPicker = false
                                onSaveVideo(option)
                            },
                            onDismiss = { showQualityPicker = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoQualityPickerDialog(
    options: List<MediaOption>,
    onPick: (MediaOption) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose quality") },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = false, onClick = { onPick(option) })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = false, onClick = { onPick(option) })
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            // A DASH video-only stream needs a separate audio download merged in
                            // afterward — flagged here so the wait for "Merging…" isn't a surprise.
                            if (option.needsAudioMux) "${option.label} (merges audio, slower)" else option.label
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun normalizeUrlForSignIn(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
}
