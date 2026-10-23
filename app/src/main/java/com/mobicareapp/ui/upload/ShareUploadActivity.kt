package com.mobicareapp.ui.upload

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.mobicareapp.handwriting.LinkNotes
import com.mobicareapp.ui.handwriting.TypeTextDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mobicareapp.MainActivity
import com.mobicareapp.ui.theme.YtSaverTheme
import com.mobicareapp.upload.PersonalServerSettings
import com.mobicareapp.upload.ShareUploadItem
import com.mobicareapp.upload.ShareUploadService
import com.mobicareapp.upload.ShareUploadState
import com.mobicareapp.upload.UploadStatus

/**
 * "Upload to my server" in another app's Share menu (WhatsApp, a file manager, the gallery): whatever is
 * shared (a video, an Excel sheet, a zip, anything) is uploaded straight to the user's server, big files
 * included. This screen shows the progress; it can be left, and the upload carries on (see the notification).
 * When it finishes it says so and the address is copied to the clipboard.
 */
class ShareUploadActivity : ComponentActivity() {
    private var notConfigured = false

    /** Files just shared while an earlier work is still on this screen: waiting for "same work or new work?". */
    private var pendingShare by mutableStateOf<List<Uri>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            YtSaverTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ShareUploadScreen(
                        notConfigured = notConfigured,
                        pendingCount = pendingShare?.size ?: 0,
                        onSameWork = { chooseWork(sameWork = true) },
                        onNewWork = { chooseWork(sameWork = false) },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        // Back in front after a finished upload: make sure the address is on the clipboard.
        if (!ShareUploadState.running.value) ShareUploadState.copyUrls(this)
    }

