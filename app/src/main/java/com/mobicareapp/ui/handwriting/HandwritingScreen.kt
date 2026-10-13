package com.mobicareapp.ui.handwriting

import android.content.ClipData
import java.io.FileOutputStream
import java.io.File
import com.mobicareapp.upload.PersonalServerUploader
import com.mobicareapp.upload.PersonalServerSettings
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.Checkbox
import java.util.Locale
import java.util.Date
import java.text.SimpleDateFormat
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.mobicareapp.handwriting.HwSnippet
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.heightIn
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.rememberLauncherForActivityResult
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalView
import androidx.compose.material3.Surface
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsets
import android.view.WindowManager
import android.os.Build
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.handwriting.HwStroke
import com.mobicareapp.handwriting.InkItem
import com.mobicareapp.handwriting.PageItem
import com.mobicareapp.handwriting.WordItem
import com.mobicareapp.handwriting.InkPt
import kotlinx.coroutines.launch

private val PaperColor = Color(0xFFFFFDF6)
private val InkColor = Color(0xFF14213D)

private const val INK_WIDTH = 0.0048f

/**
 * A full-screen notepad for English handwriting: write with a finger or stylus on plain paper, in any direction, and
 * the handwriting is read into text (shown in the panel at the top, editable) with spelling and
 * grammar repaired. Undo/Redo step through strokes; when a page fills up, "Next page" starts a fresh
 * one, as many times as needed. The text of all pages together opens in a full-screen preview where
 * it can be corrected and copied.
 */
