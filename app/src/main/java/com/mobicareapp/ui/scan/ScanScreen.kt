package com.mobicareapp.ui.scan

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.data.ScannedDocument
import com.mobicareapp.scan.ScanFileStore
import com.mobicareapp.handwriting.HandwritingStartup
import com.mobicareapp.ui.handwriting.HandwritingScreen
import com.mobicareapp.ui.upload.UploadLinkScreen
import com.mobicareapp.wifisend.WifiSendDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val combining by viewModel.combining.collectAsState()
    val scope = rememberCoroutineScope()
    var viewingDocument by remember { mutableStateOf<ScannedDocument?>(null) }
    var combiningDocument by remember { mutableStateOf<ScannedDocument?>(null) }
    var wifiSendFile by remember { mutableStateOf<File?>(null) }
    var showPassportPhoto by remember { mutableStateOf(false) }
    var showCollage by remember { mutableStateOf(false) }
    var showPdfTextExtract by remember { mutableStateOf(false) }
    var showImageMarkup by remember { mutableStateOf(false) }
    var showQuestionPaper by remember { mutableStateOf(false) }
    var showUploadLink by remember { mutableStateOf(false) }
    var showHandwriting by remember { mutableStateOf(false) }
    // Launched from a fresh app start: open the handwriting notepad straight away (once).
    LaunchedEffect(Unit) { if (HandwritingStartup.consume()) showHandwriting = true }

    // Rendered unconditionally (rather than inside one of the branches below) so it stays on top
    // as an overlay no matter which screen — the document list or a single document's pages — is
    // showing underneath when "Send over Wi-Fi" is tapped.
    wifiSendFile?.let { file ->
        WifiSendDialog(file = file, onDismiss = { wifiSendFile = null })
    }

    // "Save to…" opens Android's own file picker, which already lists a connected USB/OTG pen
    // drive as a destination (Storage Access Framework handles that automatically) — no
    // OTG-specific code needed here, just handing the picked location a copy of the file.
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        val file = pendingExportFile
        pendingExportFile = null
        if (uri != null && file != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            file.inputStream().use { it.copyTo(out) }
                        } ?: error("Couldn't write to the chosen location")
                    }
                }.isSuccess
                Toast.makeText(context, if (ok) "Saved" else "Couldn't save the file", Toast.LENGTH_SHORT).show()
            }
        }
    }
    fun exportFile(file: File) {
        pendingExportFile = file
        AppLockManager.suppressNextLock()
        exportLauncher.launch(file.name)
    }

    if (showPassportPhoto) {
        PassportPhotoScreen(onBack = { showPassportPhoto = false })
        return
    }

    if (showCollage) {
        CollageScreen(onBack = { showCollage = false })
        return
    }

    if (showImageMarkup) {
        ImageMarkupScreen(onBack = { showImageMarkup = false })
        return
    }

    if (showPdfTextExtract) {
        PdfTextExtractScreen(onBack = { showPdfTextExtract = false })
        return
    }

    if (showQuestionPaper) {
        QuestionPaperScreen(onBack = { showQuestionPaper = false })
        return
    }

    if (showUploadLink) {
        UploadLinkScreen(onBack = { showUploadLink = false })
        return
    }

    if (showHandwriting) {
        HandwritingScreen(onBack = { showHandwriting = false })
        return
    }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult -> viewModel.onScanResult(result) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> viewModel.addGalleryPages(uris) }
    val launchGalleryPicker = {
        // The Photos picker briefly backgrounds this app too — same as the document scanner
        // above, this must be called before launch or the app-lock kicks in during the round
        // trip and tears down this whole screen's state before the picked photos come back.
        AppLockManager.suppressNextLock()
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val pagesInReview = reviewPages
    if (pagesInReview != null) {
        ScanReviewScreen(
            pages = pagesInReview,
            saving = saving,
            onRotate = viewModel::rotatePage,
            onAddFromGallery = launchGalleryPicker,
            onCancel = viewModel::cancelReview,
            onSave = viewModel::confirmReview
        )
        return
    }

    combiningDocument?.let { document ->
        CombinePagesScreen(
            document = document,
            saving = combining,
            onBack = { combiningDocument = null },
            onSave = { selectedPaths, sizeFraction, gapMm ->
                viewModel.combinePages(selectedPaths, sizeFraction, gapMm) {
                    combiningDocument = null
                }
            }
        )
        return
    }

    viewingDocument?.let { document ->
        ScanPagesScreen(
            document = document,
            onBack = { viewingDocument = null },
            onSharePage = { pagePath -> sharePage(context, pagePath) },
            onShareAll = { sharePages(context, document.pagePaths) },
            onSendOverWifi = { wifiSendFile = File(document.filePath) },
            onExport = { exportFile(File(document.filePath)) },
            onCombine = { combiningDocument = document }
        )
        return
    }

    // The other tools (passport photo, collage, markup, PDF text extract) used to sit as their
    // own icons/buttons directly in the top bar alongside "New scan" and "Add from gallery" — as
    // more of them got added over time, the row grew wider than a phone screen, and a TopAppBar's
    // actions row doesn't wrap or scroll: it just silently overflows past the right edge, which is
    // exactly what pushed "New scan" itself off-screen and made this core feature look like it had
    // disappeared. Folding the less-used tools into one "More tools" overflow menu keeps the two
    // most important actions (New scan, Add from gallery) always visible regardless of screen width.
    var showMoreTools by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scans") },
                actions = {
                    IconButton(onClick = launchGalleryPicker) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Add pages from gallery")
                    }
                    Box {
                        IconButton(onClick = { showMoreTools = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More tools")
                        }
                        DropdownMenu(expanded = showMoreTools, onDismissRequest = { showMoreTools = false }) {
                            DropdownMenuItem(
                                text = { Text("Passport photo") },
                                onClick = { showMoreTools = false; showPassportPhoto = true },
                                leadingIcon = { Icon(Icons.Default.Portrait, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Combine photos into a grid") },
                                onClick = { showMoreTools = false; showCollage = true },
                                leadingIcon = { Icon(Icons.Default.GridView, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Mark up an image") },
                                onClick = { showMoreTools = false; showImageMarkup = true },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Extract text from PDF") },
                                onClick = { showMoreTools = false; showPdfTextExtract = true },
                                leadingIcon = { Icon(Icons.Default.UploadFile, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Make question paper") },
                                onClick = { showMoreTools = false; showQuestionPaper = true },
                                leadingIcon = { Icon(Icons.Default.Quiz, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Upload file & get link") },
                                onClick = { showMoreTools = false; showUploadLink = true },
                                leadingIcon = { Icon(Icons.Default.CloudUpload, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text("Handwriting to text") },
                                onClick = { showMoreTools = false; showHandwriting = true },
                                leadingIcon = { Icon(Icons.Default.Draw, contentDescription = null) }
                            )
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val intentSender: IntentSender = viewModel.startScanIntentSender(activity)
                                // The scanner's own Activity briefly takes over the foreground —
                                // without this, the app-lock treats that hand-off as backgrounding
                                // and re-locks mid-scan, which drops the result on unlock.
                                AppLockManager.suppressNextLock()
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
                        onSendOverWifi = { wifiSendFile = File(document.filePath) },
                        onExport = { exportFile(File(document.filePath)) },
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
    onAddFromGallery: () -> Unit,
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
                    // Appends a gallery photo (e.g. an ID card's back, shot earlier) as an extra
                    // page onto what the scanner already captured, before this review is saved.
                    IconButton(onClick = onAddFromGallery, enabled = !saving) {
                        Icon(Icons.Default.AddPhotoAlternate, contentDescription = "Add page from gallery")
                    }
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
    onSharePage: (String) -> Unit,
    onShareAll: () -> Unit,
    onSendOverWifi: () -> Unit,
    onExport: () -> Unit,
    onCombine: () -> Unit
) {
    var fullScreenIndex by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(document.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (document.pagePaths.size >= 2) {
                        // For a multi-page scan like an ID card's front and back — combines the
                        // pages the user picks onto one A4 sheet instead of each printing full-
                        // screen on its own page.
                        TextButton(onClick = onCombine) {
                            Icon(Icons.Default.MergeType, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Combine")
                        }
                    }
                    IconButton(onClick = onSendOverWifi) {
                        Icon(Icons.Default.WifiTethering, contentDescription = "Send over Wi-Fi")
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.Download, contentDescription = "Save to…")
                    }
                    TextButton(onClick = onShareAll) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share all")
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
                            .clickable { fullScreenIndex = index }
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

    fullScreenIndex?.let { startIndex ->
        FullScreenPageViewer(
            pagePaths = document.pagePaths,
            startIndex = startIndex,
            onDismiss = { fullScreenIndex = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CombinePagesScreen(
    document: ScannedDocument,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: (List<String>, Float, Float) -> Unit
) {
    // Pre-select every page — the common case (an ID card's front + back) wants both combined
    // by default, and the user can deselect pages they don't want on the merged sheet.
    val selected = remember { mutableStateListOf(*document.pagePaths.map { true }.toTypedArray()) }
    var sizeFraction by remember { mutableStateOf(0.85f) }
    var gapMm by remember { mutableStateOf(8f) }
    val selectedCount = selected.count { it }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Combine to one page") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val chosen = document.pagePaths.filterIndexed { index, _ -> selected[index] }
                            onSave(chosen, sizeFraction, gapMm)
                        },
                        enabled = !saving && selectedCount >= 2,
                        modifier = Modifier.padding(end = 12.dp)
                    ) {
                        Text(if (saving) "Saving…" else "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "Select pages to stack onto one A4 portrait page",
                style = MaterialTheme.typography.titleMedium
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                document.pagePaths.forEachIndexed { index, pagePath ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { selected[index] = !selected[index] }
                    ) {
                        Box(
                            modifier = Modifier
                                .size(72.dp, 96.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(
                                    width = if (selected[index]) 2.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(6.dp)
                                )
                        ) {
                            AsyncImage(
                                model = File(pagePath),
                                contentDescription = "Page ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Checkbox(checked = selected[index], onCheckedChange = { selected[index] = it })
                    }
                }
            }

            Column {
                Text("Image size: ${(sizeFraction * 100).toInt()}%")
                Slider(value = sizeFraction, onValueChange = { sizeFraction = it }, valueRange = 0.4f..1f)
            }
            Column {
                Text("Gap between images: ${gapMm.toInt()} mm")
                Slider(value = gapMm, onValueChange = { gapMm = it }, valueRange = 0f..40f)
            }

            Text("Preview", style = MaterialTheme.typography.labelLarge)
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .aspectRatio(595f / 842f)
                    .background(Color.White)
                    .border(1.dp, MaterialTheme.colorScheme.outline),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy((gapMm / 2.5f).dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                ) {
                    document.pagePaths.forEachIndexed { index, pagePath ->
                        if (selected[index]) {
                            AsyncImage(
                                model = File(pagePath),
                                contentDescription = null,
                                contentScale = ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth(sizeFraction)
                            )
                        }
                    }
                }
            }

            if (selectedCount < 2) {
                Text(
                    "Select at least 2 pages to combine",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FullScreenPageViewer(pagePaths: List<String>, startIndex: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            val pagerState = rememberPagerState(initialPage = startIndex) { pagePaths.size }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = File(pagePaths[page]),
                    contentDescription = "Page ${page + 1}",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.padding(4.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            Text(
                "${pagerState.currentPage + 1} / ${pagePaths.size}",
                color = Color.White,
                modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
            )
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
    onSendOverWifi: () -> Unit,
    onExport: () -> Unit,
    onViewPages: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
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
        IconButton(onClick = onShare) {
            Icon(Icons.Default.Share, contentDescription = "Share")
        }
        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("View/share pages") },
                    onClick = { menuExpanded = false; onViewPages() },
                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Send over Wi-Fi") },
                    onClick = { menuExpanded = false; onSendOverWifi() },
                    leadingIcon = { Icon(Icons.Default.WifiTethering, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Save to…") },
                    onClick = { menuExpanded = false; onExport() },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuExpanded = false; onDelete() },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                )
            }
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

private fun sharePages(context: android.content.Context, pagePaths: List<String>) {
    val uris = ArrayList(pagePaths.map { ScanFileStore.shareUri(context, File(it)) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/jpeg"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Share pages"))
}
