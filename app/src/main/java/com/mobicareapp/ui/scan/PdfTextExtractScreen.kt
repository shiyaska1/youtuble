package com.mobicareapp.ui.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.textextract.DetectedLanguage
import com.mobicareapp.textextract.NativeTextResult
import com.mobicareapp.textextract.PageExtraction
import com.mobicareapp.textextract.PdfNativeTextExtractor
import com.mobicareapp.textextract.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Picks a PDF, takes/picks a photo, and runs on-device OCR to split the text into one editable
 * box per detected paragraph. English, Hindi, and Malayalam — see [PdfTextExtractor]'s doc comment
 * for why Arabic still isn't available on-device. For a photo (not a PDF page), an optional
 * crop-rectangle step lets a specific region be scanned instead of the whole image.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfTextExtractScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var sourceUri by remember { mutableStateOf<Uri?>(null) }
    var isPdf by remember { mutableStateOf(true) }
    var pageCount by remember { mutableIntStateOf(0) }
    var pageNumber by remember { mutableIntStateOf(1) }
    var imagePreview by remember { mutableStateOf<Bitmap?>(null) }
    var cropLeft by remember { mutableFloatStateOf(0.05f) }
    var cropTop by remember { mutableFloatStateOf(0.05f) }
    var cropRight by remember { mutableFloatStateOf(0.95f) }
    var cropBottom by remember { mutableFloatStateOf(0.95f) }
    var pagePreview by remember { mutableStateOf<Bitmap?>(null) }
    var extraction by remember { mutableStateOf<PageExtraction?>(null) }
    var selectedLanguage by remember { mutableStateOf<DetectedLanguage?>(null) }
    // True when the page's own embedded PDF text was used verbatim instead of OCR — no language
    // to pick between in that case, since it's the document's exact text, not a script guess.
    var usedNativeText by remember { mutableStateOf(false) }
    // Set alongside usedNativeText when the direct-text result looks like it hit a broken font
    // mapping (see PdfNativeTextExtractor) — shown as a warning rather than silently switching to
    // OCR, since the user picked "Direct text" explicitly and should see what it actually found.
    var nativeUnreliable by remember { mutableStateOf(false) }
    val paragraphs = remember { mutableStateListOf<String>() }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    fun resetCrop() {
        cropLeft = 0.05f; cropTop = 0.05f; cropRight = 0.95f; cropBottom = 0.95f
    }

    fun pickSource(uri: Uri) {
        sourceUri = uri
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        isPdf = mimeType == "application/pdf"
        pageNumber = 1
        imagePreview = null
        pagePreview = null
        extraction = null
        usedNativeText = false
        nativeUnreliable = false
        paragraphs.clear()
        error = null
        resetCrop()
        if (isPdf) {
            runCatching {
                PdfTextExtractor.openPdf(context, uri).use { renderer -> pageCount = renderer.pageCount }
            }.onFailure { e -> error = e.message ?: "Couldn't open that PDF" }
        } else {
            pageCount = 1
            processing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { PdfTextExtractor.loadImage(context, uri) } }
                processing = false
                result
                    .onSuccess { imagePreview = it }
                    .onFailure { e -> error = e.message ?: "Couldn't read that image" }
            }
        }
    }

    // Renders a preview of whichever PDF page is currently selected as soon as it changes — not
    // just after the user taps to extract — so the crop/zoom selector below has something to show
    // and select an area on before any extraction happens.
    LaunchedEffect(sourceUri, isPdf, pageNumber, pageCount) {
        val uri = sourceUri ?: return@LaunchedEffect
        if (!isPdf || pageCount <= 0) return@LaunchedEffect
        val index = (pageNumber - 1).coerceIn(0, pageCount - 1)
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                PdfTextExtractor.openPdf(context, uri).use { renderer -> PdfTextExtractor.renderPage(renderer, index) }
            }.getOrNull()
        }
        pagePreview = bitmap
        resetCrop()
    }

    // suppressNextLock() must be called right before each launch below (not from the result
    // callback) — the picker/camera Activity briefly backgrounds this app, and calling it only
    // once the result comes back is too late: the app-lock has already fired by then, tearing
    // down this whole screen (and the sourceUri/pageCount/etc. state above) before the picked
    // file is even read.
    val pickPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pickSource(uri)
    }

    val pickGalleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) pickSource(uri) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        if (success && file != null) pickSource(Uri.fromFile(file))
    }

    fun launchPickPdf() {
        AppLockManager.suppressNextLock()
        pickPdfLauncher.launch(arrayOf("application/pdf"))
    }

    fun launchPickGallery() {
        AppLockManager.suppressNextLock()
        pickGalleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun launchCamera() {
        val (file, uri) = PdfTextExtractor.newCaptureTarget(context)
        pendingCameraFile = file
        AppLockManager.suppressNextLock()
        cameraLauncher.launch(uri)
    }

    /**
     * "Give me everything" — replaces the whole paragraph list with the entire page's text.
     * [useOcr] is an explicit choice, not a guess: true always renders the page and runs OCR on
     * it, false always reads the PDF's own embedded text (and shows exactly what that finds, even
     * if it looks questionable — see [nativeUnreliable]) — no automatic fallback between the two,
     * since that made it hard to tell which method actually produced a given result.
     */
    fun extractCurrentPage(useOcr: Boolean) {
        val uri = sourceUri ?: return
        processing = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (isPdf && !useOcr) {
                        val index = (pageNumber - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                        PdfNativeTextExtractor.extractParagraphs(context, uri, index) to null
                    } else {
                        val bitmap = if (isPdf) {
                            val index = (pageNumber - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                            pagePreview
                                ?: PdfTextExtractor.openPdf(context, uri).use { renderer -> PdfTextExtractor.renderPage(renderer, index) }
                        } else {
                            val full = imagePreview ?: PdfTextExtractor.loadImage(context, uri)
                            val needsCrop = cropLeft > 0.01f || cropTop > 0.01f || cropRight < 0.99f || cropBottom < 0.99f
                            if (needsCrop) PdfTextExtractor.crop(full, cropLeft, cropTop, cropRight, cropBottom) else full
                        }
                        null to PdfTextExtractor.extractText(context, bitmap)
                    }
                }
            }
            processing = false
            result
                .onSuccess { (nativeResult, pageExtraction) ->
                    paragraphs.clear()
                    if (nativeResult != null) {
                        usedNativeText = true
                        nativeUnreliable = !nativeResult.reliable
                        extraction = null
                        selectedLanguage = null
                        paragraphs.addAll(nativeResult.paragraphs)
                    } else if (pageExtraction != null) {
                        usedNativeText = false
                        nativeUnreliable = false
                        extraction = pageExtraction
                        selectedLanguage = pageExtraction.detected
                        paragraphs.addAll(pageExtraction.paragraphsFor(pageExtraction.detected))
                    }
                    if (paragraphs.isEmpty()) {
                        error = if (isPdf && !useOcr) "No embedded text found on this page — try OCR instead." else "No text found."
                    }
                }
                .onFailure { e -> error = e.message ?: "Couldn't read that" }
        }
    }

    /**
     * "Just this bit" — extracts only the hand-drawn rectangle and appends it as one more
     * paragraph, so a page can be worked through one table cell/line/paragraph at a time (zooming
     * in for precision on a dense page) instead of committing to the whole page's text at once.
     * Same explicit OCR-vs-direct choice as [extractCurrentPage], scoped to just the selection.
     */
    fun extractSelectedArea(useOcr: Boolean) {
        val uri = sourceUri ?: return
        processing = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (isPdf && !useOcr) {
                        val index = (pageNumber - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                        PdfNativeTextExtractor.extractTextInRegion(context, uri, index, cropLeft, cropTop, cropRight, cropBottom)
                    } else {
                        val bitmap = if (isPdf) {
                            val index = (pageNumber - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                            pagePreview
                                ?: PdfTextExtractor.openPdf(context, uri).use { renderer -> PdfTextExtractor.renderPage(renderer, index) }
                        } else {
                            imagePreview ?: PdfTextExtractor.loadImage(context, uri)
                        }
                        val cropped = PdfTextExtractor.crop(bitmap, cropLeft, cropTop, cropRight, cropBottom)
                        val ocr = PdfTextExtractor.extractText(context, cropped)
                        val text = ocr.paragraphsFor(ocr.detected).joinToString(" ")
                        if (text.isBlank()) null else NativeTextResult(listOf(text), reliable = true)
                    }
                }
            }
            processing = false
            result
                .onSuccess { extracted ->
                    if (extracted != null) {
                        usedNativeText = !useOcr
                        nativeUnreliable = !useOcr && !extracted.reliable
                        paragraphs.addAll(extracted.paragraphs)
                    } else {
                        error = if (isPdf && !useOcr) "No embedded text found in that area — try OCR instead." else "No text found in that area."
                    }
                }
                .onFailure { e -> error = e.message ?: "Couldn't read that" }
        }
    }

    fun switchLanguage(language: DetectedLanguage) {
        val current = extraction ?: return
        selectedLanguage = language
        paragraphs.clear()
        paragraphs.addAll(current.paragraphsFor(language))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Extract text") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = {
                    // Kept up here rather than at the bottom of the (potentially long) results
                    // list — an action button below a scrolling page of paragraphs risks ending
                    // up out of easy reach, especially with the bottom navigation bar also
                    // claiming space at the very bottom of the screen.
                    IconButton(
                        onClick = { clipboardManager.setText(AnnotatedString(paragraphs.joinToString("\n\n"))) },
                        enabled = paragraphs.isNotEmpty()
                    ) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy all") }
                    IconButton(
                        onClick = { shareAsText(context, paragraphs.joinToString("\n\n")) },
                        enabled = paragraphs.isNotEmpty()
                    ) { Icon(Icons.Default.Share, contentDescription = "Share as .txt") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (sourceUri == null) {
                Text(
                    "Works for English, Hindi, and Malayalam text. Arabic script can't be read on-device.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { launchCamera() }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Camera")
                    }
                    OutlinedButton(onClick = { launchPickPdf() }) {
                        Icon(Icons.Default.UploadFile, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("PDF")
                    }
                    OutlinedButton(onClick = { launchPickGallery() }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Gallery")
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { launchCamera() }) { Text("Camera") }
                    OutlinedButton(onClick = { launchPickPdf() }) { Text("PDF") }
                    OutlinedButton(onClick = { launchPickGallery() }) { Text("Gallery") }
                }
                Spacer(Modifier.height(16.dp))
                if (isPdf && pageCount > 0) {
                    Text("Page", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { pageNumber = (pageNumber - 1).coerceAtLeast(1) }, enabled = pageNumber > 1) { Text("−") }
                        Text("$pageNumber of $pageCount", style = MaterialTheme.typography.bodyLarge)
                        OutlinedButton(onClick = { pageNumber = (pageNumber + 1).coerceAtMost(pageCount) }, enabled = pageNumber < pageCount) { Text("+") }
                    }
                    // Stepping one page at a time is unusable once a document runs into the
                    // hundreds of pages — this jumps straight to a typed page number instead.
                    if (pageCount > 5) {
                        Spacer(Modifier.height(8.dp))
                        var pageInput by remember(sourceUri) { mutableStateOf("") }
                        fun jumpToTypedPage() {
                            pageInput.toIntOrNull()?.let { pageNumber = it.coerceIn(1, pageCount) }
                            pageInput = ""
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = pageInput,
                                onValueChange = { pageInput = it.filter(Char::isDigit).take(6) },
                                modifier = Modifier.width(110.dp),
                                singleLine = true,
                                label = { Text("Go to page") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                                keyboardActions = KeyboardActions(onGo = { jumpToTypedPage() })
                            )
                            Button(onClick = { jumpToTypedPage() }, enabled = pageInput.isNotBlank()) { Text("Go") }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                (if (isPdf) pagePreview else imagePreview)?.let { bitmap ->
                    Text(
                        "Pinch to zoom in for precision, then drag the rectangle onto just the area you want.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    CropSelector(
                        bitmap = bitmap,
                        left = cropLeft, top = cropTop, right = cropRight, bottom = cropBottom,
                        onChange = { l, t, r, b -> cropLeft = l; cropTop = t; cropRight = r; cropBottom = b }
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { resetCrop() }) { Text("Reset to full ${if (isPdf) "page" else "photo"}") }
                    Spacer(Modifier.height(8.dp))
                    Text("Extract just the selected area:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isPdf) {
                            OutlinedButton(onClick = { extractSelectedArea(useOcr = false) }, enabled = !processing) {
                                Text("Direct text")
                            }
                        }
                        Button(onClick = { extractSelectedArea(useOcr = true) }, enabled = !processing) {
                            Text("OCR")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                if (isPdf) {
                    Text("Extract the whole page:", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { extractCurrentPage(useOcr = false) }, enabled = !processing) {
                            Text("Direct text")
                        }
                        Button(onClick = { extractCurrentPage(useOcr = true) }, enabled = !processing) {
                            Text("OCR")
                        }
                    }
                } else if (imagePreview == null) {
                    // No crop selector to show yet (image still loading) — same action, same button.
                    Button(onClick = { extractCurrentPage(useOcr = true) }, enabled = !processing) {
                        Text("Extract text from selected area")
                    }
                }

                if (paragraphs.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = {
                        paragraphs.clear()
                        extraction = null
                        usedNativeText = false
                        nativeUnreliable = false
                        error = null
                    }) { Text("Clear all extracted text") }
                }
            }

            if (processing) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
            }

            if (usedNativeText) {
                Spacer(Modifier.height(16.dp))
                if (nativeUnreliable) {
                    Text(
                        "This PDF's own text looks like it may be corrupted (a broken font encoding, not something OCR would fix by re-running) — check it below, or try OCR instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "Copied directly from the PDF's own text — no OCR needed, so this is exact.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            extraction?.let {
                Spacer(Modifier.height(16.dp))
                Text("Detected language", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DetectedLanguage.entries.forEach { language ->
                        FilterChip(
                            selected = selectedLanguage == language,
                            onClick = { switchLanguage(language) },
                            label = { Text(language.label) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Wrong language? Tap the other one above — both were already read, no need to re-extract.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (paragraphs.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                paragraphs.forEachIndexed { index, paragraph ->
                    Text("Paragraph ${index + 1}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(
                        value = paragraph,
                        onValueChange = { paragraphs[index] = it },
                        modifier = Modifier.fillMaxWidth(),
                        trailingIcon = {
                            IconButton(onClick = { clipboardManager.setText(AnnotatedString(paragraph)) }) {
                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy paragraph ${index + 1}")
                            }
                        }
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            error?.let {
                Spacer(Modifier.height(16.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                LaunchedEffect(it) {
                    delay(4000)
                    error = null
                }
            }
        }
    }
}

/**
 * A draggable/resizable rectangle over [bitmap], expressed and reported as fractions (0..1) of
 * it — same interaction style as the video crop tool in Library. Also pinch-zoomable/pannable
 * (plus explicit +/− buttons, for reliable control regardless of how well pinch gestures work
 * inside a scrollable screen) for precisely selecting a small area on a dense page — the zoom is
 * purely a view transform, so the reported fractions stay relative to the whole, unzoomed image.
 */
@Composable
private fun CropSelector(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float, onChange: (Float, Float, Float, Float) -> Unit) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val minSizeFrac = 0.05f
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var panOffset by remember(bitmap) { mutableStateOf(Offset.Zero) }

    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { zoom = (zoom - 0.5f).coerceAtLeast(1f) }, enabled = zoom > 1f) { Text("−") }
            Text("${(zoom * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { zoom = (zoom + 0.5f).coerceAtMost(5f) }) { Text("+") }
            if (zoom > 1f) {
                TextButton(onClick = { zoom = 1f; panOffset = Offset.Zero }) { Text("Reset zoom") }
            }
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                // Inset slightly from the full width rather than running edge-to-edge — the corner
                // drag handles sit right at the image's own left/right edge, which otherwise lands
                // close enough to the screen's physical edge to compete with the system's edge-swipe
                // gesture instead of actually dragging.
                .fillMaxWidth(0.94f)
                .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                .clip(RoundedCornerShape(4.dp))
                .pointerInput(bitmap) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                        panOffset += pan
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = zoom, scaleY = zoom,
                        translationX = panOffset.x, translationY = panOffset.y
                    )
                    .onSizeChanged { containerSize = it }
            ) {
        Image(bitmap = bitmap.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())

        if (containerSize.width > 0 && containerSize.height > 0) {
            val w = containerSize.width.toFloat()
            val h = containerSize.height.toFloat()

            androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                val rectLeft = left * size.width
                val rectTop = top * size.height
                val rectRight = right * size.width
                val rectBottom = bottom * size.height
                val dim = Color.Black.copy(alpha = 0.5f)
                drawRect(dim, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(size.width, rectTop))
                drawRect(dim, topLeft = Offset(0f, rectBottom), size = androidx.compose.ui.geometry.Size(size.width, size.height - rectBottom))
                drawRect(dim, topLeft = Offset(0f, rectTop), size = androidx.compose.ui.geometry.Size(rectLeft, rectBottom - rectTop))
                drawRect(dim, topLeft = Offset(rectRight, rectTop), size = androidx.compose.ui.geometry.Size(size.width - rectRight, rectBottom - rectTop))
                drawRect(
                    Color.White,
                    topLeft = Offset(rectLeft, rectTop),
                    size = androidx.compose.ui.geometry.Size(rectRight - rectLeft, rectBottom - rectTop),
                    style = Stroke(width = 3f)
                )
            }

            // Drag inside the rectangle to move it. The rect's current left/top/right/bottom and
            // onChange are read through rememberUpdatedState rather than captured directly by the
            // gesture lambda: pointerInput's coroutine only (re)launches when its key changes, and
            // containerSize stops changing after the first layout pass — without this, every drag
            // callback for the rest of the screen's life would keep computing against the very
            // first composition's left/top/right/bottom, so the rectangle would jump by this
            // frame's delta and then snap back to that stale position instead of actually moving.
            val currentLeft = rememberUpdatedState(left)
            val currentTop = rememberUpdatedState(top)
            val currentRight = rememberUpdatedState(right)
            val currentBottom = rememberUpdatedState(bottom)
            val currentOnChange = rememberUpdatedState(onChange)
            Box(
                modifier = Modifier
                    .offset { IntOffset((left * w).roundToInt(), (top * h).roundToInt()) }
                    .size(
                        with(androidx.compose.ui.platform.LocalDensity.current) { ((right - left) * w).toDp() },
                        with(androidx.compose.ui.platform.LocalDensity.current) { ((bottom - top) * h).toDp() }
                    )
                    .pointerInput(containerSize) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val l = currentLeft.value
                            val t = currentTop.value
                            val r = currentRight.value
                            val b = currentBottom.value
                            val rw = r - l
                            val rh = b - t
                            val newLeft = (l + drag.x / w).coerceIn(0f, 1f - rw)
                            val newTop = (t + drag.y / h).coerceIn(0f, 1f - rh)
                            currentOnChange.value(newLeft, newTop, newLeft + rw, newTop + rh)
                        }
                    }
            )

            CropCorner(left, top, containerSize) { dx, dy ->
                onChange((left + dx).coerceIn(0f, right - minSizeFrac), (top + dy).coerceIn(0f, bottom - minSizeFrac), right, bottom)
            }
            CropCorner(right, top, containerSize) { dx, dy ->
                onChange(left, (top + dy).coerceIn(0f, bottom - minSizeFrac), (right + dx).coerceIn(left + minSizeFrac, 1f), bottom)
            }
            CropCorner(left, bottom, containerSize) { dx, dy ->
                onChange((left + dx).coerceIn(0f, right - minSizeFrac), top, right, (bottom + dy).coerceIn(top + minSizeFrac, 1f))
            }
            CropCorner(right, bottom, containerSize) { dx, dy ->
                onChange(left, top, (right + dx).coerceIn(left + minSizeFrac, 1f), (bottom + dy).coerceIn(top + minSizeFrac, 1f))
            }
        }
            }
        }
    }
}

@Composable
private fun CropCorner(xFrac: Float, yFrac: Float, container: IntSize, onDrag: (dxFrac: Float, dyFrac: Float) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val handleSizePx = with(density) { 24.dp.toPx() }
    val w = container.width.toFloat()
    val h = container.height.toFloat()
    // Same staleness issue as the move-box above: pointerInput(container) only relaunches when
    // container changes, so without rememberUpdatedState this handle would keep calling the very
    // first onDrag closure it was given (from CropSelector's first composition) instead of the
    // current one that captures this handle's up-to-date corner.
    val currentOnDrag = rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .offset { IntOffset((xFrac * w - handleSizePx / 2).roundToInt(), (yFrac * h - handleSizePx / 2).roundToInt()) }
            .size(24.dp)
            .background(Color.White, androidx.compose.foundation.shape.CircleShape)
            .border(1.dp, Color.Black, androidx.compose.foundation.shape.CircleShape)
            .pointerInput(container) {
                detectDragGestures { change, drag ->
                    change.consume()
                    currentOnDrag.value(drag.x / w, drag.y / h)
                }
            }
    )
}

private fun shareAsText(context: Context, text: String) {
    val dir = File(context.getExternalFilesDir(null), "PdfText").apply { mkdirs() }
    val file = File(dir, "extracted-text.txt")
    FileOutputStream(file).use { it.write(text.toByteArray()) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.let { context.startActivity(Intent.createChooser(it, "Share extracted text")) }
}
