package com.mobicareapp.ui.handwriting

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobicareapp.handwriting.GrammarFixer
import com.mobicareapp.handwriting.HandwritingRecognizer
import com.mobicareapp.handwriting.HandwritingStore
import com.mobicareapp.handwriting.HwPage
import com.mobicareapp.handwriting.HwStroke
import com.mobicareapp.handwriting.InkItem
import com.mobicareapp.handwriting.InkLayout
import com.mobicareapp.handwriting.PageItem
import com.mobicareapp.handwriting.WordItem
import com.mobicareapp.handwriting.WordSuggester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        _suggestions.value = emptyList()
    }

    /** Moves to the next page, adding a fresh blank one if this was the last. */
    fun nextPage() {
        if (_pageIndex.value == _pages.value.lastIndex) {
            _pages.update { it + HwPage() }
            persist()
        }
        _pageIndex.value = _pageIndex.value + 1
        _suggestions.value = emptyList()
    }

    fun previousPage() {
        _pageIndex.value = (_pageIndex.value - 1).coerceAtLeast(0)
        _suggestions.value = emptyList()
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
            val remaining = it.items.dropLast(1)
            // Undoing a tapped suggestion gives the half-written word's strokes back.
            val restored = if (last is WordItem) remaining + last.replaced.map { stroke -> InkItem(stroke) } else remaining
            it.copy(items = restored, redo = it.redo + last)
        }
        scheduleRecognition(index, RECOGNIZE_DELAY_MS)
        scheduleSuggestions(index)
    }

    fun redo() {
        val index = _pageIndex.value
        val page = _pages.value[index]
        val next = page.redo.lastOrNull() ?: return
        updatePage(index) {
            val base = if (next is WordItem) it.items.dropLast(next.replaced.size) else it.items
            it.copy(items = base + next, redo = it.redo.dropLast(1))
        }
        scheduleRecognition(index, RECOGNIZE_DELAY_MS)
        scheduleSuggestions(index)
    }

    fun clearPage() {
        updatePage(_pageIndex.value) { HwPage() }
        _suggestions.value = emptyList()
    }

    /** Wipes every page and starts a fresh notepad. */
    fun newNotepad() {
        recognizeJobs.values.forEach { it.cancel() }
        recognizeJobs.clear()
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        _pages.value = listOf(HwPage())
        _pageIndex.value = 0
        persist()
    }

    // --- suggestions -----------------------------------------------------------------------

    /**
     * Shortly after the pen stops, reads the word being written (just its strokes so far) and offers
     * common English words that start with those letters.
     */
    private fun scheduleSuggestions(index: Int) {
        suggestJob?.cancel()
        _suggestions.value = emptyList()
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DELAY_MS)
            if (_model.value !is ModelState.Ready) return@launch
            val page = _pages.value.getOrNull(index) ?: return@launch
            val word = InkLayout.currentWord(page.items)
            if (word == null || word.strokes.isEmpty()) return@launch
            val candidates = runCatching { HandwritingRecognizer.candidatesFor(word) }
                .onFailure { _readError.value = it.message ?: it.javaClass.simpleName }
                .getOrNull() ?: return@launch
            val found = withContext(Dispatchers.Default) { WordSuggester.suggest(context, candidates) }
            // Ignore the answer if more was written (or the page changed) while it was being worked out.
            if (_pageIndex.value == index && _pages.value.getOrNull(index)?.items?.size == page.items.size) {
                _suggestions.value = found
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
        _suggestions.value = emptyList()
        suggestJob?.cancel()
        scheduleRecognition(index, RECOGNIZE_DELAY_MS)
    }

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

    /** Every page's text, page after page with a blank line between, ready to copy. */
    fun combinedText(): String = _pages.value.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n\n")

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

        /** Limits (in page widths) for the font size of a completed word. */
        const val WORD_SIZE_MIN = 0.045f
        const val WORD_SIZE_MAX = 0.11f
    }
}
