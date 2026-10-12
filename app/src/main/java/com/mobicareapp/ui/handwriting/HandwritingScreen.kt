package com.mobicareapp.ui.handwriting

import android.content.ClipData
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
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
private val RuleColor = Color(0xFFBBD6EC)
private val MarginColor = Color(0xFFEBA8A8)
private val InkColor = Color(0xFF14213D)

/** Distance between the ruled lines, in page widths — wide enough to write comfortably with a finger. */
private const val RULE_SPACING = 0.09f
private const val INK_WIDTH = 0.0048f

/**
 * A full-screen notepad for English handwriting: write with a finger or stylus on ruled paper, and
 * the handwriting is read into text (shown in the panel at the top, editable) with spelling and
 * grammar repaired. Undo/Redo step through strokes; when a page fills up, "Next page" starts a fresh
 * one, as many times as needed. The text of all pages together opens in a full-screen preview where
 * it can be corrected and copied.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandwritingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: HandwritingViewModel = viewModel()
    val pages by viewModel.pages.collectAsState()
    val pageIndex by viewModel.pageIndex.collectAsState()
    val model by viewModel.model.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val autoFix by viewModel.autoFix.collectAsState()

    // The notepad is saved to disk, but locking would still swap this screen out mid-sentence.
    DisposableEffect(Unit) {
        AppLockManager.acquireLockHold()
        onDispose { AppLockManager.releaseLockHold() }
    }

    var showPreview by remember { mutableStateOf(false) }
    var previewText by remember { mutableStateOf("") }
    var panelOpen by remember { mutableStateOf(true) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClearPage by remember { mutableStateOf(false) }
    var confirmNewNotepad by remember { mutableStateOf(false) }
    var fixingPage by remember { mutableStateOf(false) }
    var canvasRatio by remember { mutableStateOf(1.8f) }

    if (showPreview) {
        HandwritingPreviewScreen(
            text = previewText,
            onTextChange = { previewText = it },
            onFix = { viewModel.repair(it) },
            onRefresh = { previewText = viewModel.combinedText() },
            onBack = { showPreview = false }
        )
        return
    }

    val page = pages[pageIndex.coerceIn(0, pages.lastIndex)]
    val index = pageIndex.coerceIn(0, pages.lastIndex)
    // Nudge towards "Next page" once writing reaches the bottom edge.
    val pageFull = page.lowestY > canvasRatio * 0.9f
    val suggestions by viewModel.suggestions.collectAsState()
    val readError by viewModel.readError.collectAsState()

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Handwriting  ${index + 1}/${pages.size}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = { viewModel.undo() }, enabled = page.items.isNotEmpty()) {
                        Icon(Icons.Default.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { viewModel.redo() }, enabled = page.redo.isNotEmpty()) {
                        Icon(Icons.Default.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { previewText = viewModel.combinedText(); showPreview = true }) {
                        Icon(Icons.Default.Fullscreen, contentDescription = "Full preview")
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(if (autoFix) "Auto-fix spelling & grammar: ON" else "Auto-fix spelling & grammar: OFF") },
                            onClick = { menuOpen = false; viewModel.setAutoFix(!autoFix) }
                        )
                        DropdownMenuItem(text = { Text("Clear this page") }, onClick = { menuOpen = false; confirmClearPage = true })
                        DropdownMenuItem(text = { Text("New notepad") }, onClick = { menuOpen = false; confirmNewNotepad = true })
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val state = model) {
                is ModelState.Checking, is ModelState.Downloading -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state is ModelState.Downloading) "Downloading the English handwriting model (one time)…" else "Preparing handwriting…",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                is ModelState.Failed -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.prepareModel() }) { Text("Retry") }
                    }
                }
                is ModelState.Ready -> {}
            }

            // The text read from this page — a pop-down panel, editable, so a misread word can be fixed right here.
            Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { panelOpen = !panelOpen },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Text from this page", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        if (busy || fixingPage) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(6.dp))
                            Text("Reading…", style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(if (panelOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = if (panelOpen) "Hide" else "Show")
                    }
                    if (panelOpen) {
                        OutlinedTextField(
                            value = page.text,
                            onValueChange = { viewModel.setPageText(index, it) },
                            placeholder = { Text("Your handwriting appears here as text…") },
                            minLines = 2,
                            maxLines = 4,
                            modifier = Modifier.fillMaxWidth()
                        )
                        readError?.let { message ->
                            Text(
                                "Couldn't read the handwriting: $message",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
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
                            // Reads the handwriting right now (it normally happens a moment after the pen stops).
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
                                TextButton(onClick = { viewModel.rereadPage(index) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Re-read")
                                }
                            }
                        }
                    }
                }
            }

            // Word suggestions for the word being written. Always takes the same height, so the pad
            // below doesn't jump up and down as suggestions come and go.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (suggestions.isEmpty()) {
                    Text(
                        "Write 2-3 letters of a word — suggested words appear here. Tap one to finish the word.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    suggestions.forEach { word ->
                        SuggestionChip(
                            onClick = { viewModel.pickSuggestion(word) },
                            label = { Text(word, style = MaterialTheme.typography.titleMedium) }
                        )
                    }
                }
            }

            // The writing pad itself: fills all the space that's left.
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp)) {
                HandwritingCanvas(
                    items = page.items,
                    onStroke = { viewModel.addStroke(it) },
                    onSize = { ratio -> canvasRatio = ratio },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { viewModel.previousPage() }, enabled = index > 0) {
                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Previous")
                }
                Text(
                    "Page ${index + 1} of ${pages.size}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                // Filled and relabelled when the page is full, plain otherwise — it works either way (on the last page it adds a new one).
                if (pageFull) {
                    Button(onClick = { viewModel.nextPage() }) {
                        Text("Page full — Next page")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                } else {
                    OutlinedButton(onClick = { viewModel.nextPage() }) {
                        Text("Next page")
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Ruled paper that records strokes (one finger or stylus) and draws them as smooth curves. */
@Composable
private fun HandwritingCanvas(
    items: List<PageItem>,
    onStroke: (HwStroke) -> Unit,
    onSize: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var live by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val textMeasurer = rememberTextMeasurer()

    Canvas(
        modifier = modifier
            .background(PaperColor)
            .onSizeChanged { if (it.width > 0) onSize(it.height.toFloat() / it.width) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat().coerceAtLeast(1f)
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
                        points += InkPt(change.position.x / width, change.position.y / width, change.uptimeMillis)
                        live = live + change.position
                        change.consume()
                    }
                    live = emptyList()
                    // A tap becomes a dot (a full stop, the dot of an "i"): two identical points so it has a direction.
                    if (points.size == 1) points += points.first().copy(t = points.first().t + 1)
                    onStroke(HwStroke(points))
                }
            }
    ) {
        val spacing = size.width * RULE_SPACING
        var y = spacing
        while (y < size.height) {
            drawLine(RuleColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5f)
            y += spacing
        }
        drawLine(MarginColor, Offset(size.width * 0.1f, 0f), Offset(size.width * 0.1f, size.height), strokeWidth = 2f)

        val inkWidth = size.width * INK_WIDTH
        items.forEach { item ->
            when (item) {
                is InkItem -> drawInk(item.stroke.points.map { Offset(it.x * size.width, it.y * size.width) }, inkWidth)
                // A word completed from a suggestion, set in a handwriting-style font where the unfinished word was.
                is WordItem -> drawText(
                    textMeasurer = textMeasurer,
                    text = item.text,
                    topLeft = Offset(item.x * size.width, item.y * size.width),
                    style = TextStyle(fontSize = (item.size * size.width * 1.15f).toSp(), fontFamily = FontFamily.Cursive, color = InkColor)
                )
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
