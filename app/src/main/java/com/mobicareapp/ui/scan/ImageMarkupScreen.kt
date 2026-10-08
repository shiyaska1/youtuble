package com.mobicareapp.ui.scan

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Environment
import com.mobicareapp.applock.AppLockManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStyle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

private enum class MarkupTool { PAN, PEN, HIGHLIGHTER, CIRCLE, TEXT }

private data class DrawnStroke(val color: Color, val widthPx: Float, val alpha: Float, val points: List<Offset>)
private data class DrawnCircle(val color: Color, val widthPx: Float, val center: Offset, val radius: Float)
/** A typed note placed on the image: [position] is where the top-left of the text sits, in the same display-pixel space as the strokes. */
private data class DrawnText(val text: String, val color: Color, val sizePx: Float, val position: Offset)
private sealed class MarkupAction {
    data class StrokeAction(val stroke: DrawnStroke) : MarkupAction()
    data class CircleAction(val circle: DrawnCircle) : MarkupAction()
    data class TextAction(val text: DrawnText) : MarkupAction()
}

private val TEXT_SIZES = listOf("Small" to 32f, "Medium" to 48f, "Large" to 72f)

private val PEN_COLORS = listOf(Color.Red, Color(0xFF1565C0), Color(0xFF2E7D32), Color.Black)
private val HIGHLIGHTER_COLOR = Color(0xFFFFEB3B)

