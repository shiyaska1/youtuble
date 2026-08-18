package com.ytsaver.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
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
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.data.SavedMedia
import com.ytsaver.app.playback.PlayerController
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenVideo: (SavedMedia) -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarMessage by viewModel.snackbarMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var pendingDelete by remember { mutableStateOf<SavedMedia?>(null) }

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
                label = { Text("Search by name") }
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

            if (state.selectionMode) {
                SelectionBar(
                    selectedCount = state.selectedIds.size,
                    allAudio = state.items.filter { it.id in state.selectedIds }.all { it.type == MediaType.AUDIO },
                    onCancel = viewModel::exitSelection,
                    onPlay = { loop -> viewModel.playSelected(loop) }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.items, key = { it.id }) { item ->
                        LibraryRow(
                            item = item,
                            selectionMode = state.selectionMode,
                            selected = item.id in state.selectedIds,
                            onClick = {
                                when {
                                    state.selectionMode -> viewModel.toggleSelected(item.id)
                                    item.type == MediaType.VIDEO -> onOpenVideo(item)
                                    else -> viewModel.playSingleAudio(item)
                                }
                            },
                            onLongClick = { viewModel.enterSelection(item.id) },
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
private fun SelectionBar(
    selectedCount: Int,
    allAudio: Boolean,
    onCancel: () -> Unit,
    onPlay: (loop: Boolean) -> Unit
) {
    var loop by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text("$selectedCount selected")
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (allAudio) {
                Text("Loop", modifier = Modifier.padding(end = 4.dp))
                Switch(checked = loop, onCheckedChange = { loop = it })
                TextButton(onClick = { onPlay(loop) }) { Text("Play") }
            }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LibraryRow(
    item: SavedMedia,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
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
                "${formatSize(item.sizeBytes)} • ${DateFormat.getDateInstance().format(Date(item.createdAt))}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
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
