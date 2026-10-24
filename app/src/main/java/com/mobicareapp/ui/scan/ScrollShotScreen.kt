package com.mobicareapp.ui.scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.scrollshot.ScrollOcr
import com.mobicareapp.scrollshot.ScrollStitcher
import com.mobicareapp.upload.PersonalServerSettings
import com.mobicareapp.upload.ShareUploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

private sealed class ScrollStage {
    data object Idle : ScrollStage()
    data class Working(val status: String) : ScrollStage()
    data class Done(val analysis: ScrollStitcher.Analysis, val result: ScrollStitcher.Stitched) : ScrollStage()
    data class Failed(val message: String) : ScrollStage()
}

/**
 * Makes one long (or wide) picture from a screen recording of something being scrolled, up and down or
 * sideways, with nothing repeated, and shows it right here (pinch to zoom in and out, drag in every
 * direction, double tap to zoom), so it can be read from end to end without saving anything. Pictures
 * that came out unclear can be ticked and removed, and the picture is made again from the rest. It can
 * also be read as text (OCR), uploaded, saved or shared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrollShotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stage by remember { mutableStateOf<ScrollStage>(ScrollStage.Idle) }
    var ocrText by remember { mutableStateOf<String?>(null) }
    var ocrBusy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var showReview by remember { mutableStateOf(false) }
    // The pictures taken out of the video that were ticked as unclear (their numbers).
    var removed by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Picking a video leaves the app for a moment, and making the picture takes a while.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }
    // The pictures kept for the review are not needed once the screen is left.
    DisposableEffect(Unit) {
        onDispose { ScrollStitcher.discard((stage as? ScrollStage.Done)?.analysis) }
    }
    BackHandler(onBack = onBack)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            ScrollStitcher.discard((stage as? ScrollStage.Done)?.analysis)
            removed = emptySet()
            message = null
            stage = ScrollStage.Working("Starting…")
            scope.launch {
                val analysis = ScrollStitcher.analyze(context, uri) { status -> stage = ScrollStage.Working(status) }
                analysis
                    .onSuccess { found ->
                        ScrollStitcher.compose(context, found, emptySet()) { status -> stage = ScrollStage.Working(status) }
                            .onSuccess { stage = ScrollStage.Done(found, it) }
                            .onFailure { stage = ScrollStage.Failed(it.message ?: "Couldn't make the picture") }
                    }
                    .onFailure { stage = ScrollStage.Failed(it.message ?: "Couldn't make the picture") }
            }
        }
    }

    ocrText?.let { text ->
        var edited by remember(text) { mutableStateOf(text) }
        AlertDialog(
            onDismissRequest = { ocrText = null },
            title = { Text("Text from the picture") },
            text = {
                OutlinedTextField(
                    value = edited,
                    onValueChange = { edited = it },
                    modifier = Modifier.fillMaxWidth().height(360.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Text from the picture", edited))
                    Toast.makeText(context, "Copied to the clipboard", Toast.LENGTH_SHORT).show()
                }) { Text("Copy") }
            },
            dismissButton = { TextButton(onClick = { ocrText = null }) { Text("Close") } }
        )
    }

    // Review: the pictures taken from the video, with a tick box on each; ticked ones are left out when the picture is made again.
    (stage as? ScrollStage.Done)?.let { done ->
        if (showReview) {
            ReviewPicturesDialog(
                analysis = done.analysis,
                removed = removed,
                onRemovedChange = { removed = it },
                onClose = { showReview = false },
                onMakeAgain = {
                    showReview = false
                    val before = done.result.files
                    scope.launch {
                        stage = ScrollStage.Working("Making the picture again…")
                        ScrollStitcher.compose(context, done.analysis, removed) { status -> stage = ScrollStage.Working(status) }
                            .onSuccess {
                                before.forEach { file -> runCatching { file.delete() } }
                                stage = ScrollStage.Done(done.analysis, it)
                                message = "Made again without ${removed.size} picture(s)."
                            }
                            .onFailure {
                                stage = done
                                message = it.message ?: "Couldn't make the picture again"
                            }
                    }
                }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Long screenshot from a video") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = stage) {
                is ScrollStage.Idle, is ScrollStage.Failed -> {
                    Column(
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Record your screen while you slowly scroll a page (a chat, a web page, a document, a row of pictures), up and down or sideways, " +
                                "then choose that video here. The scrolled screens are joined into one long picture with no part repeated, " +
                                "that you can read from end to end, with zoom, in any direction, and read as text.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Scroll at a steady, not too fast speed for the best result. Bars that stay in place (a title bar, a bottom bar) are left out. " +
                                "Afterwards you can tick unclear parts and remove them.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (current is ScrollStage.Failed) {
                            Text(current.message, color = MaterialTheme.colorScheme.error)
                        }
                        Button(onClick = {
                            AppLockManager.suppressNextLock()
                            picker.launch(arrayOf("video/*"))
                        }) { Text("Choose the scrolling video") }
                    }
                }

                is ScrollStage.Working -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(current.status, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                is ScrollStage.Done -> {
                    val result = current.result
                    // The buttons: remove unclear parts, read the text, upload, save to the phone, share.
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(enabled = ocrBusy == null, onClick = { showReview = true }) { Text("Remove unclear parts") }
                        OutlinedButton(enabled = ocrBusy == null, onClick = {
                            scope.launch {
                                ocrBusy = "Reading the text…"
                                val text = runCatching { ScrollOcr.read(context, result.files) { ocrBusy = it } }
                                ocrBusy = null
                                text.onSuccess { ocrText = it.ifBlank { "(no text was found)" } }
                                    .onFailure { message = it.message ?: "Couldn't read the text" }
                            }
                        }) { Text("Read text (OCR)") }
                        OutlinedButton(enabled = ocrBusy == null, onClick = {
                            if (!PersonalServerSettings.isConfigured(context)) {
                                message = "Set up your server first (Record, Copy images/videos)."
                            } else {
                                scope.launch {
                                    uploadParts(context, result.files) { ocrBusy = it }
                                        .onSuccess { message = it }
                                        .onFailure { message = "Upload failed: ${it.message}" }
                                    ocrBusy = null
                                }
                            }
                        }) { Text("Upload") }
                        OutlinedButton(onClick = { message = saveToPhone(context, result.files) }) { Text("Save to phone") }
                        OutlinedButton(onClick = { shareParts(context, result.files) }) { Text("Share") }
                    }
                    ocrBusy?.let {
                        Row(modifier = Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                    }
                    Text(
                        (if (result.horizontal) "A sideways scroll: " else "") + "${result.files.size} picture(s), ${result.lengths.sum()} px " +
                            (if (result.horizontal) "wide" else "tall") +
                            if (result.jumps > 0) ". The scrolling jumped ${result.jumps} time(s): a thin red line marks where." else ".",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    LongPictureViewer(files = result.files, horizontal = result.horizontal, modifier = Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }
}

/**
 * Every picture taken out of the video, with a tick box: tick the unclear ones (a blurred one is marked
 * as a suggestion), and the long picture is made again without them. Nothing is repeated in it, and the
 * part that belonged to a removed picture is simply left out (what was beside it is joined up).
 */
