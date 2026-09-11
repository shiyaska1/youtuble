package com.mobicareapp.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobicareapp.YtSaverApp
import com.mobicareapp.backup.BackupManager
import com.mobicareapp.data.MediaAccess
import com.mobicareapp.data.MediaCategory
import com.mobicareapp.data.MediaImporter
import com.mobicareapp.data.MediaType
import com.mobicareapp.data.SavedMedia
import com.mobicareapp.edit.OverlayCorner
import com.mobicareapp.edit.PictureInPictureProcessor
import com.mobicareapp.edit.ReplaceAudioProcessor
import com.mobicareapp.edit.RotateVideoProcessor
import com.mobicareapp.edit.VideoConvertProcessor
import com.mobicareapp.edit.VideoCropProcessor
import com.mobicareapp.edit.VideoEditProcessor
import com.mobicareapp.playback.PlayerController
import com.mobicareapp.process.NoiseFilterProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

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

    private val _denoisingIds = MutableStateFlow<Set<Long>>(emptySet())
    val denoisingIds: StateFlow<Set<Long>> = _denoisingIds.asStateFlow()

    private val _editingIds = MutableStateFlow<Set<Long>>(emptySet())
    val editingIds: StateFlow<Set<Long>> = _editingIds.asStateFlow()

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

    /** Filters background noise out of [item]'s audio and saves the result as a new Library item, leaving [item] itself untouched. */
    fun denoise(item: SavedMedia) {
        if (item.id in _denoisingIds.value) return
        _denoisingIds.update { it + item.id }
        viewModelScope.launch {
            val result = NoiseFilterProcessor.process(getApplication(), item)
            _denoisingIds.update { it - item.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved a noise-filtered copy — you can delete the original once you've compared them"
                }
                .onFailure { e ->
                    _snackbarMessage.value = "Couldn't filter noise: ${e.message}"
                }
        }
    }

    /** Same as [denoise] but with user-chosen per-band cuts instead of the fixed automatic filter. */
    fun denoiseManual(item: SavedMedia, bandGainsDb: List<Double>) {
        if (item.id in _denoisingIds.value) return
        _denoisingIds.update { it + item.id }
        viewModelScope.launch {
            val result = NoiseFilterProcessor.processManual(getApplication(), item, bandGainsDb)
            _denoisingIds.update { it - item.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved a filtered copy — you can delete the original once you've compared them"
                }
                .onFailure { e ->
                    _snackbarMessage.value = "Couldn't filter noise: ${e.message}"
                }
        }
    }

    /** Cuts [item] down to [startMs]..[endMs] and saves the result as a new Library item. */
    fun trim(item: SavedMedia, startMs: Long, endMs: Long) {
        if (item.id in _editingIds.value) return
        _editingIds.update { it + item.id }
        viewModelScope.launch {
            val result = VideoEditProcessor.trim(getApplication(), item, startMs, endMs)
            _editingIds.update { it - item.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved the trimmed clip as a new video"
                }
                .onFailure { e -> _snackbarMessage.value = "Couldn't trim: ${e.message}" }
        }
    }

    /** Splices [clip] into [base] at [insertAtMs] and saves the result as a new Library item. */
    fun insertClip(base: SavedMedia, insertAtMs: Long, clip: SavedMedia) {
        if (base.id in _editingIds.value) return
        _editingIds.update { it + base.id }
        viewModelScope.launch {
            val result = VideoEditProcessor.insertClip(getApplication(), base, insertAtMs, clip)
            _editingIds.update { it - base.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved the spliced video as a new file"
                }
                .onFailure { e -> _snackbarMessage.value = "Couldn't insert clip: ${e.message}" }
        }
    }

    /** Composites [overlay] as a small inset into a corner of [base] and saves the result as a new Library item. */
    fun addOverlay(base: SavedMedia, overlay: SavedMedia, corner: OverlayCorner) {
        if (base.id in _editingIds.value) return
        _editingIds.update { it + base.id }
        viewModelScope.launch {
            _snackbarMessage.value = "Compositing overlay — this re-encodes the whole video, so it can take a while…"
            val result = PictureInPictureProcessor.compose(getApplication(), base, overlay, corner)
            _editingIds.update { it - base.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved the composited video as a new file"
                }
                .onFailure { e -> _snackbarMessage.value = "Couldn't add overlay: ${e.message}" }
        }
    }

    /** Joins [orderedItems] into one new video, in the exact order given. */
    fun mergeSelected(orderedItems: List<SavedMedia>) {
        if (orderedItems.size < 2) return
        exitSelection()
        viewModelScope.launch {
            _snackbarMessage.value = "Merging ${orderedItems.size} videos…"
            val result = VideoEditProcessor.merge(getApplication(), orderedItems)
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved the merged video as a new file"
                }
                .onFailure { e -> _snackbarMessage.value = "Couldn't merge: ${e.message}" }
        }
    }

    /** Crops [item] to a rectangle (fractions 0..1 of the video as displayed) and saves the result as a new Library item. */
    fun cropVideo(item: SavedMedia, left: Float, top: Float, right: Float, bottom: Float) {
        if (item.id in _editingIds.value) return
        _editingIds.update { it + item.id }
        viewModelScope.launch {
            val result = VideoCropProcessor.crop(getApplication(), item, left, top, right, bottom)
            _editingIds.update { it - item.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved the cropped video as a new file"
                }
                .onFailure { e -> _snackbarMessage.value = "Couldn't crop: ${e.message}" }
        }
    }

    /** Strips [item]'s audio track entirely and saves the result as a new Library item. */
    fun removeAudio(item: SavedMedia) {
        if (item.id in _editingIds.value) return
        _editingIds.update { it + item.id }
        viewModelScope.launch {
            val result = ReplaceAudioProcessor.removeAudio(getApplication(), item)
            _editingIds.update { it - item.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved a muted copy"
                }
                .onFailure { e -> _snackbarMessage.value = "Couldn't remove audio: ${e.message}" }
        }
    }

    /** Swaps [item]'s audio for [newAudio] and saves the result as a new Library item. */
    fun replaceAudio(item: SavedMedia, newAudio: SavedMedia) {
        if (item.id in _editingIds.value) return
        _editingIds.update { it + item.id }
        viewModelScope.launch {
            val result = ReplaceAudioProcessor.replaceAudio(getApplication(), item, newAudio)
            _editingIds.update { it - item.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved a copy with the new audio"
                }
                .onFailure { e -> _snackbarMessage.value = "Couldn't replace audio: ${e.message}" }
        }
    }

    /** Rotates [item] by [degreesClockwise] (90/180/270) and saves the result as a new Library item. */
    fun rotateVideo(item: SavedMedia, degreesClockwise: Int) {
        if (item.id in _editingIds.value) return
        _editingIds.update { it + item.id }
        viewModelScope.launch {
            val result = RotateVideoProcessor.rotate(getApplication(), item, degreesClockwise)
            _editingIds.update { it - item.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved the rotated video as a new file"
                }
                .onFailure { e -> _snackbarMessage.value = "Couldn't rotate: ${e.message}" }
        }
    }

    /** Rewrites [item] as a plain MP4 for videos this phone's player can't handle as-is. */
    fun convertToMp4(item: SavedMedia) {
        if (item.id in _editingIds.value) return
        _editingIds.update { it + item.id }
        viewModelScope.launch {
            _snackbarMessage.value = "Converting — this can take a while for a long video…"
            val result = VideoConvertProcessor.toMp4(getApplication(), item)
            _editingIds.update { it - item.id }
            result
                .onSuccess { saved ->
                    dao.insert(saved)
                    _snackbarMessage.value = "Saved a converted copy — try playing that one"
                }
                .onFailure { e -> _snackbarMessage.value = "Couldn't convert: ${e.message}" }
        }
    }

    /** Copies videos picked from the phone's storage into the Library. */
    fun importVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _snackbarMessage.value = if (uris.size == 1) "Importing video…" else "Importing ${uris.size} videos…"
            val categoryId = categoryDao.findByName("Video")?.id ?: categoryDao.insert(MediaCategory(name = "Video"))
            var imported = 0
            var lastError: String? = null
            for (uri in uris) {
                MediaImporter.importVideo(getApplication(), uri, categoryId)
                    .onSuccess {
                        dao.insert(it)
                        imported++
                    }
                    .onFailure { e -> lastError = e.message }
            }
            _snackbarMessage.value = when {
                imported == 0 -> "Couldn't import: ${lastError ?: "unknown error"}"
                lastError != null -> "Imported $imported, ${uris.size - imported} failed: $lastError"
                imported == 1 -> "Imported 1 video"
                else -> "Imported $imported videos"
            }
        }
    }

    /** Copies [item] out to a destination the user picked, so it can leave the app's private storage. */
    fun exportTo(item: SavedMedia, destination: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val source = MediaAccess.openInputStream(context, item.filePath)
                        ?: throw IOException("The file is missing")
                    source.use { input ->
                        val output = context.contentResolver.openOutputStream(destination)
                            ?: throw IOException("Couldn't write to the chosen location")
                        output.use { input.copyTo(it) }
                    }
                }
            }
            _snackbarMessage.value = result.fold(
                onSuccess = { "Saved \"${item.caption}\" to your phone" },
                onFailure = { "Couldn't save: ${it.message}" }
            )
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
