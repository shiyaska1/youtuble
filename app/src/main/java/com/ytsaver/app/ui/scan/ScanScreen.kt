package com.ytsaver.app.ui.scan

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ytsaver.app.scan.ScanFileStore
import com.ytsaver.app.scan.ScanFolder
import com.ytsaver.app.scan.ScanPdfBuilder
import com.ytsaver.app.scan.shareUri
import kotlinx.coroutines.launch

private sealed class ScanScreenState {
    data object List : ScanScreenState()
    data object Capturing : ScanScreenState()
}

/**
 * "Photocopy from camera": tap the shutter once per page (like Google
 * Drive/CamScanner-style scanning) instead of photographing pages one by
 * one in the regular camera app. Past scan sessions show up below, each
 * with Share and PDF actions for the whole folder.
 */
@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var screenState by remember { mutableStateOf<ScanScreenState>(ScanScreenState.List) }
    var folders by remember { mutableStateOf(ScanFileStore.listFolders(context)) }
    var buildingPdfFor by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        folders = ScanFileStore.listFolders(context)
    }

    when (screenState) {
        is ScanScreenState.Capturing -> {
            PhotoCaptureScreen(onDone = {
                screenState = ScanScreenState.List
                refresh()
            })
        }
        is ScanScreenState.List -> {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Scans", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { screenState = ScanScreenState.Capturing }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                            Text("New scan")
                        }
                    }

                    if (folders.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "Tap \"New scan\" to photograph a book page by page - each page becomes its own photo, saved to the Library under \"CAMERA\".",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyColumn {
                            items(folders, key = { it.folderName }) { folder ->
                                ScanFolderRow(
                                    folder = folder,
                                    building = buildingPdfFor == folder.folderName,
                                    onShare = {
                                        val images = ScanFileStore.listImagesInFolder(context, folder.folderName)
                                        val uris = ArrayList(images.map { it.shareUri(context) })
                                        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                                            type = "image/jpeg"
                                            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(intent, "Share ${folder.folderName}"))
                                    },
                                    onMakePdf = {
                                        buildingPdfFor = folder.folderName
                                        scope.launch {
                                            val images = ScanFileStore.listImagesInFolder(context, folder.folderName)
                                            val result = ScanPdfBuilder.buildPdf(context, images, "${folder.folderName}.pdf")
                                            buildingPdfFor = null
                                            val message = result.fold(
                                                onSuccess = { "Saved as PDF to Downloads/YTSaver" },
                                                onFailure = { e -> "Couldn't build PDF: ${e.message}" }
                                            )
                                            snackbarHostState.showSnackbar(message)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp))
            }
        }
    }
}

@Composable
private fun ScanFolderRow(
    folder: ScanFolder,
    building: Boolean,
    onShare: () -> Unit,
    onMakePdf: () -> Unit
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (folder.thumbnail != null) {
            AsyncImage(
                model = folder.thumbnail.let { loc ->
                    when (loc) {
                        is com.ytsaver.app.scan.PageLocation.MediaStoreUri -> loc.uri
                        is com.ytsaver.app.scan.PageLocation.LegacyFile -> loc.file
                    }
                },
                contentDescription = null,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp))
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(folder.folderName, style = MaterialTheme.typography.bodyLarge)
            Text("${folder.imageCount} page(s)", style = MaterialTheme.typography.bodySmall)
        }
        if (building) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
            IconButton(onClick = onMakePdf) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = "Save as PDF")
            }
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = "Share pages")
        }
    }
}
