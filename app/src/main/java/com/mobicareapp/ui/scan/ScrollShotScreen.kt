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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
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
    data class Done(val result: ScrollStitcher.Stitched) : ScrollStage()
    data class Failed(val message: String) : ScrollStage()
}

/**
 * Makes one long picture from a screen recording of something being scrolled, and shows it right here
 * (pinch to zoom in and out, drag in every direction, double tap to zoom), so it can be read from top to
 * bottom without saving anything. The picture can also be read as text (OCR), uploaded, saved or shared.
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

    // Picking a video leaves the app for a moment, and making the picture takes a while.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }
    BackHandler(onBack = onBack)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            stage = ScrollStage.Working("Starting…")
            scope.launch {
                ScrollStitcher.stitch(context, uri) { status -> stage = ScrollStage.Working(status) }
                    .onSuccess { stage = ScrollStage.Done(it) }
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
                            "Record your screen while you slowly scroll down a page (a chat, a web page, a document), then choose that video here. " +
                                "The pictures of the scrolled screen are joined into one long picture that you can read from top to bottom, " +
                                "with zoom, in any direction, and read as text.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Scroll at a steady, not too fast speed for the best result. Bars that stay in place (a title bar, a bottom bar) are left out.",
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
                    // The buttons: read the text, upload, save to the phone, share.
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                        "${result.files.size} picture(s), ${result.heights.sum()} px tall" +
                            if (result.jumps > 0) ". The scrolling jumped ${result.jumps} time(s): a thin red line marks where." else ".",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    LongPictureViewer(files = result.files, modifier = Modifier.weight(1f).fillMaxWidth())
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

private const val TILE_HEIGHT = 1024
private const val DIVIDER_HEIGHT = 10

/**
 * Shows the long picture(s) one above the other and lets you move over it in every direction: drag to
 * scroll up, down, left and right, pinch (or the + and - buttons, or a double tap) to zoom in and out. Only
 * the pieces on the screen are loaded (at a lower detail when zoomed out), so a very long picture is fine.
 */
