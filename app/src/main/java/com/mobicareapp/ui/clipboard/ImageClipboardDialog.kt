package com.mobicareapp.ui.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import com.mobicareapp.applock.AppLockManager
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.compose.material.icons.filled.Description
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.mobicareapp.upload.ImageCombiner
import com.mobicareapp.upload.PersonalServerSettings
import com.mobicareapp.upload.PersonalServerUploader
import com.mobicareapp.upload.ShareUploader
import kotlinx.coroutines.launch

/**
 * Standalone image/video-to-clipboard tool — no recording or transcription involved. Pick one or
 * more photos/videos from the gallery and copy them to the system clipboard as one unit, so they
 * can be pasted wherever the clipboard is actually read from next (another app on this phone, or
 * forwarded on by a remote-desktop app's clipboard sync, if the one being used supports images —
 * plain text-only clipboard sync tools won't carry an image across to another device).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageClipboardDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Choosing a file leaves the app for a moment (and an upload can take a while): without this
    // the app-lock would cover the screen and the picked file would never be uploaded.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }
    var attachments by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var copied by remember { mutableStateOf(false) }

    var showServerSettings by remember { mutableStateOf(!PersonalServerSettings.isConfigured(context)) }
    var serverUrl by remember { mutableStateOf(PersonalServerSettings.getUploadUrl(context) ?: "") }
    var serverSecret by remember { mutableStateOf(PersonalServerSettings.getSecret(context)) }
    var uploading by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    var noteText by remember { mutableStateOf("") }

    // Any other kind of file: documents, zip, rar... Uploaded as "1" plus the file's own ending (1.zip).
    var files by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var progressText by remember { mutableStateOf<String?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        files = files + uris.filter { it !in files }
        uploadStatus = null
    }

    val mediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        attachments = attachments + uris
        copied = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Copy images/videos") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Pick photos or videos, then copy them to the clipboard to paste elsewhere.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (attachments.isEmpty()) "Choose photos/videos" else "Add more")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (files.isEmpty()) "Choose documents / zip / any file" else "Add more files")
                }
                files.forEach { uri ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            displayNameOf(context, uri) + "  →  " + (planNames(context, attachments, files)[uri] ?: serverNameFor(context, uri)),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { files = files - uri }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                        }
                    }
                }

                if (attachments.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(attachments) { uri ->
                            val isVideo = context.contentResolver.getType(uri)?.startsWith("video") == true
                            Box(
                                modifier = Modifier
                                    .size(84.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                if (isVideo) {
                                    Icon(
                                        Icons.Default.Videocam,
                                        contentDescription = "Video attachment",
                                        modifier = Modifier.size(32.dp).align(Alignment.Center)
                                    )
                                } else {
                                    AsyncImage(
                                        model = uri,
                                        contentDescription = "Image attachment",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        attachments = attachments - uri
                                        copied = false
                                    },
                                    modifier = Modifier.align(Alignment.TopEnd).size(22.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    Button(
                        onClick = {
                            val clipItems = attachments.map { ClipData.Item(it) }
                            val clipData = ClipData("YT Saver attachments", arrayOf("image/*", "video/*"), clipItems.first())
                            clipItems.drop(1).forEach { clipData.addItem(it) }
                            val manager = context.getSystemService(ClipboardManager::class.java)
                            manager.setPrimaryClip(clipData)
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Copy ${attachments.size} to clipboard")
                    }
                    if (copied) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Copied — now paste it wherever you need it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text("Upload to my server", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Sends the actual file to your own server, so it can be downloaded anywhere — not limited to this phone the way clipboard copying is. Always overwrites the same file there. Pictures become one 1.jpg. Videos and other files are named 1, 2, 3… plus their own ending (1.mp4, 1.pdf, 2.pdf), so files with the same ending never overwrite each other.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))

                if (showServerSettings) {
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Upload URL (e.g. .../upload.php)") }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = serverSecret,
                        onValueChange = { serverSecret = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Secret (matches \$SECRET in upload.php)") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            PersonalServerSettings.setUploadUrl(context, serverUrl)
                            PersonalServerSettings.setSecret(context, serverSecret)
                            showServerSettings = false
                        },
                        enabled = serverUrl.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Save server settings") }
                } else {
                    Text(
                        serverUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = { showServerSettings = true }) { Text("Change server settings") }
                }

                if (!showServerSettings && (attachments.isNotEmpty() || files.isNotEmpty())) {
                    val imageCount = attachments.count { context.contentResolver.getType(it)?.startsWith("image") == true }
                    if (imageCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Note (optional)") },
                            placeholder = { Text("e.g. instructions for Claude to read on the image") }
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (imageCount > 1) {
                                "$imageCount images picked — they'll be stacked into one tall picture, with this note added underneath, before uploading."
                            } else {
                                "This note will be added underneath the picture before uploading."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            uploading = true
                            uploadStatus = null
                            scope.launch {
                                val images = attachments.filter { context.contentResolver.getType(it)?.startsWith("image") == true }
                                val videos = attachments - images.toSet()
                                var lastError: String? = null
                                var uploadedImage = false
                                val uploadedVideos = mutableListOf<String>()
                                val names = planNames(context, attachments, files)

                                if (images.isNotEmpty()) {
                                    // Always combined (even a single image) so the note text — if
                                    // any — gets baked into the same image as its own block
                                    // underneath, the same way Transcribe adds the transcript.
                                    ImageCombiner.combineVerticallyWithText(context, images, noteText).fold(
                                        onSuccess = { combinedFile ->
                                            PersonalServerUploader.uploadFile(context, combinedFile, "image/jpeg", isVideo = false)
                                                .onSuccess { uploadedImage = true }
                                                .onFailure { e -> lastError = e.message ?: "Upload failed" }
                                            combinedFile.delete()
                                        },
                                        onFailure = { e -> lastError = e.message ?: "Couldn't combine the images" }
                                    )
                                }
                                // Videos, documents, zips and anything else go by the same rule: 1.mp4, 2.mp4, 1.pdf, 2.pdf...
                                // (small ones through upload.php, big ones in pieces through uploadnew.php).
                                val uploadedFiles = mutableListOf<String>()
                                suspend fun send(uri: Uri, into: MutableList<String>) {
                                    val name = names[uri] ?: return
                                    val source = ShareUploader.describe(context, uri)
                                    runCatching {
                                        ShareUploader.upload(
                                            context, uri, source, name,
                                            onProgress = { sent ->
                                                progressText = if (source.size > 0) "${source.name}: ${(sent * 100 / source.size).toInt()}%" else source.name
                                            },
                                            cancelled = { false }
                                        )
                                    }
                                        .onSuccess { into.add(name) }
                                        .onFailure { e -> lastError = e.message ?: "Upload failed" }
                                }
                                for (uri in videos) send(uri, uploadedVideos)
                                for (uri in files) send(uri, uploadedFiles)
                                progressText = null

                                uploading = false
                                if (lastError != null && !uploadedImage && uploadedVideos.isEmpty() && uploadedFiles.isEmpty()) {
                                    uploadStatus = lastError
                                } else {
                                    // The script keeps images and videos in separate fixed slots
                                    // (1.jpg / 1.mp4) next to itself, so each URL is just the upload
                                    // URL's folder + that fixed name — shown as plain text (not the
                                    // media itself) and copied to the clipboard automatically, since
                                    // text is the one thing that actually survives a remote-desktop
                                    // clipboard sync.
                                    val folder = serverUrl.substringBeforeLast('/')
                                    val lines = mutableListOf<String>()
                                    if (uploadedImage) lines.add("Image: $folder/1.jpg")
                                    uploadedVideos.forEach { lines.add("Video: $folder/$it") }
                                    uploadedFiles.forEach { lines.add("File: $folder/$it") }
                                    val message = "Uploaded to the following URL(s):\n" + lines.joinToString("\n")
                                    uploadStatus = if (lastError != null) message + "\n(Some did not upload: $lastError)" else message
                                    val manager = context.getSystemService(ClipboardManager::class.java)
                                    manager.setPrimaryClip(ClipData.newPlainText("Uploaded URL", message))
                                }
                            }
                        },
                        enabled = !uploading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (uploading) "Uploading… ${progressText.orEmpty()}" else "Upload to my server")
                    }
                    uploadStatus?.let {
                        Spacer(Modifier.height(8.dp))
                        if (it.startsWith("Uploaded to the following URL")) {
                            // A real (read-only) text box, not just a label — already auto-copied
                            // to the clipboard, but this lets it be selected/copied again by hand
                            // if that clipboard content gets overwritten by something else first.
                            OutlinedTextField(
                                value = it,
                                onValueChange = {},
                                readOnly = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodySmall,
                                label = { Text("Uploaded URL (already copied)") },
                                trailingIcon = {
                                    IconButton(onClick = {
                                        val manager = context.getSystemService(ClipboardManager::class.java)
                                        manager.setPrimaryClip(ClipData.newPlainText("Uploaded URL", it))
                                    }) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                                    }
                                }
                            )
                        } else {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}


/** The file's name as the phone knows it (falls back to the last part of its address). */
private fun displayNameOf(context: android.content.Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
    return fromProvider?.takeIf { it.isNotBlank() } ?: (uri.lastPathSegment ?: "file")
}

/** "1" plus the file's own ending (1.zip, 1.pdf); the ending comes from its name, or from its type when the name has none. */
private fun serverNameFor(context: android.content.Context, uri: Uri): String {
    val fromName = displayNameOf(context, uri).substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
    val ext = fromName.ifEmpty {
        context.contentResolver.getType(uri)?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "bin"
    }
    return "1.$ext"
}


/**
 * The name each picked video / file gets on the server. The pictures are always combined into the one 1.jpg;
 * after that the first file of each ending is 1.ending, the second with the same ending 2.ending, and so on
 * (a different ending starts again at 1), so nothing overwrites anything else in the same upload.
 */
private fun planNames(context: android.content.Context, picked: List<Uri>, files: List<Uri>): Map<Uri, String> {
    val videos = picked.filter { context.contentResolver.getType(it)?.startsWith("image") != true }
    val hasImages = picked.size > videos.size
    val ordered = videos + files
    val sources = ordered.map { ShareUploader.describe(context, it) }
    val names = ShareUploader.assignNames(sources, if (hasImages) mapOf("jpg" to 1) else emptyMap())
    return ordered.zip(names).toMap()
}
