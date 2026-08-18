package com.ytsaver.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ytsaver.app.data.MediaType

@Composable
fun HomeScreen(viewModel: HomeViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsState()
    val progress by viewModel.downloadProgress.collectAsState()
    val queuedDownloads by viewModel.queuedDownloads.collectAsState()

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
                    onCaptionChanged = { viewModel.onCaptionChanged(queued.id, it) },
                    onRemove = { viewModel.removeFromQueue(queued.id) },
                    onSave = { type -> viewModel.saveAs(queued.id, type) }
                )
            }
        }
    }
}

@Composable
private fun QueueItemCard(
    queued: QueuedLink,
    onCaptionChanged: (String) -> Unit,
    onRemove: () -> Unit,
    onSave: (MediaType) -> Unit
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(status.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, contentDescription = "Remove") }
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

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onSave(MediaType.VIDEO) },
                            enabled = status.stream.videoOption != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(status.stream.videoOption?.let { "Save Video (${it.label})" } ?: "No video stream")
                        }
                        OutlinedButton(
                            onClick = { onSave(MediaType.AUDIO) },
                            enabled = status.stream.audioOption != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(status.stream.audioOption?.let { "Save Audio (${it.label})" } ?: "No audio stream")
                        }
                    }
                }
            }
        }
    }
}