@Composable
private fun ReviewPicturesDialog(
    analysis: ScrollStitcher.Analysis,
    removed: Set<Int>,
    onRemovedChange: (Set<Int>) -> Unit,
    onClose: () -> Unit,
    onMakeAgain: () -> Unit
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Remove unclear parts", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                }
                Text(
                    "These are the pictures the long picture was made from, in order. Tick the ones that are unclear (a blurred one says \"unclear\"). " +
                        "The long picture is made again without them: nothing is repeated, and their part is simply left out.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = {
                        onRemovedChange(analysis.frames.withIndex().filter { it.value.unclear }.map { it.index }.toSet())
                    }) { Text("Tick the unclear ones") }
                    OutlinedButton(onClick = { onRemovedChange(emptySet()) }) { Text("Untick all") }
                    Button(
                        enabled = removed.size < analysis.frames.size,
                        onClick = onMakeAgain
                    ) { Text(if (removed.isEmpty()) "Make the picture again" else "Remove ${removed.size} and make again") }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(analysis.frames) { index, frame ->
                        val ticked = index in removed
                        Box(
                            modifier = Modifier
                                .aspectRatio(0.6f)
                                .border(
                                    if (ticked) 3.dp else 1.dp,
                                    if (ticked) MaterialTheme.colorScheme.error else Color.Gray,
                                    RoundedCornerShape(6.dp)
                                )
                                .clickable { onRemovedChange(if (ticked) removed - index else removed + index) }
                        ) {
                            AsyncImage(
                                model = frame.file,
                                contentDescription = "Picture ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            Checkbox(
                                checked = ticked,
                                onCheckedChange = { onRemovedChange(if (ticked) removed - index else removed + index) },
                                modifier = Modifier.align(Alignment.TopStart)
                            )
                            Text(
                                "${index + 1}" + if (frame.unclear) "  unclear" else "",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.align(Alignment.BottomStart).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Uploads every part as 1.jpg, 2.jpg... and copies the addresses; returns the message to show. */
private suspend fun uploadParts(context: Context, files: List<File>, onStatus: (String) -> Unit): Result<String> = runCatching {
    val folder = ShareUploader.serverFolder(context) ?: error("No server is set up")
    val urls = mutableListOf<String>()
    files.forEachIndexed { index, file ->
        val name = "${index + 1}.jpg"
        onStatus("Uploading $name…")
        val source = ShareUploader.Source(name, file.length(), "image/jpeg")
        ShareUploader.upload(context, Uri.fromFile(file), source, name, onProgress = {}, cancelled = { false })
        urls += "$folder/$name"
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Uploaded URL", urls.joinToString("\n")))
    "Uploaded. The URL is copied to the clipboard:\n" + urls.joinToString("\n")
}

/** Saves the pictures in the phone's gallery (Pictures/ScrollShots). */
private fun saveToPhone(context: Context, files: List<File>): String {
    var saved = 0
    for (file in files) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ScrollShots")
                }
                val target = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no target")
                context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ScrollShots").apply { mkdirs() }
                file.copyTo(File(dir, file.name), overwrite = true)
            }
            saved++
        }
    }
    return if (saved == files.size) "Saved in the gallery (Pictures/ScrollShots)." else "Could only save $saved of ${files.size}."
}

