package com.mobicareapp.ui.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.mobicareapp.applock.AppLockManager
import com.mobicareapp.collage.CollageEntry
import com.mobicareapp.collage.CollageFileStore
import com.mobicareapp.collage.CollageHistoryStore
import com.mobicareapp.collage.CollageLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private const val MAX_PHOTOS = 9

private sealed class CollageMode {
    data object List : CollageMode()
    data class Editor(val entry: CollageEntry?) : CollageMode()
}

/** A saved-collages list (share/edit/delete) plus an editor for picking photos, choosing a layout (row/column/grid), and combining them into one JPG. */
@Composable
fun CollageScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf<CollageMode>(CollageMode.List) }
    var historyVersion by remember { mutableIntStateOf(0) }

    when (val current = mode) {
        is CollageMode.List -> {
            val entries = remember(historyVersion) { CollageHistoryStore.loadAll(context) }
            CollageListScreen(
                entries = entries,
                onBack = onBack,
                onNew = { mode = CollageMode.Editor(null) },
                onEdit = { entry -> mode = CollageMode.Editor(entry) },
                onDelete = { entry ->
                    CollageHistoryStore.delete(context, entry)
                    historyVersion++
                },
                onShare = { entry -> shareCollageJpg(context, File(entry.outputPath)) }
            )
        }
        is CollageMode.Editor -> {
            CollageEditorScreen(
                entry = current.entry,
                onDone = {
                    historyVersion++
                    mode = CollageMode.List
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollageListScreen(
    entries: List<CollageEntry>,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onEdit: (CollageEntry) -> Unit,
    onDelete: (CollageEntry) -> Unit,
    onShare: (CollageEntry) -> Unit
) {
    var pendingDelete by remember { mutableStateOf<CollageEntry?>(null) }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this collage?") },
            text = { Text("This removes it and its source photos. This can't be undone.") },
            confirmButton = { TextButton(onClick = { onDelete(entry); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Photo grids") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = { IconButton(onClick = onNew) { Icon(Icons.Default.Add, contentDescription = "New collage") } }
            )
        }
    ) { padding ->
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Default.GridView, contentDescription = null, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(12.dp))
                Text("No photo grids yet", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onNew) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Combine photos")
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(entries, key = { it.id }) { entry ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.width(56.dp).height(56.dp).clip(RoundedCornerShape(4.dp))) {
                            AsyncImage(
                                model = rememberFreshImageRequest(File(entry.outputPath)),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.timestampMillis)),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "${entry.sourcePaths.size} photos • ${entry.layout.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { onShare(entry) }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                        IconButton(onClick = { onEdit(entry) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                        IconButton(onClick = { pendingDelete = entry }) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    }
                }
            }
        }
    }
}

/**
 * Reprocessed files overwrite the same path in place, so a `File` model alone can look "unchanged"
 * to both Compose state ([File.equals] compares only the path) and Coil's cache — the same issue
 * documented in [PassportPhotoScreen]'s equivalent helper. [cacheBustKey] should be an explicit
 * counter for anything reprocessed more than once in one sitting; lastModified's second-level
 * resolution isn't reliable for that.
 */
