package com.ytsaver.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytsaver.app.YtSaverApp
import com.ytsaver.app.backup.BackupManager
import com.ytsaver.app.data.MediaAccess
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

enum class CategoryFilter { ALL, VIDEO, AUDIO }

enum class SortOption { DATE_NEWEST, DATE_OLDEST, SIZE_LARGEST, SIZE_SMALLEST }

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

data class LibraryUiState(
    val items: List<SavedMedia> = emptyList(),
    val query: String = "",
    val category: CategoryFilter = CategoryFilter.ALL,
    val sort: SortOption = SortOption.DATE_NEWEST,
    val minSizeBytes: Long = 0,
    val minAgeDays: Int = 0,
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet()
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = (application as YtSaverApp).database.savedMediaDao()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow(CategoryFilter.ALL)
    private val sort = MutableStateFlow(SortOption.DATE_NEWEST)
    private val minSizeBytes = MutableStateFlow(0L)
    private val minAgeDays = MutableStateFlow(0)
    private val selectionMode = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<LibraryUiState> =
        combine(
            dao.observeAll(), query, category, sort, minSizeBytes, minAgeDays, selectionMode, selectedIds
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val items = values[0] as List<SavedMedia>
            val q = values[1] as String
            val cat = values[2] as CategoryFilter
            val sortOption = values[3] as SortOption
            val minSize = values[4] as Long
            val minAge = values[5] as Int
            val selMode = values[6] as Boolean
            @Suppress("UNCHECKED_CAST")
            val selIds = values[7] as Set<Long>

            val cutoff = if (minAge > 0) System.currentTimeMillis() - minAge * DAY_MILLIS else Long.MAX_VALUE

            val filtered = items
                .filter { cat == CategoryFilter.ALL || (cat == CategoryFilter.VIDEO) == (it.type == MediaType.VIDEO) }
                .filter { q.isBlank() || it.caption.contains(q, ignoreCase = true) }
                .filter { minSize <= 0 || it.sizeBytes >= minSize }
                .filter { minAge <= 0 || it.createdAt <= cutoff }

            val sorted = when (sortOption) {
                SortOption.DATE_NEWEST -> filtered.sortedByDescending { it.createdAt }
                SortOption.DATE_OLDEST -> filtered.sortedBy { it.createdAt }
                SortOption.SIZE_LARGEST -> filtered.sortedByDescending { it.sizeBytes }
                SortOption.SIZE_SMALLEST -> filtered.sortedBy { it.sizeBytes }
            }

            LibraryUiState(sorted, q, cat, sortOption, minSize, minAge, selMode, selIds)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    fun onQueryChanged(value: String) {
        query.value = value
    }

    fun onCategoryChanged(value: CategoryFilter) {
        category.value = value
        exitSelection()
    }

    fun onSortChanged(value: SortOption) {
        sort.value = value
    }

    fun onMinSizeChanged(bytes: Long) {
        minSizeBytes.value = bytes
    }

    fun onMinAgeChanged(days: Int) {
        minAgeDays.value = days
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

    fun selectAllVisible() {
        selectionMode.value = true
        selectedIds.value = uiState.value.items.map { it.id }.toSet()
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
            MediaAccess.delete(getApplication(), item.filePath)
            dao.delete(item)
        }
    }

    fun deleteSelected() {
        val ids = selectedIds.value
        val toDelete = uiState.value.items.filter { it.id in ids }
        viewModelScope.launch {
            for (item in toDelete) {
                MediaAccess.delete(getApplication(), item.filePath)
                dao.delete(item)
            }
            _snackbarMessage.value = "Deleted ${toDelete.size} file(s)"
        }
        exitSelection()
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
