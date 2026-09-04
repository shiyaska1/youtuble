package com.mobicareapp.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.mobicareapp.data.MediaCategory
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.SavedMedia
import com.mobicareapp.playback.PlayerController
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

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
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var pendingDelete by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingBulkDelete by remember { mutableStateOf(false) }
    var pendingRename by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingMoveItem by remember { mutableStateOf<SavedMedia?>(null) }
    var pendingMoveSelection by remember { mutableStateOf(false) }
    var pendingNewCategory by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library") },
                actions = {
                    IconButton(onClick = { backupLauncher.launch(null) }) {
                        Icon(Icons.Default.CloudUpload, contentDescription = "Backup")
                    }
                    IconButton(onClick = { restoreLauncher.launch(null) }) {
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
                    onMove = { pendingMoveSelection = true }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { it.id }) { item ->
                        LibraryRow(
                            item = item,
                            categoryName = state.albums.find { it.id == item.categoryId }?.name,
                            selectionMode = state.selectionMode,
                            selected = item.id in state.selectedIds,
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
                            onDelete = { pendingDelete = item }
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
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onPlay: (loop: Boolean) -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit
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
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit,
    onMoveCategory: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

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

        Box {
            IconButton(onClick = { menuExpanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More options")
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuExpanded = false; onRename() })
                DropdownMenuItem(text = { Text("Move to category") }, onClick = { menuExpanded = false; onMoveCategory() })
                DropdownMenuItem(
                    text = { Text("Delete") },
                    onClick = { menuExpanded = false; onDelete() },
                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                )
            }
        }
    }
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