@Composable
private fun rememberFreshImageRequest(file: File, cacheBustKey: Any = file.lastModified()): ImageRequest {
    val context = LocalContext.current
    return remember(file.path, cacheBustKey) {
        ImageRequest.Builder(context)
            .data(file)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
}

private fun shareCollageJpg(context: Context, file: File) {
    Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, CollageFileStore.shareUri(context, file))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.let { context.startActivity(Intent.createChooser(it, "Share collage")) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollageEditorScreen(entry: CollageEntry?, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val baseName = remember { entry?.id ?: CollageFileStore.newBaseName() }
    val sourceFiles = remember { mutableStateListOf<File>().apply { entry?.sourcePaths?.forEach { add(File(it)) } } }
    var layout by remember { mutableStateOf(entry?.layout ?: CollageLayout.GRID) }
    var outputFile by remember { mutableStateOf(entry?.let { File(it.outputPath) }, neverEqualPolicy()) }
    var outputVersion by remember { mutableIntStateOf(0) }
    var hasHistoryEntry by remember { mutableStateOf(entry != null) }
    var processing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingCameraFile by remember { mutableStateOf<File?>(null) }

    fun createOrUpdate() {
        if (processing) return
        if (sourceFiles.size < 2) {
            error = "Add at least 2 photos."
            return
        }
        processing = true
        val paths = sourceFiles.map { it.absolutePath }
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CollageFileStore.createCollage(context, baseName, paths, layout) }
            }
            processing = false
            result
                .onSuccess { saved ->
                    outputFile = saved
                    outputVersion++
                    if (!hasHistoryEntry) {
                        CollageHistoryStore.add(
                            context,
                            CollageEntry(
                                id = baseName,
                                timestampMillis = System.currentTimeMillis(),
                                sourcePaths = paths,
                                layoutName = layout.name,
                                outputPath = saved.absolutePath
                            )
                        )
                        hasHistoryEntry = true
                    } else {
                        CollageHistoryStore.updateLayout(context, baseName, layout.name)
                    }
                }
                .onFailure { e -> error = e.message ?: "Couldn't create the collage" }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingCameraFile
        if (success && file != null) sourceFiles.add(file)
    }

    fun launchCamera() {
        val (file, uri) = CollageFileStore.newCaptureTarget(context, baseName, sourceFiles.size)
        pendingCameraFile = file
        AppLockManager.suppressNextLock()
        cameraLauncher.launch(uri)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            processing = true
            scope.launch {
                val copied = withContext(Dispatchers.IO) {
                    runCatching { CollageFileStore.copySources(context, baseName, uris) }
                }
                processing = false
                copied
                    .onSuccess { sourceFiles.addAll(it) }
                    .onFailure { e -> error = e.message ?: "Couldn't load the selected photos" }
            }
        }
    }

    fun launchGallery() {
        AppLockManager.suppressNextLock()
        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (entry == null) "New photo grid" else "Edit photo grid") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") } },
                actions = {
                    // Kept up here rather than below the combined preview, so it can never end up
                    // scrolled out of reach near the bottom navigation bar.
                    IconButton(
                        onClick = { outputFile?.let { shareCollageJpg(context, it) } },
                        enabled = outputFile != null
                    ) { Icon(Icons.Default.Share, contentDescription = "Share") }
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text("${sourceFiles.size} of $MAX_PHOTOS photos added", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            if (sourceFiles.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                ) {
                    sourceFiles.forEachIndexed { index, file ->
                        Box(modifier = Modifier.size(72.dp)) {
                            Image(
                                painter = coil.compose.rememberAsyncImagePainter(file),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))
                            )
                            IconButton(
                                onClick = { sourceFiles.removeAt(index) },
                                modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { launchCamera() }, enabled = !processing && sourceFiles.size < MAX_PHOTOS) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Camera")
                }
                OutlinedButton(onClick = { launchGallery() }, enabled = !processing && sourceFiles.size < MAX_PHOTOS) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Gallery")
                }
            }
            Spacer(Modifier.height(20.dp))
            Text("Layout", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CollageLayout.entries.forEach { option ->
                    FilterChip(
                        selected = layout == option,
                        onClick = {
                            layout = option
                            if (outputFile != null) createOrUpdate()
                        },
                        label = { Text(option.label) }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            if (processing) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Combining…", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { createOrUpdate() }, enabled = sourceFiles.size >= 2) {
                    Text(if (outputFile == null) "Create collage" else "Update collage")
                }
            }

            val current = outputFile
            if (current != null) {
                Spacer(Modifier.height(20.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                ) {
                    AsyncImage(
                        model = rememberFreshImageRequest(current, outputVersion),
                        contentDescription = "Combined photo",
                        modifier = Modifier.fillMaxWidth()
                    )
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
