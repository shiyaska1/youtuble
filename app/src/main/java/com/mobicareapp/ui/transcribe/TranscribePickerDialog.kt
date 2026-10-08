package com.mobicareapp.ui.transcribe

import android.net.Uri
import android.media.MediaPlayer
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mobicareapp.YtSaverApp
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.SavedMedia
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date

/** Lets you jump into Transcribe for any past audio recording directly from the Record screen, or pick an arbitrary audio file from elsewhere on the phone (e.g. a WhatsApp voice note saved to Downloads) — not just recordings already inside this app's own Library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscribePickerDialog(onDismiss: () -> Unit, onPick: (File) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as YtSaverApp
    val dao = app.database.savedMediaDao()
    val items by dao.observeAll().collectAsState(initial = emptyList())
    val audioItems = items.filter { it.type == MediaType.AUDIO && !it.filePath.startsWith("content://") }
        .sortedByDescending { it.createdAt }

    // Own small player (not the Library's global one), so listening here never opens the video
    // player screen or disturbs whatever else the app is playing; only one recording plays at a time.
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playingId by remember { mutableStateOf<Long?>(null) }
    var renameTarget by remember { mutableStateOf<SavedMedia?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SavedMedia?>(null) }

    fun stopPlayback() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        playingId = null
    }

    fun play(item: SavedMedia) {
        stopPlayback()
        runCatching {
            val p = MediaPlayer()
            p.setDataSource(item.filePath)
            p.setOnCompletionListener { stopPlayback() }
            p.prepare()
            p.start()
            player = p
            playingId = item.id
        }.onFailure { Toast.makeText(context, "Couldn't play this recording", Toast.LENGTH_SHORT).show() }
    }

    DisposableEffect(Unit) { onDispose { stopPlayback() } }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename recording") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.isNotBlank(),
                    onClick = {
                        val newName = renameText.trim()
                        renameTarget = null
                        scope.launch { dao.updateCaption(target.id, newName) }
                    }
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete this recording?") },
            text = { Text("\"${target.caption}\" will be removed from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    if (playingId == target.id) stopPlayback()
                    scope.launch {
                        MediaAccess.delete(context, target.filePath)
                        dao.delete(target)
                    }
                }) { Text("Yes, delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("No") } }
        )
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                // A picked content:// URI (e.g. a WhatsApp voice note) isn't a plain file path —
                // Transcribe needs an actual File, so this copies it into the app's own storage first.
                val tempFile = File.createTempFile("import_audio", ".tmp", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }
                onPick(tempFile)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Choose a recording to transcribe") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Button(
                        onClick = { filePicker.launch("audio/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Pick an audio file from this phone")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Use this for a WhatsApp voice note or any other audio file saved on the phone, not just recordings already in this app's Library.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                HorizontalDivider()
                if (audioItems.isEmpty()) {
                    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                        Text("No audio recordings in Library yet.", style = MaterialTheme.typography.bodyMedium)
                    }
                } else {
                    Text(
                        "Tap a recording to transcribe it. Use the buttons to play, rename or delete it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(audioItems, key = { it.id }) { item ->
                            val isPlaying = playingId == item.id
                            ListItem(
                                headlineContent = { Text(item.caption) },
                                supportingContent = { Text(DateFormat.getDateTimeInstance().format(Date(item.createdAt))) },
                                trailingContent = {
                                    Row {
                                        IconButton(onClick = { if (isPlaying) stopPlayback() else play(item) }) {
                                            Icon(
                                                if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                contentDescription = if (isPlaying) "Stop" else "Play"
                                            )
                                        }
                                        IconButton(onClick = { renameTarget = item; renameText = item.caption }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Rename")
                                        }
                                        IconButton(onClick = { deleteTarget = item }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    stopPlayback()
                                    onPick(File(item.filePath))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
