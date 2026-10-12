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
            val strokes = currentWordStrokes(page.items)
            if (strokes.isEmpty()) return@launch
            val candidates = runCatching { HandwritingRecognizer.candidatesFor(strokes) }.getOrNull() ?: return@launch
            val found = withContext(Dispatchers.Default) { WordSuggester.suggest(context, candidates) }
            // Ignore the answer if more was written (or the page changed) while it was being worked out.
            if (_pageIndex.value == index && _pages.value.getOrNull(index)?.items?.size == page.items.size) {
                _suggestions.value = found
            }
        }
    }

    /**
     * The strokes of the word being written: the latest strokes, going backwards while each earlier
     * stroke is on the same line and not separated from the word by a word-sized gap. A finished
     * (suggestion-completed) word, or a big gap, ends it.
     */
    private fun currentWordStrokes(items: List<PageItem>): List<HwStroke> {
        val word = mutableListOf<HwStroke>()
        for (item in items.asReversed()) {
            if (item !is InkItem) break
            val stroke = item.stroke
            if (word.isNotEmpty()) {
                val wordLeft = word.minOf { it.minX }
                val wordRight = word.maxOf { it.maxX }
                val gap = if (stroke.maxX < wordLeft) wordLeft - stroke.maxX else if (stroke.minX > wordRight) stroke.minX - wordRight else 0f
                val sameLine = kotlin.math.abs((stroke.minY + stroke.maxY) / 2f - (word.minOf { it.minY } + word.maxOf { it.maxY }) / 2f) < SAME_LINE
                if (gap > WORD_GAP || !sameLine) break
            }
            word += stroke
        }
        return word.asReversed()
    }

    /** The writer tapped a suggestion: the half-written word's strokes are swapped for the full word. */
    fun pickSuggestion(word: String) {
        val index = _pageIndex.value
        val page = _pages.value.getOrNull(index) ?: return
        val strokes = currentWordStrokes(page.items)
        if (strokes.isEmpty()) return
        val left = strokes.minOf { it.minX }
        val bottom = strokes.maxOf { it.maxY }
        val replaced = WordItem(
            text = word,
            x = left,
            // Text sits with its baseline at the bottom of the letters that were written.
            y = bottom - WORD_SIZE * 0.95f,
            size = WORD_SIZE,
            replaced = strokes
        )
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
                var text = HandwritingRecognizer.recognize(page.items)
                if (_autoFix.value && text.isNotBlank()) text = GrammarFixer.fix(context, text)
                // Only store the result if the page wasn't edited by hand while this was running.
                updatePage(index) { current -> if (current.textEdited) current else current.copy(text = text) }
            } catch (e: Exception) {
                // A failed reading leaves the old text; the next stroke tries again.
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
        /** Quiet time after the last stroke before the page is read — long enough that pausing between words doesn't trigger a half-read. */
        const val RECOGNIZE_DELAY_MS = 1200L

        /** Shorter wait before suggestions appear, so they're there while the writer is still deciding about the word. */
        const val SUGGEST_DELAY_MS = 450L

        /** A gap wider than this (in page widths) between strokes means a new word has started. */
        const val WORD_GAP = 0.035f

        /** How far apart (in page widths) two strokes' centres may be vertically and still be on the same line. */
        const val SAME_LINE = 0.045f

        /** Font size of a completed word, in page widths — fits the ruled lines (spacing 0.09). */
        const val WORD_SIZE = 0.055f
    }
}
