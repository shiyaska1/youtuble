package com.mobicareapp.ui.library

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.MusicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import androidx.core.content.FileProvider
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.MediaCategory
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.SavedMedia
import com.mobicareapp.download.DownloadService
import com.mobicareapp.playback.PlayerController
import com.mobicareapp.process.NoiseFilterProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private val SORT_OPTIONS = listOf(
    "Newest first" to SortOption.DATE_NEWEST,
    "Oldest first" to SortOption.DATE_OLDEST,
    "Largest first" to SortOption.SIZE_LARGEST,
    "Smallest first" to SortOption.SIZE_SMALLEST
)
private val MIN_SIZE_OPTIONS = listOf(
    "Any size" to 0L,
    "50 MB+" to 50L * 1024 * 1024,
    "100 MB+" to 100L * 1024 * 1024,
    "500 MB+" to 500L * 1024 * 1024
)
private val MIN_AGE_OPTIONS = listOf(
    "Any age" to 0,
    "7+ days old" to 7,
    "30+ days old" to 30,
    "90+ days old" to 90
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenVideo: (queue: List<SavedMedia>, startIndex: Int, loop: Boolean) -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val denoisingIds by viewModel.denoisingIds.collectAsState()
    val editingIds by viewModel.editingIds.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val downloadProgress by DownloadService.progress.collectAsState()
    val queuedDownloads by DownloadService.queueSize.collectAsState()

    var pendingDelete by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingBulkDelete by remember { mutableStateOf(false) }
    var pendingRename by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingMoveItem by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingMoveSelection by remember { mutableStateOf(false) }
    var pendingNewCategory by remember { mutableStateOf(false) }
    var pendingManualFilter by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingTrim by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingInsertClip by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingAddOverlay by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingCrop by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingReplaceAudio by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingMergeOrder by remember { mutableStateOf<List<SavedMedia>?>(null) }
    var pendingRotate by remember { mutableStateOf<SavedMedia?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        viewModel.importVideos(uris)
    }

    // "Save to phone" copies out to wherever the user picks. The app keeps its own files private,
    // so getting one out to shared storage is deliberately an explicit, per-file choice.
    var pendingExport by remember { mutableStateOf<SavedMedia?>(null) }
    val exportVideoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri: Uri? ->
        val item = pendingExport
        pendingExport = null
        if (uri != null && item != null) viewModel.exportTo(item, uri)
    }
    val exportAudioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/mp4")) { uri: Uri? ->
        val item = pendingExport
        pendingExport = null
        if (uri != null && item != null) viewModel.exportTo(item, uri)
    }

    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.backupTo(it)
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            context.contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.restoreFrom(it)
        }
    }

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.snackbarShown()
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${item.caption}\"?") },
            text = { Text("This removes the saved file from your phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(item)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (pendingBulkDelete) {
        val selected = state.items.filter { it.id in state.selectedIds }
        val totalSize = selected.sumOf { it.sizeBytes }
        AlertDialog(
            onDismissRequest = { pendingBulkDelete = false },
            title = { Text("Delete ${selected.size} file(s)?") },
            text = { Text("This frees up ${formatSize(totalSize)} of storage. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    pendingBulkDelete = false
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingBulkDelete = false }) { Text("Cancel") }
            }
        )
    }

    pendingRename?.let { item ->
        RenameDialog(
            initialCaption = item.caption,
            onDismiss = { pendingRename = null },
            onSave = { newCaption ->
                viewModel.rename(item, newCaption)
                pendingRename = null
            }
        )
    }

    pendingMoveItem?.let { item ->
        MoveToCategoryDialog(
            albums = state.albums,
            onDismiss = { pendingMoveItem = null },
            onAssign = { categoryId ->
                viewModel.assignCategory(setOf(item.id), categoryId)
                pendingMoveItem = null
            },
            onCreateAndAssign = { name ->
                viewModel.createAndAssignCategory(setOf(item.id), name)
                pendingMoveItem = null
            }
        )
    }

    if (pendingMoveSelection) {
        MoveToCategoryDialog(
            albums = state.albums,
            onDismiss = { pendingMoveSelection = false },
            onAssign = { categoryId ->
                viewModel.assignCategory(state.selectedIds, categoryId)
                pendingMoveSelection = false
            },
            onCreateAndAssign = { name ->
                viewModel.createAndAssignCategory(state.selectedIds, name)
                pendingMoveSelection = false
            }
        )
    }

    if (pendingNewCategory) {
        NewCategoryDialog(
            onDismiss = { pendingNewCategory = false },
            onCreate = { name ->
                viewModel.createCategory(name)
                pendingNewCategory = false
            }
        )
    }

    pendingManualFilter?.let { item ->
        ManualEqDialog(
            onDismiss = { pendingManualFilter = null },
            onApply = { bandGains ->
                viewModel.denoiseManual(item, bandGains)
                pendingManualFilter = null
            }
        )
    }

    pendingTrim?.let { item ->
        TrimDialog(
            item = item,
            onDismiss = { pendingTrim = null },
            onApply = { startMs, endMs ->
                viewModel.trim(item, startMs, endMs)
                pendingTrim = null
            }
        )
    }

    pendingInsertClip?.let { base ->
        InsertClipDialog(
            base = base,
            candidates = state.items.filter { it.type == MediaType.VIDEO && it.id != base.id },
            onDismiss = { pendingInsertClip = null },
            onApply = { insertAtMs, clip ->
                viewModel.insertClip(base, insertAtMs, clip)
                pendingInsertClip = null
            }
        )
    }

    pendingAddOverlay?.let { base ->
        OverlayDialog(
            base = base,
            candidates = state.items.filter { it.type == MediaType.VIDEO && it.id != base.id },
            onDismiss = { pendingAddOverlay = null },
            onApply = { overlay, corner ->
                viewModel.addOverlay(base, overlay, corner)
                pendingAddOverlay = null
            }
        )
    }

    pendingCrop?.let { item ->
        CropDialog(
            item = item,
            onDismiss = { pendingCrop = null },
            onApply = { left, top, right, bottom ->
                viewModel.cropVideo(item, left, top, right, bottom)
                pendingCrop = null
            }
        )
    }

    pendingReplaceAudio?.let { video ->
        ReplaceAudioDialog(
            video = video,
            candidates = state.items.filter { it.type == MediaType.AUDIO },
            onDismiss = { pendingReplaceAudio = null },
            onApply = { audio ->
                viewModel.replaceAudio(video, audio)
                pendingReplaceAudio = null
            }
        )
    }

    pendingRotate?.let { item ->
        RotateDialog(
            item = item,
            onDismiss = { pendingRotate = null },
            onApply = { degrees ->
                viewModel.rotateVideo(item, degrees)
                pendingRotate = null
            }
        )
    }

    pendingMergeOrder?.let { items ->
        MergeOrderDialog(
            items = items,
            onDismiss = { pendingMergeOrder = null },
            onApply = { ordered ->
                viewModel.mergeSelected(ordered)
                pendingMergeOrder = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = {
                        AppLockManager.suppressNextLock()
                        importLauncher.launch(arrayOf("video/*"))
                    }) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = "Import video from phone")
                    }
                    IconButton(onClick = {
                        // The system folder picker briefly takes over the foreground — without
                        // this, the app-lock treats that hand-off as backgrounding and re-locks
                        // mid-pick, which drops the chosen folder on unlock (same bug as Scan).
                        AppLockManager.suppressNextLock()
                        backupLauncher.launch(null)
                    }) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Backup")
                    }
                    IconButton(onClick = {
                        AppLockManager.suppressNextLock()
                        restoreLauncher.launch(null)
                    }) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Restore")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text("Search by name") },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onQueryChanged("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.category == CategoryFilter.ALL,
                    onClick = { viewModel.onCategoryChanged(CategoryFilter.ALL) },
                    label = { Text("All") }
                )
                FilterChip(
                    selected = state.category == CategoryFilter.VIDEO,
                    onClick = { viewModel.onCategoryChanged(CategoryFilter.VIDEO) },
                    label = { Text("Video") }
                )
                FilterChip(
                    selected = state.category == CategoryFilter.AUDIO,
                    onClick = { viewModel.onCategoryChanged(CategoryFilter.AUDIO) },
                    label = { Text("Audio (MP3)") }
                )
            }

            if (state.category != CategoryFilter.ALL && state.items.isNotEmpty()) {
                PlayAllRow(
                    isVideo = state.category == CategoryFilter.VIDEO,
                    onPlayAll = { loop ->
                        if (state.category == CategoryFilter.VIDEO) {
                            onOpenVideo(state.items, 0, loop)
                        } else {
                            viewModel.playAudioQueue(state.items, 0, loop)
                        }
                    }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = state.albumFilter == AlbumFilter.All,
                    onClick = { viewModel.onAlbumFilterChanged(AlbumFilter.All) },
                    label = { Text("All categories") }
                )
                FilterChip(
                    selected = state.albumFilter == AlbumFilter.Uncategorized,
                    onClick = { viewModel.onAlbumFilterChanged(AlbumFilter.Uncategorized) },
                    label = { Text("Uncategorized") }
                )
                state.albums.forEach { album ->
                    FilterChip(
                        selected = state.albumFilter == AlbumFilter.ById(album.id),
                        onClick = { viewModel.onAlbumFilterChanged(AlbumFilter.ById(album.id)) },
                        label = { Text(album.name) }
                    )
                }
                AssistChip(
                    onClick = { pendingNewCategory = true },
                    label = { Text("New category") },
                    leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterDropdown(
                    currentLabel = SORT_OPTIONS.first { it.second == state.sort }.first,
                    options = SORT_OPTIONS,
                    onSelect = viewModel::onSortChanged
                )
                FilterDropdown(
                    currentLabel = MIN_SIZE_OPTIONS.first { it.second == state.minSizeBytes }.first,
                    options = MIN_SIZE_OPTIONS,
                    onSelect = viewModel::onMinSizeChanged
                )
                FilterDropdown(
                    currentLabel = MIN_AGE_OPTIONS.first { it.second == state.minAgeDays }.first,
                    options = MIN_AGE_OPTIONS,
                    onSelect = viewModel::onMinAgeChanged
                )
            }

            if (state.selectionMode) {
                val selectedItems = state.items.filter { it.id in state.selectedIds }
                val allVideo = selectedItems.isNotEmpty() && selectedItems.all { it.type == MediaType.VIDEO }
                val allAudio = selectedItems.isNotEmpty() && selectedItems.all { it.type == MediaType.AUDIO }
                SelectionBar(
                    selectedCount = state.selectedIds.size,
                    canPlay = allVideo || allAudio,
                    canMerge = allVideo && selectedItems.size >= 2,
                    onCancel = viewModel::exitSelection,
                    onSelectAll = viewModel::selectAllVisible,
                    onPlay = { loop ->
                        if (allVideo) {
                            onOpenVideo(selectedItems, 0, loop)
                            viewModel.exitSelection()
                        } else {
                            viewModel.playSelected(loop)
                        }
                    },
                    onDelete = { pendingBulkDelete = true },
                    onMove = { pendingMoveSelection = true },
                    onMerge = { pendingMergeOrder = selectedItems }
                )
            }

            downloadProgress?.let { p ->
                Card(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                modifier = Modifier.weight(1f),
                                text = if (p.done) {
                                    if (p.error != null) "Failed: ${p.error}" else "Saved \"${p.caption}\""
                                } else {
                                    "Downloading \"${p.caption}\"…" + if (queuedDownloads > 1) " (${queuedDownloads - 1} more queued)" else ""
                                }
                            )
                            if (p.done) {
                                IconButton(onClick = { DownloadService.clearProgress() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                                }
                            } else {
                                TextButton(onClick = { DownloadService.cancelCurrent() }) { Text("Cancel") }
                            }
                        }
                        if (!p.done) {
                            Spacer(Modifier.height(8.dp))
                            val fraction = if (p.totalBytes > 0) p.bytesDone.toFloat() / p.totalBytes else 0f
                            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { it.id }) { item ->
                        LibraryRow(
                            item = item,
                            categoryName = state.albums.find { it.id == item.categoryId }?.name,
                            selectionMode = state.selectionMode,
                            selected = item.id in state.selectedIds,
                            isDenoising = item.id in denoisingIds,
                            isEditing = item.id in editingIds,
                            onClick = {
                                when {
                                    state.selectionMode -> viewModel.toggleSelected(item.id)
                                    item.type == MediaType.VIDEO -> {
                                        val sameType = state.items.filter { it.type == MediaType.VIDEO }
                                        onOpenVideo(sameType, sameType.indexOf(item).coerceAtLeast(0), false)
                                    }
                                    else -> {
                                        val sameType = state.items.filter { it.type == MediaType.AUDIO }
                                        viewModel.playAudioQueue(sameType, sameType.indexOf(item).coerceAtLeast(0), false)
                                    }
                                }
                            },
                            onLongClick = { viewModel.enterSelection(item.id) },
                            onRename = { pendingRename = item },
                            onMoveCategory = { pendingMoveItem = item },
                            onDelete = { pendingDelete = item },
                            onDenoise = { viewModel.denoise(item) },
                            onManualFilter = { pendingManualFilter = item },
                            onTrim = { pendingTrim = item },
                            onInsertClip = { pendingInsertClip = item },
                            onAddOverlay = { pendingAddOverlay = item },
                            onShare = {
                                AppLockManager.suppressNextLock()
                                shareMedia(context, item)
                            },
                            onExport = {
                                pendingExport = item
                                AppLockManager.suppressNextLock()
                                if (item.type == MediaType.VIDEO) exportVideoLauncher.launch(item.fileName)
                                else exportAudioLauncher.launch(item.fileName)
                            },
                            onConvert = { viewModel.convertToMp4(item) },
                            onCrop = { pendingCrop = item },
                            onRemoveAudio = { viewModel.removeAudio(item) },
                            onReplaceAudio = { pendingReplaceAudio = item },
                            onRotate = { pendingRotate = item }
                        )
                    }
                }

                NowPlayingBar(modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
private fun RenameDialog(initialCaption: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initialCaption) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun NewCategoryDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New category") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Category name") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(text) }, enabled = text.isNotBlank()) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ManualEqDialog(onDismiss: () -> Unit, onApply: (List<Double>) -> Unit) {
    val bandGains = remember {
        mutableStateListOf(*DoubleArray(NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ.size) { 0.0 }.toTypedArray())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual noise filter") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    "Cut the frequency range the unwanted sound sits in, save a copy, and listen — adjust and re-run if it's not enough.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                NoiseFilterProcessor.MANUAL_BAND_FREQUENCIES_HZ.forEachIndexed { index, freq ->
                    Text(
                        "${formatFrequency(freq)} — ${bandGains[index].toInt()} dB",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Slider(
                        value = bandGains[index].toFloat(),
                        onValueChange = { bandGains[index] = it.toDouble() },
                        valueRange = -40f..0f
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(bandGains.toList()) }) { Text("Apply & save copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun formatFrequency(hz: Int): String = if (hz >= 1000) "${hz / 1000}kHz" else "${hz}Hz"

/** Loads a video's actual duration off the file itself — SavedMedia.durationSeconds isn't reliably populated for recordings, so this is the only source that's always accurate. */
@Composable
private fun rememberVideoDurationMs(item: SavedMedia): Long? {
    val context = LocalContext.current
    var durationMs by remember(item.id) { mutableStateOf<Long?>(null) }
    LaunchedEffect(item.id) {
        durationMs = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, MediaAccess.uri(item.filePath))
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }
    return durationMs
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun TrimDialog(item: SavedMedia, onDismiss: () -> Unit, onApply: (startMs: Long, endMs: Long) -> Unit) {
    val durationMs = rememberVideoDurationMs(item)
    var range by remember(durationMs) { mutableStateOf(0f..(durationMs?.toFloat() ?: 1f)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trim \"${item.caption}\"") },
        text = {
            if (durationMs == null) {
                Text("Reading video length…", style = MaterialTheme.typography.bodySmall)
            } else {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TrimFramePreview(
                            item = item,
                            label = "Start",
                            positionMs = range.start.toLong(),
                            modifier = Modifier.weight(1f)
                        )
                        TrimFramePreview(
                            item = item,
                            label = "End",
                            positionMs = range.endInclusive.toLong(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatMs(range.start.toLong())} – ${formatMs(range.endInclusive.toLong())} of ${formatMs(durationMs)}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    RangeSlider(
                        value = range,
                        onValueChange = { range = it },
                        valueRange = 0f..durationMs.toFloat()
                    )
                    Text(
                        "The start snaps to the nearest keyframe, so it may land a moment before this if the video's keyframes are spaced out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(range.start.toLong(), range.endInclusive.toLong()) },
                enabled = durationMs != null && range.endInclusive > range.start
            ) { Text("Trim & save copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Shows the actual frame at [positionMs], updated as the slider moves, so trimming is "cut where
 * this looks right" instead of guessing from a bare timestamp. Rounding to the nearest 300ms
 * bucket for the cache key means a fast drag doesn't try to decode a new frame on every pixel of
 * movement, while still feeling live.
 */
@Composable
private fun TrimFramePreview(item: SavedMedia, label: String, positionMs: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bucketMs = (positionMs / 300) * 300
    var frame by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, bucketMs) {
        frame = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, MediaAccess.uri(item.filePath))
                retriever.getFrameAtTime(bucketMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }
    Column(modifier = modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black)
        ) {
            frame?.let {
                Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun InsertClipDialog(
    base: SavedMedia,
    candidates: List<SavedMedia>,
    onDismiss: () -> Unit,
    onApply: (insertAtMs: Long, clip: SavedMedia) -> Unit
) {
    val durationMs = rememberVideoDurationMs(base)
    var insertAtMs by remember(durationMs) { mutableStateOf(0f) }
    var selectedClip by remember { mutableStateOf<SavedMedia?>(candidates.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Insert clip into \"${base.caption}\"") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (durationMs == null) {
                    Text("Reading video length…", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Insert at ${formatMs(insertAtMs.toLong())} of ${formatMs(durationMs)}", style = MaterialTheme.typography.bodySmall)
                    Slider(value = insertAtMs, onValueChange = { insertAtMs = it }, valueRange = 0f..durationMs.toFloat())
                }
                Spacer(Modifier.height(12.dp))
                if (candidates.isEmpty()) {
                    Text(
                        "No other videos in Library to insert — record or save one first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Clip to insert:", style = MaterialTheme.typography.bodySmall)
                    candidates.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedClip = candidate },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedClip?.id == candidate.id, onClick = { selectedClip = candidate })
                            Text(candidate.caption, maxLines = 1)
                        }
                    }
                }
                Text(
                    "Only clips with the same video format/resolution as \"${base.caption}\" can be spliced in without re-encoding — this will fail with an error if they don't match.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedClip?.let { onApply(insertAtMs.toLong(), it) } },
                enabled = durationMs != null && selectedClip != null
            ) { Text("Insert & save copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun OverlayDialog(
    base: SavedMedia,
    candidates: List<SavedMedia>,
    onDismiss: () -> Unit,
    onApply: (overlay: SavedMedia, corner: com.mobicareapp.edit.OverlayCorner) -> Unit
) {
    var selectedClip by remember { mutableStateOf<SavedMedia?>(candidates.firstOrNull()) }
    var corner by remember { mutableStateOf(com.mobicareapp.edit.OverlayCorner.BOTTOM_RIGHT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add overlay to \"${base.caption}\"") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "\"${base.caption}\" plays full-screen as the background; the clip you pick below plays inset in a corner on top of it, for as long as it lasts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                if (candidates.isEmpty()) {
                    Text(
                        "No other videos in Library to overlay — record or save one first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Clip to overlay:", style = MaterialTheme.typography.bodySmall)
                    candidates.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedClip = candidate },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selectedClip?.id == candidate.id, onClick = { selectedClip = candidate })
                            Text(candidate.caption, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Corner:", style = MaterialTheme.typography.bodySmall)
                val corners = listOf(
                    com.mobicareapp.edit.OverlayCorner.TOP_LEFT to "Top left",
                    com.mobicareapp.edit.OverlayCorner.TOP_RIGHT to "Top right",
                    com.mobicareapp.edit.OverlayCorner.BOTTOM_LEFT to "Bottom left",
                    com.mobicareapp.edit.OverlayCorner.BOTTOM_RIGHT to "Bottom right"
                )
                corners.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { corner = value },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = corner == value, onClick = { corner = value })
                        Text(label)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "This re-encodes the whole video (not a fast file copy like Trim/Merge), so it can take roughly as long as \"${base.caption}\"'s own length to finish. Only H.264/H.265 videos are supported.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selectedClip?.let { onApply(it, corner) } },
                enabled = selectedClip != null
            ) { Text("Add overlay & save copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun RotateDialog(item: SavedMedia, onDismiss: () -> Unit, onApply: (degreesClockwise: Int) -> Unit) {
    var degrees by remember { mutableStateOf(180) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rotate \"${item.caption}\"") },
        text = {
            Column {
                Text(
                    "Fixes a video that's sideways or upside down — this re-encodes the whole video, so it can take a while.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(90, 180, 270).forEach { option ->
                        FilterChip(
                            selected = degrees == option,
                            onClick = { degrees = option },
                            label = { Text("$option°") }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(degrees) }) { Text("Rotate & save copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun MergeOrderDialog(items: List<SavedMedia>, onDismiss: () -> Unit, onApply: (List<SavedMedia>) -> Unit) {
    var ordered by remember { mutableStateOf(items) }

    fun moveUp(index: Int) {
        if (index <= 0) return
        ordered = ordered.toMutableList().apply { add(index - 1, removeAt(index)) }
    }
    fun moveDown(index: Int) {
        if (index >= ordered.size - 1) return
        ordered = ordered.toMutableList().apply { add(index + 1, removeAt(index)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Order the ${ordered.size} videos") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "They'll be joined in this order, top to bottom.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                ordered.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${index + 1}.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.width(8.dp))
                        Text(item.caption, maxLines = 1, modifier = Modifier.weight(1f))
                        IconButton(onClick = { moveUp(index) }, enabled = index > 0) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                        }
                        IconButton(onClick = { moveDown(index) }, enabled = index < ordered.size - 1) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(ordered) }) { Text("Merge & save copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ReplaceAudioDialog(
    video: SavedMedia,
    candidates: List<SavedMedia>,
    onDismiss: () -> Unit,
    onApply: (audio: SavedMedia) -> Unit
) {
    var selected by remember { mutableStateOf<SavedMedia?>(candidates.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Replace audio in \"${video.caption}\"") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text(
                        "No audio in Library to use — record or save one first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("New audio track:", style = MaterialTheme.typography.bodySmall)
                    candidates.forEach { candidate ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selected = candidate },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected?.id == candidate.id, onClick = { selected = candidate })
                            Text(candidate.caption, maxLines = 1)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The video's own audio is dropped; the shorter of the two decides how long the result is.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let { onApply(it) } },
                enabled = selected != null
            ) { Text("Replace & save copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CropDialog(item: SavedMedia, onDismiss: () -> Unit, onApply: (left: Float, top: Float, right: Float, bottom: Float) -> Unit) {
    val context = LocalContext.current
    var frame by remember(item.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id) {
        frame = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, MediaAccess.uri(item.filePath))
                retriever.getFrameAtTime(0)
            } catch (e: Exception) {
                null
            } finally {
                retriever.release()
            }
        }
    }

    var left by remember { mutableFloatStateOf(0.1f) }
    var top by remember { mutableFloatStateOf(0.1f) }
    var right by remember { mutableFloatStateOf(0.9f) }
    var bottom by remember { mutableFloatStateOf(0.9f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val minSizeFrac = 0.1f
    val handleSizePx = with(density) { 24.dp.toPx() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Crop \"${item.caption}\"") },
        text = {
            val bmp = frame
            if (bmp == null) {
                Text("Reading a frame…", style = MaterialTheme.typography.bodySmall)
            } else {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                            .onSizeChanged { containerSize = it }
                    ) {
                        Image(bitmap = bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())

                        if (containerSize.width > 0 && containerSize.height > 0) {
                            val w = containerSize.width.toFloat()
                            val h = containerSize.height.toFloat()

                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val rectLeft = left * size.width
                                val rectTop = top * size.height
                                val rectRight = right * size.width
                                val rectBottom = bottom * size.height
                                val dim = Color.Black.copy(alpha = 0.55f)
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

                            // Drag anywhere inside the rectangle to move it.
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset((left * w).roundToInt(), (top * h).roundToInt()) }
                                    .size(
                                        with(density) { ((right - left) * w).toDp() },
                                        with(density) { ((bottom - top) * h).toDp() }
                                    )
                                    .pointerInput(containerSize) {
                                        detectDragGestures { change, drag ->
                                            change.consume()
                                            val rw = right - left
                                            val rh = bottom - top
                                            left = (left + drag.x / w).coerceIn(0f, 1f - rw)
                                            top = (top + drag.y / h).coerceIn(0f, 1f - rh)
                                            right = left + rw
                                            bottom = top + rh
                                        }
                                    }
                            )

                            CropHandle(xFrac = left, yFrac = top, handleSizePx = handleSizePx, container = containerSize) { dxF, dyF ->
                                left = (left + dxF).coerceIn(0f, right - minSizeFrac)
                                top = (top + dyF).coerceIn(0f, bottom - minSizeFrac)
                            }
                            CropHandle(xFrac = right, yFrac = top, handleSizePx = handleSizePx, container = containerSize) { dxF, dyF ->
                                right = (right + dxF).coerceIn(left + minSizeFrac, 1f)
                                top = (top + dyF).coerceIn(0f, bottom - minSizeFrac)
                            }
                            CropHandle(xFrac = left, yFrac = bottom, handleSizePx = handleSizePx, container = containerSize) { dxF, dyF ->
                                left = (left + dxF).coerceIn(0f, right - minSizeFrac)
                                bottom = (bottom + dyF).coerceIn(top + minSizeFrac, 1f)
                            }
                            CropHandle(xFrac = right, yFrac = bottom, handleSizePx = handleSizePx, container = containerSize) { dxF, dyF ->
                                right = (right + dxF).coerceIn(left + minSizeFrac, 1f)
                                bottom = (bottom + dyF).coerceIn(top + minSizeFrac, 1f)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Drag inside the rectangle to move it, or its corners to resize.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(left, top, right, bottom) },
                enabled = frame != null
            ) { Text("Crop & save copy") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun CropHandle(xFrac: Float, yFrac: Float, handleSizePx: Float, container: IntSize, onDrag: (dxFrac: Float, dyFrac: Float) -> Unit) {
    val density = LocalDensity.current
    val w = container.width.toFloat()
    val h = container.height.toFloat()
    Box(
        modifier = Modifier
            .offset {
                IntOffset((xFrac * w - handleSizePx / 2).roundToInt(), (yFrac * h - handleSizePx / 2).roundToInt())
            }
            .size(with(density) { handleSizePx.toDp() })
            .background(Color.White, CircleShape)
            .pointerInput(container) {
                detectDragGestures { change, drag ->
                    change.consume()
                    onDrag(drag.x / w, drag.y / h)
                }
            }
    )
}

@Composable
private fun MoveToCategoryDialog(
    albums: List<MediaCategory>,
    onDismiss: () -> Unit,
    onAssign: (categoryId: Long?) -> Unit,
    onCreateAndAssign: (name: String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move to category") },
        text = {
            Column {
                TextButton(onClick = { onAssign(null) }) { Text("No category") }
                albums.forEach { album ->
                    TextButton(onClick = { onAssign(album.id) }) { Text(album.name) }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New category name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreateAndAssign(newName) }, enabled = newName.isNotBlank()) {
                Text("Create & Move")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun SelectionBar(
    selectedCount: Int,
    canPlay: Boolean,
    canMerge: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onPlay: (loop: Boolean) -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onMerge: () -> Unit
) {
    var loop by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("$selectedCount selected", modifier = Modifier.padding(end = 8.dp))
        TextButton(onClick = onSelectAll) { Text("Select all") }
        if (canPlay) {
            Text("Loop", modifier = Modifier.padding(start = 8.dp, end = 4.dp))
            Switch(checked = loop, onCheckedChange = { loop = it })
            TextButton(onClick = { onPlay(loop) }) { Text("Play") }
        }
        if (canMerge) {
            TextButton(onClick = onMerge) { Text("Merge") }
        }
        TextButton(onClick = onMove) { Text("Move") }
        TextButton(onClick = onDelete) { Text("Delete") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun PlayAllRow(isVideo: Boolean, onPlayAll: (loop: Boolean) -> Unit) {
    var loop by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = { onPlayAll(loop) }) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Text(if (isVideo) "Play all videos" else "Play all audio")
        }
        Text("Loop", modifier = Modifier.padding(start = 8.dp, end = 4.dp))
        Switch(checked = loop, onCheckedChange = { loop = it })
    }
}

@Composable
private fun <T> FilterDropdown(currentLabel: String, options: List<Pair<String, T>>, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { expanded = true }, label = { Text(currentLabel) })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (label, value) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    item: SavedMedia,
    categoryName: String?,
    selectionMode: Boolean,
    selected: Boolean,
    isDenoising: Boolean,
    isEditing: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onMoveCategory: () -> Unit,
    onDelete: () -> Unit,
    onDenoise: () -> Unit,
    onManualFilter: () -> Unit,
    onTrim: () -> Unit,
    onInsertClip: () -> Unit,
    onAddOverlay: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onConvert: () -> Unit,
    onCrop: () -> Unit,
    onRemoveAudio: () -> Unit,
    onReplaceAudio: () -> Unit,
    onRotate: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val busy = isDenoising || isEditing

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onClick() })
        }

        if (item.thumbnailUrl != null) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(6.dp))
            )
        } else {
            Icon(
                imageVector = if (item.type == MediaType.VIDEO) Icons.Default.Videocam else Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(item.caption, maxLines = 2, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append(formatSize(item.sizeBytes))
                    append(" • ")
                    append(DateFormat.getDateInstance().format(Date(item.createdAt)))
                    if (categoryName != null) {
                        append(" • ")
                        append(categoryName)
                    }
                },
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        Box {
            IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = { menuExpanded = false; onShare() },
                    leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Save to phone") },
                    onClick = { menuExpanded = false; onExport() },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) }
                )
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; onRename() })
                DropdownMenuItem(text = { Text("Move to category") }, onClick = { menuExpanded = false; onMoveCategory() })
                if (item.type == MediaType.VIDEO) {
                    DropdownMenuItem(
                        text = { Text("Trim…") },
                        onClick = { menuExpanded = false; onTrim() },
                        leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Crop…") },
                        onClick = { menuExpanded = false; onCrop() },
                        leadingIcon = { Icon(Icons.Default.Crop, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Insert clip…") },
                        onClick = { menuExpanded = false; onInsertClip() },
                        leadingIcon = { Icon(Icons.Default.CallMerge, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Add overlay (picture-in-picture)…") },
                        onClick = { menuExpanded = false; onAddOverlay() },
                        leadingIcon = { Icon(Icons.Default.PictureInPictureAlt, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove audio (mute)") },
                        onClick = { menuExpanded = false; onRemoveAudio() },
                        leadingIcon = { Icon(Icons.Default.MusicOff, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Replace audio…") },
                        onClick = { menuExpanded = false; onReplaceAudio() },
                        leadingIcon = { Icon(Icons.Default.MusicNote, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Rotate…") },
                        onClick = { menuExpanded = false; onRotate() },
                        leadingIcon = { Icon(Icons.Default.RotateRight, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("Convert to MP4 (if it won't play)") },
                        onClick = { menuExpanded = false; onConvert() },
                        leadingIcon = { Icon(Icons.Default.Autorenew, contentDescription = null) }
                    )
                }
                DropdownMenuItem(
                    text = { Text("Filter noise (saves a copy)") },
                    onClick = { menuExpanded = false; onDenoise() },
                    leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) }
                )
                DropdownMenuItem(
                    text = { Text("Manual noise filter…") },
                    onClick = { menuExpanded = false; onManualFilter() },
                    leadingIcon = { Icon(Icons.Default.GraphicEq, contentDescription = null) }
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

private fun shareMedia(context: Context, item: SavedMedia) {
    val uri = if (item.filePath.startsWith("content://")) {
        Uri.parse(item.filePath)
    } else {
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(item.filePath))
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (item.type == MediaType.VIDEO) "video/mp4" else "audio/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, item.caption))
}

@Composable
private fun NowPlayingBar(modifier: Modifier = Modifier) {
    val controller by PlayerController.controller.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var isPlaying by remember { mutableStateOf(false) }
    var hasMedia by remember { mutableStateOf(false) }

    LaunchedEffect(controller) {
        val c = controller ?: return@LaunchedEffect
        fun refresh() {
            title = c.mediaMetadata.title?.toString() ?: ""
            isPlaying = c.isPlaying
            hasMedia = c.mediaItemCount > 0
        }
        refresh()
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                refresh()
            }
        }
        c.addListener(listener)
    }

    if (hasMedia) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { scope.launch { PlayerController.skipPrevious(context) } }) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous")
            }
            IconButton(onClick = { scope.launch { PlayerController.togglePlayPause(context) } }) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play/Pause")
            }
            IconButton(onClick = { scope.launch { PlayerController.skipNext(context) } }) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) String.format("%.2f GB", mb / 1024.0) else String.format("%.1f MB", mb)
}