private fun shareParts(context: Context, files: List<File>) {
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) })
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    }.apply {
        type = "image/jpeg"
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    AppLockManager.suppressNextLock()
    context.startActivity(Intent.createChooser(intent, "Share the long picture"))
}

private const val TILE_LENGTH = 1024
private const val DIVIDER_LENGTH = 10

/**
 * Shows the long (or wide) picture(s) one after the other and lets you move over it in every direction:
 * drag to scroll up, down, left and right, pinch (or the + and - buttons, or a double tap) to zoom in and
 * out. Only the pieces on the screen are loaded (at a lower detail when zoomed out), so a very long
 * picture is fine.
 */
@Composable
private fun LongPictureViewer(files: List<File>, horizontal: Boolean, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val decoders = remember(files) { files.map { BitmapRegionDecoder.newInstance(it.absolutePath, false) } }
    DisposableEffect(decoders) { onDispose { decoders.forEach { runCatching { it.recycle() } } } }
    fun along(d: BitmapRegionDecoder) = if (horizontal) d.width else d.height
    val across = if (horizontal) decoders.first().height else decoders.first().width
    // Where each picture starts in the combined picture (with a thin line between pictures).
    val partStarts = remember(decoders) {
        var position = 0
        decoders.map { d -> val start = position; position += along(d) + DIVIDER_LENGTH; start }
    }
    val totalAlong = along(decoders.last()) + partStarts.last()

    BoxWithConstraints(modifier = modifier.background(Color(0xFF3A3A3A))) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        // First the whole width (or, for a sideways picture, the whole height) is shown.
        val fit = if (horizontal) viewH / across else viewW / across
        var scale by remember(files) { mutableFloatStateOf(fit) }
        var offsetX by remember(files) { mutableFloatStateOf(0f) }
        var offsetY by remember(files) { mutableFloatStateOf(0f) }
        val tiles = remember(files) { mutableStateMapOf<Long, ImageBitmap>() }
        val order = remember(files) { ArrayDeque<Long>() }
        var flingJob by remember { mutableStateOf<Job?>(null) }
        val minScale = fit * 0.25f
        val maxScale = fit * 8f

        fun contentW(s: Float) = (if (horizontal) totalAlong else across) * s
        fun contentH(s: Float) = (if (horizontal) across else totalAlong) * s
        fun clampX(x: Float, s: Float): Float {
            val w = contentW(s)
            return if (w <= viewW) (viewW - w) / 2f else x.coerceIn(viewW - w, 0f)
        }
        fun clampY(y: Float, s: Float): Float {
            val h = contentH(s)
            return if (h <= viewH) (viewH - h) / 2f else y.coerceIn(viewH - h, 0f)
        }
        fun zoomAt(centroid: Offset, factor: Float) {
            val newScale = (scale * factor).coerceIn(minScale, maxScale)
            val imageX = (centroid.x - offsetX) / scale
            val imageY = (centroid.y - offsetY) / scale
            scale = newScale
            offsetX = clampX(centroid.x - imageX * newScale, newScale)
            offsetY = clampY(centroid.y - imageY * newScale, newScale)
        }
        LaunchedEffect(files, viewW, viewH) { offsetX = clampX(offsetX, scale); offsetY = clampY(offsetY, scale) }

        // The detail needed: half, quarter... of the picture's pixels when zoomed out.
        val sampleLog = when {
            scale >= 0.9f -> 0
            scale >= 0.45f -> 1
            scale >= 0.22f -> 2
            else -> 3
        }
        // The part of the combined picture on the screen now, along its direction.
        val visibleFrom = ((if (horizontal) -offsetX else -offsetY) / scale).coerceAtLeast(0f)
        val visibleTo = (((if (horizontal) -offsetX + viewW else -offsetY + viewH)) / scale).coerceAtMost(totalAlong.toFloat())
        val needed = remember(visibleFrom.toInt() / 256, visibleTo.toInt() / 256, sampleLog, files) {
            val list = mutableListOf<Long>()
            for (p in decoders.indices) {
                val partStart = partStarts[p]
                val partEnd = partStart + along(decoders[p])
                if (partEnd < visibleFrom || partStart > visibleTo) continue
                val firstTile = ((max(visibleFrom, partStart.toFloat()) - partStart) / TILE_LENGTH).toInt().coerceAtLeast(0)
                val lastTile = ((min(visibleTo, partEnd.toFloat()) - partStart) / TILE_LENGTH).toInt()
                for (t in firstTile..lastTile) {
                    if (t * TILE_LENGTH < along(decoders[p])) list += tileKey(p, t, sampleLog)
                }
            }
            list
        }
        LaunchedEffect(needed) {
            for (key in needed) {
                if (tiles.containsKey(key)) continue
                val (p, t, s) = splitKey(key)
                val image = withContext(Dispatchers.IO) {
                    val decoder = decoders[p]
                    val from = t * TILE_LENGTH
                    val to = min(along(decoder), from + TILE_LENGTH)
                    val region = if (horizontal) Rect(from, 0, to, decoder.height) else Rect(0, from, decoder.width, to)
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 shl s }
                    synchronized(decoder) { decoder.decodeRegion(region, options) }?.asImageBitmap()
                } ?: continue
                tiles[key] = image
                order.addLast(key)
                // Keep only the newest pieces (a piece is about 4 MB at full detail).
                while (order.size > 36) tiles.remove(order.removeFirst())
            }
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(files) {
                    detectTapGestures(onDoubleTap = { position ->
                        // Double tap: close up, and back to the whole width.
                        val target = if (scale < fit * 1.6f) fit * 2.5f else fit
                        zoomAt(position, target / scale)
                    })
                }
                .pointerInput(files) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        flingJob?.cancel()
                        val tracker = VelocityTracker()
                        var several = false
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.count { it.pressed } > 1) several = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            if (zoom != 1f) zoomAt(centroid, zoom)
                            if (pan != Offset.Zero) {
                                offsetX = clampX(offsetX + pan.x, scale)
                                offsetY = clampY(offsetY + pan.y, scale)
                                event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                            }
                            if (zoom != 1f || pan != Offset.Zero) event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        // A flick keeps the picture sliding for a moment, like a normal list.
                        if (!several) {
                            val velocity = tracker.calculateVelocity()
                            val speed = if (horizontal) velocity.x else velocity.y
                            if (kotlin.math.abs(speed) > 300f) {
                                flingJob = scope.launch {
                                    if (horizontal) {
                                        Animatable(offsetX).animateDecay(speed, exponentialDecay()) { offsetX = clampX(value, scale) }
                                    } else {
                                        Animatable(offsetY).animateDecay(speed, exponentialDecay()) { offsetY = clampY(value, scale) }
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            val s = scale
            for (p in decoders.indices) {
                val partStart = partStarts[p]
                // The line between two pictures.
                if (p > 0) {
                    val lineStart = (partStart - DIVIDER_LENGTH) * s
                    if (horizontal) {
                        drawRect(Color(0xFFC62828), topLeft = Offset(offsetX + lineStart, offsetY), size = androidx.compose.ui.geometry.Size(DIVIDER_LENGTH * s, across * s))
                    } else {
                        drawRect(Color(0xFFC62828), topLeft = Offset(offsetX, offsetY + lineStart), size = androidx.compose.ui.geometry.Size(across * s, DIVIDER_LENGTH * s))
                    }
                }
                val partLength = along(decoders[p])
                val tileCount = (partLength + TILE_LENGTH - 1) / TILE_LENGTH
                for (t in 0 until tileCount) {
                    val start = (partStart + t * TILE_LENGTH) * s
                    val length = min(TILE_LENGTH, partLength - t * TILE_LENGTH) * s
                    val screenStart = (if (horizontal) offsetX else offsetY) + start
                    val screenEnd = screenStart + length
                    if (screenStart > (if (horizontal) size.width else size.height) || screenEnd < 0f) continue
                    // The wanted detail, or any detail already loaded for this piece (so nothing flashes empty).
                    val image = (sampleLog..3).firstNotNullOfOrNull { tiles[tileKey(p, t, it)] }
                        ?: (0 until sampleLog).firstNotNullOfOrNull { tiles[tileKey(p, t, it)] }
                    val tileX = if (horizontal) offsetX + start else offsetX
                    val tileY = if (horizontal) offsetY else offsetY + start
                    val tileW = if (horizontal) length else across * s
                    val tileH = if (horizontal) across * s else length
                    if (image != null) {
                        drawImage(
                            image,
                            dstOffset = IntOffset(tileX.toInt(), tileY.toInt()),
                            dstSize = IntSize(tileW.toInt() + 1, tileH.toInt() + 1)
                        )
                    } else {
                        drawRect(Color(0xFF555555), topLeft = Offset(tileX, tileY), size = androidx.compose.ui.geometry.Size(tileW, tileH))
                    }
                }
            }
        }

        // Zoom buttons (pinching does the same).
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCCFFFFFF), RoundedCornerShape(24.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { zoomAt(Offset(viewW / 2f, viewH / 2f), 1f / 1.5f) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
            }
            Text("${(scale / fit * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { zoomAt(Offset(viewW / 2f, viewH / 2f), 1.5f) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
            }
        }
    }
}

private fun tileKey(part: Int, tile: Int, sampleLog: Int): Long = (part.toLong() shl 40) or (tile.toLong() shl 8) or sampleLog.toLong()

private fun splitKey(key: Long): Triple<Int, Int, Int> =
    Triple((key shr 40).toInt(), ((key shr 8) and 0xFFFFFFFFL).toInt(), (key and 0xFF).toInt())
