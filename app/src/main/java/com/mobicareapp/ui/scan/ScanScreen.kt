package com.mobicareapp.ui.scan

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mobicareapp.data.ScannedDocument
import com.mobicareapp.scan.ScanFileStore
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen() {
    val context = LocalContext.current
    val activity = context as Activity
    val viewModel: ScanViewModel = viewModel()
    val documents by viewModel.documents.collectAsState()
    val reviewPages by viewModel.reviewPages.collectAsState()
    val saving by viewModel.saving.collectAsState()
    val scope = rememberCoroutineScope()
    var viewingDocument by remember { mutableStateOf<ScannedDocument?>(null) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult -> viewModel.onScanResult(result) }

    val pagesInReview = reviewPages
    if (pagesInReview != null) {
        ScanReviewScreen(
            pages = pagesInReview,
            saving = saving,
            onRotate = viewModel::rotatePage,
            onCancel = viewModel::cancelReview,
            onSave = viewModel::confirmReview
        )
        return
    }

    viewingDocument?.let { document ->
        ScanPagesScreen(
            document = document,
            onBack = { viewingDocument = null },
            onSharePage = { pagePath -> sharePage(context, pagePath) }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scans") },
                actions = {
                    Button(
                        onClick = {
                            scope.launch {
                                val intentSender: IntentSender = viewModel.startScanIntentSender(activity)
                                scanLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                            }
                        },
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New scan")
                    }
                }
            )
        }
    ) { padding ->
        if (documents.isEmpty()) {
            EmptyScanState(padding)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp, padding.calculateTopPadding(), 16.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(documents, key = { it.id }) { document ->
                    ScanRow(
                        document = document,
                        onOpen = { openPdf(context, document) },
                        onShare = { sharePdf(context, document) },
                        onViewPages = { viewingDocument = document },
                        onDelete = { viewModel.delete(document) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanReviewScreen(
    pages: List<ReviewPage>,
    saving: Boolean,
    onRotate: (Int) -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review pages") },
                navigationIcon = {
                    IconButton(onClick = onCancel, enabled = !saving) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    Button(
                        onClick = onSave,
                        enabled = !saving,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding(), 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            itemsIndexed(pages) { index, page ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Page ${index + 1} of ${pages.size}", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(260.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = page.uri,
                            contentDescription = "Page ${index + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .rotate(page.rotationDegrees.toFloat())
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { onRotate(index) }, enabled = !saving) {
                        Icon(Icons.Default.RotateRight, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rotate")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanPagesScreen(
    document: ScannedDocument,
    onBack: () -> Unit,
    onSharePage: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding(), 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(document.pagePaths) { index, pagePath ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp, 84.dp)
                            .clip(RoundedCornerShape(6.dp))
                    ) {
                        AsyncImage(
                            model = File(pagePath),
                            contentDescription = "Page ${index + 1}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Text(
                        "Page ${index + 1}",
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    IconButton(onClick = { onSharePage(pagePath) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share page ${index + 1}")
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyScanState(padding: PaddingValues) {
    Box(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.DocumentScanner,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "No scans yet — tap New scan to capture a document",
                modifier = Modifier.padding(top = 12.dp, start = 32.dp, end = 32.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ScanRow(
    document: ScannedDocument,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onViewPages: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp, 72.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            if (document.thumbnailPath != null) {
                AsyncImage(
                    model = File(document.thumbnailPath),
                    contentDescription = document.name,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
        ) {
            Text(document.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${document.pageCount} page(s) · ${DateFormat.getDateInstance().format(Date(document.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        IconButton(onClick = onOpen) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = "Open PDF")
        }
        IconButton(onClick = onViewPages) {
            Icon(Icons.Default.PhotoLibrary, contentDescription = "View/share pages")
        }
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = "Share")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
    }
}

private fun openPdf(context: android.content.Context, document: ScannedDocument) {
    val uri = ScanFileStore.shareUri(context, File(document.filePath))
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, document.name))
}

private fun sharePdf(context: android.content.Context, document: ScannedDocument) {
    val uri = ScanFileStore.shareUri(context, File(document.filePath))
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, document.name))
}

private fun sharePage(context: android.content.Context, pagePath: String) {
    val uri = ScanFileStore.shareUri(context, File(pagePath))
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Share page"))
}