    private fun handleShare(intent: Intent?) {
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)).orEmpty()
            else -> return // opened from the notification: just show what is there
        }
        // Handled once, even if the screen is recreated.
        intent?.action = null
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to upload: no file was shared.", Toast.LENGTH_LONG).show()
            return
        }
        if (!PersonalServerSettings.isConfigured(this)) {
            notConfigured = true
            return
        }
        val earlierWork = ShareUploadState.items.value.isNotEmpty()
        when {
            // Nothing on this screen yet: a new work.
            !earlierWork -> ShareUploadService.start(this, uris, sameWork = false)
            // An upload is still going: this is certainly more of the same work.
            ShareUploadState.running.value -> ShareUploadService.start(this, uris, sameWork = true)
            // Otherwise ask once.
            else -> pendingShare = uris
        }
    }

    /** The answer to "same work or new work?" for the files that were just shared. */
    private fun chooseWork(sameWork: Boolean) {
        val uris = pendingShare ?: return
        pendingShare = null
        if (!sameWork) ShareUploadState.resetAll()
        ShareUploadService.start(this, uris, sameWork)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareUploadScreen(
    notConfigured: Boolean,
    pendingCount: Int,
    onSameWork: () -> Unit,
    onNewWork: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val items by ShareUploadState.items.collectAsState()
    val running by ShareUploadState.running.collectAsState()
    val allDone = !running && items.isNotEmpty()
    val urls = items.filter { it.status == UploadStatus.DONE }.mapNotNull { it.url }

    // Notes written (or spoken) for this link, and how big they are shown; the one top button copies them together with the URL.
    val note by ShareUploadState.note.collectAsState()
    fun setNote(value: String) { ShareUploadState.note.value = value }
    var noteSize by rememberSaveable { mutableStateOf(0.075f) }
    var showNoteWindow by remember { mutableStateOf(false) }
    val noteSp = when {
        noteSize <= 0.05f -> 24.sp
        noteSize <= 0.08f -> 32.sp
        else -> 42.sp
    }
    fun copyTextAndUrl() {
        val combined = (listOf(note.trim()).filter { it.isNotEmpty() } + urls).joinToString("\n")
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Notes and URL", combined))
        Toast.makeText(context, if (note.isBlank()) "URL copied" else "Text and URL copied together", Toast.LENGTH_SHORT).show()
    }

    // New files were shared while an earlier work is on this screen: same work (added here, numbering goes on) or a new one?
    if (pendingCount > 0) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Same work or a new work?") },
            text = {
                Text(
                    "$pendingCount new file(s) were shared. Is this more of the work above, or a new work?\n\n" +
                        "Same work: they are added to this page, and the numbers go on (1.jpg, then 2.jpg). A different ending starts at 1.\n\n" +
                        "New work: this page starts again and the numbers start at 1 (an old 1.jpg on the server is replaced)."
                )
            },
            confirmButton = { TextButton(onClick = onSameWork) { Text("Same work") } },
            dismissButton = { TextButton(onClick = onNewWork) { Text("New work") } }
        )
    }

    if (showNoteWindow) {
        // The same window as "Type text" on the writing screen: big letters, voice recording and transcribing.
        TypeTextDialog(
            onInsert = { text, size -> setNote(text); noteSize = size; showNoteWindow = false },
            onDismiss = { showNoteWindow = false },
            store = LinkNotes,
            initialText = note,
            useDraft = false,
            insertLabel = "Use this text",
            initialSize = noteSize
        )
    }

    // The moment it finishes: the address goes on the clipboard.
    LaunchedEffect(allDone, urls.size) {
        if (allDone && urls.isNotEmpty()) ShareUploadState.copyUrls(context)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Upload to my server", style = MaterialTheme.typography.headlineSmall)

        // One tap at the top: the notes and the uploaded URL go on the clipboard together.
        if (urls.isNotEmpty()) {
            Button(onClick = { copyTextAndUrl() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (note.isBlank()) "Copy the URL" else "Copy text + URL (one copy)", style = MaterialTheme.typography.titleMedium)
            }
        }

        if (notConfigured && items.isEmpty()) {
            Text("Your server is not set up yet. Open YT Saver, go to Record, Copy images/videos, and save the server address and key. Then share again.")
            Button(onClick = {
                context.startActivity(Intent(context, MainActivity::class.java))
                onClose()
            }) { Text("Open YT Saver") }
            return@Column
        }

        if (items.isEmpty()) Text("Nothing is being uploaded.")
        items.forEach { ItemRow(it) }

        // Notes for this link: type here, or open the bigger window to speak and transcribe.
        Text("Notes for this link", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = note,
            onValueChange = { setNote(it) },
            textStyle = TextStyle(fontSize = noteSp),
            minLines = 3,
            placeholder = { Text("Type your notes here…") },
            modifier = Modifier.fillMaxWidth()
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Normal" to 0.045f, "Big" to 0.075f, "Extra big" to 0.11f).forEach { (label, value) ->
                FilterChip(selected = noteSize == value, onClick = { noteSize = value }, label = { Text(label) })
            }
            OutlinedButton(onClick = { showNoteWindow = true }) { Text("Voice / bigger window") }
            if (note.isNotEmpty()) OutlinedButton(onClick = { setNote("") }) { Text("Clear") }
        }

        if (running) {
            Text(
                "You can leave this screen: the upload carries on, and the notification shows how far it is.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(onClick = { ShareUploadService.cancel(context) }) { Text("Cancel the upload") }
        }

        if (allDone) {
            if (urls.isNotEmpty()) {
                Text(
                    if (urls.size == items.size) "Finished. The URL is copied to the clipboard." else "Finished with some problems. The URL of what went through is copied.",
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedTextField(
                    value = urls.joinToString("\n"),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Uploaded URL (tap and hold to select)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        ShareUploadState.copyUrls(context)
                        Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy the URL again") }
                    OutlinedButton(onClick = onClose) { Text("Close") }
                }
            } else {
                Text("The upload did not finish.", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ShareUploadItem) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${item.label}  →  ${item.serverName}", style = MaterialTheme.typography.titleSmall)
        when (item.status) {
            UploadStatus.WAITING -> Text("Waiting…", style = MaterialTheme.typography.bodySmall)
            UploadStatus.UPLOADING -> {
                val fraction = if (item.size > 0) (item.sent.toFloat() / item.size).coerceIn(0f, 1f) else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text("${size(item.sent)} of ${size(item.size)}  (${(fraction * 100).toInt()}%)", style = MaterialTheme.typography.bodySmall)
            }
            UploadStatus.DONE -> Text("Uploaded (${size(item.size)})", style = MaterialTheme.typography.bodySmall)
            UploadStatus.FAILED -> Text("Failed: ${item.error ?: "unknown error"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(2.dp))
    }
}

private fun size(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
