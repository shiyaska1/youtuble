package com.mobicareapp.ui.handwriting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.mobicareapp.handwriting.GrammarFixer
import com.mobicareapp.handwriting.HandwritingExport
import com.mobicareapp.handwriting.HandwritingRecognizer
import com.mobicareapp.handwriting.HandwritingStore
import com.mobicareapp.handwriting.HwPage
import com.mobicareapp.handwriting.HandwritingNoteStore
import com.mobicareapp.handwriting.HwNoteMeta
import com.mobicareapp.handwriting.HwSnippet
import com.mobicareapp.handwriting.HwStroke
import com.mobicareapp.handwriting.ItemTransform
import com.mobicareapp.handwriting.InkItem
import com.mobicareapp.handwriting.InkLayout
import com.mobicareapp.handwriting.PageItem
import com.mobicareapp.handwriting.SharedItem
import com.mobicareapp.handwriting.WordItem
import com.mobicareapp.handwriting.WordSuggester
import com.mobicareapp.textextract.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed class ModelState {
    data object Checking : ModelState()
    data object Downloading : ModelState()
    data object Ready : ModelState()
    data class Failed(val message: String) : ModelState()
}

/**
 * State of the handwriting notepad: pages of strokes and completed words, the text read from each
 * page, Undo/Redo, and word suggestions for the word being written. Everything is saved to disk
 * after each change (see [HandwritingStore]), and handwriting is read automatically a moment after
 * the pen stops, page by page.
 */
class HandwritingViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    private val _pages = MutableStateFlow(HandwritingStore.load(application))
    val pages: StateFlow<List<HwPage>> = _pages.asStateFlow()

    private val _pageIndex = MutableStateFlow(0)
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()

    private val _model = MutableStateFlow<ModelState>(ModelState.Checking)
    val model: StateFlow<ModelState> = _model.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _autoFix = MutableStateFlow(HandwritingStore.isAutoFixEnabled(application))
    val autoFix: StateFlow<Boolean> = _autoFix.asStateFlow()

    /** Whole-word completions for the word being written right now (empty when there's nothing to offer). */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    /**
     * True while the newest strokes are still being read, so the suggestions on show are for what was
     * written a moment ago. They are kept (dimmed) instead of being cleared and redrawn on every
     * stroke, which looked like blinking.
     */
    private val _suggestionsStale = MutableStateFlow(false)
    val suggestionsStale: StateFlow<Boolean> = _suggestionsStale.asStateFlow()

    private val _snippets = MutableStateFlow(HandwritingStore.loadSnippets(application))
    /** Saved sentences/handwriting that can be inserted into any page (newest first). */
    val snippets: StateFlow<List<HwSnippet>> = _snippets.asStateFlow()

    private val _notes = MutableStateFlow(HandwritingNoteStore.loadIndex(application))
    /** Saved notes, newest first. */
    val notes: StateFlow<List<HwNoteMeta>> = _notes.asStateFlow()

    /** The saved note this notepad currently holds (so "Save note" updates it), or null for a new, unsaved notepad. */
    private val _noteId = MutableStateFlow<Long?>(null)
    val currentNoteId: StateFlow<Long?> = _noteId.asStateFlow()

    private val _pageRatio = MutableStateFlow(HandwritingStore.pageRatio(application))
    /** The shape of every page (height divided by width). The pad shows each page at this shape, and the export draws it the same, so nothing is cut off. */
    val pageRatioFlow: StateFlow<Float> = _pageRatio.asStateFlow()
    val pageRatio: Float get() = _pageRatio.value

    /**
     * The pad reports the shape of the room it has. Only an upright (taller than wide) measurement
     * counts, and the page only ever gets taller, never shorter: a sideways phone, or a shorter screen
     * for a moment, must not shrink the page under writing that is already on it.
     */
    fun noteViewportRatio(measured: Float) {
        if (measured <= 1f) return
        val ratio = maxOf(_pageRatio.value, measured.coerceAtMost(4f))
        if (ratio > _pageRatio.value + 0.001f) {
            _pageRatio.value = ratio
            HandwritingStore.setPageRatio(context, ratio)
        }
    }

    /** Why the last attempt to read the handwriting failed, if it did — shown under the text panel instead of failing silently. */
    private val _readError = MutableStateFlow<String?>(null)
    val readError: StateFlow<String?> = _readError.asStateFlow()

    // One reading job per page: starting to write on page 3 must not cancel the pending reading of page 2.
    private val recognizeJobs = mutableMapOf<Int, Job>()
    private var suggestJob: Job? = null
    private var saveJob: Job? = null

    init {
        prepareModel()
        // A draft saved with writing but no text yet (e.g. the app closed mid-reading) gets read now.
        _pages.value.forEachIndexed { index, page -> if (page.items.isNotEmpty() && page.text.isBlank()) scheduleRecognition(index, 0) }
    }

    fun prepareModel() {
        _model.value = ModelState.Checking
        viewModelScope.launch {
            try {
                if (!HandwritingRecognizer.isModelReady()) {
                    _model.value = ModelState.Downloading
                    HandwritingRecognizer.ensureModel()
                }
                _model.value = ModelState.Ready
            } catch (e: Exception) {
                _model.value = ModelState.Failed(e.message ?: "Couldn't get the handwriting model — check your internet and try again.")
            }
        }
    }

    // --- pages -----------------------------------------------------------------------------

    fun goTo(index: Int) {
        _pageIndex.value = index.coerceIn(0, _pages.value.lastIndex)
        clearSuggestions()
    }

    /** Moves to the next page, adding a fresh blank one if this was the last. */
    fun nextPage() {
        if (_pageIndex.value == _pages.value.lastIndex) {
            _pages.update { it + HwPage() }
            persist()
        }
        _pageIndex.value = _pageIndex.value + 1
        clearSuggestions()
    }

    fun previousPage() {
        _pageIndex.value = (_pageIndex.value - 1).coerceAtLeast(0)
        clearSuggestions()
    }

    // --- writing ---------------------------------------------------------------------------

    fun addStroke(stroke: HwStroke) {
        val index = _pageIndex.value
        updatePage(index) { it.copy(items = it.items + InkItem(stroke), redo = emptyList()) }
        scheduleRecognition(index, RECOGNIZE_DELAY_MS)
        scheduleSuggestions(index)
    }

    fun undo() {
        val index = _pageIndex.value
        val page = _pages.value[index]
        val last = page.items.lastOrNull() ?: return
        updatePage(index) {
            if (last.group != 0) {
                // A whole inserted snippet goes in one Undo, not stroke by stroke.
                val removed = it.items.takeLastWhile { item -> item.group == last.group }
                it.copy(items = it.items.dropLast(removed.size), redo = it.redo + removed.asReversed())
            } else {
                val remaining = it.items.dropLast(1)
                // Undoing a tapped suggestion gives the half-written word's strokes back.
                val restored = if (last is WordItem) remaining + last.replaced.map { stroke -> InkItem(stroke) } else remaining
                it.copy(items = restored, redo = it.redo + last)
            }
        }
        scheduleRecognition(index, RECOGNIZE_DELAY_MS)
        scheduleSuggestions(index)
    }

    fun redo() {
        val index = _pageIndex.value
        val page = _pages.value[index]
        val next = page.redo.lastOrNull() ?: return
        updatePage(index) {
            if (next.group != 0) {
                val back = it.redo.takeLastWhile { item -> item.group == next.group }
                it.copy(items = it.items + back.asReversed(), redo = it.redo.dropLast(back.size))
            } else {
                val base = if (next is WordItem) it.items.dropLast(next.replaced.size) else it.items
                it.copy(items = base + next, redo = it.redo.dropLast(1))
            }
        }
        scheduleRecognition(index, RECOGNIZE_DELAY_MS)
        scheduleSuggestions(index)
    }

    fun clearPage() {
        _pages.value.getOrNull(_pageIndex.value)?.imagePath?.let { File(it).delete() }
        updatePage(_pageIndex.value) { HwPage() }
        clearSuggestions()
    }

    /**
     * Removes the current screen from the notepad altogether (its writing, text and picture), so the
     * screens after it move up by one. If it is the only screen, it is just cleared. Clear page, by
     * contrast, empties a screen but keeps it.
     */
    fun deletePage() {
        val index = _pageIndex.value
        val pages = _pages.value
        if (index !in pages.indices) return
        pages[index].imagePath?.let { File(it).delete() }
        recognizeJobs.values.forEach { it.cancel() }
        recognizeJobs.clear()
        val remaining = pages.filterIndexed { i, _ -> i != index }.ifEmpty { listOf(HwPage()) }
        _pages.value = remaining
        _pageIndex.value = index.coerceIn(0, remaining.lastIndex)
        clearSuggestions()
        persist()
        // The screens after the deleted one changed number, so any reading that was waiting is redone for the right screen.
        remaining.forEachIndexed { i, pg -> if (pg.items.isNotEmpty() && pg.text.isBlank() && !pg.textEdited) scheduleRecognition(i, 0) }
    }

    /** Wipes every page and starts a fresh notepad. */
    fun newNotepad() {
        recognizeJobs.values.forEach { it.cancel() }
        recognizeJobs.clear()
        clearSuggestions()
        _noteId.value = null
        _pages.value.forEach { page -> page.imagePath?.let { File(it).delete() } }
        _pages.value = listOf(HwPage())
        _pageIndex.value = 0
        persist()
    }

    // --- suggestions -----------------------------------------------------------------------

    private fun clearSuggestions() {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        _suggestionsStale.value = false
    }

    /**
     * Shortly after the pen stops, reads the word being written (just its strokes so far) and offers
     * common English words that start with those letters.
     */
    private fun scheduleSuggestions(index: Int) {
        suggestJob?.cancel()
        _suggestionsStale.value = true
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DELAY_MS)
            if (_model.value !is ModelState.Ready) return@launch
            val page = _pages.value.getOrNull(index) ?: return@launch
            val word = InkLayout.currentWord(page.items)
            if (word == null || word.strokes.isEmpty()) {
                _suggestions.value = emptyList()
                _suggestionsStale.value = false
                return@launch
            }
            val candidates = runCatching { HandwritingRecognizer.candidatesFor(word) }
                .onFailure { _readError.value = it.message ?: it.javaClass.simpleName }
                .getOrNull() ?: return@launch
            val found = withContext(Dispatchers.Default) { WordSuggester.suggest(context, candidates) }
            // Ignore the answer if more was written (or the page changed) while it was being worked out.
            if (_pageIndex.value == index && _pages.value.getOrNull(index)?.items?.size == page.items.size) {
                _suggestions.value = found
                _suggestionsStale.value = false
            }
        }
    }

    /** The writer tapped a suggestion: the half-written word's strokes are swapped for the full word. */
    fun pickSuggestion(word: String) {
        val index = _pageIndex.value
        val page = _pages.value.getOrNull(index) ?: return
        val current = InkLayout.currentWord(page.items) ?: return
        val strokes = current.strokes
        if (strokes.isEmpty()) return
        val angle = current.angle
        val c = kotlin.math.cos(angle)
        val s = kotlin.math.sin(angle)

        // In the writing's own frame, "along" runs in the direction of writing and "down" is across it.
        // The word starts at the first letter's left edge and sits on the baseline, taken as the
        // typical bottom of the strokes (so a few descending letters like y or p don't drag it down).
        val alongStart = strokes.minOf { st -> st.points.minOf { it.x * c + it.y * s } }
        val bottoms = strokes.map { st -> st.points.maxOf { -it.x * s + it.y * c } }.sorted()
        val baseline = bottoms[bottoms.size / 2]
        val startX = alongStart * c - baseline * s
        val startY = alongStart * s + baseline * c

        val letterSize = InkLayout.typicalSize(strokes.map { InkLayout.Element(InkItem(it)) })
        // Set the finished word about as big as the letters it replaces (within sensible limits).
        val size = (letterSize * 0.6f).coerceIn(WORD_SIZE_MIN, WORD_SIZE_MAX)
        val replaced = WordItem(text = word, x = startX, y = startY, size = size, replaced = strokes, angle = angle)
        updatePage(index) { it.copy(items = it.items.dropLast(strokes.size) + replaced, redo = emptyList()) }
        clearSuggestions()
        scheduleRecognition(index, RECOGNIZE_DELAY_MS)
    }

    // --- saved notes -------------------------------------------------------------------------

    /**
     * Saves the whole notepad (every page, pictures included) as a note named after the date and time,
     * titled with a small part of its text. If this notepad was opened from a saved note, that note is
     * updated instead of a new one being made. [onSaved] gets the note, or null if there was nothing to save.
     */
    fun saveNote(onSaved: (HwNoteMeta?) -> Unit) {
        val pages = _pages.value
        if (pages.all { it.items.isEmpty() && it.imagePath == null }) {
            onSaved(null)
            return
        }
        val text = combinedText()
        val existing = _noteId.value?.let { id -> _notes.value.firstOrNull { it.id == id } }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = existing?.id ?: now
            val meta = HwNoteMeta(
                id = id,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
                title = existing?.title ?: HandwritingNoteStore.makeTitle(text),
                text = text,
                audioPath = existing?.audioPath,
                pageCount = pages.size
            )
            withContext(Dispatchers.IO) {
                HandwritingNoteStore.savePages(context, id, pages)
                val index = listOf(meta) + _notes.value.filterNot { it.id == id }
                HandwritingNoteStore.saveIndex(context, index)
                _notes.value = index
            }
            _noteId.value = id
            onSaved(meta)
        }
    }

    /** Replaces the notepad's content with the saved note [id], so it can be read and edited on the pad. */
    fun openNote(id: Long, onOpened: () -> Unit) {
        viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                HandwritingNoteStore.pagesForEditing(context, HandwritingNoteStore.loadPages(context, id))
            }
            recognizeJobs.values.forEach { it.cancel() }
            recognizeJobs.clear()
            clearSuggestions()
            _pages.value.forEach { page -> page.imagePath?.let { File(it).delete() } }
            _pages.value = pages
            _pageIndex.value = 0
            _noteId.value = id
            persist()
            onOpened()
        }
    }

    fun deleteNote(id: Long) {
        com.mobicareapp.reminder.NoteReminders.remove(context, id)
        viewModelScope.launch(Dispatchers.IO) {
            HandwritingNoteStore.delete(context, id)
            val index = _notes.value.filterNot { it.id == id }
            HandwritingNoteStore.saveIndex(context, index)
            _notes.value = index
        }
        if (_noteId.value == id) _noteId.value = null
    }

    /** Changes made on the note's own screen (title, text). The handwriting itself is not touched. */
    fun updateNoteTexts(id: Long, title: String, text: String) {
        val note = _notes.value.firstOrNull { it.id == id } ?: return
        val updated = note.copy(title = title.trim().ifEmpty { note.title }, text = text, updatedAt = System.currentTimeMillis())
        val index = _notes.value.map { if (it.id == id) updated else it }
        _notes.value = index
        viewModelScope.launch(Dispatchers.IO) { HandwritingNoteStore.saveIndex(context, index) }
    }

    /** Attaches a voice recording (MP3 or any audio) to the note, keeping a copy with the note. */
    fun attachAudio(id: Long, source: File, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) { runCatching { HandwritingNoteStore.storeAudio(context, id, source) }.getOrNull() }
            if (stored == null) {
                onDone(false)
                return@launch
            }
            val index = _notes.value.map { if (it.id == id) it.copy(audioPath = stored, updatedAt = System.currentTimeMillis()) else it }
            _notes.value = index
            withContext(Dispatchers.IO) { HandwritingNoteStore.saveIndex(context, index) }
            onDone(true)
        }
    }

    fun removeAudio(id: Long) {
        val index = _notes.value.map { if (it.id == id) it.copy(audioPath = null) else it }
        _notes.value = index
        viewModelScope.launch(Dispatchers.IO) {
            HandwritingNoteStore.deleteAudio(context, id)
            HandwritingNoteStore.saveIndex(context, index)
        }
    }

    // --- saved snippets ----------------------------------------------------------------------

    /** Saves what is on the current page under [title], to be inserted later. Returns false if the page is empty. */
    fun saveSnippet(title: String): Boolean {
        val page = _pages.value.getOrNull(_pageIndex.value) ?: return false
        val name = title.trim()
        if (name.isEmpty() || page.items.isEmpty()) return false
        val snippet = HwSnippet(
            id = System.currentTimeMillis(),
            title = name,
            createdAt = System.currentTimeMillis(),
            // Stored without grouping tags; they're given a fresh one each time the snippet is inserted.
            items = page.items.map { item ->
                when (item) {
                    is InkItem -> item.copy(group = 0)
                    is WordItem -> item.copy(group = 0)
                }
            },
            text = page.text
        )
        _snippets.update { listOf(snippet) + it }
        persistSnippets()
        return true
    }

    fun deleteSnippet(id: Long) {
        _snippets.update { list -> list.filterNot { it.id == id } }
        persistSnippets()
    }

    /**
     * Puts a saved snippet onto the current page, below whatever is already written there (or at the
     * top of an empty page), and reads it straight away. The whole snippet goes in as one Undo step.
     */
    fun insertSnippet(snippet: HwSnippet) {
        val index = _pageIndex.value
        val page = _pages.value.getOrNull(index) ?: return
        if (snippet.items.isEmpty()) return
        val snippetTop = HwPage(items = snippet.items).highestY
        val targetTop = if (page.items.isEmpty()) INSERT_MARGIN else page.lowestY + INSERT_GAP
        val timeShift = if (page.items.isEmpty()) 0L else ItemTransform.lastTime(page.items) + 1000L - ItemTransform.firstTime(snippet.items)
        val group = (System.currentTimeMillis() % Int.MAX_VALUE).toInt().coerceAtLeast(1)
        val moved = ItemTransform.shifted(snippet.items, targetTop - snippetTop, timeShift, group)
        updatePage(index) { current ->
            if (current.textEdited) {
                // The text was fixed by hand and is protected from being re-read, so add the saved text to it.
                current.copy(items = current.items + moved, redo = emptyList(), text = HandwritingRecognizer.joinPieces(listOf(current.text.trim(), snippet.text.trim()).filter { it.isNotEmpty() }, context))
            } else {
                current.copy(items = current.items + moved, redo = emptyList())
            }
        }
        clearSuggestions()
        scheduleRecognition(index, 0)
    }

    /** Inserts each of [snippets] in turn, every one below the previous, then everything is read. */
    fun insertSnippets(snippets: List<HwSnippet>) {
        snippets.forEach { insertSnippet(it) }
    }

    private fun persistSnippets() {
        viewModelScope.launch(Dispatchers.IO) { HandwritingStore.saveSnippets(context, _snippets.value) }
    }

    // --- picture from the gallery ---------------------------------------------------------------

    /**
     * Puts gallery pictures on the pad, each scaled to fit a whole page, behind the writing. The
     * first goes on the current page; every further picture gets a page of its own, added right after
     * the one before it (pages that were already there are pushed further along, not overwritten).
     */
    fun insertImages(uris: List<Uri>, deleteSourceFiles: Boolean = false) {
        if (uris.isEmpty()) return
        val startIndex = _pageIndex.value
        viewModelScope.launch { insertImagesNow(uris, deleteSourceFiles, startIndex) }
    }

    /** [startIndex] null = the first blank screen (a new one if none is), used for shared pictures. */
    private suspend fun insertImagesNow(uris: List<Uri>, deleteSourceFiles: Boolean, startIndex: Int?) {
        val paths = withContext(Dispatchers.IO) {
            uris.mapNotNull { uri -> runCatching { copyDownscaled(uri) }.getOrNull() }.also {
                // A photo just taken with the camera (or a copy of a shared picture) is a temporary file; once the page has its own copy it can go.
                if (deleteSourceFiles) uris.forEach { uri -> if (uri.scheme == "file") runCatching { File(uri.path!!).delete() } }
            }
        }
        if (paths.isEmpty()) {
            _readError.value = "Couldn't open that picture"
            return
        }
        val target = startIndex ?: firstBlankIndex()
        placeImagePaths(paths, target)
        if (startIndex == null) _pageIndex.value = target
        if (paths.size < uris.size) _readError.value = "${uris.size - paths.size} picture(s) couldn't be opened"
    }

    /** The first screen with nothing on it; a new one is added at the end if every screen is used. */
    private fun firstBlankIndex(): Int {
        var index = _pages.value.indexOfFirst { it.items.isEmpty() && it.imagePath == null }
        if (index < 0) {
            _pages.update { it + HwPage() }
            index = _pages.value.lastIndex
        }
        return index
    }

    /**
     * Puts pictures (already stored as files) onto pages: the first on the page at [startIndex]
     * (replacing any picture there), each further one on a new page added right after the one before.
     */
    private fun placeImagePaths(paths: List<String>, startIndex: Int) {
        // Picture one replaces any picture already on this page.
        _pages.value.getOrNull(startIndex)?.imagePath?.let { old -> File(old).delete() }
        _pages.update { pages ->
            val result = pages.toMutableList()
            result[startIndex] = result[startIndex].copy(imagePath = paths.first())
            paths.drop(1).forEachIndexed { i, path -> result.add(startIndex + 1 + i, HwPage(imagePath = path)) }
            result
        }
        persist()
        // New pages shifted the ones after them, so any reading that was waiting is redone for the right page.
        recognizeJobs.values.forEach { it.cancel() }
        recognizeJobs.clear()
        _pages.value.forEachIndexed { i, page -> if (page.items.isNotEmpty() && !page.textEdited) scheduleRecognition(i, 0) }
    }

    // --- voice -> text on the latest blank screen ------------------------------------------------

    /**
     * Transcribes a voice recording (with the engine chosen in the Transcribe window, in English) and
     * puts the text on the latest blank screen of the notepad.
     */
    fun transcribeAndInsert(file: File) {
        if (_importStatus.value != null) return
        viewModelScope.launch { transcribeNow(file, firstBlank = false) }
    }

    /** [firstBlank] false = the latest blank screen (picked voice), true = the first blank screen (shared voice). */
    private suspend fun transcribeNow(file: File, firstBlank: Boolean) {
        _importStatus.value = "Transcribing the voice…"
        val result = com.mobicareapp.transcribe.TranscriptionRouter.transcribe(context, file, "en") { status ->
            _importStatus.value = status
        }
        _importStatus.value = null
        result
            .onSuccess { text ->
                if (text.isBlank()) _readError.value = "No speech was recognized in that recording"
                else insertTextOnBlankPage(text.trim(), firstBlank)
            }
            .onFailure { e -> _readError.value = e.message ?: "Couldn't transcribe that recording" }
    }

    /**
     * Lays [text] out on the page as words in the handwriting-style font, wrapped across the page.
     * It goes on the latest screen that is blank (or a new one added at the end if none is); text too
     * long for one screen carries on onto new screens right after it. The notepad then shows that screen.
     */
    private fun insertTextOnBlankPage(text: String, useFirstBlank: Boolean = false) {
        val size = TEXT_SIZE
        val lineHeight = size * 1.55f
        val firstBaseline = 0.05f + size
        val lastBaseline = pageRatio.coerceIn(1f, 4f) - 0.06f
        val linesPerPage = (((lastBaseline - firstBaseline) / lineHeight).toInt() + 1).coerceAtLeast(1)

        // Wrap the words into lines (each word remembers its x on the line).
        val lines = mutableListOf<MutableList<Pair<String, Float>>>(mutableListOf())
        var x = 0.05f
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val width = word.length * size * 0.52f
            if (x + width > 0.95f && lines.last().isNotEmpty()) {
                lines.add(mutableListOf())
                x = 0.05f
            }
            lines.last().add(word to x)
            x += width + size * 0.35f
        }
        val chunks = lines.filter { it.isNotEmpty() }.chunked(linesPerPage)
        if (chunks.isEmpty()) return

        val group = (System.currentTimeMillis() % Int.MAX_VALUE).toInt().coerceAtLeast(1)
        fun itemsFor(chunk: List<List<Pair<String, Float>>>): List<PageItem> =
            chunk.flatMapIndexed { lineIndex, line ->
                line.map { (word, wordX) -> WordItem(word, wordX, firstBaseline + lineIndex * lineHeight, size, emptyList(), 0f, group) }
            }
        fun textFor(chunk: List<List<Pair<String, Float>>>): String = chunk.joinToString(" ") { line -> line.joinToString(" ") { it.first } }

        var targetIndex = 0
        _pages.update { pages ->
            val result = pages.toMutableList()
            // The latest blank screen; if every screen has something on it, a new one at the end.
            var target = if (useFirstBlank) {
                result.indexOfFirst { it.items.isEmpty() && it.imagePath == null }
            } else {
                result.indexOfLast { it.items.isEmpty() && it.imagePath == null }
            }
            if (target < 0) {
                result += HwPage()
                target = result.lastIndex
            }
            targetIndex = target
            result[target] = result[target].copy(items = itemsFor(chunks[0]), redo = emptyList(), text = textFor(chunks[0]), textEdited = false)
            chunks.drop(1).forEachIndexed { i, chunk ->
                result.add(target + 1 + i, HwPage(items = itemsFor(chunk), text = textFor(chunk)))
            }
            result
        }
        _pageIndex.value = targetIndex
        persist()
        clearSuggestions()
        // Pages may have shifted, so any reading that was waiting is redone for the right page.
        recognizeJobs.values.forEach { it.cancel() }
        recognizeJobs.clear()
        _pages.value.forEachIndexed { i, page -> if (page.items.isNotEmpty() && page.text.isBlank() && !page.textEdited) scheduleRecognition(i, 0) }
    }

    // --- text typed on the keyboard ---------------------------------------------------------------

    /**
     * Puts text typed on the keyboard onto the notepad in the handwriting-style font, at [size] (page
     * widths; bigger = a bigger font). On the screen showing, it starts below whatever is already
     * written (at the top of an empty one); if it doesn't fit, the rest carries on onto new screens
     * added right after.
     */
    fun insertTypedText(text: String, size: Float) {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return
        val index = _pageIndex.value
        val page = _pages.value.getOrNull(index) ?: return

        val lineHeight = size * 1.55f
        val topBaseline = 0.05f + size
        val lastBaseline = pageRatio.coerceIn(1f, 4f) - 0.06f
        val startBaseline = if (page.items.isEmpty()) topBaseline else page.lowestY + 0.03f + size
        val firstCapacity = if (startBaseline > lastBaseline) 0 else ((lastBaseline - startBaseline) / lineHeight).toInt() + 1
        val perPage = (((lastBaseline - topBaseline) / lineHeight).toInt() + 1).coerceAtLeast(1)

        // Wrap the words into lines across the page (each word remembers its x on the line).
        val lines = mutableListOf<MutableList<Pair<String, Float>>>(mutableListOf())
        var x = 0.05f
        for (word in words) {
            val width = word.length * size * 0.52f
            if (x + width > 0.95f && lines.last().isNotEmpty()) {
                lines.add(mutableListOf())
                x = 0.05f
            }
            lines.last().add(word to x)
            x += width + size * 0.35f
        }
        val nonEmpty = lines.filter { it.isNotEmpty() }
        val firstLines = nonEmpty.take(firstCapacity)
        val laterPages = nonEmpty.drop(firstCapacity).chunked(perPage)

        val group = (System.currentTimeMillis() % Int.MAX_VALUE).toInt().coerceAtLeast(1)
        fun itemsFor(chunk: List<List<Pair<String, Float>>>, baseline: Float): List<PageItem> =
            chunk.flatMapIndexed { lineIndex, line ->
                line.map { (word, wordX) -> WordItem(word, wordX, baseline + lineIndex * lineHeight, size, emptyList(), 0f, group) }
            }
        fun textFor(chunk: List<List<Pair<String, Float>>>): String = chunk.joinToString(" ") { line -> line.joinToString(" ") { it.first } }

        _pages.update { pages ->
            val result = pages.toMutableList()
            if (firstLines.isNotEmpty()) {
                val current = result[index]
                result[index] = current.copy(
                    items = current.items + itemsFor(firstLines, startBaseline),
                    redo = emptyList(),
                    text = HandwritingRecognizer.joinPieces(listOf(current.text.trim(), textFor(firstLines)).filter { it.isNotEmpty() }, context)
                )
            }
            laterPages.forEachIndexed { i, chunk ->
                result.add(index + 1 + i, HwPage(items = itemsFor(chunk, topBaseline), text = textFor(chunk)))
            }
            result
        }
        // If nothing fitted below the writing on this screen, show the first new screen instead.
        if (firstLines.isEmpty() && laterPages.isNotEmpty()) _pageIndex.value = index + 1
        persist()
        clearSuggestions()
        // New screens shifted the ones after them, so any reading that was waiting is redone for the right screen.
        recognizeJobs.values.forEach { it.cancel() }
        recognizeJobs.clear()
        _pages.value.forEachIndexed { i, pg -> if (pg.items.isNotEmpty() && pg.text.isBlank() && !pg.textEdited) scheduleRecognition(i, 0) }
    }

    // --- PDF: one page per screen ----------------------------------------------------------------

    private val _importStatus = MutableStateFlow<String?>(null)
    /** Progress text while a PDF is being turned into pages (null otherwise). */
    val importStatus: StateFlow<String?> = _importStatus.asStateFlow()

    /**
     * Puts a PDF into the notepad with every page on a screen of its own: the first PDF page goes on
     * the current screen, the rest on new screens added after it, in order. Each page becomes a
     * picture fitted to the whole pad, and can be written on like any other picture.
     */
    fun insertPdf(uri: Uri) {
        if (_importStatus.value != null) return
        val startIndex = _pageIndex.value
        viewModelScope.launch { insertPdfNow(uri, startIndex) }
    }

    /** [startIndex] null = the first blank screen, used for a shared PDF. */
    private suspend fun insertPdfNow(uri: Uri, startIndex: Int?) {
        _importStatus.value = "Reading the PDF…"
        val result = withContext(Dispatchers.IO) { runCatching { renderPdfPages(uri) } }
        _importStatus.value = null
        result
            .onSuccess { (paths, pageCount) ->
                if (paths.isEmpty()) {
                    _readError.value = "That PDF has no pages"
                    return@onSuccess
                }
                val target = startIndex ?: firstBlankIndex()
                placeImagePaths(paths, target)
                if (startIndex == null) _pageIndex.value = target
                if (pageCount > paths.size) _readError.value = "Only the first ${paths.size} of $pageCount PDF pages were added"
            }
            .onFailure { _readError.value = "Couldn't read that PDF" }
    }

    // --- content shared from other apps -------------------------------------------------------------

    /**
     * Handles what another app shared (WhatsApp voice notes and pictures, a PDF…), one after the
     * other: a voice is transcribed and its text put on the first free screen; a picture goes on the
     * first free screen; a PDF's pages go on free screens from the first one on.
     */
    fun handleShared(items: List<SharedItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            for (item in items) {
                val mime = item.mime.lowercase()
                when {
                    mime.startsWith("audio/") || mime == "application/ogg" -> {
                        transcribeNow(item.file, firstBlank = true)
                        item.file.delete()
                    }
                    mime.startsWith("image/") -> insertImagesNow(listOf(Uri.fromFile(item.file)), deleteSourceFiles = true, startIndex = null)
                    mime == "application/pdf" -> {
                        insertPdfNow(Uri.fromFile(item.file), startIndex = null)
                        item.file.delete()
                    }
                    else -> item.file.delete()
                }
            }
        }
    }

    /** Draws each PDF page (up to [MAX_PDF_PAGES]) to a picture file, one at a time so a long PDF never fills memory. Returns the files and the PDF's real page count. */
    private fun renderPdfPages(uri: Uri): Pair<List<String>, Int> {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: error("unreadable")
        val dir = File(context.filesDir, "handwriting_images").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val paths = mutableListOf<String>()
        var pageCount = 0
        PdfRenderer(descriptor).use { renderer ->
            pageCount = renderer.pageCount
            val total = minOf(pageCount, MAX_PDF_PAGES)
            for (i in 0 until total) {
                _importStatus.value = "Adding PDF page ${i + 1} of $total…"
                val bitmap = PdfTextExtractor.renderPage(renderer, i, PDF_RENDER_WIDTH)
                val file = File(dir, "pdf_${stamp}_$i.jpg")
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
                bitmap.recycle()
                paths += file.absolutePath
            }
        }
        return paths to pageCount
    }

    fun removeImage() {
        val index = _pageIndex.value
        _pages.value.getOrNull(index)?.imagePath?.let { File(it).delete() }
        updatePage(index) { it.copy(imagePath = null) }
    }

    /** Keeps a copy inside the app (so the picture survives the gallery moving it) at no more than 1600 px, turned upright. */
    private fun copyDownscaled(uri: Uri): String {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: error("unreadable")
        val degrees = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                when (android.media.ExifInterface(stream).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            }
        }.getOrNull() ?: 0f
        val upright = if (degrees == 0f) decoded else Bitmap.createBitmap(
            decoded, 0, 0, decoded.width, decoded.height, android.graphics.Matrix().apply { postRotate(degrees) }, true
        )
        val dir = File(context.filesDir, "handwriting_images").apply { mkdirs() }
        val file = File(dir, "img_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file.absolutePath
    }

    // --- download everything ---------------------------------------------------------------------

    /** Every page stacked top to bottom as one tall image, ready to save. Heavy: call off the main thread. */
    fun renderAllPages(): Bitmap = HandwritingExport.renderAll(_pages.value, pageRatio)

    // --- text ------------------------------------------------------------------------------

    /** The user fixed the text by hand: keep it, and stop the next automatic reading from overwriting it. */
    fun setPageText(index: Int, text: String) {
        updatePage(index) { it.copy(text = text, textEdited = true) }
    }

    /** Throws away hand edits for this page and reads the handwriting again. */
    fun rereadPage(index: Int) {
        updatePage(index) { it.copy(textEdited = false) }
        scheduleRecognition(index, 0)
    }

    /** Reads this page's handwriting right now, without waiting for the pause after writing. */
    fun readNow(index: Int) {
        _readError.value = null
        scheduleRecognition(index, 0)
    }

    /** Replaces the page text with a repaired version without counting it as a hand edit, so new handwriting still updates it. */
    fun replacePageText(index: Int, text: String) {
        updatePage(index) { if (it.textEdited) it else it.copy(text = text) }
    }

    fun setAutoFix(enabled: Boolean) {
        _autoFix.value = enabled
        HandwritingStore.setAutoFixEnabled(context, enabled)
    }

    /** Repairs spelling and grammar in [text] — used by the page and full-screen "Fix" buttons. */
    suspend fun repair(text: String): String = GrammarFixer.fix(context, text)

    /** Every page's text as one continuous paragraph (no line break between pages), ready to copy. */
    fun combinedText(): String =
        HandwritingRecognizer.joinPieces(_pages.value.map { it.text.trim() }.filter { it.isNotEmpty() }, context)

    // --- internals -------------------------------------------------------------------------

    private fun scheduleRecognition(index: Int, delayMs: Long) {
        recognizeJobs[index]?.cancel()
        recognizeJobs[index] = viewModelScope.launch {
            delay(delayMs)
            // The model may still be downloading; wait for it rather than dropping the reading.
            while (_model.value is ModelState.Checking || _model.value is ModelState.Downloading) delay(500)
            if (_model.value !is ModelState.Ready) return@launch
            val page = _pages.value.getOrNull(index) ?: return@launch
            if (page.textEdited) return@launch
            _busy.value = true
            try {
                val text = HandwritingRecognizer.recognize(page.items, context)
                // Only store the result if the page wasn't edited by hand while this was running.
                updatePage(index) { current -> if (current.textEdited) current else current.copy(text = text) }
                _readError.value = null
                _busy.value = false
                if (_autoFix.value && text.isNotBlank()) {
                    // Cancelled (and so skipped) if another stroke arrives during the wait.
                    delay(AUTO_FIX_IDLE_MS)
                    _busy.value = true
                    val fixed = GrammarFixer.fix(context, text)
                    updatePage(index) { current -> if (current.textEdited) current else current.copy(text = fixed) }
                }
            } catch (e: Exception) {
                // A failed reading leaves the old text; the next stroke tries again.
                _readError.value = e.message ?: e.javaClass.simpleName
            } finally {
                _busy.value = false
            }
        }
    }

    private fun updatePage(index: Int, change: (HwPage) -> HwPage) {
        _pages.update { pages -> pages.mapIndexed { i, page -> if (i == index) change(page) else page } }
        persist()
    }

    private fun persist() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) { HandwritingStore.save(context, _pages.value) }
        }
    }

    override fun onCleared() {
        // The delayed save may not have run yet; make sure the last strokes aren't lost.
        HandwritingStore.save(context, _pages.value)
        super.onCleared()
    }

    private companion object {
        /** Quiet time after a stroke before the page is read again: short, so the preview follows the writing stroke by stroke. */
        const val RECOGNIZE_DELAY_MS = 350L

        /**
         * Spelling/grammar repair waits for this much quiet. While writing, the preview shows the
         * raw reading - a repair would quietly fix the very character the writer wants to notice
         * and Undo - and a repair per stroke would also hammer the free grammar service.
         */
        const val AUTO_FIX_IDLE_MS = 3000L

        /** Shorter wait before suggestions appear, so they're there while the writer is still deciding about the word. */
        const val SUGGEST_DELAY_MS = 450L

        /** Font size (in page widths) of text put on a page from a voice transcript. */
        const val TEXT_SIZE = 0.045f

        /** A PDF longer than this only has its first pages added (each becomes a picture file). */
        const val MAX_PDF_PAGES = 60

        /** Width, in pixels, each PDF page is drawn at: sharp enough to read when zoomed, small enough to keep files light. */
        const val PDF_RENDER_WIDTH = 1400

        /** Where an inserted snippet lands on an empty page (page widths from the top), and the gap left below existing writing. */
        const val INSERT_MARGIN = 0.05f
        const val INSERT_GAP = 0.08f

        /** Limits (in page widths) for the font size of a completed word. */
        const val WORD_SIZE_MIN = 0.045f
        const val WORD_SIZE_MAX = 0.11f
    }
}
