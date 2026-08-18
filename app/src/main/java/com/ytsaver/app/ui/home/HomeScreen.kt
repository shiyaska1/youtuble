package com.ytsaver.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Paste a YouTube link", style = MaterialTheme.typography.titleMedium)

        OutlinedTextField(
            value = state.urlText,
            onValueChange = viewModel::onUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("https://youtube.com/watch?v=...") }
        )

        Button(
            onClick = viewModel::fetch,
            enabled = state.urlText.isNotBlank() && !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Fetch")
        }

        if (state.loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Text("Looking up video…")
            }
        }

        state.error?.let { error ->
            Text(error, color = MaterialTheme.colorScheme.error)
        }

        state.fetched?.let { stream ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    stream.thumbnailUrl?.let { thumb ->
                        AsyncImage(
                            model = thumb,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    }

                    OutlinedTextField(
                        value = state.caption,
                        onValueChange = viewModel::onCaptionChanged,
                        label = { Text("Save as (name)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.saveAs(MediaType.VIDEO) },
                            enabled = stream.videoOption != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stream.videoOption?.let { "Save Video (${it.label})" } ?: "No video stream")
                        }
                        OutlinedButton(
                            onClick = { viewModel.saveAs(MediaType.AUDIO) },
                            enabled = stream.audioOption != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stream.audioOption?.let { "Save Audio (${it.label})" } ?: "No audio stream")
                        }
                    }
                }
            }
        }

        progress?.let { p ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        if (p.done) {
                            if (p.error != null) "Failed: ${p.error}" else "Saved \"${p.caption}\""
                        } else {
                            "Saving \"${p.caption}\"…"
                        }
                    )
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
}
