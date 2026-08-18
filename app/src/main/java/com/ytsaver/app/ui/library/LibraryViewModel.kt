package com.ytsaver.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytsaver.app.YtSaverApp
import com.ytsaver.app.backup.BackupManager
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.data.SavedMedia
import com.ytsaver.app.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class CategoryFilter { ALL, VIDEO, AUDIO }

data class LibraryUiState(
    val items: List<SavedMedia> = emptyList(),
    val query: String = "",
    val category: CategoryFilter = CategoryFilter.ALL,
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet()
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = (application as YtSaverApp).database.savedMediaDao()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow(CategoryFilter.ALL)
    private val selectionMode = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<LibraryUiState> =
        combine(dao.observeAll(), query, category, selectionMode, selectedIds) { values ->
            @Suppress("UNCHECKED_CAST")
            val items = values[0] as List<SavedMedia>
            val q = values[1] as String
            val cat = values[2] as CategoryFilter
            val selMode = values[3] as Boolean
            @Suppress("UNCHECKED_CAST")
            val selIds = values[4] as Set<Long>

            val filtered = items
                .filter { cat == CategoryFilter.ALL || (cat == CategoryFilter.VIDEO) == (it.type == MediaType.VIDEO) }
                .filter { q.isBlank() || it.caption.contains(q, ignoreCase = true) }

            LibraryUiState(filtered, q, cat, selMode, selIds)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    fun onQueryChanged(value: String) {
        query.value = value
    }

    fun onCategoryChanged(value: CategoryFilter) {
        category.value = value
        exitSelection()
    }

    fun enterSelection(id: Long) {
        selectionMode.value = true
        selectedIds.value = setOf(id)
    }

    fun toggleSelected(id: Long) {
        val current = selectedIds.value
        selectedIds.value = if (id in current) current - id else current + id
        if (selectedIds.value.isEmpty()) selectionMode.value = false
    }

    fun exitSelection() {
        selectionMode.value = false
        selectedIds.value = emptySet()
    }

    fun playSelected(loopAll: Boolean) {
        val ids = selectedIds.value
        val queue = uiState.value.items.filter { it.id in ids }
        viewModelScope.launch {
            PlayerController.playQueue(getApplication(), queue, loopAll)
        }
        exitSelection()
    }

    fun playSingleAudio(item: SavedMedia) {
        viewModelScope.launch {
            PlayerController.playQueue(getApplication(), listOf(item), loopAll = false)
        }
    }

    fun delete(item: SavedMedia) {
        viewModelScope.launch {
            File(item.filePath).delete()
            dao.delete(item)
        }
    }

    fun backupTo(treeUri: Uri) {
        viewModelScope.launch {
            val result = BackupManager.backup(getApplication(), treeUri, uiState.value.items)
            _snackbarMessage.value = result.fold(
                onSuccess = { count -> "Backed up $count file(s)" },
                onFailure = { e -> "Backup failed: ${e.message}" }
            )
        }
    }

    fun restoreFrom(treeUri: Uri) {
        viewModelScope.launch {
            val result = BackupManager.restore(getApplication(), treeUri, dao)
            _snackbarMessage.value = result.fold(
                onSuccess = { count -> "Restored $count file(s)" },
                onFailure = { e -> "Restore failed: ${e.message}" }
            )
        }
    }

    fun snackbarShown() {
        _snackbarMessage.value = null
    }
}