@Composable
fun HandwritingScreen(onBack: () -> Unit) {
    // Held here (not inside the content) so the phone's Back button, which this window receives, can
    // close the full preview first and only then leave the notepad.
    var showPreview by remember { mutableStateOf(false) }
    // A true full-screen window on top of everything (the app's banner and bottom bar included), with
    // the phone's own status and navigation bars hidden too, so the writing pad gets all the space.
    Dialog(
        onDismissRequest = { if (showPreview) showPreview = false else onBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        FullScreenWindowEffect()
        Surface(modifier = Modifier.fillMaxSize(), color = PaperColor) {
            HandwritingContent(onBack = onBack, showPreview = showPreview, onShowPreview = { showPreview = it })
        }
    }
}

/** Hides the status/navigation bars (a swipe from the edge brings them back briefly) and lets the page use the notch area. */
@Composable
private fun FullScreenWindowEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.also {
                    it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            val controller = WindowCompat.getInsetsController(window, view)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandwritingContent(onBack: () -> Unit, showPreview: Boolean, onShowPreview: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: HandwritingViewModel = viewModel()
    val pages by viewModel.pages.collectAsState()
    val pageIndex by viewModel.pageIndex.collectAsState()
    val model by viewModel.model.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val autoFix by viewModel.autoFix.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val suggestionsStale by viewModel.suggestionsStale.collectAsState()
    val readError by viewModel.readError.collectAsState()

    // The notepad is saved to disk, but locking would still swap this screen out mid-sentence.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }

    var previewText by remember { mutableStateOf("") }
    var panelOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearPage by remember { mutableStateOf(false) }
    var confirmNewNotepad by remember { mutableStateOf(false) }
    var fixingPage by remember { mutableStateOf(false) }
    var canvasRatio by remember { mutableStateOf(1.8f) }
    val snippets by viewModel.snippets.collectAsState()
    var uploadMessage by remember { mutableStateOf<String?>(null) }
    var showSaveSnippet by remember { mutableStateOf(false) }
    var showInsertSnippet by remember { mutableStateOf(false) }

    // A photo from the gallery goes in behind the writing, fitted to the whole pad.
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.insertImage(uri)
    }

    // "Download": every page stacked top to bottom as one tall PNG, saved wherever the user chooses.
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) {
            Toast.makeText(context, "Saving all pages…", Toast.LENGTH_SHORT).show()
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        val bitmap = viewModel.renderAllPages()
                        try {
                            context.contentResolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                                ?: error("Couldn't write to the chosen place")
                        } finally {
                            bitmap.recycle()
                        }
                    }.isSuccess
                }
                Toast.makeText(context, if (ok) "Saved all pages as one image" else "Couldn't save the image", Toast.LENGTH_LONG).show()
            }
        }
    }

    // "Upload to my server": all pages stacked into one picture, sent to the same 1.jpg slot the
    // Transcribe screen uses, and the web address is copied to the clipboard.
    fun uploadAllPages() {
        if (!PersonalServerSettings.isConfigured(context)) {
            Toast.makeText(
                context,
                "Set up your server under \"Copy images/videos to clipboard\" in Record first.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(context, "Uploading all pages…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = viewModel.renderAllPages()
                    val file = File(context.cacheDir, "handwriting_upload.jpg")
                    try {
                        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out) }
                        PersonalServerUploader.uploadFile(context, file, "image/jpeg", isVideo = false).getOrThrow()
                    } finally {
                        bitmap.recycle()
                        file.delete()
                    }
                }
            }
            result
                .onSuccess {
                    val folder = (PersonalServerSettings.getUploadUrl(context) ?: "").substringBeforeLast('/')
                    val message = "Uploaded to the following URL(s):\nImage: $folder/1.jpg"
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Uploaded URL", message))
                    uploadMessage = message
                }
                .onFailure { e -> uploadMessage = "Upload failed: ${e.message ?: "unknown error"}" }
        }
    }

    if (showPreview) {
        HandwritingPreviewScreen(
            text = previewText,
            onTextChange = { previewText = it },
            onFix = { viewModel.repair(it) },
            onRefresh = { previewText = viewModel.combinedText() },
            onBack = { onShowPreview(false) }
        )
        return
    }

    val index = pageIndex.coerceIn(0, pages.lastIndex)
    val page = pages[index]
    val pageImage by produceState<ImageBitmap?>(null, page.imagePath) {
        value = page.imagePath?.let { path -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() } }
    }
    // Nudge towards "Next page" once writing reaches the bottom edge.
    val pageFull = page.lowestY > canvasRatio * 0.9f

    uploadMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { uploadMessage = null },
            title = { Text(if (message.startsWith("Uploaded")) "Uploaded" else "Upload") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    if (message.startsWith("Uploaded")) {
                        Text("The address is already copied.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { uploadMessage = null }) { Text("OK") } },
            dismissButton = {
                if (message.startsWith("Uploaded")) {
                    TextButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Uploaded URL", message))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy again") }
                }
            }
        )
    }
    if (showSaveSnippet) {
        SaveSnippetDialog(
            onSave = { title ->
                showSaveSnippet = false
                val saved = viewModel.saveSnippet(title)
                Toast.makeText(context, if (saved) "Saved as \"$title\"" else "Nothing to save on this page", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSaveSnippet = false }
        )
    }
    if (showInsertSnippet) {
        InsertSnippetDialog(
            snippets = snippets,
            onInsert = { chosen -> showInsertSnippet = false; viewModel.insertSnippets(chosen) },
            onDelete = { viewModel.deleteSnippet(it.id) },
            onDismiss = { showInsertSnippet = false }
        )
    }
    if (confirmClearPage) {
        AlertDialog(
            onDismissRequest = { confirmClearPage = false },
            title = { Text("Clear this page?") },
            text = { Text("The handwriting and text on this page will be removed.") },
            confirmButton = { TextButton(onClick = { confirmClearPage = false; viewModel.clearPage() }) { Text("Yes, clear") } },
            dismissButton = { TextButton(onClick = { confirmClearPage = false }) { Text("No") } }
        )
    }
    if (confirmNewNotepad) {
        AlertDialog(
            onDismissRequest = { confirmNewNotepad = false },
            title = { Text("Start a new notepad?") },
            text = { Text("All ${pages.size} page(s) of handwriting and text will be removed. Copy anything you need first.") },
            confirmButton = { TextButton(onClick = { confirmNewNotepad = false; viewModel.newNotepad() }) { Text("Yes, start new") } },
            dismissButton = { TextButton(onClick = { confirmNewNotepad = false }) { Text("No") } }
        )
    }

    // Everything outside the pad has a fixed height, and the editing panel floats over the pad
    // instead of pushing it down, so nothing resizes while writing (no flicker), and the pad gets
    // all the rest of the screen.
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
        // Compact toolbar.
        Row(
            modifier = Modifier.fillMaxWidth().height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("${index + 1} / ${pages.size}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { viewModel.undo() }, enabled = page.items.isNotEmpty(), modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = { viewModel.redo() }, enabled = page.redo.isNotEmpty(), modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Redo, contentDescription = "Redo")
            }
            IconButton(onClick = { previewText = viewModel.combinedText(); onShowPreview(true) }, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Full preview of all pages")
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (autoFix) "Auto-fix spelling & grammar: ON" else "Auto-fix spelling & grammar: OFF") },
                        onClick = { menuOpen = false; viewModel.setAutoFix(!autoFix) }
                    )
                    DropdownMenuItem(
                        text = { Text("Save this page's writing under a title…") },
                        enabled = page.items.isNotEmpty(),
                        onClick = { menuOpen = false; showSaveSnippet = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Insert a saved writing (search by title)…") },
                        onClick = { menuOpen = false; showInsertSnippet = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Insert a picture from the gallery…") },
                        onClick = {
                            menuOpen = false
                            AppLockManager.suppressNextLock()
                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    if (page.imagePath != null) {
                        DropdownMenuItem(text = { Text("Remove the picture from this page") }, onClick = { menuOpen = false; viewModel.removeImage() })
                    }
                    DropdownMenuItem(
                        text = { Text("Download all pages as one image…") },
                        onClick = {
                            menuOpen = false
                            AppLockManager.suppressNextLock()
                            exportLauncher.launch("Handwriting_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png")
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Upload all pages to my server (1.jpg)") },
                        onClick = { menuOpen = false; uploadAllPages() }
                    )
                    DropdownMenuItem(text = { Text("Clear this page") }, onClick = { menuOpen = false; confirmClearPage = true })
                    DropdownMenuItem(text = { Text("New notepad") }, onClick = { menuOpen = false; confirmNewNotepad = true })
                }
            }
        }

        // One line showing what has been read so far (its end, where the newest writing lands); tap to open the editor.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clickable { panelOpen = !panelOpen }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val strip = when (val state = model) {
                is ModelState.Checking -> "Preparing handwriting…"
                is ModelState.Downloading -> "Downloading the English handwriting model (one time)…"
                is ModelState.Failed -> state.message
                is ModelState.Ready -> page.text.replace('\n', ' ').trim().let { if (it.length > 70) "…" + it.takeLast(70) else it }
                    .ifEmpty { "Your handwriting appears here as text" }
            }
            Text(
                strip,
                style = MaterialTheme.typography.bodyMedium,
                color = if (model is ModelState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            // Always takes its 16dp, spinning or not, so the text beside it never shifts.
            Box(modifier = Modifier.size(16.dp)) {
                if (busy || fixingPage || model is ModelState.Checking || model is ModelState.Downloading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
            Icon(if (panelOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (panelOpen) "Close editor" else "Edit text")
        }

        // Word suggestions. The previous ones stay (dimmed, not tappable) while the newest writing is
        // being read, instead of disappearing and reappearing on every stroke, which looked like blinking.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (suggestions.isEmpty()) {
                Text(
                    "Write 2-3 letters of a word: suggested words appear here",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                suggestions.forEach { word ->
                    SuggestionChip(
                        onClick = { viewModel.pickSuggestion(word) },
                        enabled = !suggestionsStale,
                        label = { Text(word, style = MaterialTheme.typography.titleMedium) }
                    )
                }
            }
        }

        // The writing pad: everything that's left of the screen.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            HandwritingCanvas(
                items = page.items,
                image = pageImage,
                onStroke = { viewModel.addStroke(it) },
                onSize = { ratio -> canvasRatio = ratio; viewModel.pageRatio = ratio },
                modifier = Modifier.fillMaxSize()
            )

            readError?.let { message ->
                Text(
                    "Couldn't read the handwriting: $message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                )
            }

            // The editor floats over the top of the pad (it doesn't push the pad down).
            if (panelOpen) {
                Card(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        OutlinedTextField(
                            value = page.text,
                            onValueChange = { viewModel.setPageText(index, it) },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            placeholder = { Text("Your handwriting appears here as text…", style = MaterialTheme.typography.bodyMedium) },
                            minLines = 3,
                            maxLines = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (page.textEdited) {
                            Text(
                                "Edited by hand — new writing won't change this text. Tap Re-read to read the handwriting again.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                enabled = page.text.isNotBlank() && !fixingPage,
                                onClick = {
                                    fixingPage = true
                                    scope.launch {
                                        val fixed = viewModel.repair(page.text)
                                        // Repairs a hand-edited page too — that's what the button is for.
                                        viewModel.setPageText(index, fixed)
                                        fixingPage = false
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Fix")
                            }
                            // Reads the handwriting right now (it normally happens a moment after each stroke).
                            TextButton(enabled = page.items.isNotEmpty() && !busy, onClick = { viewModel.readNow(index) }) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Read")
                            }
                            TextButton(enabled = page.text.isNotBlank(), onClick = { copyToClipboard(context, page.text) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy")
                            }
                            if (page.textEdited) {
                                TextButton(onClick = { viewModel.rereadPage(index) }) { Text("Re-read") }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { panelOpen = false }) { Text("Done") }
                        }
                    }
                }
            }
        }

        // Page navigation.
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(onClick = { viewModel.previousPage() }, enabled = index > 0) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Previous page", modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Prev", maxLines = 1)
            }
            Text(
                "Page ${index + 1} of ${pages.size}",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            // Filled when the page is full, plain otherwise; it works either way (on the last page it adds a new one).
            if (pageFull) {
                Button(onClick = { viewModel.nextPage() }) {
                    Text("Next", maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            } else {
                OutlinedButton(onClick = { viewModel.nextPage() }) {
                    Text("Next", maxLines = 1)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Asks for the title a piece of writing is saved under. */
@Composable
private fun SaveSnippetDialog(onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var title by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this writing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Give it a title, so you can find it later and insert it into any page.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { onSave(title) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Search the saved writings by title; tapping one puts it on the page (and it is read automatically). */
@Composable
private fun InsertSnippetDialog(
    snippets: List<HwSnippet>,
    onInsert: (List<HwSnippet>) -> Unit,
    onDelete: (HwSnippet) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<HwSnippet?>(null) }
    // Ticked saved writings, in the order they were ticked (that's the order they go onto the page).
    val ticked = remember { mutableStateListOf<Long>() }
    val matches = snippets.filter { it.title.contains(query.trim(), ignoreCase = true) }

    pendingDelete?.let { snippet ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this saved writing?") },
            text = { Text("\"${snippet.title}\" will be removed. This can't be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(snippet); pendingDelete = null }) { Text("Yes, delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("No") } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert saved writing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tick the ones to put on this page, then tap Insert.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search by title") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (snippets.isEmpty()) {
                    Text("Nothing saved yet. Write something, then use \"Save this page's writing under a title\" in the ⋮ menu.", style = MaterialTheme.typography.bodyMedium)
                } else if (matches.isEmpty()) {
                    Text("No saved writing matches that title.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(matches, key = { it.id }) { snippet ->
                            val isTicked = snippet.id in ticked
                            val toggle = { if (isTicked) ticked.remove(snippet.id) else ticked.add(snippet.id) }
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = isTicked, onCheckedChange = { toggle() })
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(snippet.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (snippet.text.isNotBlank()) {
                                        Text(
                                            snippet.text.replace('\n', ' '),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                IconButton(onClick = { ticked.remove(snippet.id); pendingDelete = snippet }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ticked.isNotEmpty(),
                onClick = { onInsert(ticked.mapNotNull { id -> snippets.firstOrNull { it.id == id } }) }
            ) { Text(if (ticked.isEmpty()) "Insert" else "Insert (${ticked.size})") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Plain paper that records strokes (one finger or stylus) and draws them as smooth curves. */
@Composable
private fun HandwritingCanvas(
    items: List<PageItem>,
    image: ImageBitmap?,
    onStroke: (HwStroke) -> Unit,
    onSize: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .clipToBounds()
            .background(PaperColor)
            .onSizeChanged { if (it.width > 0) onSize(it.height.toFloat() / it.width) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val maxY = size.height.toFloat()
                    val points = mutableListOf(InkPt(down.position.x / width, down.position.y / width, down.uptimeMillis))
                    live = listOf(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        // Only the finger/stylus that started this stroke; a second touch (a resting palm) is ignored.
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        // A finger that slides off the page keeps drawing along its edge instead of outside it.
                        val inside = Offset(change.position.x.coerceIn(0f, width), change.position.y.coerceIn(0f, maxY))
                        points += InkPt(inside.x / width, inside.y / width, change.uptimeMillis)
                        live = live + inside
                        change.consume()
                    }
                    live = emptyList()
                    // A tap becomes a dot (a full stop, the dot of an "i"): two identical points so it has a direction.
                    if (points.size == 1) points += points.first().copy(t = points.first().t + 1)
                    onStroke(HwStroke(points))
                }
            }
    ) {
        // An inserted photo: scaled to fit the whole pad (nothing cropped), centred, behind the writing.
        if (image != null) {
            val scale = minOf(size.width / image.width, size.height / image.height)
            val w = (image.width * scale).toInt()
            val h = (image.height * scale).toInt()
            drawImage(
                image,
                dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()),
                dstSize = IntSize(w, h)
            )
        }

        val inkWidth = size.width * INK_WIDTH
        items.forEach { item ->
            when (item) {
                is InkItem -> drawInk(item.stroke.points.map { Offset(it.x * size.width, it.y * size.width) }, inkWidth)
                // A word completed from a suggestion, set in a handwriting-style font where the unfinished word was.
                is WordItem -> {
                    val layout = textMeasurer.measure(
                        item.text,
                        TextStyle(fontSize = (item.size * size.width * 1.15f).toSp(), fontFamily = FontFamily.Cursive, color = InkColor)
                    )
                    // The word starts at its baseline's left end and runs at the same angle as the writing it replaced.
                    withTransform({
                        translate(item.x * size.width, item.y * size.width)
                        rotate(Math.toDegrees(item.angle.toDouble()).toFloat(), Offset.Zero)
                    }) {
                        drawText(layout, topLeft = Offset(0f, -layout.firstBaseline))
                    }
                }
            }
        }
        drawInk(live, inkWidth)
    }
}

/** Draws a stroke as one smooth curve: each touch point becomes a control point and the curve passes through the midpoints between them, which rounds off the jitter of a finger. */
private fun DrawScope.drawInk(points: List<Offset>, width: Float) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(InkColor, radius = width / 2f, center = points[0])
        return
    }
    val path = Path().apply {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size - 1) {
            val midX = (points[i].x + points[i + 1].x) / 2f
            val midY = (points[i].y + points[i + 1].y) / 2f
            quadraticBezierTo(points[i].x, points[i].y, midX, midY)
        }
        lineTo(points.last().x, points.last().y)
    }
    drawPath(path, InkColor, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Handwriting text", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
