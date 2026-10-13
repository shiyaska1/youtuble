package com.mobicareapp.ui.handwriting

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobicareapp.handwriting.HwNoteMeta
import com.mobicareapp.ui.transcribe.TranscribePickerDialog
import java.io.File
import java.text.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date

private enum class DatePreset(val label: String, val days: Int?) {
    WEEK("7 days", 7),
    MONTH("1 month", 30),
    THREE_MONTHS("3 months", 90),
    SIX_MONTHS("6 months", 180),
    ALL("All", null),
    CUSTOM("Custom", null)
}

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The list of saved notes. By default it shows the last 3 months; the date chips narrow or widen
 * that (or a custom From/To range), and the search box filters by what the note says (its title or text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(viewModel: HandwritingViewModel, onBack: () -> Unit, onOpen: (HwNoteMeta) -> Unit) {
    val notes by viewModel.notes.collectAsState()
    var preset by remember { mutableStateOf(DatePreset.THREE_MONTHS) }
    var fromDay by remember { mutableStateOf<LocalDate?>(null) }
    var toDay by remember { mutableStateOf<LocalDate?>(null) }
    var pickingFrom by remember { mutableStateOf(false) }
    var pickingTo by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<HwNoteMeta?>(null) }
    val zone = ZoneId.systemDefault()

    val now = System.currentTimeMillis()
    val shown = notes.filter { note ->
        val inRange = when {
            preset == DatePreset.ALL -> true
            preset == DatePreset.CUSTOM -> {
                val start = fromDay?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
                val end = toDay?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
                note.createdAt in start until end
            }
            else -> note.createdAt >= now - preset.days!! * DAY_MS
        }
        val q = query.trim()
        inRange && (q.isEmpty() || note.title.contains(q, ignoreCase = true) || note.text.contains(q, ignoreCase = true))
    }.sortedByDescending { it.updatedAt }

    if (pickingFrom) {
        DayPickerDialog(initial = fromDay, onPicked = { fromDay = it; pickingFrom = false }, onDismiss = { pickingFrom = false })
    }
    if (pickingTo) {
        DayPickerDialog(initial = toDay, onPicked = { toDay = it; pickingTo = false }, onDismiss = { pickingTo = false })
    }
    pendingDelete?.let { note ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this note?") },
            text = { Text("\"${note.title}\" (${note.name}) will be removed with its pictures and attached voice. This can't be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.deleteNote(note.id); pendingDelete = null }) { Text("Yes, delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("No") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("Saved notes", style = MaterialTheme.typography.titleMedium)
        }

        // Date range: presets (3 months is the default) or a custom From/To.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DatePreset.entries.forEach { option ->
                FilterChip(selected = preset == option, onClick = { preset = option }, label = { Text(option.label) })
            }
        }
        if (preset == DatePreset.CUSTOM) {
            val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
            fun show(day: LocalDate?, empty: String) =
                day?.let { dateFormat.format(Date(it.atStartOfDay(zone).toInstant().toEpochMilli())) } ?: empty
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { pickingFrom = true }) { Text("From: " + show(fromDay, "any")) }
                OutlinedButton(onClick = { pickingTo = true }) { Text("To: " + show(toDay, "any")) }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            label = { Text("Search in the notes' text") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
        )
        Text(
            "${shown.size} note(s)" + if (shown.size < notes.size) " of ${notes.size}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        if (shown.isEmpty()) {
            Text(
                if (notes.isEmpty()) "No saved notes yet. On the writing pad, use ⋮ → Save as a note." else "No note matches these filters.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(shown, key = { it.id }) { note ->
                    Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(note) }) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    note.name + " · ${note.pageCount} page(s)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (note.text.isNotBlank()) {
                                    Text(note.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            if (note.audioPath != null) {
                                Icon(Icons.Default.AudioFile, contentDescription = "Has a voice recording", modifier = Modifier.padding(horizontal = 4.dp))
                            }
                            IconButton(onClick = { pendingDelete = note }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(initial: LocalDate?, onPicked: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // The picker answers in UTC midnight milliseconds; read the calendar day out of that.
                state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { DatePicker(state = state) }
}

/**
 * One saved note: read it, correct its title or text, attach a voice recording (MP3 or any audio,
 * from the Library or the phone) and play it, open it on the writing pad to edit the handwriting, or delete it.
 */
@Composable
fun NoteDetailScreen(
    viewModel: HandwritingViewModel,
    noteId: Long,
    padHasWriting: Boolean,
    onBack: () -> Unit,
    onOpenOnPad: () -> Unit
) {
    val context = LocalContext.current
    val notes by viewModel.notes.collectAsState()
    val note = notes.firstOrNull { it.id == noteId }
    if (note == null) {
        onBack()
        return
    }

    var title by remember(noteId) { mutableStateOf(note.title) }
    var text by remember(noteId) { mutableStateOf(note.text) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }
    var showAudioPicker by remember { mutableStateOf(false) }
    val changed = title != note.title || text != note.text

    // The attached voice plays through a small player of its own; one at a time, stopped when leaving.
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    fun stopPlayback() {
        player?.let { p ->
            runCatching { p.stop() }
            runCatching { p.release() }
        }
        player = null
        playing = false
    }
    fun play(path: String) {
        stopPlayback()
        runCatching {
            val p = MediaPlayer()
            p.setDataSource(path)
            p.setOnCompletionListener { stopPlayback() }
            p.prepare()
            p.start()
            player = p
            playing = true
        }.onFailure { Toast.makeText(context, "Couldn't play this recording", Toast.LENGTH_SHORT).show() }
    }
    DisposableEffect(noteId) { onDispose { stopPlayback() } }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("It will be removed with its pictures and attached voice. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; stopPlayback(); viewModel.deleteNote(noteId); onBack() }) { Text("Yes, delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("No") } }
        )
    }
    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("Open on the writing pad?") },
            text = { Text("The writing now on the pad is replaced. Save it as a note first if you want to keep it.") },
            confirmButton = { TextButton(onClick = { confirmOpen = false; stopPlayback(); onOpenOnPad() }) { Text("Yes, open") } },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("No") } }
        )
    }
    if (showAudioPicker) {
        // The same chooser as "Transcribe a recording": voice already in the Library, or any audio file on the phone.
        TranscribePickerDialog(
            onDismiss = { showAudioPicker = false },
            onPick = { file: File ->
                showAudioPicker = false
                viewModel.attachAudio(noteId, file) { ok ->
                    Toast.makeText(context, if (ok) "Voice attached" else "Couldn't attach that file", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { stopPlayback(); onBack() }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to the list") }
            Text("Note", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
        }
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                note.name + " · ${note.pageCount} page(s) · saved " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.updatedAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 8,
                label = { Text("Text of the note") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Changes here update the saved text. The handwriting itself is edited on the writing pad.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Attached voice.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Voice recording", style = MaterialTheme.typography.titleSmall)
                    val audio = note.audioPath
                    if (audio == null) {
                        Text("None attached.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { if (playing) stopPlayback() else play(audio) }) {
                                Icon(if (playing) Icons.Default.Stop else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (playing) "Stop" else "Play")
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(File(audio).name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            TextButton(onClick = { stopPlayback(); viewModel.removeAudio(noteId) }) { Text("Remove") }
                        }
                    }
                    OutlinedButton(onClick = { showAudioPicker = true }) { Text(if (audio == null) "Attach a voice recording" else "Replace the voice recording") }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = changed,
                onClick = {
                    viewModel.updateNoteTexts(noteId, title, text)
                    Toast.makeText(context, "Changes saved", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Save changes") }
            IconButton(onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Note text", text))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
            }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy text") }
            OutlinedButton(
                onClick = { if (padHasWriting && viewModel.currentNoteId.value != noteId) confirmOpen = true else { stopPlayback(); onOpenOnPad() } },
                modifier = Modifier.weight(1f)
            ) { Text("Open on pad", maxLines = 1) }
        }
    }
}
