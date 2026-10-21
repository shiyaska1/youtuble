package com.mobicareapp.ui.handwriting

import android.content.ClipData
import androidx.compose.material.icons.filled.SaveAs
import androidx.compose.ui.graphics.asAndroidBitmap
import com.mobicareapp.handwriting.ItemGeometry
import com.mobicareapp.handwriting.HandwritingStyle
import com.mobicareapp.handwriting.TypedDraft
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import android.content.Intent
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.CloudUpload
import com.mobicareapp.handwriting.SavedTexts
import com.mobicareapp.handwriting.SavedText
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Save
import kotlinx.coroutines.delay
import com.mobicareapp.ui.transcribe.TranscribePickerDialog
import com.mobicareapp.transcribe.TranscriptionRouter
import com.mobicareapp.transcribe.TranscribeSettings
import com.mobicareapp.record.RecordingStatus
import com.mobicareapp.record.RecordingSource
import com.mobicareapp.record.AudioRecordService
import com.mobicareapp.questionpaper.QuestionPaperSettings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Mic
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Keyboard
import com.mobicareapp.textextract.PdfTextExtractor
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import android.net.Uri
import android.content.pm.PackageManager
import android.Manifest
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
import com.mobicareapp.handwriting.HandwritingStore
import com.mobicareapp.handwriting.SharedContentInbox
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.PhotoCamera
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.handwriting.HwStroke
import com.mobicareapp.handwriting.InkItem
import com.mobicareapp.handwriting.PageItem
import com.mobicareapp.handwriting.WordItem
import com.mobicareapp.handwriting.InkPt
import kotlinx.coroutines.launch

private val PaperColor = Color(0xFFFFFDF6)

/** Whether the "keep the old writing or start new" question was already dealt with since the app process started. */
private var askedThisProcess = false
private val InkColor = Color(0xFF14213D)

private const val INK_WIDTH = 0.0048f

/**
 * A full-screen notepad for English handwriting: write with a finger or stylus on plain paper, in any direction, and
 * the handwriting is read into text (shown in the panel at the top, editable) with spelling and
 * grammar repaired. Undo/Redo step through strokes; when a page fills up, "Next page" starts a fresh
 * one, as many times as needed. The text of all pages together opens in a full-screen preview where
 * it can be corrected and copied.
 */
/** What fills the full-screen window instead of the writing pad. */
internal sealed interface HwOverlay {
    data object None : HwOverlay
    data object Preview : HwOverlay
    data object NotesList : HwOverlay
    data class NoteDetail(val id: Long) : HwOverlay
}