/**
 * Pick a photo, mark it up (freehand pen, a yellow highlighter, or circles), pinch-zoom in for
 * precision, then share or save the result — for calling out something in a screenshot or photo
 * without needing a separate image editor app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageMarkupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Locking swaps out the whole nav graph and disposes this screen's state (see
    // AppLockManager's own comment on suppressNextLock) — losing every stroke/circle drawn so far
    // to a lock triggered by, say, the screen timing out mid-edit. Held for as long as this screen
    // is open, not just around the image picker's own hand-off, since drawing can take a while.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var baseSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var tool by remember { mutableStateOf(MarkupTool.PEN) }
    var penColor by remember { mutableStateOf(Color.Red) }
    val history = remember { mutableStateListOf<MarkupAction>() }
    val strokes = remember { mutableStateListOf<DrawnStroke>() }
    val circles = remember { mutableStateListOf<DrawnCircle>() }
    var currentPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var circleStart by remember { mutableStateOf<Offset?>(null) }
    var circleCurrent by remember { mutableStateOf<Offset?>(null) }
    val texts = remember { mutableStateListOf<DrawnText>() }
    // Where the user tapped in Text mode (non-null while the "type your note" box is showing).
    var pendingTextPosition by remember { mutableStateOf<Offset?>(null) }
    var pendingTextInput by remember { mutableStateOf("") }
    var pendingTextSize by remember { mutableStateOf(TEXT_SIZES[1].second) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val loaded = withContext(Dispatchers.IO) { loadUprightBitmap(context, uri) }
                bitmap = loaded
                scale = 1f
                panOffset = Offset.Zero
                history.clear()
                strokes.clear()
                circles.clear()
                texts.clear()
            }
        }
    }

    fun undo() {
        val last = history.removeLastOrNull() ?: return
        when (last) {
            is MarkupAction.StrokeAction -> strokes.remove(last.stroke)
            is MarkupAction.CircleAction -> circles.remove(last.circle)
            is MarkupAction.TextAction -> texts.remove(last.text)
        }
    }

    fun exportBitmap(): Bitmap? {
        val original = bitmap ?: return null
        if (baseSize.width <= 0) return original
        val scaleFactor = original.width.toFloat() / baseSize.width.toFloat()
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        for (s in strokes) {
            paint.color = s.color.toArgb()
            paint.alpha = (s.alpha * 255).roundToInt()
            paint.strokeWidth = s.widthPx * scaleFactor
            val path = android.graphics.Path()
            s.points.forEachIndexed { i, p ->
                val x = p.x * scaleFactor
                val y = p.y * scaleFactor
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        for (c in circles) {
            paint.color = c.color.toArgb()
            paint.alpha = 255
            paint.strokeWidth = c.widthPx * scaleFactor
            canvas.drawCircle(c.center.x * scaleFactor, c.center.y * scaleFactor, c.radius * scaleFactor, paint)
        }
        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        for (t in texts) {
            textPaint.color = t.color.toArgb()
            textPaint.textSize = t.sizePx * scaleFactor
            drawTextLines(canvas, t, textPaint, scaleFactor)
        }
        return result
    }

    fun saveToFile(): File {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "markup").apply { mkdirs() }
        val name = "MARKUP_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "$name.jpg")
        FileOutputStream(file).use { out -> exportBitmap()?.compress(Bitmap.CompressFormat.JPEG, 92, out) }
        return file
    }

    val saveAsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            exportBitmap()?.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        }
                    }
                }
            }
        }
    }

    pendingTextPosition?.let { position ->
        AlertDialog(
            onDismissRequest = { pendingTextPosition = null },
            title = { Text("Type your note") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = pendingTextInput,
                        onValueChange = { pendingTextInput = it },
                        label = { Text("Text") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TEXT_SIZES.forEach { (label, size) ->
                            FilterChip(
                                selected = pendingTextSize == size,
                                onClick = { pendingTextSize = size },
                                label = { Text(label) }
                            )
                        }
                    }
                    // The note takes the colour picked in the row under the tools.
                    Box(
                        modifier = Modifier.size(20.dp).clip(CircleShape).background(penColor)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = pendingTextInput.isNotBlank(),
                    onClick = {
                        val note = DrawnText(pendingTextInput.trim(), penColor, pendingTextSize, position)
                        texts.add(note)
                        history.add(MarkupAction.TextAction(note))
                        pendingTextPosition = null
                    }
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { pendingTextPosition = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Mark up image") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (bitmap != null) {
                        IconButton(onClick = { undo() }, enabled = history.isNotEmpty()) {
                            Icon(Icons.Default.Undo, contentDescription = "Undo")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) { saveToFile() }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(Intent.createChooser(intent, "Share marked-up image"))
                            }
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Share")
                        }
                        IconButton(onClick = {
                            val name = "MARKUP_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
                            saveAsLauncher.launch(name)
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "Save to device")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val bmp = bitmap
            if (bmp == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        "Pick a photo to mark up — draw freehand, highlight, or circle something, then share or save it.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text("Choose a photo")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolButton(Icons.Default.PanTool, "Pan/zoom", tool == MarkupTool.PAN) { tool = MarkupTool.PAN }
                    ToolButton(Icons.Default.Edit, "Pen", tool == MarkupTool.PEN) { tool = MarkupTool.PEN }
                    ToolButton(Icons.Default.Brush, "Highlighter", tool == MarkupTool.HIGHLIGHTER) { tool = MarkupTool.HIGHLIGHTER }
                    ToolButton(Icons.Default.Circle, "Circle", tool == MarkupTool.CIRCLE) { tool = MarkupTool.CIRCLE }
                    ToolButton(Icons.Default.TextFields, "Text", tool == MarkupTool.TEXT) { tool = MarkupTool.TEXT }
                }
                // Always occupies the same space, whether or not it's relevant to the current
                // tool (hidden via alpha, not conditional composition) — this row appearing or
                // disappearing used to change how much height was left for the image viewport
                // below, which shifted the coordinate space every mark's position is stored in.
                // Switching tools (e.g. to Pan, to zoom out) after marking something then silently
                // moved that mark relative to the image, since the box it was drawn against had
                // changed size in the meantime.
                val colorRowRelevant = tool == MarkupTool.PEN || tool == MarkupTool.CIRCLE || tool == MarkupTool.TEXT
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .alpha(if (colorRowRelevant) 1f else 0f),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PEN_COLORS.forEach { c ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (penColor == c) 3.dp else 0.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable(enabled = colorRowRelevant) { penColor = c }
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))

                BoxWithConstraints(
                    // graphicsLayer scaling is a render-only transform — it doesn't change this
                    // Box's actual layout bounds, so a zoomed-in image visually overflowed past
                    // this container and drew on top of the toolbar above it (which is drawn
                    // earlier and otherwise has nothing clipping the sibling below it). Clipping
                    // this viewport keeps the zoomed content contained to its own area.
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .clipToBounds()
                ) {
                    val maxWidthPx = with(density) { maxWidth.toPx() }
                    val maxHeightPx = with(density) { maxHeight.toPx() }
                    val bmpAspect = bmp.width.toFloat() / bmp.height.toFloat()
                    val boxAspect = if (maxHeightPx > 0) maxWidthPx / maxHeightPx else 1f
                    val dispW: Float
                    val dispH: Float
                    if (bmpAspect > boxAspect) {
                        dispW = maxWidthPx
                        dispH = maxWidthPx / bmpAspect
                    } else {
                        dispH = maxHeightPx
                        dispW = maxHeightPx * bmpAspect
                    }
                    LaunchedEffect(bmp, dispW, dispH) {
                        baseSize = IntSize(dispW.roundToInt().coerceAtLeast(1), dispH.roundToInt().coerceAtLeast(1))
                    }

                    Box(
                        modifier = Modifier
                            .size(with(density) { dispW.toDp() }, with(density) { dispH.toDp() })
                            .align(Alignment.Center)
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = panOffset.x,
                                translationY = panOffset.y
                            )
                            .pointerInput(tool) {
                                when (tool) {
                                    MarkupTool.PAN -> {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            scale = (scale * zoom).coerceIn(1f, 6f)
                                            panOffset += pan
                                        }
                                    }
                                    MarkupTool.TEXT -> {
                                        // A tap (not a drag) places the note: opens the typing box for that spot.
                                        detectTapGestures { position ->
                                            pendingTextPosition = position
                                            pendingTextInput = ""
                                        }
                                    }
                                    MarkupTool.CIRCLE -> {
                                        detectDragGestures(
                                            onDragStart = { start -> circleStart = start; circleCurrent = start },
                                            onDrag = { change, _ -> change.consume(); circleCurrent = change.position },
                                            onDragEnd = {
                                                val start = circleStart
                                                val end = circleCurrent
                                                if (start != null && end != null) {
                                                    val cx = (start.x + end.x) / 2f
                                                    val cy = (start.y + end.y) / 2f
                                                    val radius = hypot((end.x - start.x) / 2f, (end.y - start.y) / 2f)
                                                    if (radius > 4f) {
                                                        val circle = DrawnCircle(penColor, 7f, Offset(cx, cy), radius)
                                                        circles.add(circle)
                                                        history.add(MarkupAction.CircleAction(circle))
                                                    }
                                                }
                                                circleStart = null
                                                circleCurrent = null
                                            }
                                        )
                                    }
                                    else -> {
                                        detectDragGestures(
                                            onDragStart = { start -> currentPoints = listOf(start) },
                                            onDrag = { change, _ -> change.consume(); currentPoints = currentPoints + change.position },
                                            onDragEnd = {
                                                if (currentPoints.size > 1) {
                                                    val isHighlighter = tool == MarkupTool.HIGHLIGHTER
                                                    val stroke = DrawnStroke(
                                                        color = if (isHighlighter) HIGHLIGHTER_COLOR else penColor,
                                                        widthPx = if (isHighlighter) 32f else 7f,
                                                        alpha = if (isHighlighter) 0.45f else 1f,
                                                        points = currentPoints
                                                    )
                                                    strokes.add(stroke)
                                                    history.add(MarkupAction.StrokeAction(stroke))
                                                }
                                                currentPoints = emptyList()
                                            }
                                        )
                                    }
                                }
                            }
                    ) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            strokes.forEach { s -> drawStrokePath(s) }
                            if (currentPoints.size > 1) {
                                val isHighlighter = tool == MarkupTool.HIGHLIGHTER
                                drawStrokePath(
                                    DrawnStroke(
                                        color = if (isHighlighter) HIGHLIGHTER_COLOR else penColor,
                                        widthPx = if (isHighlighter) 32f else 7f,
                                        alpha = if (isHighlighter) 0.45f else 1f,
                                        points = currentPoints
                                    )
                                )
                            }
                            circles.forEach { c ->
                                drawCircle(color = c.color, radius = c.radius, center = c.center, style = DrawStyle(width = c.widthPx))
                            }
                            if (texts.isNotEmpty()) {
                                drawIntoCanvas { composeCanvas ->
                                    val textPaint = android.graphics.Paint().apply {
                                        isAntiAlias = true
                                        style = android.graphics.Paint.Style.FILL
                                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    }
                                    texts.forEach { t ->
                                        textPaint.color = t.color.toArgb()
                                        textPaint.textSize = t.sizePx
                                        drawTextLines(composeCanvas.nativeCanvas, t, textPaint, 1f)
                                    }
                                }
                            }
                            val cs = circleStart
                            val ce = circleCurrent
                            if (cs != null && ce != null) {
                                val cx = (cs.x + ce.x) / 2f
                                val cy = (cs.y + ce.y) / 2f
                                val r = hypot((ce.x - cs.x) / 2f, (ce.y - cs.y) / 2f)
                                drawCircle(color = penColor, radius = r, center = Offset(cx, cy), style = DrawStyle(width = 7f))
                            }
                        }
                    }
                }

                Text(
                    when (tool) {
                        MarkupTool.PAN -> "Pinch or drag to zoom and pan for precision."
                        MarkupTool.PEN -> "Draw freehand with your finger."
                        MarkupTool.HIGHLIGHTER -> "Drag over text to highlight it in yellow."
                        MarkupTool.CIRCLE -> "Drag from the center outward to draw a circle."
                        MarkupTool.TEXT -> "Tap where you want a note, then type it."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        }
    } else {
        OutlinedButton(onClick = onClick) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        }
    }
}

/** Draws [note] line by line (Enter in the box starts a new line); [scale] maps its display-pixel position/size to the target canvas. */
private fun drawTextLines(canvas: android.graphics.Canvas, note: DrawnText, paint: android.graphics.Paint, scale: Float) {
    val lineHeight = note.sizePx * 1.2f * scale
    note.text.split('\n').forEachIndexed { index, line ->
        // Position is the text block's top-left, so the baseline sits roughly one text-size below it.
        canvas.drawText(line, note.position.x * scale, note.position.y * scale + note.sizePx * scale + index * lineHeight, paint)
    }
}

private fun DrawScope.drawStrokePath(s: DrawnStroke) {
    if (s.points.size < 2) return
    val path = Path()
    path.moveTo(s.points[0].x, s.points[0].y)
    s.points.drop(1).forEach { path.lineTo(it.x, it.y) }
    drawPath(path, color = s.color, alpha = s.alpha, style = DrawStyle(width = s.widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Decodes [uri] and rotates it upright per its EXIF orientation — a gallery photo taken in portrait is otherwise stored sideways in raw pixel data. */
private fun loadUprightBitmap(context: Context, uri: Uri): Bitmap {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't read that image")
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Couldn't decode that image")
    val orientation = runCatching {
        ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return decoded
    val matrix = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
}