@Composable
private fun LongPictureViewer(files: List<File>, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val decoders = remember(files) { files.map { BitmapRegionDecoder.newInstance(it.absolutePath, false) } }
    DisposableEffect(decoders) { onDispose { decoders.forEach { runCatching { it.recycle() } } } }
    val imageWidth = decoders.first().width
    // Where each picture starts in the combined picture (with a thin line between pictures).
    val partTops = remember(decoders) {
        var y = 0
        decoders.map { d -> val top = y; y += d.height + DIVIDER_HEIGHT; top }
    }
    val totalHeight = decoders.last().height + partTops.last()

    BoxWithConstraints(modifier = modifier.background(Color(0xFF3A3A3A))) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val fit = viewW / imageWidth
        var scale by remember(files) { mutableFloatStateOf(fit) }
        var offsetX by remember(files) { mutableFloatStateOf(0f) }
        var offsetY by remember(files) { mutableFloatStateOf(0f) }
        val tiles = remember(files) { mutableStateMapOf<Long, ImageBitmap>() }
        val order = remember(files) { ArrayDeque<Long>() }
        var flingJob by remember { mutableStateOf<Job?>(null) }
        val minScale = fit * 0.25f
        val maxScale = fit * 8f

        fun clampX(x: Float, s: Float): Float {
            val w = imageWidth * s
            return if (w <= viewW) (viewW - w) / 2f else x.coerceIn(viewW - w, 0f)
        }
        fun clampY(y: Float, s: Float): Float {
            val h = totalHeight * s
            return if (h <= viewH) 0f else y.coerceIn(viewH - h, 0f)
        }
        fun zoomAt(centroid: Offset, factor: Float) {
            val newScale = (scale * factor).coerceIn(minScale, maxScale)
            val imageX = (centroid.x - offsetX) / scale
            val imageY = (centroid.y - offsetY) / scale
            scale = newScale
            offsetX = clampX(centroid.x - imageX * newScale, newScale)
            offsetY = clampY(centroid.y - imageY * newScale, newScale)
        }
        LaunchedEffect(files, viewW) { offsetX = clampX(offsetX, scale); offsetY = clampY(offsetY, scale) }

        // The detail needed: half, quarter... of the picture's pixels when zoomed out.
        val sampleLog = when {
            scale >= 0.9f -> 0
            scale >= 0.45f -> 1
            scale >= 0.22f -> 2
            else -> 3
        }
        // Which pieces are showing now: (picture, piece, detail).
        val visibleTop = (-offsetY / scale).coerceAtLeast(0f)
        val visibleBottom = ((-offsetY + viewH) / scale).coerceAtMost(totalHeight.toFloat())
        val needed = remember(visibleTop.toInt() / 256, visibleBottom.toInt() / 256, sampleLog, files) {
            val list = mutableListOf<Long>()
            for (p in decoders.indices) {
                val partTop = partTops[p]
                val partBottom = partTop + decoders[p].height
                if (partBottom < visibleTop || partTop > visibleBottom) continue
                val firstTile = ((max(visibleTop, partTop.toFloat()) - partTop) / TILE_HEIGHT).toInt().coerceAtLeast(0)
                val lastTile = ((min(visibleBottom, partBottom.toFloat()) - partTop) / TILE_HEIGHT).toInt()
                for (t in firstTile..lastTile) {
                    if (t * TILE_HEIGHT < decoders[p].height) list += tileKey(p, t, sampleLog)
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
                    val top = t * TILE_HEIGHT
                    val bottom = min(decoder.height, top + TILE_HEIGHT)
                    val options = BitmapFactory.Options().apply { inSampleSize = 1 shl s }
                    synchronized(decoder) { decoder.decodeRegion(Rect(0, top, decoder.width, bottom), options) }?.asImageBitmap()
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
                            if (kotlin.math.abs(velocity.y) > 300f) {
                                flingJob = scope.launch {
                                    Animatable(offsetY).animateDecay(velocity.y, exponentialDecay()) { offsetY = clampY(value, scale) }
                                }
                            }
                        }
                    }
                }
        ) {
            val s = scale
            for (p in decoders.indices) {
                val partTop = partTops[p]
                // The line between two pictures.
                if (p > 0) {
                    val y = offsetY + (partTop - DIVIDER_HEIGHT) * s
                    drawRect(Color(0xFFC62828), topLeft = Offset(0f, y), size = androidx.compose.ui.geometry.Size(size.width, DIVIDER_HEIGHT * s))
                }
                val tileCount = (decoders[p].height + TILE_HEIGHT - 1) / TILE_HEIGHT
                for (t in 0 until tileCount) {
                    val top = offsetY + (partTop + t * TILE_HEIGHT) * s
                    val height = min(TILE_HEIGHT, decoders[p].height - t * TILE_HEIGHT) * s
                    if (top > size.height || top + height < 0f) continue
                    // The wanted detail, or any detail already loaded for this piece (so nothing flashes empty).
                    val image = (sampleLog..3).firstNotNullOfOrNull { tiles[tileKey(p, t, it)] }
                        ?: (0 until sampleLog).firstNotNullOfOrNull { tiles[tileKey(p, t, it)] }
                    if (image != null) {
                        drawImage(
                            image,
                            dstOffset = IntOffset(offsetX.toInt(), top.toInt()),
                            dstSize = IntSize((imageWidth * s).toInt() + 1, height.toInt() + 1)
                        )
                    } else {
                        drawRect(Color(0xFF555555), topLeft = Offset(offsetX, top), size = androidx.compose.ui.geometry.Size(imageWidth * s, height))
                    }
                }
            }
        }

        // Zoom buttons (pinching does the same).
        Row(
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCCFFFFFF), androidx.compose.foundation.shape.RoundedCornerShape(24.dp)),
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