@Composable
fun HandwritingScreen(onBack: () -> Unit) {
    // Held here (not inside the content) so the phone's Back button, which this window receives, can
    // step back one screen at a time: note -> list -> pad -> out of the notepad.
    var overlay by remember { mutableStateOf<HwOverlay>(HwOverlay.None) }
    // A true full-screen window on top of everything (the app's banner and bottom bar included), with
    // the phone's own status and navigation bars hidden too, so the writing pad gets all the space.
    Dialog(
        onDismissRequest = {
            when (overlay) {
                is HwOverlay.NoteDetail -> overlay = HwOverlay.NotesList
                HwOverlay.None -> onBack()
                else -> overlay = HwOverlay.None
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        FullScreenWindowEffect()
        Surface(modifier = Modifier.fillMaxSize(), color = PaperColor) {
            HandwritingContent(onBack = onBack, overlay = overlay, onOverlay = { overlay = it })
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
private fun HandwritingContent(onBack: () -> Unit, overlay: HwOverlay, onOverlay: (HwOverlay) -> Unit) {
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
    val currentNoteId by viewModel.currentNoteId.collectAsState()
    val importStatus by viewModel.importStatus.collectAsState()
    val pageRatio by viewModel.pageRatioFlow.collectAsState()
    val inkColor by viewModel.inkColor.collectAsState()
    val textColor by viewModel.textColor.collectAsState()
    val normalFont by viewModel.normalFont.collectAsState()
    val itemClipboard by viewModel.itemClipboard.collectAsState()

    // The notepad is saved to disk, but locking would still swap this screen out mid-sentence.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }

    // Whenever the screen leaves the front (home, recents, swiped away, another app), everything is written to disk at once.
    DisposableEffect(Unit) {
        var owner: Context? = context
        while (owner is android.content.ContextWrapper && owner !is androidx.lifecycle.LifecycleOwner) owner = owner.baseContext
        val lifecycle = (owner as? androidx.lifecycle.LifecycleOwner)?.lifecycle
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE || event == androidx.lifecycle.Lifecycle.Event.ON_STOP) viewModel.saveNow()
        }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    // When the notepad opens with earlier writing in it, ask once: carry on with it, or start a clean
    // single page for a quick note. Decided at the moment of opening, so it never pops up mid-writing.
    // (Not when something was just shared to the app: that goes onto the first free screen, and a
    // "New notepad" answer would wipe it.)
    val askAtStart = remember {
        // Only once after the app is started: coming back to the notepad later never asks again.
        val ask = !askedThisProcess && !SharedContentInbox.hasPending() && (pages.any { it.items.isNotEmpty() || it.imagePath != null } || pages.size > 1)
        askedThisProcess = true
        ask
    }
    val sharedVersion by SharedContentInbox.version.collectAsState()
    LaunchedEffect(sharedVersion) {
        val shared = SharedContentInbox.drain()
        if (shared.isNotEmpty()) viewModel.handleShared(shared)
    }
    var startChoiceDone by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf("") }
    var panelOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearPage by remember { mutableStateOf(false) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    var confirmNewNotepad by remember { mutableStateOf(false) }
    var fixingPage by remember { mutableStateOf(false) }
    // Zoom (1 = the whole width) and how far the zoomed page is moved, in screen pixels.
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val snippets by viewModel.snippets.collectAsState()
    var uploadMessage by remember { mutableStateOf<String?>(null) }
    // "Read text from an area": while selecting, a finger drag draws a box instead of writing.
    var selecting by remember { mutableStateOf(false) }
    var ocrBusy by remember { mutableStateOf(false) }
    var ocrText by remember { mutableStateOf<String?>(null) }
    // Open again if the app was closed with the Type text window open (its text is kept).
    var showTypeText by rememberSaveable { mutableStateOf(TypedDraft.isOpen(context)) }
    var showVoicePicker by remember { mutableStateOf(false) }
    var showSaveSnippet by remember { mutableStateOf(false) }
    var showInsertSnippet by remember { mutableStateOf(false) }
    // Move mode: pick writing (tap it, or drag a box round it), drag it, cut it, paste it on any screen.
    var moveMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var showStyle by remember { mutableStateOf(false) }

    // A photo from the gallery goes in behind the writing, fitted to the whole pad.
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        if (uris.isNotEmpty()) viewModel.insertImages(uris)
    }

    // The camera: takes a photo and fits it to the page that's showing (replacing that page's picture).
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (success && file != null && file.length() > 0) viewModel.insertImages(listOf(Uri.fromFile(file)), deleteSourceFiles = true)
        else file?.delete()
    }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.insertPdf(uri)
    }
    val videoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.transcribeVideo(uri)
    }
    fun launchCamera() {
        val dir = File(context.getExternalFilesDir(null), "HandwritingCamera").apply { mkdirs() }
        val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
        pendingCameraFile = file
        AppLockManager.suppressNextLock()
        cameraLauncher.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else Toast.makeText(context, "Camera permission is needed to take a photo", Toast.LENGTH_LONG).show()
    }

    fun takePhoto() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            AppLockManager.suppressNextLock()
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
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
        // Blank screens are never uploaded; with nothing but blank screens, nothing is sent at all.
        if (!viewModel.hasContent()) {
            uploadMessage = "Nothing to upload: every screen is blank."
            return
        }
        Toast.makeText(context, "Uploading all pages…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = viewModel.renderAllPages(skipBlank = true)
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

    // Sends this screen (or all of them, as one tall picture) to any app: WhatsApp, mail, a gallery...
    fun sharePictures(all: Boolean) {
        val at = viewModel.pageIndex.value
        if (all && !viewModel.hasContent() || !all && pages.getOrNull(at)?.let { it.items.isEmpty() && it.imagePath == null } != false) {
            Toast.makeText(context, "Nothing to share: this screen is blank.", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bitmap = if (all) viewModel.renderAllPages(skipBlank = true) else viewModel.renderPage(at)
                    val dir = File(context.getExternalFilesDir(null), "HandwritingShare").apply { mkdirs() }
                    val file = File(dir, (if (all) "pages" else "page_${at + 1}") + "_${System.currentTimeMillis()}.png")
                    try {
                        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    } finally {
                        bitmap.recycle()
                    }
                    file
                }
            }
            result
                .onSuccess { file ->
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    AppLockManager.suppressNextLock()
                    context.startActivity(Intent.createChooser(send, if (all) "Share all pages" else "Share this page"))
                }
                .onFailure { e -> Toast.makeText(context, e.message ?: "Couldn't share", Toast.LENGTH_LONG).show() }
        }
    }

    when (overlay) {
        HwOverlay.Preview -> {
            HandwritingPreviewScreen(
                text = previewText,
                onTextChange = { previewText = it },
                onFix = { viewModel.repair(it) },
                onRefresh = { previewText = viewModel.combinedText() },
                onBack = { onOverlay(HwOverlay.None) }
            )
            return
        }
        HwOverlay.NotesList -> {
            NotesListScreen(
                viewModel = viewModel,
                onBack = { onOverlay(HwOverlay.None) },
                onOpen = { note -> onOverlay(HwOverlay.NoteDetail(note.id)) }
            )
            return
        }
        is HwOverlay.NoteDetail -> {
            NoteDetailScreen(
                viewModel = viewModel,
                noteId = overlay.id,
                padHasWriting = pages.any { it.items.isNotEmpty() || it.imagePath != null },
                onBack = { onOverlay(HwOverlay.NotesList) },
                onOpenOnPad = { viewModel.openNote(overlay.id) { onOverlay(HwOverlay.None) } }
            )
            return
        }
        HwOverlay.None -> {}
    }

    val index = pageIndex.coerceIn(0, pages.lastIndex)
    val page = pages[index]
    val pageImage by produceState<ImageBitmap?>(null, page.imagePath) {
        value = page.imagePath?.let { path -> withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() } }
    }
    // Nudge towards "Next page" once writing reaches the bottom edge.
    val pageFull = page.lowestY > pageRatio * 0.9f
    LaunchedEffect(index) { zoom = 1f; pan = Offset.Zero; selected = emptySet() }

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
    ocrText?.let { text ->
        var edited by remember(text) { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { ocrText = null },
            title = { Text("Text read from the picture") },
            text = {
                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 5,
                    maxLines = 12,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Text from picture", edited))
                    Toast.makeText(context, "Copied to the clipboard", Toast.LENGTH_SHORT).show()
                    ocrText = null
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { ocrText = null }) { Text("Close") } }
        )
    }
    if (showStyle) {
        AlertDialog(
            onDismissRequest = { showStyle = false },
            title = { Text("Pen & text style") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text("Pen colour (your handwriting)", style = MaterialTheme.typography.labelLarge)
                    ColourRow(current = inkColor, onPick = { viewModel.setInkColor(it) })
                    Text("Text colour (typed and transcribed text)", style = MaterialTheme.typography.labelLarge)
                    ColourRow(current = textColor, onPick = { viewModel.setTextColor(it) })
                    Text("Text font", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !normalFont, onClick = { viewModel.setNormalFont(false) }, label = { Text("Handwriting style") })
                        FilterChip(selected = normalFont, onClick = { viewModel.setNormalFont(true) }, label = { Text("Normal") })
                    }
                    Text(
                        "New writing and text use these colours; what is already on the pages keeps its colour. The font applies to all the text on the pages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showStyle = false }) { Text("Done") } }
        )
    }
    if (showTypeText) {
        TypeTextDialog(
            onInsert = { text, size -> showTypeText = false; viewModel.insertTypedText(text, size) },
            onDismiss = { showTypeText = false }
        )
    }
    if (showVoicePicker) {
        // The same chooser as "Transcribe a recording": voice already in the Library, or any audio file on the phone.
        com.mobicareapp.ui.transcribe.TranscribePickerDialog(
            onDismiss = { showVoicePicker = false },
            onPick = { file -> showVoicePicker = false; viewModel.transcribeAndInsert(file) }
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
    if (askAtStart && !startChoiceDone && overlay == HwOverlay.None) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Handwriting pad") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("There is writing from before on the pad (${pages.size} page(s)). Keep it, or start a new notepad with one clear page?")
                    // Starting new throws the old writing away, so offer to put it in the saved-notes list first.
                    TextButton(onClick = {
                        viewModel.saveNote { note ->
                            Toast.makeText(context, if (note != null) "Saved: ${note.title}" else "Nothing to save", Toast.LENGTH_SHORT).show()
                            viewModel.newNotepad()
                            startChoiceDone = true
                        }
                    }) { Text("Save it as a note, then start new") }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.newNotepad(); startChoiceDone = true }) { Text("New notepad") } },
            dismissButton = { TextButton(onClick = { startChoiceDone = true }) { Text("Keep existing") } }
        )
    }
    if (confirmDeletePage) {
        AlertDialog(
            onDismissRequest = { confirmDeletePage = false },
            title = { Text("Delete this screen?") },
            text = {
                Text(
                    if (pages.size > 1) "Screen ${index + 1} of ${pages.size} will be removed, with its writing, text and picture. The screens after it move up. This can't be undone."
                    else "This is the only screen, so it will just be cleared."
                )
            },
            confirmButton = { TextButton(onClick = { confirmDeletePage = false; viewModel.deletePage() }) { Text("Yes, delete") } },
            dismissButton = { TextButton(onClick = { confirmDeletePage = false }) { Text("No") } }
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
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
            Text("${index + 1} / ${pages.size}", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { selected = emptySet(); viewModel.undo() }, enabled = page.items.isNotEmpty(), modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Undo, contentDescription = "Undo")
            }
            IconButton(onClick = { selected = emptySet(); viewModel.redo() }, enabled = page.redo.isNotEmpty(), modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Redo, contentDescription = "Redo")
            }
            IconButton(onClick = { showTypeText = true }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Keyboard, contentDescription = "Type text with the keyboard")
            }
            IconButton(onClick = { takePhoto() }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.PhotoCamera, contentDescription = "Take a photo")
            }
            IconButton(onClick = { previewText = viewModel.combinedText(); onOverlay(HwOverlay.Preview) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Fullscreen, contentDescription = "Full preview of all pages")
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (autoFix) "Auto-fix spelling & grammar: ON" else "Auto-fix spelling & grammar: OFF") },
                        onClick = { menuOpen = false; viewModel.setAutoFix(!autoFix) }
                    )
                    DropdownMenuItem(
                        text = { Text(if (currentNoteId == null) "Save as a note (named by date and time)" else "Update the saved note") },
                        onClick = {
                            menuOpen = false
                            viewModel.saveNote { note ->
                                Toast.makeText(
                                    context,
                                    if (note == null) "Nothing to save yet" else "Saved: ${note.title}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("My saved notes (list)…") },
                        onClick = { menuOpen = false; onOverlay(HwOverlay.NotesList) }
                    )
                    DropdownMenuItem(
                        text = { Text("Move, cut or paste writing (also to another screen)…") },
                        onClick = { menuOpen = false; moveMode = true; selected = emptySet() }
                    )
                    DropdownMenuItem(
                        text = { Text("Pen & text colours, text font…") },
                        onClick = { menuOpen = false; showStyle = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Share this screen (picture)…") },
                        onClick = { menuOpen = false; sharePictures(all = false) }
                    )
                    DropdownMenuItem(
                        text = { Text("Share all screens (one picture)…") },
                        onClick = { menuOpen = false; sharePictures(all = true) }
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
                        text = { Text("Insert pictures from the gallery (one per page)…") },
                        onClick = {
                            menuOpen = false
                            AppLockManager.suppressNextLock()
                            galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Take a photo with the camera (fits this page)…") },
                        onClick = { menuOpen = false; takePhoto() }
                    )
                    DropdownMenuItem(
                        text = { Text("Type text with the keyboard (big font)…") },
                        onClick = { menuOpen = false; showTypeText = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Read text from a picture (select an area, copy)…") },
                        onClick = {
                            menuOpen = false
                            if (page.imagePath == null) {
                                Toast.makeText(context, "Put a picture on this screen first (camera, gallery or a PDF page).", Toast.LENGTH_LONG).show()
                            } else {
                                selecting = true
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Pick a voice recording and put its text on the blank screen…") },
                        onClick = { menuOpen = false; showVoicePicker = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Pick a video and transcribe its voice…") },
                        onClick = {
                            menuOpen = false
                            AppLockManager.suppressNextLock()
                            videoLauncher.launch(arrayOf("video/*"))
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Insert a PDF (each page on its own screen)…") },
                        onClick = {
                            menuOpen = false
                            AppLockManager.suppressNextLock()
                            pdfLauncher.launch(arrayOf("application/pdf"))
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
                    DropdownMenuItem(text = { Text("Clear this screen (keep it, empty it)") }, onClick = { menuOpen = false; confirmClearPage = true })
                    DropdownMenuItem(
                        text = { Text("Delete this screen") },
                        onClick = { menuOpen = false; confirmDeletePage = true }
                    )
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
            val strip = importStatus
                ?: (if (selecting) "Drag over the text to read, then lift your finger" else if (ocrBusy) "Reading the text…" else null)
                ?: when (val state = model) {
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
                if (busy || fixingPage || ocrBusy || importStatus != null || model is ModelState.Checking || model is ModelState.Downloading) {
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
            // The page always uses the full width of the pad; if the room is shorter than the page (a
            // split-screen window, a sideways phone), the page scrolls with two fingers.
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                LaunchedEffect(maxWidth, maxHeight) { viewModel.noteViewportRatio(maxHeight.value / maxWidth.value) }
            HandwritingCanvas(
                items = page.items,
                image = pageImage,
                pageRatio = pageRatio,
                zoom = zoom,
                pan = pan,
                onTransform = { newZoom, newPan -> zoom = newZoom; pan = newPan },
                selecting = selecting,
                onSelect = { from, to, canvas ->
                    selecting = false
                    val picture = pageImage
                    val path = page.imagePath
                    if (picture == null || path == null) {
                        Toast.makeText(context, "There is no picture on this screen", Toast.LENGTH_SHORT).show()
                    } else {
                        scope.launch {
                            ocrBusy = true
                            val result = runCatching { readTextInArea(context, path, picture.asAndroidBitmap(), from, to, canvas, picture.width, picture.height) }
                            ocrBusy = false
                            result
                                .onSuccess { text ->
                                    if (text.isBlank()) {
                                        Toast.makeText(context, "No text found in that area", Toast.LENGTH_LONG).show()
                                    } else {
                                        // Copied straight away; the box below lets it be corrected and copied again.
                                        context.getSystemService(ClipboardManager::class.java)
                                            .setPrimaryClip(ClipData.newPlainText("Text from picture", text))
                                        Toast.makeText(context, "Text copied to the clipboard", Toast.LENGTH_SHORT).show()
                                        ocrText = text
                                    }
                                }
                                .onFailure { e -> Toast.makeText(context, e.message ?: "Couldn't read the text", Toast.LENGTH_LONG).show() }
                        }
                    }
                },
                onStroke = { viewModel.addStroke(it) },
                moveMode = moveMode,
                selected = selected,
                onPress = { x, y ->
                    val items = page.items
                    val tol = 0.025f
                    if (selected.any { it in items.indices && ItemGeometry.hit(items[it], x, y, tol) }) {
                        true
                    } else {
                        val hit = items.indices.lastOrNull { ItemGeometry.hit(items[it], x, y, tol) }
                        if (hit == null) {
                            false
                        } else {
                            // Writing put on the page together (typed text, a pasted piece) is picked as one.
                            val group = items[hit].group
                            selected = if (group != 0) items.indices.filter { items[it].group == group }.toSet() else setOf(hit)
                            true
                        }
                    }
                },
                onMoveEnd = { dx, dy -> viewModel.moveItems(selected, dx, dy) },
                onArea = { left, top, right, bottom ->
                    val items = page.items
                    selected = if (right - left < 0.01f && bottom - top < 0.01f) {
                        emptySet()
                    } else {
                        items.indices.filter { ItemGeometry.intersects(items[it], left, top, right, bottom) }.toSet()
                    }
                },
                inkColor = inkColor,
                normalFont = normalFont,
                modifier = Modifier.fillMaxSize()
            )

            // Zoom buttons (pinching with two fingers does the same): in, out, and back to the whole page.
            val density = androidx.compose.ui.platform.LocalDensity.current
            val widthPx = with(density) { maxWidth.toPx() }
            val viewportHeightPx = with(density) { maxHeight.toPx() }
            fun zoomTo(target: Float) {
                val newZoom = target.coerceIn(1f, MAX_ZOOM)
                // Zoom about the middle of what is showing.
                val centre = Offset(widthPx / 2f, viewportHeightPx / 2f)
                val pagePointAtCentre = (centre + pan) / zoom
                pan = clampPan(pagePointAtCentre * newZoom - centre, newZoom, widthPx, widthPx * pageRatio, viewportHeightPx)
                zoom = newZoom
            }
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .background(Color(0xCCFFFFFF), RoundedCornerShape(24.dp)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { zoomTo(zoom / 1.5f) }, enabled = zoom > 1f, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                }
                Text(
                    "${(zoom * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(enabled = zoom > 1f) { zoomTo(1f) }.padding(horizontal = 2.dp)
                )
                IconButton(onClick = { zoomTo(zoom * 1.5f) }, enabled = zoom < MAX_ZOOM, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                }
            }
            }

            if (moveMode) {
                Card(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(
                            if (selected.isEmpty()) "Tap writing to pick it, or drag a box round it. Then drag it to move it. To put it on another screen: Cut, go to that screen, Paste."
                            else "${selected.size} piece(s) picked. Drag one to move them all.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(enabled = selected.isNotEmpty(), onClick = { viewModel.cutItems(selected); selected = emptySet() }) { Text("Cut") }
                            TextButton(
                                enabled = selected.isNotEmpty(),
                                onClick = { viewModel.copyItems(selected); Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show() }
                            ) { Text("Copy") }
                            TextButton(
                                enabled = itemClipboard.isNotEmpty(),
                                onClick = {
                                    val before = page.items.size
                                    val added = viewModel.pasteItems()
                                    selected = (before until before + added).toSet()
                                }
                            ) { Text("Paste") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { moveMode = false; selected = emptySet() }) { Text("Done") }
                        }
                    }
                }
            }

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

/**
 * Reads the text inside the box dragged over the page. The box is in pad pixels; the picture sits
 * fitted and centred in the pad, so the box is first mapped back onto the picture's own pixels, then
 * that part of the picture is cut out and read with the same on-device OCR as "Extract text from PDF"
 * (English, Hindi and Malayalam).
 */
private suspend fun readTextInArea(
    context: Context,
    imagePath: String,
    loaded: Bitmap?,
    from: Offset,
    to: Offset,
    pad: IntSize,
    imageWidth: Int,
    imageHeight: Int
): String {
    val scale = minOf(pad.width.toFloat() / imageWidth, pad.height.toFloat() / imageHeight)
    val offsetX = (pad.width - imageWidth * scale) / 2f
    val offsetY = (pad.height - imageHeight * scale) / 2f
    fun x(v: Float) = ((v - offsetX) / scale).coerceIn(0f, imageWidth.toFloat())
    fun y(v: Float) = ((v - offsetY) / scale).coerceIn(0f, imageHeight.toFloat())
    val left = minOf(x(from.x), x(to.x))
    val right = maxOf(x(from.x), x(to.x))
    val top = minOf(y(from.y), y(to.y))
    val bottom = maxOf(y(from.y), y(to.y))
    if (right - left < 12f || bottom - top < 12f) error("Drag a bigger area over the text")

    // The picture that is already showing on the pad is used; the file is only read when that is not available.
    val bitmap = loaded ?: withContext(Dispatchers.IO) { BitmapFactory.decodeFile(imagePath) } ?: error("Couldn't open the picture")
    val region = PdfTextExtractor.crop(bitmap, left / imageWidth, top / imageHeight, right / imageWidth, bottom / imageHeight)
    // A small area (a line of text) is enlarged first: the reader does much better on bigger letters.
    val longSide = maxOf(region.width, region.height)
    val cropped = if (longSide in 1..1599) {
        val factor = minOf(4f, 1600f / longSide)
        Bitmap.createScaledBitmap(region, (region.width * factor).toInt(), (region.height * factor).toInt(), true)
    } else {
        region
    }
    val extraction = PdfTextExtractor.extractText(context, cropped)
    return extraction.paragraphsFor(extraction.detected).joinToString("\n").trim()
}

/**
 * Type text on the keyboard (or speak it) and choose how big it is; it goes onto the page in the
 * handwriting-style font. The window takes the whole screen above the keyboard, with a big text box
 * that grows with the chosen font size, so a lot can be typed and read comfortably.
 *
 * "Voice" opens the tools to record your voice here (start/stop; the recording is saved in the
 * Library too) or to pick a recording you already have; either is transcribed with the engine you
 * choose, and the text lands in the box, where it can be corrected before Insert.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TypeTextDialog(
    onInsert: (String, Float) -> Unit,
    onDismiss: () -> Unit,
    store: com.mobicareapp.handwriting.SavedTextStore = SavedTexts,
    // The plain version (no voice, no font size, no Insert): just a box to type, copy and keep texts in.
    simple: Boolean = false,
    initialText: String = ""
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Typed text not yet on the page is kept (see TypedDraft), so it is back if the app was closed.
    // The notes version opens exactly as it was left (the same saved text open, with what was typed); the first time it uses [initialText].
    var text by remember { mutableStateOf(if (simple) (store.sessionText(context) ?: initialText) else initialText.ifEmpty { TypedDraft.text(context) }) }
    var size by remember { mutableStateOf(0.075f) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } } // opens the keyboard straight away

    // --- undo / redo: snapshots of the text, taken a moment after typing pauses ---
    val history = remember { mutableStateListOf(text) }
    var pos by remember { mutableStateOf(0) }
    fun commitPending() {
        if (text != history[pos]) {
            while (history.size > pos + 1) history.removeAt(history.size - 1)
            history.add(text)
            if (history.size > 100) history.removeAt(0)
            pos = history.size - 1
        }
    }
    LaunchedEffect(text) {
        delay(500)
        commitPending()
    }
    fun undo() {
        commitPending()
        if (pos > 0) { pos--; text = history[pos] }
    }
    fun redo() {
        commitPending()
        if (pos < history.size - 1) { pos++; text = history[pos] }
    }

    // --- draft: the text is kept as it is typed, so a crash, a swipe-away or closing the app loses nothing ---
    val textNow by rememberUpdatedState(text)
    LaunchedEffect(text) {
        if (!simple) {
            delay(200)
            TypedDraft.setText(context, text)
        }
    }
    DisposableEffect(Unit) {
        if (!simple) TypedDraft.setOpen(context, true)
        onDispose { if (!simple) TypedDraft.setText(context, textNow) }
    }
    // Closing it on purpose: the text stays saved, but the window will not pop up by itself next time.
    val closeDialog = {
        if (!simple) TypedDraft.setOpen(context, false)
        onDismiss()
    }

    // --- saved texts (kept under a title for later) ---
    var showSave by remember { mutableStateOf(false) }
    var showSaved by remember { mutableStateOf(false) }
    // The saved text that is open in the box: Save then updates it instead of asking for a name again.
    var openedId by remember {
        mutableStateOf<Long?>(if (simple) store.sessionOpenedId(context)?.takeIf { id -> store.load(context).any { it.id == id } } else null)
    }
    // Notes: remember the box and the open saved text, so reopening continues where it was left (no searching and inserting again).
    val openedNow by rememberUpdatedState(openedId)
    LaunchedEffect(text, openedId) {
        if (simple) {
            delay(150)
            store.saveSession(context, text, openedId)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (simple) store.saveSession(context, textNow, openedNow) }
    }
    // The plain (notes) version can add its text to 1.txt on the user's server.
    var serverStatus by remember { mutableStateOf<String?>(null) }
    var serverBusy by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var savedTexts by remember { mutableStateOf(store.load(context)) }

    // --- voice tools ---
    var showVoice by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf(TranscribeSettings.getEngine(context)) }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var keyInput by remember { mutableStateOf("") }
    var keyVersion by remember { mutableStateOf(0) }
    // Set when Stop was pressed here, so the next finished recording is the one to transcribe.
    var waitingForRecording by remember { mutableStateOf(false) }

    val active by RecordingStatus.active.collectAsState()
    val finished by RecordingStatus.lastFinished.collectAsState()
    val recordingHere = active?.type == com.mobicareapp.data.MediaType.AUDIO && active?.source == RecordingSource.MIC
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(recordingHere) {
        while (recordingHere) {
            elapsed = (System.currentTimeMillis() - (active?.startedAtMillis ?: System.currentTimeMillis())) / 1000
            delay(500)
        }
    }

    fun keyFor(e: TranscribeSettings.Engine): String? = when (e) {
        TranscribeSettings.Engine.GEMINI -> QuestionPaperSettings.getApiKey(context)
        TranscribeSettings.Engine.GROQ -> TranscribeSettings.getGroqKey(context)
        TranscribeSettings.Engine.SARVAM -> TranscribeSettings.getSarvamKey(context)
        TranscribeSettings.Engine.ASSEMBLYAI -> TranscribeSettings.getApiKey(context)
    }
    val hasKey = remember(engine, keyVersion) { keyFor(engine) != null }

    // Transcribes [file] with the chosen engine and adds the words to the box.
    fun transcribe(file: File) {
        voiceError = null
        voiceStatus = "Transcribing…"
        // Remembered until its text is in the box, so a kill in between is picked up again next time.
        if (!simple) TypedDraft.setPendingVoice(context, file.absolutePath)
        scope.launch {
            val result = TranscriptionRouter.transcribe(context, file, "en") { status -> voiceStatus = status }
            voiceStatus = null
            result
                .onSuccess { heard ->
                    if (heard.isBlank()) voiceError = "No speech was recognized"
                    else {
                        text = if (text.isBlank()) heard.trim() else text.trimEnd() + " " + heard.trim()
                        if (!simple) TypedDraft.setText(context, text)
                    }
                }
                .onFailure { e -> voiceError = e.message ?: "Couldn't transcribe that" }
            if (!simple) TypedDraft.setPendingVoice(context, null)
        }
    }

    // The recording that was just stopped here has been saved: transcribe it.
    LaunchedEffect(finished) {
        val done = finished
        if (waitingForRecording && done != null && done.type == com.mobicareapp.data.MediaType.AUDIO) {
            waitingForRecording = false
            transcribe(File(done.filePath))
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) AudioRecordService.start(context)
        else voiceError = "Microphone permission is needed to record"
    }
    fun startRecording() {
        voiceError = null
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            AudioRecordService.start(context)
        } else {
            AppLockManager.suppressNextLock()
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Opening the voice tools starts recording at once, so it can't be forgotten (the engine needs its key first).
    // If the recorder has not reported back after a moment, it is asked once more (a second start is ignored when it is already running).
    LaunchedEffect(showVoice) {
        if (showVoice && !recordingHere && voiceStatus == null && hasKey) {
            RecordingStatus.errorShown()
            startRecording()
            delay(2000)
            if (RecordingStatus.active.value == null && RecordingStatus.error.value == null) startRecording()
        }
    }
    // A recording that was being transcribed when the app was closed carries on now.
    LaunchedEffect(Unit) {
        if (!simple) {
            val path = TypedDraft.pendingVoice(context)
            if (path != null) {
                val file = File(path)
                if (file.exists() && hasKey) {
                    showVoice = true
                    transcribe(file)
                } else {
                    TypedDraft.setPendingVoice(context, null)
                }
            }
        }
    }
    val recorderError by RecordingStatus.error.collectAsState()

    if (showPicker) {
        // Recordings already in the Library, or any audio file on the phone.
        TranscribePickerDialog(
            onDismiss = { showPicker = false },
            onPick = { file -> showPicker = false; transcribe(file) }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete 1.txt on the server?") },
            text = { Text("Everything uploaded to 1.txt so far will be removed. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    serverBusy = true
                    serverStatus = "Deleting…"
                    scope.launch {
                        PersonalServerUploader.deleteRemoteFile(context, "1.txt")
                            .onSuccess { serverStatus = "1.txt deleted from the server" }
                            .onFailure { e -> serverStatus = e.message ?: "Couldn't delete" }
                        serverBusy = false
                    }
                }) { Text("Yes, delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("No") } }
        )
    }
    if (showSave) {
        SaveSnippetDialog(
            onSave = { title ->
                showSave = false
                savedTexts = store.add(context, title, text)
                // The new copy is the one open now.
                openedId = savedTexts.firstOrNull()?.id
                Toast.makeText(context, "Saved as \"" + title.trim() + "\"", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showSave = false }
        )
    }
    if (showSaved) {
        SavedTextsDialog(
            items = savedTexts,
            // Tapping one opens it in the box (what was there stays in Undo); Save then updates it.
            onUse = { chosen ->
                showSaved = false
                commitPending()
                text = chosen.text
                openedId = chosen.id
            },
            // Adds it to the end of what is in the box instead of opening it.
            onAppend = { chosen ->
                showSaved = false
                text = if (text.isBlank()) chosen.text else text.trimEnd() + "\n" + chosen.text
            },
            onDelete = {
                savedTexts = store.delete(context, it.id)
                if (openedId == it.id) openedId = null
            },
            onDismiss = { showSaved = false }
        )
    }

    Dialog(onDismissRequest = closeDialog, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (simple) "Notes" else "Type text", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    // Opening the voice tools starts recording at once, so it can't be forgotten (needs the engine's key first).
                    if (!simple) OutlinedButton(onClick = { showVoice = !showVoice }) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (showVoice) "Hide voice" else "Voice", style = MaterialTheme.typography.titleMedium)
                    }
                }

                // Undo / redo, copy to the clipboard, and keeping the text under a title for later.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { undo() }, enabled = pos > 0 || text != history[pos]) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { redo() }, enabled = pos < history.size - 1) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo")
                    }
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Typed text", text))
                            Toast.makeText(context, "Copied to the clipboard", Toast.LENGTH_SHORT).show()
                        }
                    ) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy") }
                    // Save: updates the saved text that is open; if none is open, asks for a title. Save as: always a new copy under a new title.
                    IconButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            val openId = openedId
                            val openTitle = savedTexts.firstOrNull { it.id == openId }?.title
                            if (openId != null && openTitle != null) {
                                savedTexts = store.update(context, openId, text)
                                Toast.makeText(context, "Updated \"$openTitle\"", Toast.LENGTH_SHORT).show()
                            } else {
                                showSave = true
                            }
                        }
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save (updates the open saved text)")
                    }
                    IconButton(enabled = text.isNotBlank(), onClick = { showSave = true }) {
                        Icon(Icons.Default.SaveAs, contentDescription = "Save as a new copy")
                    }
                    IconButton(onClick = { showSaved = true }) {
                        Icon(Icons.Default.Bookmarks, contentDescription = "Saved texts")
                    }
                    // Empties the box in one tap (Undo brings it back).
                    TextButton(enabled = text.isNotBlank(), onClick = { commitPending(); text = ""; openedId = null }) {
                        Text("Clear", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.weight(1f))
                }
                savedTexts.firstOrNull { it.id == openedId }?.let { open ->
                    Text(
                        "Open: \"${open.title}\". Save updates it; Save as makes a new copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Notes only: add the text to the end of 1.txt on the server, clear that file, or copy its address.
                if (simple) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = text.isNotBlank() && !serverBusy,
                            onClick = {
                                serverBusy = true
                                serverStatus = "Uploading…"
                                scope.launch {
                                    PersonalServerUploader.appendText(context, text, "1.txt")
                                        .onSuccess { serverStatus = "Added to the end of 1.txt on the server" }
                                        .onFailure { e -> serverStatus = e.message ?: "Upload failed" }
                                    serverBusy = false
                                }
                            }
                        ) {
                            Icon(Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Upload")
                        }
                        OutlinedButton(enabled = !serverBusy, onClick = { confirmClear = true }) { Text("Clear") }
                        IconButton(onClick = {
                            val link = PersonalServerUploader.remoteFileUrl(context, "1.txt")
                            if (link == null) {
                                serverStatus = "Set up your server first (Record, Copy images/videos)"
                            } else {
                                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("1.txt address", link))
                                serverStatus = "Copied: $link"
                            }
                        }) { Icon(Icons.Default.Link, contentDescription = "Copy the 1.txt address") }
                    }
                    serverStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }

                // The box takes all the room that is left, so a long text can be typed and read.
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = when {
                            size <= 0.05f -> 24.sp
                            size <= 0.08f -> 32.sp
                            else -> 42.sp
                        }
                    ),
                    label = { Text("Text") },
                    modifier = Modifier.weight(1f).fillMaxWidth().focusRequester(focus)
                )

                if (showVoice && !simple) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (recordingHere) {
                                    Button(
                                        onClick = {
                                            waitingForRecording = true
                                            voiceStatus = "Saving the recording…"
                                            AudioRecordService.stop(context)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Stop  %d:%02d".format(elapsed / 60, elapsed % 60), style = MaterialTheme.typography.titleMedium)
                                    }
                                } else {
                                    Button(onClick = { startRecording() }, enabled = voiceStatus == null && hasKey) {
                                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Record", style = MaterialTheme.typography.titleMedium)
                                    }
                                    OutlinedButton(onClick = { showPicker = true }, enabled = voiceStatus == null && hasKey) {
                                        Text("Pick a recorded voice", style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                            Text("Transcribe with", style = MaterialTheme.typography.labelLarge)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(
                                    "Gemini" to TranscribeSettings.Engine.GEMINI,
                                    "Groq" to TranscribeSettings.Engine.GROQ,
                                    "Sarvam" to TranscribeSettings.Engine.SARVAM,
                                    "AssemblyAI" to TranscribeSettings.Engine.ASSEMBLYAI
                                ).forEach { (label, value) ->
                                    FilterChip(
                                        selected = engine == value,
                                        onClick = { engine = value; TranscribeSettings.setEngine(context, value); keyInput = "" },
                                        label = { Text(label, style = MaterialTheme.typography.titleMedium) }
                                    )
                                }
                            }
                            // The chosen engine has no key yet: ask for it right here.
                            if (!hasKey) {
                                OutlinedTextField(
                                    value = keyInput,
                                    onValueChange = { keyInput = it },
                                    singleLine = true,
                                    label = { Text("API key for this engine") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                TextButton(
                                    enabled = keyInput.isNotBlank(),
                                    onClick = {
                                        when (engine) {
                                            TranscribeSettings.Engine.GEMINI -> QuestionPaperSettings.setApiKey(context, keyInput)
                                            TranscribeSettings.Engine.GROQ -> TranscribeSettings.setGroqKey(context, keyInput)
                                            TranscribeSettings.Engine.SARVAM -> TranscribeSettings.setSarvamKey(context, keyInput)
                                            TranscribeSettings.Engine.ASSEMBLYAI -> TranscribeSettings.setApiKey(context, keyInput)
                                        }
                                        keyInput = ""
                                        keyVersion++
                                    }
                                ) { Text("Save key", style = MaterialTheme.typography.titleMedium) }
                            }
                            if (recordingHere) {
                                Text("Recording… speak now, then press Stop", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                            }
                            // Why the recorder could not start (the microphone in use by a call, a permission...), instead of failing silently.
                            recorderError?.let { Text("Recorder: $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                            voiceStatus?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                            voiceError?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                            Text(
                                "A recording made here is saved in the Library too.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (!simple) {
                    Text("Font size on the page", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Normal" to 0.045f, "Big" to 0.075f, "Extra big" to 0.11f).forEach { (label, value) ->
                            FilterChip(selected = size == value, onClick = { size = value }, label = { Text(label, style = MaterialTheme.typography.titleMedium) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = closeDialog) { Text(if (simple) "Close" else "Cancel", style = MaterialTheme.typography.titleMedium) }
                    if (!simple) TextButton(
                        enabled = text.isNotBlank() && !recordingHere,
                        onClick = {
                            // The text stays in the box (and saved) after Insert: only the Clear button empties it.
                            TypedDraft.setText(context, text)
                            TypedDraft.setOpen(context, false)
                            onInsert(text, size)
                        }
                    ) {
                        Text("Insert", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

/** The texts saved from the Type text window: search by title, tap one to put it in the box, or delete it. */
@Composable
private fun SavedTextsDialog(
    items: List<SavedText>,
    onUse: (SavedText) -> Unit,
    onAppend: (SavedText) -> Unit,
    onDelete: (SavedText) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<SavedText?>(null) }
    val matches = items.filter { it.title.contains(query.trim(), ignoreCase = true) || it.text.contains(query.trim(), ignoreCase = true) }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this saved text?") },
            text = { Text("\"${item.title}\" will be removed. This can't be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(item); pendingDelete = null }) { Text("Yes, delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("No") } }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Saved texts") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Tap one to open it in the text box (Save then updates it). \"Add\" puts it at the end of what is already there.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (items.isEmpty()) {
                    Text("Nothing saved yet. Type something, then tap the save icon and give it a title.", style = MaterialTheme.typography.bodyMedium)
                } else if (matches.isEmpty()) {
                    Text("Nothing matches that.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                        items(matches, key = { it.id }) { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clickable { onUse(item) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        item.text.replace('\n', ' '),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(onClick = { onAppend(item) }) { Text("Add") }
                                IconButton(onClick = { pendingDelete = item }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
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

/**
 * Plain paper that records strokes (one finger or stylus) and draws them as smooth curves.
 *
 * The page always uses the pad's full width, and is [pageRatio] times as tall. Two fingers placed
 * together zoom and move the page (pinch to zoom in and out, drag to move), like the Mark up image
 * screen; one finger writes. At 1x with a window shorter than the page, moving two fingers up or
 * down scrolls it. A thin bar at the right edge shows where you are on a page that doesn't fit.
 *
 * Everything recorded is in page coordinates (pixels at the pad's width at 1x). Zoom ([zoom]) and
 * the shift ([pan], in screen pixels) only change how the page is drawn and how touches are mapped back onto it.
 */
@Composable
private fun HandwritingCanvas(
    items: List<PageItem>,
    image: ImageBitmap?,
    pageRatio: Float,
    zoom: Float,
    pan: Offset,
    onTransform: (Float, Offset) -> Unit,
    selecting: Boolean,
    onSelect: (Offset, Offset, IntSize) -> Unit,
    onStroke: (HwStroke) -> Unit,
    moveMode: Boolean,
    selected: Set<Int>,
    onPress: (Float, Float) -> Boolean,
    onMoveEnd: (Float, Float) -> Unit,
    onArea: (Float, Float, Float, Float) -> Unit,
    inkColor: Int,
    normalFont: Boolean,
    modifier: Modifier = Modifier
) {
    // The stroke being drawn, and the box being dragged while choosing an area to read (page coordinates).
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var box by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    val selectingNow by rememberUpdatedState(selecting)
    // How far the picked writing has been dragged so far, in page widths (it is only saved when the finger lifts).
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val moveModeNow by rememberUpdatedState(moveMode)
    val onPressNow by rememberUpdatedState(onPress)
    val onMoveEndNow by rememberUpdatedState(onMoveEnd)
    val onAreaNow by rememberUpdatedState(onArea)
    val zoomNow by rememberUpdatedState(zoom)
    val panNow by rememberUpdatedState(pan)
    val ratioNow by rememberUpdatedState(pageRatio)
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .clipToBounds()
            .background(Color(0xFFE6E3D8))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val pageHeight = width * ratioNow
                    // Where a touch is on the page (not on the screen): undo the pan and zoom, keep it inside the page.
                    fun pagePoint(p: Offset): Offset = Offset(
                        ((p.x + panNow.x) / zoomNow).coerceIn(0f, width),
                        ((p.y + panNow.y) / zoomNow).coerceIn(0f, pageHeight)
                    )

                    if (selectingNow) {
                        val start = pagePoint(down.position)
                        var end = start
                        box = start to end
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            end = pagePoint(change.position)
                            box = start to end
                            change.consume()
                            if (!change.pressed) break
                        }
                        box = null
                        onSelect(start, end, IntSize(width.toInt(), pageHeight.toInt()))
                        return@awaitEachGesture
                    }

                    if (moveModeNow) {
                        val start = pagePoint(down.position)
                        down.consume()
                        var twoFingersDown = false
                        if (onPressNow(start.x / width, start.y / width)) {
                            // On picked writing: drag it. A second finger cancels the drag and zooms instead.
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    twoFingersDown = true
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    change.consume()
                                    break
                                }
                                val now = pagePoint(change.position)
                                dragOffset = Offset((now.x - start.x) / width, (now.y - start.y) / width)
                                change.consume()
                            }
                            val moved = dragOffset
                            dragOffset = Offset.Zero
                            if (twoFingersDown) {
                                zoomMoveLoop(zoomNow, panNow, width, pageHeight, onTransform)
                            } else if (kotlin.math.abs(moved.x) > 0.002f || kotlin.math.abs(moved.y) > 0.002f) {
                                onMoveEndNow(moved.x, moved.y)
                            }
                        } else {
                            // On empty paper: drag a box round writing to pick it (a plain tap picks nothing).
                            var end = start
                            box = start to end
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.count { it.pressed } >= 2) {
                                    twoFingersDown = true
                                    event.changes.forEach { it.consume() }
                                    break
                                }
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                end = pagePoint(change.position)
                                box = start to end
                                change.consume()
                                if (!change.pressed) break
                            }
                            box = null
                            if (twoFingersDown) {
                                zoomMoveLoop(zoomNow, panNow, width, pageHeight, onTransform)
                            } else {
                                onAreaNow(minOf(start.x, end.x) / width, minOf(start.y, end.y) / width, maxOf(start.x, end.x) / width, maxOf(start.y, end.y) / width)
                            }
                        }
                        return@awaitEachGesture
                    }

                    val first = pagePoint(down.position)
                    val points = mutableListOf(InkPt(first.x / width, first.y / width, down.uptimeMillis))
                    live = listOf(first)
                    down.consume()
                    var twoFingers = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        // A second finger that lands together with the first (within a moment) means "zoom/move the page",
                        // not "write". A second touch that comes later (a resting palm) is ignored.
                        if (pressed.size >= 2 && event.changes.maxOf { it.uptimeMillis } - down.uptimeMillis < TWO_FINGER_MS) {
                            twoFingers = true
                            event.changes.forEach { it.consume() }
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            break
                        }
                        val inside = pagePoint(change.position)
                        points += InkPt(inside.x / width, inside.y / width, change.uptimeMillis)
                        live = live + inside
                        change.consume()
                    }
                    live = emptyList()

                    if (twoFingers) {
                        // Zooming/moving: the stroke that had started is dropped.
                        zoomMoveLoop(zoomNow, panNow, width, pageHeight, onTransform)
                        return@awaitEachGesture
                    }

                    // A tap becomes a dot (a full stop, the dot of an "i"): two identical points so it has a direction.
                    if (points.size == 1) points += points.first().copy(t = points.first().t + 1)
                    onStroke(HwStroke(points))
                }
            }
    ) {
        val pageHeight = size.width * pageRatio
        val z = zoom.coerceIn(1f, MAX_ZOOM)
        val shift = clampPan(pan, z, size.width, pageHeight, size.height)

        withTransform({
            translate(-shift.x, -shift.y)
            scale(z, z, Offset.Zero)
        }) {
            // The paper (the page's own height, which may be more or less than the room).
            drawRect(PaperColor, size = androidx.compose.ui.geometry.Size(size.width, pageHeight))

            // An inserted photo: scaled to fit the whole page (nothing cropped), centred, behind the writing.
            if (image != null) {
                val scale = minOf(size.width / image.width, pageHeight / image.height)
                val w = (image.width * scale).toInt()
                val h = (image.height * scale).toInt()
                drawImage(
                    image,
                    dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((pageHeight - h) / 2f).toInt()),
                    dstSize = IntSize(w, h)
                )
            }

            val inkWidth = size.width * INK_WIDTH
            val drag = Offset(dragOffset.x * size.width, dragOffset.y * size.width)
            val liveColor = if (inkColor != 0) Color(inkColor) else InkColor
            items.forEachIndexed { i, item ->
                val moving = drag != Offset.Zero && i in selected
                val color = if (item.color != 0) Color(item.color) else InkColor
                withTransform({ if (moving) translate(drag.x, drag.y) }) {
                    when (item) {
                        is InkItem -> drawInk(item.stroke.points.map { Offset(it.x * size.width, it.y * size.width) }, inkWidth, color)
                        // Text (a word completed from a suggestion, typed or transcribed text), in the chosen font.
                        is WordItem -> {
                            val layout = textMeasurer.measure(
                                item.text,
                                TextStyle(
                                    fontSize = (item.size * size.width * (if (normalFont) 0.95f else 1.15f)).toSp(),
                                    fontFamily = if (normalFont) FontFamily.SansSerif else FontFamily.Cursive,
                                    color = color
                                )
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
            }
            drawInk(live, inkWidth, liveColor)

            // Picked writing: a dashed box round it (it follows the finger while it is dragged).
            if (moveMode) {
                val picked = selected.filter { it in items.indices }.map { ItemGeometry.bounds(items[it]) }
                if (picked.isNotEmpty()) {
                    val pad = size.width * 0.008f
                    val left = picked.minOf { it[0] } * size.width + drag.x - pad
                    val top = picked.minOf { it[1] } * size.width + drag.y - pad
                    val right = picked.maxOf { it[2] } * size.width + drag.x + pad
                    val bottom = picked.maxOf { it[3] } * size.width + drag.y + pad
                    val boxSize = androidx.compose.ui.geometry.Size(right - left, bottom - top)
                    drawRect(Color(0x221976D2), topLeft = Offset(left, top), size = boxSize)
                    drawRect(
                        Color(0xFF1976D2),
                        topLeft = Offset(left, top),
                        size = boxSize,
                        style = Stroke(width = 3f / z, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f / z, 10f / z)))
                    )
                }
            }

            box?.let { (a, b) ->
                val topLeft = Offset(minOf(a.x, b.x), minOf(a.y, b.y))
                val boxSize = androidx.compose.ui.geometry.Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y))
                drawRect(Color(0x331976D2), topLeft = topLeft, size = boxSize)
                drawRect(Color(0xFF1976D2), topLeft = topLeft, size = boxSize, style = Stroke(width = 3f / z))
            }
        }

        // The bar at the right edge: shows the page is taller than the room, and which part is showing.
        val maxShiftY = pageHeight * z - size.height
        if (maxShiftY > 0f) {
            val barHeight = (size.height * size.height / (pageHeight * z)).coerceAtLeast(48f)
            val barTop = (shift.y / maxShiftY) * (size.height - barHeight)
            drawRoundRect(
                Color(0x66455A64),
                topLeft = Offset(size.width - 10f, barTop),
                size = androidx.compose.ui.geometry.Size(6f, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
            )
        }
    }
}

/** Two fingers: the point of the page under them stays under them while zooming, and their movement pans the page. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.zoomMoveLoop(
    startZoom: Float,
    startPan: Offset,
    width: Float,
    pageHeight: Float,
    onTransform: (Float, Offset) -> Unit
) {
    var z = startZoom
    var p = startPan
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) break
        if (event.changes.count { it.pressed } >= 2) {
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            val centroid = event.calculateCentroid(useCurrent = true)
            if (centroid.isSpecified) {
                val newZoom = (z * zoomChange).coerceIn(1f, MAX_ZOOM)
                val pagePointUnderFingers = ((centroid - panChange) + p) / z
                p = clampPan(pagePointUnderFingers * newZoom - centroid, newZoom, width, pageHeight, size.height.toFloat())
                z = newZoom
                onTransform(z, p)
            }
        }
        event.changes.forEach { it.consume() }
    }
}

/** A row of colour dots to choose from; the current one has a thick ring. */
@Composable
private fun ColourRow(current: Int, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        HandwritingStyle.PALETTE.forEach { (name, value) ->
            val chosen = current == value
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (value == 0) InkColor else Color(value))
                    .border(if (chosen) 4.dp else 1.dp, if (chosen) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                    .clickable(onClickLabel = name) { onPick(value) }
            )
        }
    }
}

/** The most the page can be zoomed in. */
internal const val MAX_ZOOM = 6f

/** Keeps the zoomed page covering the room: no gap shows beside or above/below it. [pan] is the page's shift in screen pixels. */
internal fun clampPan(pan: Offset, zoom: Float, width: Float, pageHeight: Float, viewportHeight: Float): Offset = Offset(
    pan.x.coerceIn(0f, maxOf(0f, width * zoom - width)),
    pan.y.coerceIn(0f, maxOf(0f, pageHeight * zoom - viewportHeight))
)

/** Two touches landing within this many milliseconds of each other are one two-finger gesture (zoom/move), not writing plus a stray touch. */
private const val TWO_FINGER_MS = 120L

/** Draws a stroke as one smooth curve: each touch point becomes a control point and the curve passes through the midpoints between them, which rounds off the jitter of a finger. */
private fun DrawScope.drawInk(points: List<Offset>, width: Float, color: Color = InkColor) {
    if (points.isEmpty()) return
    if (points.size == 1) {
        drawCircle(color, radius = width / 2f, center = points[0])
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
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Handwriting text", text))
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}
