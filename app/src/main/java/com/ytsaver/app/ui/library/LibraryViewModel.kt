package com.ytsaver.app.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ytsaver.app.YtSaverApp
import com.ytsaver.app.backup.BackupManager
import com.ytsaver.app.data.MediaAccess
import com.ytsaver.app.data.MediaCategory
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.data.SavedMedia
import com.ytsaver.app.download.DownloadService
import com.ytsaver.app.extract.DirectLinkFetcher
import com.ytsaver.app.extract.YoutubeStreamFetcher
import com.ytsaver.app.playback.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CategoryFilter { ALL, VIDEO, AUDIO }

enum class SortOption { DATE_NEWEST, DATE_OLDEST, SIZE_LARGEST, SIZE_SMALLEST }

/** Which named album (if any) the list is narrowed to. Distinct from [CategoryFilter], which is video-vs-audio. */
sealed class AlbumFilter {
    data object All : AlbumFilter()
    data object Uncategorized : AlbumFilter()
    data class ById(val id: Long) : AlbumFilter()
}

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

data class LibraryUiState(
    val items: List<SavedMedia> = emptyList(),
    val albums: List<MediaCategory> = emptyList(),
    val query: String = "",
    val category: CategoryFilter = CategoryFilter.ALL,
    val albumFilter: AlbumFilter = AlbumFilter.All,
    val sort: SortOption = SortOption.DATE_NEWEST,
    val minSizeBytes: Long = 0,
    val minAgeDays: Int = 0,
    val selectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet()
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = (application as YtSaverApp).database.savedMediaDao()
    private val categoryDao = (application as YtSaverApp).database.mediaCategoryDao()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val query = MutableStateFlow("")
    private val category = MutableStateFlow(CategoryFilter.ALL)
    private val albumFilter = MutableStateFlow<AlbumFilter>(AlbumFilter.All)
    private val sort = MutableStateFlow(SortOption.DATE_NEWEST)
    private val minSizeBytes = MutableStateFlow(0L)
    private val minAgeDays = MutableStateFlow(0)
    private val selectionMode = MutableStateFlow(false)
    private val selectedIds = MutableStateFlow<Set<Long>>(emptySet())

    val uiState: StateFlow<LibraryUiState> =
        combine(
            dao.observeAll(), categoryDao.observeAll(), query, category, albumFilter,
            sort, minSizeBytes, minAgeDays, selectionMode, selectedIds
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val items = values[0] as List<SavedMedia>
            @Suppress("UNCHECKED_CAST")
            val albums = values[1] as List<MediaCategory>
            val q = values[2] as String
            val cat = values[3] as CategoryFilter
            val album = values[4] as AlbumFilter
            val sortOption = values[5] as SortOption
            val minSize = values[6] as Long
            val minAge = values[7] as Int
            val selMode = values[8] as Boolean
            @Suppress("UNCHECKED_CAST")
            val selIds = values[9] as Set<Long>

            val cutoff = if (minAge > 0) System.currentTimeMillis() - minAge * DAY_MILLIS else Long.MAX_VALUE

            val filtered = items
                .filter { cat == CategoryFilter.ALL || (cat == CategoryFilter.VIDEO) == (it.type == MediaType.VIDEO) }
                .filter {
                    when (album) {
                        is AlbumFilter.All -> true
                        is AlbumFilter.Uncategorized -> it.categoryId == null
                        is AlbumFilter.ById -> it.categoryId == album.id
                    }
                }
                .filter { q.isBlank() || it.caption.contains(q, ignoreCase = true) }
                .filter { minSize <= 0 || it.sizeBytes >= minSize }
                .filter { minAge <= 0 || it.createdAt <= cutoff }

            val sorted = when (sortOption) {
                SortOption.DATE_NEWEST -> filtered.sortedByDescending { it.createdAt }
                SortOption.DATE_OLDEST -> filtered.sortedBy { it.createdAt }
                SortOption.SIZE_LARGEST -> filtered.sortedByDescending { it.sizeBytes }
                SortOption.SIZE_SMALLEST -> filtered.sortedBy { it.sizeBytes }
            }

            LibraryUiState(sorted, albums, q, cat, album, sortOption, minSize, minAge, selMode, selIds)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    fun onQueryChanged(value: String) {
        query.value = value
    }

    fun onCategoryChanged(value: CategoryFilter) {
        category.value = value
        exitSelection()
    }

    fun onAlbumFilterChanged(value: AlbumFilter) {
        albumFilter.value = value
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

    /** Plays [queue] starting at [startIndex] — used for both "tap a track" (rest of the
     *  currently visible list becomes the queue) and explicit "Play All". */
    fun playAudioQueue(queue: List<SavedMedia>, startIndex: Int, loopAll: Boolean) {
        viewModelScope.launch {
            PlayerController.playQueue(getApplication(), queue, loopAll, startIndex)
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

    /** Re-fetches [item]'s source link and saves it again — for when the file was deleted
     *  outside the app (Gallery, a file manager, etc.) and playback would otherwise fail. */
    fun redownload(item: SavedMedia) {
        viewModelScope.launch {
            dao.delete(item)

            val result = if (DirectLinkFetcher.looksLikeYoutubeUrl(item.sourceUrl)) {
                YoutubeStreamFetcher.fetch(item.sourceUrl)
            } else {
                DirectLinkFetcher.fetch(item.sourceUrl)
            }

            result.onSuccess { stream ->
                val option = if (item.type == MediaType.VIDEO) stream.videoOption else stream.audioOption
                if (option == null) {
                    _snackbarMessage.value = "Couldn't find a matching ${item.type.name.lowercase()} stream to re-download"
                    return@onSuccess
                }
                DownloadService.start(
                    context = getApplication(),
                    caption = item.caption,
                    sourceUrl = stream.sourceUrl,
                    streamUrl = option.streamUrl,
                    type = item.type,
                    fileExtension = option.fileExtension,
                    mimeType = option.mimeType,
                    thumbnailUrl = stream.thumbnailUrl ?: item.thumbnailUrl,
                    durationSeconds = stream.durationSeconds,
                    categoryId = item.categoryId
                )
            }.onFailure { e ->
                _snackbarMessage.value = "Couldn't re-download: ${e.message ?: "link no longer works"}"
            }
        }
    }

    fun rename(item: SavedMedia, newCaption: String) {
        val trimmed = newCaption.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { dao.updateCaption(item.id, trimmed) }
    }

    /** Creates an empty category (not yet assigned to anything). No-op if the name already exists. */
    fun createCategory(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val exists = uiState.value.albums.any { it.name.equals(trimmed, ignoreCase = true) }
            if (!exists) categoryDao.insert(MediaCategory(name = trimmed))
        }
    }

    /** Creates the category if [name] is new, then assigns [ids] to it. Pass a null [name] (via [assignCategory]) to clear. */
    fun createAndAssignCategory(ids: Set<Long>, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank() || ids.isEmpty()) return
        viewModelScope.launch {
            val existing = uiState.value.albums.find { it.name.equals(trimmed, ignoreCase = true) }
            val categoryId = existing?.id ?: categoryDao.insert(MediaCategory(name = trimmed))
            dao.updateCategory(ids.toList(), categoryId)
        }
        exitSelection()
    }

    fun assignCategory(ids: Set<Long>, categoryId: Long?) {
        if (ids.isEmpty()) return
        viewModelScope.launch { dao.updateCategory(ids.toList(), categoryId) }
        exitSelection()
    }

    fun deleteCategory(category: MediaCategory) {
        viewModelScope.launch {
            categoryDao.delete(category)
            if (albumFilter.value == AlbumFilter.ById(category.id)) albumFilter.value = AlbumFilter.All
        }
    }

    fun backupTo(treeUri: Uri) {
        viewModelScope.launch {
            // Back up everything, not just what's currently filtered/visible.
            val allItems = dao.observeAll().first()
            val allCategories = categoryDao.observeAll().first()
            val result = BackupManager.backup(getApplication(), treeUri, allItems, allCategories)
            _snackbarMessage.value = result.fold(
                onSuccess = { r ->
                    if (r.skipped > 0) "Backed up ${r.copied} file(s), skipped ${r.skipped} (missing/unreadable)"
                    else "Backed up ${r.copied} file(s)"
                },
                onFailure = { e -> "Backup failed: ${e.message}" }
            )
        }
    }

    fun restoreFrom(treeUri: Uri) {
        viewModelScope.launch {
            val result = BackupManager.restore(getApplication(), treeUri, dao, categoryDao)
            _snackbarMessage.value = result.fold(
                onSuccess = { r ->
                    if (r.skipped > 0) "Restored ${r.copied} file(s), skipped ${r.skipped} (missing/unreadable)"
                    else "Restored ${r.copied} file(s)"
                },
                onFailure = { e -> "Restore failed: ${e.message}" }
            )
        }
    }

    fun snackbarShown() {
        _snackbarMessage.value = null
    }
}
