package com.mobicareapp.ui.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.passport.BrushTool
import com.mobicareapp.passport.PassportPhotoEntry
import com.mobicareapp.passport.PassportPhotoFileStore
import com.mobicareapp.passport.PassportPhotoHistoryStore
import com.mobicareapp.passport.PhotoFilter
import com.mobicareapp.passport.PhotoStroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private sealed class PassportMode {
    data object List : PassportMode()
    data class Editor(val entry: PassportPhotoEntry?) : PassportMode()
}

/** Top-level: a saved-photos list (with share/edit/delete) plus an editor for taking a new photo or reworking an existing one's background/filter. */
@Composable
fun PassportPhotoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<PassportMode>(PassportMode.List) }
    var historyVersion by remember { mutableIntStateOf(0) }

    when (val current = mode) {
        is PassportMode.List -> {
            val entries = remember(historyVersion) { PassportPhotoHistoryStore.loadAll(context) }
            PassportPhotoListScreen(
                entries = entries,
                onBack = onBack,
                onNew = { mode = PassportMode.Editor(null) },
                onEdit = { entry -> mode = PassportMode.Editor(entry) },
                onDelete = { entry ->
                    PassportPhotoHistoryStore.delete(context, entry)
                    historyVersion++
                },
                onShare = { entry -> shareJpg(context, File(entry.photoPath)) }
            )
        }
        is PassportMode.Editor -> {
            PassportPhotoEditorScreen(
                entry = current.entry,
                onDone = {
                    historyVersion++
                    mode = PassportMode.List
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassportPhotoListScreen(
    entries: List<PassportPhotoEntry>,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (PassportPhotoEntry) -> Unit,
    onDelete: (PassportPhotoEntry) -> Unit,
    onShare: (PassportPhotoEntry) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<PassportPhotoEntry?>(null) }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this photo?") },
            text = { Text("This removes it and its original capture. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { onDelete(entry); pendingDelete = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Passport photos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNew) {
                        Icon(Icons.Default.Add, contentDescription = "New passport photo")
                    }
                }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.Portrait, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No passport photos yet", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onNew) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Take photo")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .aspectRatio(PassportPhotoFileStore.PHOTO_ASPECT_RATIO)
                                .clip(RoundedCornerShape(4.dp))
                        ) {
                            AsyncImage(
                                model = rememberFreshPhotoRequest(File(entry.photoPath)),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.timestampMillis)),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onShare(entry) }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = { onEdit(entry) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { pendingDelete = entry }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Edit/Retake overwrite the same file path in place (needed so the saved history entry keeps
 * pointing at one stable file instead of piling up copies) — but that means Coil's default cache
 * key, which is just the path, treats every reprocessed version as "the same image" and keeps
 * showing the old one. Disabling caching for this request (these are small local files; decoding
 * them fresh each time costs nothing noticeable) handles that.
 *
 * [cacheBustKey] forces a genuinely new request object when it changes — callers editing the same
 * file repeatedly in one sitting should pass their own incrementing counter rather than
 * `file.lastModified()`: that only has *second*-level resolution on many Android filesystems, so
 * two reprocesses within the same second (easy once the segmentation model is warmed up and each
 * pass gets fast) would resolve to an identical key and silently keep showing the first result —
 * which is exactly what made background/filter changes appear to only work "the first time".
 *
 * That's not enough on its own, though: [ImageRequest] compares equal (by its own equals(), which
 * Coil/Compose can use to skip restarting a load) whenever [data][ImageRequest.Builder.data] is the
 * same File — and it always is here, since Edit/Retake deliberately overwrite one stable path. So
 * [cacheBustKey] is baked into the *data itself* (as a URI fragment, which has no effect on which
 * file actually gets read) rather than left as a plain remember() key outside it — that's what
 * forces every reprocess to actually be treated as a different image, not just a different Kotlin
 * object wrapping the same one.
 */
@Composable
private fun rememberFreshPhotoRequest(file: File, cacheBustKey: Any = file.lastModified()): ImageRequest {
    val context = LocalContext.current
    return remember(file.path, cacheBustKey) {
        ImageRequest.Builder(context)
            .data(Uri.fromFile(file).buildUpon().fragment(cacheBustKey.toString()).build())
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
}

private fun shareJpg(context: Context, file: File) {
    Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, PassportPhotoFileStore.shareUri(context, file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.let { context.startActivity(Intent.createChooser(it, "Share photo")) }
}

/**
 * Captures a plain photo (no document edge-detection — that's for flat pages, not a face),
 * removes the background (on-device face/person segmentation) and fills it with a chosen color,
 * applies a filter, auto-crops to the 35x45mm passport-photo ratio, and offers it as a standalone
 * JPG or a print-ready PDF sheet with 4 copies in a row. The crop is centered with no interactive
 * adjustment step for now — center the face in the camera viewfinder before taking the shot, the
 * same framing a passport photo needs anyway.
 *
 * When [entry] is non-null, this reworks an already-saved photo's background/filter from its
 * original raw capture (rather than starting a new one) and updates it in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PassportPhotoEditorScreen(entry: PassportPhotoEntry?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val baseName = remember { entry?.id ?: PassportPhotoFileStore.newBaseName() }
    var rawCaptureFile by remember { mutableStateOf(entry?.let { File(it.rawCapturePath) }) }
    // Edit/Retake overwrite the same path in place, so a new File instance for the same photo
    // still equals() the old one — with the default structural-equality policy, Compose would
    // treat that reassignment as "unchanged" and silently skip the write and any recomposition,
    // which is exactly why switching background/filter appeared to do nothing. neverEqualPolicy
    // forces every reprocess to count as a real change.
    var photoFile by remember { mutableStateOf(entry?.let { File(it.photoPath) }, neverEqualPolicy()) }
    var selectedBackground by remember {
        mutableIntStateOf(
            entry?.let { e -> PassportPhotoFileStore.BACKGROUND_PRESETS.indexOfFirst { it.second == e.backgroundColorArgb } }
                ?.takeIf { it >= 0 } ?: 0
        )
    }
    var selectedFilter by remember { mutableStateOf(entry?.filter ?: PhotoFilter.NONE) }
    var brightness by remember { mutableFloatStateOf(entry?.brightness ?: 0f) }
    var contrast by remember { mutableFloatStateOf(entry?.contrast ?: 1f) }
    // Hand-drawn eraser/brush edits, replayed on every reprocess (see PhotoStroke) - so changing the
    // background/filter afterwards keeps them, and Undo just drops the last one and reprocesses.
    val strokes = remember { mutableStateListOf<PhotoStroke>().also { list -> entry?.let { list.addAll(it.strokes) } } }
    var editTool by remember { mutableStateOf(EditTool.NONE) }
    var eraseRestores by remember { mutableStateOf(false) }
    var brushSize by remember { mutableFloatStateOf(0.07f) }
    var brushStrength by remember { mutableFloatStateOf(0.5f) }
    // Preview zoom: scale, and pan as a fraction of the frame size (so it stays valid if the frame is resized).
    var zoom by remember { mutableFloatStateOf(1f) }
    var panFraction by remember { mutableStateOf(Offset.Zero) }
    // Zooming from the buttons/slider is always about the frame's center.
    fun setZoom(newZoom: Float) {
        val clamped = newZoom.coerceIn(1f, MAX_PREVIEW_ZOOM)
        panFraction = clampPan(clamped, panFraction * (clamped / zoom))
        zoom = clamped
    }
    var processing by remember { mutableStateOf(false) }
    // Set when a background/filter/slider change comes in while a reprocess from an *earlier*
    // change is still running. process() itself always reads the latest selectedBackground/
    // selectedFilter/brightness/contrast off state, so re-running it once the in-flight one
    // finishes picks up whatever the user's most recent choice was — instead of that change
    // being silently dropped (the "changed the background/moved the slider and nothing happened"
    // bug: it only *looked* fixed once they left and reopened the editor, because that reloads
    // whichever reprocess had actually won the race).
    var pendingReprocess by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Bumped on every successful reprocess so the preview's cache-bust key is guaranteed unique —
    // see rememberFreshPhotoRequest's doc comment for why file.lastModified() alone isn't enough.
    var photoVersion by remember { mutableIntStateOf(0) }
    // Whether *this session* has already written a history entry for baseName — not the same as
    // "entry != null": a brand-new capture also only gets one add(), the very first time it's
    // processed. Every reprocess after that (background/filter tweaks) must update() the existing
    // entry instead — using entry==null as that check called add() again on every tweak, writing
    // a second entry with the same id and crashing the list's LazyColumn ("Key ... already used").
    var hasHistoryEntry by remember { mutableStateOf(entry != null) }

    fun process(source: File) {
        // Swatches/chips/sliders aren't individually disabled while processing, so a quick second
        // tap before the first reprocess finishes could otherwise launch overlapping coroutines
        // racing to write the same output file — whichever finished last would win, which needn't
        // be whichever the user tapped last. Instead of dropping that second change entirely,
        // queue it: process() always reads the current state values, so re-running it once the
        // in-flight one finishes still picks up the latest background/filter/brightness/contrast.
        if (processing) {
            pendingReprocess = true
            return
        }
        processing = true
        val backgroundColor = PassportPhotoFileStore.BACKGROUND_PRESETS[selectedBackground].second
        val strokeSnapshot = strokes.toList()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    PassportPhotoFileStore.cropAndSave(context, Uri.fromFile(source), backgroundColor, selectedFilter, baseName, brightness, contrast, strokeSnapshot)
                }
            }
            processing = false
            if (pendingReprocess) {
                pendingReprocess = false
                process(source)
                return@launch
            }
            result
                .onSuccess { saved ->
                    photoFile = saved
                    photoVersion++
                    if (!hasHistoryEntry) {
                        PassportPhotoHistoryStore.add(
                            context,
                            PassportPhotoEntry(
                                id = baseName,
                                timestampMillis = System.currentTimeMillis(),
                                rawCapturePath = source.absolutePath,
                                photoPath = saved.absolutePath,
                                backgroundColorArgb = backgroundColor,
                                filterName = selectedFilter.name,
                                brightness = brightness,
                                contrast = contrast,
                                strokes = strokeSnapshot
                            )
                        )
                        hasHistoryEntry = true
                    } else {
                        PassportPhotoHistoryStore.update(context, baseName, backgroundColor, selectedFilter.name, brightness, contrast, strokeSnapshot)
                    }
                }
                .onFailure { e -> error = e.message ?: "Couldn't process the photo" }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val captureFile = rawCaptureFile
        if (success && captureFile != null) {
            // Strokes were drawn on the previous photo - they mean nothing on a new one.
            strokes.clear()
            process(captureFile)
        }
    }

    fun launchCamera() {
        val (file, uri) = PassportPhotoFileStore.captureTarget(context, baseName)
        rawCaptureFile = file
        AppLockManager.suppressNextLock()
        cameraLauncher.launch(uri)
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            processing = true
            scope.launch {
                val copied = withContext(Dispatchers.IO) {
                    runCatching { PassportPhotoFileStore.copyGalleryImage(context, uri, baseName) }
                }
                copied
                    .onSuccess { file ->
                        rawCaptureFile = file
                        strokes.clear()
                        process(file)
                    }
                    .onFailure { e ->
                        processing = false
                        error = e.message ?: "Couldn't load the selected photo"
                    }
            }
        }
    }

    fun launchGallery() {
        AppLockManager.suppressNextLock()
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun sharePrintSheet() {
        val photo = photoFile ?: return
        processing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { PassportPhotoFileStore.buildPrintSheet(context, photo) }
            }
            processing = false
            result
                .onSuccess { sheet ->
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, PassportPhotoFileStore.shareUri(context, sheet))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }.let { context.startActivity(Intent.createChooser(it, "Share print sheet")) }
                }
                .onFailure { e -> error = e.message ?: "Couldn't build the print sheet" }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (entry == null) "New passport photo" else "Edit passport photo") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Kept up here (not below the brightness/contrast sliders and filter chips)
                    // so they're never at risk of ending up scrolled down near the bottom
                    // navigation bar.
                    IconButton(onClick = { photoFile?.let { shareJpg(context, it) } }, enabled = photoFile != null && !processing) {
                        Icon(Icons.Default.Image, contentDescription = "Share JPG")
                    }
                    IconButton(onClick = { sharePrintSheet() }, enabled = photoFile != null && !processing) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Share PDF (4 copies)")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                // The content here (photo, swatches, 11 filter chips, two sliders, buttons) can
                // easily be taller than the screen. With no scroll, Arrangement.Center pushed the
                // overflow off both the top *and* bottom of the screen — the background/filter
                // controls could end up genuinely unreachable rather than just below the fold.
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            val currentPhoto = photoFile
            if (currentPhoto == null) {
                Text(
                    "35 x 45mm, the size used by India, the UK, the EU/Schengen area, and most of the world.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Text("Background color", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                BackgroundSwatchRow(selectedBackground) { selectedBackground = it }
                Spacer(Modifier.height(16.dp))
                Text("Filter", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                FilterChipRow(selectedFilter) { selectedFilter = it }
                Spacer(Modifier.height(24.dp))
                if (processing) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Removing background…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { launchCamera() }) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Take photo")
                        }
                        OutlinedButton(onClick = { launchGallery() }) {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Gallery")
                        }
                    }
                }
            } else {
                val toolActive = editTool != EditTool.NONE
                val currentToolActive by rememberUpdatedState(toolActive)
                val currentZoom by rememberUpdatedState(zoom)
                // The frame itself stays put (and clips); only what's inside it is scaled/moved, so
                // zooming in shows a close-up of the face inside the same frame. Strokes are drawn in
                // the zoomed layer, so they land on the right spot of the photo at any zoom level.
                Box(
                    modifier = Modifier
                        .width(PREVIEW_FRAME_DP.dp)
                        .aspectRatio(PassportPhotoFileStore.PHOTO_ASPECT_RATIO)
                        .clip(RoundedCornerShape(4.dp))
                        .zoomPanGestures(
                            // A single finger pans a zoomed-in photo only when no drawing tool is
                            // active (otherwise it's drawing), and not at 1x (so the page can still
                            // scroll past the photo). Two fingers always pinch-zoom / move.
                            allowSinglePointerPan = { !currentToolActive && currentZoom > 1f },
                            onTransform = { centroid, pan, zoomChange, frame ->
                                val oldZoom = zoom
                                val newZoom = (oldZoom * zoomChange).coerceIn(1f, MAX_PREVIEW_ZOOM)
                                val center = Offset(frame.width / 2f, frame.height / 2f)
                                val translation = Offset(panFraction.x * frame.width, panFraction.y * frame.height)
                                // Keeps the point under the fingers fixed while scaling.
                                val newTranslation = centroid - center - (centroid - center - translation) * (newZoom / oldZoom) + pan
                                zoom = newZoom
                                panFraction = clampPan(newZoom, Offset(newTranslation.x / frame.width, newTranslation.y / frame.height))
                            }
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = panFraction.x * size.width
                                translationY = panFraction.y * size.height
                            }
                    ) {
                        AsyncImage(
                            model = rememberFreshPhotoRequest(currentPhoto, photoVersion),
                            contentDescription = "Passport photo",
                            modifier = Modifier.fillMaxSize()
                        )
                        if (toolActive) {
                            StrokeOverlay(
                                tool = when {
                                    editTool == EditTool.BRUSH -> BrushTool.BRIGHTEN
                                    eraseRestores -> BrushTool.RESTORE
                                    else -> BrushTool.ERASE
                                },
                                diameter = brushSize,
                                strength = brushStrength,
                                onStroke = { stroke ->
                                    strokes.add(stroke)
                                    rawCaptureFile?.let { process(it) }
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    if (processing) {
                        // Only dimmed when not drawing - a dim flash after every stroke would be distracting.
                        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (toolActive) 0f else 0.4f))) {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(if (toolActive) 20.dp else 40.dp))
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // Zoom: buttons/slider here, or pinch with two fingers on the photo itself.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.width(PREVIEW_FRAME_DP.dp)
                ) {
                    IconButton(onClick = { setZoom((zoom - 0.5f).coerceAtLeast(1f)) }, enabled = zoom > 1f) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                    }
                    Slider(
                        value = zoom,
                        onValueChange = { setZoom(it) },
                        valueRange = 1f..MAX_PREVIEW_ZOOM,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { setZoom((zoom + 0.5f).coerceAtMost(MAX_PREVIEW_ZOOM)) }, enabled = zoom < MAX_PREVIEW_ZOOM) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Zoom ${"%.1f".format(zoom)}x - pinch with two fingers to zoom and move",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (zoom > 1f) {
                        TextButton(onClick = { setZoom(1f) }) { Text("Reset") }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = editTool == EditTool.NONE, onClick = { editTool = EditTool.NONE }, label = { Text("Adjust") })
                    FilterChip(selected = editTool == EditTool.ERASER, onClick = { editTool = EditTool.ERASER }, label = { Text("Eraser") })
                    FilterChip(selected = editTool == EditTool.BRUSH, onClick = { editTool = EditTool.BRUSH }, label = { Text("Brush") })
                }
                if (toolActive) {
                    Spacer(Modifier.height(8.dp))
                    if (editTool == EditTool.ERASER) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !eraseRestores, onClick = { eraseRestores = false }, label = { Text("Erase") })
                            FilterChip(selected = eraseRestores, onClick = { eraseRestores = true }, label = { Text("Restore") })
                        }
                        Text(
                            if (eraseRestores) "Drag over a part that was cut out by mistake to bring it back."
                            else "Drag over leftover background (around the hair or shoulders) to remove it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            "Drag over the face to lighten just that part. The background is not changed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Brush size", style = MaterialTheme.typography.labelMedium)
                            Slider(value = brushSize, onValueChange = { brushSize = it }, valueRange = 0.01f..0.25f, modifier = Modifier.fillMaxWidth())
                        }
                        // A live sample of the brush, at the size it appears on screen (so it grows when zoomed in); capped to fit its box.
                        Box(modifier = Modifier.padding(start = 12.dp).size(76.dp), contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size((brushSize * PREVIEW_FRAME_DP * zoom).coerceIn(3f, 72f).dp)
                                    .clip(CircleShape)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                            )
                        }
                    }
                    if (editTool == EditTool.BRUSH) {
                        Text("Lightness", style = MaterialTheme.typography.labelMedium)
                        Slider(value = brushStrength, onValueChange = { brushStrength = it }, valueRange = 0.1f..1f, modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                strokes.removeAt(strokes.lastIndex)
                                rawCaptureFile?.let { process(it) }
                            },
                            enabled = strokes.isNotEmpty()
                        ) {
                            Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Undo")
                        }
                        TextButton(
                            onClick = {
                                strokes.clear()
                                rawCaptureFile?.let { process(it) }
                            },
                            enabled = strokes.isNotEmpty()
                        ) { Text("Clear all edits") }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("Background color", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                BackgroundSwatchRow(selectedBackground) { index ->
                    selectedBackground = index
                    rawCaptureFile?.let { process(it) }
                }
                Spacer(Modifier.height(16.dp))
                Text("Filter", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                FilterChipRow(selectedFilter) { filter ->
                    selectedFilter = filter
                    rawCaptureFile?.let { process(it) }
                }
                Spacer(Modifier.height(16.dp))
                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    onValueChangeFinished = { rawCaptureFile?.let { process(it) } },
                    valueRange = -50f..50f,
                    enabled = !processing,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text("Contrast", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = contrast,
                    onValueChange = { contrast = it },
                    onValueChangeFinished = { rawCaptureFile?.let { process(it) } },
                    valueRange = 0.7f..1.3f,
                    enabled = !processing,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        brightness = 0f
                        contrast = 1f
                        rawCaptureFile?.let { process(it) }
                    },
                    enabled = brightness != 0f || contrast != 1f
                ) { Text("Reset brightness/contrast") }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { launchCamera() }, enabled = !processing) { Text("Retake") }
                    OutlinedButton(onClick = { launchGallery() }, enabled = !processing) { Text("Gallery") }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Share JPG / Share PDF are in the top bar.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "When printing the PDF, choose \"Actual size\" (not \"Fit to page\") so each photo stays exactly 35 x 45mm.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
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

@Composable
private fun FilterChipRow(selected: PhotoFilter, onSelect: (PhotoFilter) -> Unit) {
    // 11 chips don't fit one screen width — this scrolls on its own axis, independent of (and
    // nested inside) the screen's own vertical scroll, same as a normal horizontal chip strip.
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState())
    ) {
        PhotoFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) }
            )
        }
    }
}

@Composable
private fun BackgroundSwatchRow(selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PassportPhotoFileStore.BACKGROUND_PRESETS.forEachIndexed { index, (name, colorInt) ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(colorInt))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = name,
                        modifier = Modifier.size(18.dp),
                        tint = if (colorInt == android.graphics.Color.WHITE) Color.Black else Color.White
                    )
                }
            }
        }
    }
}

private enum class EditTool { NONE, ERASER, BRUSH }

private const val PREVIEW_FRAME_DP = 280f
private const val MAX_PREVIEW_ZOOM = 6f

/** Keeps the zoomed photo covering its frame: at zoom z it can only shift (z - 1) / 2 of the frame size either way. */
private fun clampPan(zoom: Float, pan: Offset): Offset {
    val limit = (zoom - 1f) / 2f
    return Offset(pan.x.coerceIn(-limit, limit), pan.y.coerceIn(-limit, limit))
}

/**
 * Pinch-zoom + pan, listening in the Initial pass (before children) so two fingers on the photo
 * are taken as a zoom/move even when the drawing layer underneath would otherwise treat the first
 * finger as the start of a stroke — that child then sees its pointer consumed and cancels the stroke.
 */
private fun Modifier.zoomPanGestures(
    allowSinglePointerPan: () -> Boolean,
    onTransform: (centroid: Offset, pan: Offset, zoomChange: Float, frame: IntSize) -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2 || (pressed == 1 && allowSinglePointerPan())) {
                val zoomChange = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoomChange != 1f || pan != Offset.Zero) {
                    onTransform(event.calculateCentroid(useCurrent = true), pan, zoomChange, size)
                    event.changes.forEach { it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Transparent layer over the photo preview that turns finger drags (and single taps) into
 * [PhotoStroke]s, in coordinates relative to the photo (0..1) so they're resolution-independent.
 * The stroke being drawn is only previewed here as a tinted line; it's the reprocess after the
 * finger lifts that produces the real result.
 */
@Composable
private fun StrokeOverlay(
    tool: BrushTool,
    diameter: Float,
    strength: Float,
    onStroke: (PhotoStroke) -> Unit,
    modifier: Modifier = Modifier
) {
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val currentTool by rememberUpdatedState(tool)
    val currentDiameter by rememberUpdatedState(diameter)
    val currentStrength by rememberUpdatedState(strength)
    val currentOnStroke by rememberUpdatedState(onStroke)

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    currentOnStroke(
                        PhotoStroke(
                            currentTool, currentDiameter, currentStrength,
                            listOf((offset.x / size.width).coerceIn(0f, 1f) to (offset.y / size.height).coerceIn(0f, 1f))
                        )
                    )
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { live = listOf(it) },
                    onDrag = { change, _ ->
                        change.consume()
                        live = live + change.position
                    },
                    onDragEnd = {
                        if (live.isNotEmpty()) {
                            currentOnStroke(
                                PhotoStroke(
                                    currentTool, currentDiameter, currentStrength,
                                    live.map { (it.x / size.width).coerceIn(0f, 1f) to (it.y / size.height).coerceIn(0f, 1f) }
                                )
                            )
                        }
                        live = emptyList()
                    },
                    onDragCancel = { live = emptyList() }
                )
            }
    ) {
        if (live.isEmpty()) return@Canvas
        val color = when (tool) {
            BrushTool.ERASE -> Color.Red.copy(alpha = 0.45f)
            BrushTool.RESTORE -> Color.Green.copy(alpha = 0.45f)
            BrushTool.BRIGHTEN -> Color.White.copy(alpha = 0.55f)
        }
        val width = diameter * size.width
        if (live.size == 1) {
            drawCircle(color, radius = width / 2f, center = live.first())
        } else {
            val path = Path().apply {
                moveTo(live.first().x, live.first().y)
                live.drop(1).forEach { lineTo(it.x, it.y) }
            }
            drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}
