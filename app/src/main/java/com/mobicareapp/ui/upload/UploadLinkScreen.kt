package com.mobicareapp.ui.upload

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.upload.FileHost
import com.mobicareapp.upload.FileLinkUploader
import com.mobicareapp.upload.UploadedLink
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private data class PickedFile(val uri: Uri, val name: String, val sizeBytes: Long, val mimeType: String)

private sealed class UploadState {
    data object Idle : UploadState()
    data class Uploading(val host: FileHost?, val sentBytes: Long) : UploadState()
    data class Done(val link: UploadedLink) : UploadState()
    data class Failed(val message: String) : UploadState()
}

/**
 * Uploads a file straight from the phone to a free file host and puts the download link on the
 * clipboard, ready to paste into WhatsApp or anywhere else. [initialFile] pre-selects a file
 * (e.g. from the Library); otherwise the user picks one. Closing the screen cancels an upload in
 * progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadLinkScreen(initialFile: File? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Sharing the link means leaving the app for WhatsApp etc., and a big upload can take a while —
    // without this, the app-lock firing in between would swap this screen out and lose the upload
    // and the link. Held only while this screen is open.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }

    var picked by remember {
        mutableStateOf(
            initialFile?.takeIf { it.exists() }?.let {
                PickedFile(Uri.fromFile(it), it.name, it.length(), guessMime(it.name))
            }
        )
    }
    var hostChoice by remember { mutableStateOf<FileHost?>(null) }
    var state by remember { mutableStateOf<UploadState>(UploadState.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            picked = describe(context, uri)
            state = UploadState.Idle
        }
    }

    fun copy(link: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Download link", link))
    }

    fun start() {
        val file = picked ?: return
        state = UploadState.Uploading(hostChoice, 0)
        job = scope.launch {
            var currentHost: FileHost? = hostChoice
            var lastUi = 0L
            val result = FileLinkUploader.upload(
                context, file.uri, file.name, file.sizeBytes, file.mimeType, hostChoice,
                onHost = { host -> currentHost = host },
                onProgress = { sent ->
                    // Called for every 64 KB chunk — redrawing for each would flood the UI thread.
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 250 || sent == 0L) {
                        lastUi = now
                        state = UploadState.Uploading(currentHost, sent)
                    }
                }
            )
            result
                .onSuccess { link ->
                    copy(link.url)
                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                    state = UploadState.Done(link)
                }
                .onFailure { e -> state = UploadState.Failed(e.message ?: "Upload failed") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Upload and get link") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val current = state
            val uploading = current is UploadState.Uploading

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("File", style = MaterialTheme.typography.titleMedium)
                    val file = picked
                    if (file == null) {
                        Text("Choose any file from your phone.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(file.name, style = MaterialTheme.typography.bodyLarge)
                        Text(formatBytes(file.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (file != null && file.sizeBytes <= 0) {
                        Text("This file looks empty, so there is nothing to upload.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(
                        enabled = !uploading,
                        onClick = {
                            AppLockManager.suppressNextLock()
                            pickLauncher.launch(arrayOf("*/*"))
                        }
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (file == null) "Choose file" else "Choose another file")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Upload to", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        FilterChip(selected = hostChoice == null, enabled = !uploading, onClick = { hostChoice = null }, label = { Text("Auto") })
                        FileHost.entries.forEach { host ->
                            FilterChip(selected = hostChoice == host, enabled = !uploading, onClick = { hostChoice = host }, label = { Text(host.label) })
                        }
                    }
                    val chosen = hostChoice
                    Text(
                        chosen?.note ?: "Tries Gofile first, then another free host if it can't be reached.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val file = picked
                    if (chosen != null && file != null && !chosen.accepts(file.sizeBytes)) {
                        Text(
                            "This file is too big for ${chosen.label} (${chosen.note.substringBefore(',')}). Pick Gofile or Auto.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            when (current) {
                is UploadState.Uploading -> {
                    val total = picked?.sizeBytes ?: 0L
                    Text(
                        "Uploading to ${current.host?.label ?: "a free host"}…  ${formatBytes(current.sentBytes)} of ${formatBytes(total)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    LinearProgressIndicator(
                        progress = { if (total > 0) (current.sentBytes.toFloat() / total).coerceIn(0f, 1f) else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = {
                        job?.cancel()
                        state = UploadState.Idle
                    }) { Text("Cancel") }
                }

                is UploadState.Done -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Link copied to clipboard", style = MaterialTheme.typography.titleMedium)
                            SelectionContainer { Text(current.link.url, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary) }
                            Text(
                                "${current.link.host.label}: ${current.link.host.note}. Anyone who has this link can download the file.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    copy(current.link.url)
                                    Toast.makeText(context, "Link copied", Toast.LENGTH_SHORT).show()
                                }) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Copy")
                                }
                                OutlinedButton(onClick = {
                                    val send = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, current.link.url)
                                    }
                                    context.startActivity(Intent.createChooser(send, "Share link"))
                                }) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Share")
                                }
                            }
                        }
                    }
                }

                is UploadState.Failed -> {
                    Text("Upload failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(current.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                UploadState.Idle -> {}
            }

            if (!uploading) {
                val file = picked
                val tooBig = hostChoice?.let { host -> file != null && !host.accepts(file.sizeBytes) } == true
                Button(
                    onClick = { start() },
                    enabled = file != null && file.sizeBytes > 0 && !tooBig,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (current is UploadState.Done || current is UploadState.Failed) "Upload again" else "Upload and copy link")
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Keep the app open while a large file uploads.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun describe(context: Context, uri: Uri): PickedFile {
    var name = "file"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) ?: name }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { if (!cursor.isNull(it)) size = cursor.getLong(it) }
        }
    }
    if (size <= 0) {
        // Some providers report no size, or a wrong one of 0 (seen with images saved to the gallery
        // by the app's own markup tool) — and the upload needs the exact length up front, or the
        // host rejects it ("expected 215 bytes but received 65536"). Ask the file descriptor first
        // (cheap), and only if that doesn't know either, count by reading the whole file.
        size = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it > 0 } ?: -1L
    }
    if (size <= 0) {
        size = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        } ?: 0L
    }
    return PickedFile(uri, name, size, context.contentResolver.getType(uri) ?: guessMime(name))
}

private fun guessMime(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
    else -> "$bytes B"
}
