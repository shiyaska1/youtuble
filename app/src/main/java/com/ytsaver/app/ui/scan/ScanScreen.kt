package com.ytsaver.app.ui.scan

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ytsaver.app.scan.PageLocation
import com.ytsaver.app.scan.PageScanExtractor
import com.ytsaver.app.scan.ScanPdfBuilder
import com.ytsaver.app.scan.ScanProgress
import com.ytsaver.app.scan.shareUri
import kotlinx.coroutines.launch

private sealed class ScanState {
    data object Idle : ScanState()
    data class Running(val progress: ScanProgress) : ScanState()
    data class Done(val pages: List<PageLocation>) : ScanState()
    data class Failed(val message: String) : ScanState()
}

/**
 * "Photocopy from video": pick a video of yourself flipping through a book
 * page by page, and get back one clean JPG per page - no per-page photos.
 */
@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var state by remember { mutableStateOf<ScanState>(ScanState.Idle) }
    var building by remember { mutableStateOf(false) }

    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            state = ScanState.Running(ScanProgress(0, 1, 0))
            scope.launch {
                val result = PageScanExtractor.extractPages(context, uri) { progress ->
                    state = ScanState.Running(progress)
                }
                state = result.fold(
                    onSuccess = { pages -> ScanState.Done(pages) },
                    onFailure = { e -> ScanState.Failed(e.message ?: "Couldn't process that video") }
                )
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "Video → Page Photos",
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Pick a video of you flipping through a book, one page at a time. " +
                        "Each page you hold still for a moment gets saved as its own JPG - the blurry " +
                        "flipping in between is skipped automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )

                when (val s = state) {
                    is ScanState.Idle -> {
                        Button(onClick = { pickVideo.launch("video/*") }) {
                            Text("Pick a video")
                        }
                    }
                    is ScanState.Running -> {
                        val fraction = if (s.progress.totalMs > 0) {
                            (s.progress.sampledMs.toFloat() / s.progress.totalMs).coerceIn(0f, 1f)
                        } else 0f
                        CircularProgressIndicator(modifier = Modifier.padding(bottom = 12.dp))
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Scanning… ${s.progress.pagesFound} page(s) found so far",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    is ScanState.Done -> {
                        Text("Saved ${s.pages.size} page(s) to Pictures/YTSaver.")
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !building,
                                onClick = {
                                    building = true
                                    scope.launch {
                                        val fileName = "Scan-${System.currentTimeMillis()}.pdf"
                                        val result = ScanPdfBuilder.buildPdf(context, s.pages, fileName)
                                        building = false
                                        val message = result.fold(
                                            onSuccess = { "Saved as PDF to Downloads/YTSaver" },
                                            onFailure = { e -> "Couldn't build PDF: ${e.message}" }
                                        )
                                        snackbarHostState.showSnackbar(message)
                                    }
                                }
                            ) {
                                Text("Save as PDF")
                            }
                            OutlinedButton(onClick = {
                                val uris = ArrayList(s.pages.map { it.shareUri(context) })
                                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                    type = "image/jpeg"
                                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share pages"))
                            }) {
                                Text("Share pages")
                            }
                        }
                        Button(onClick = { state = ScanState.Idle }, modifier = Modifier.padding(top = 12.dp)) {
                            Text("Scan another video")
                        }
                    }
                    is ScanState.Failed -> {
                        Text(s.message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        Button(onClick = { state = ScanState.Idle }, modifier = Modifier.padding(top = 16.dp)) {
                            Text("Try again")
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }
}
