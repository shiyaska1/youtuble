package com.mobicareapp.questionpaper

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mobicareapp.textextract.PdfNativeTextExtractor
import com.mobicareapp.textextract.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class QuestionPaperViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    /** One entry per page read from the uploaded book pages, in the order they were added. */
    private val _pageTexts = MutableStateFlow<List<String>>(emptyList())
    val pageTexts: StateFlow<List<String>> = _pageTexts.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    /** Non-null while pages are being read or a paper is being generated. */
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _papers = MutableStateFlow<List<QuestionPaper>>(emptyList())
    val papers: StateFlow<List<QuestionPaper>> = _papers.asStateFlow()

    val busy: Boolean get() = _status.value != null

    /** Photos and PDFs both end up as plain text; a PDF with its own text layer skips OCR entirely, a scanned one goes through the same OCR the "Extract text from PDF" tool uses. */
    fun addSources(uris: List<Uri>) {
        if (uris.isEmpty() || busy) return
        _error.value = null
        viewModelScope.launch {
            val added = mutableListOf<String>()
            try {
                for ((index, uri) in uris.withIndex()) {
                    val isPdf = context.contentResolver.getType(uri) == "application/pdf"
                    if (isPdf) {
                        val pageCount = withContext(Dispatchers.IO) {
                            runCatching { PdfTextExtractor.openPdf(context, uri).use { it.pageCount } }.getOrDefault(0)
                        }
                        for (page in 0 until minOf(pageCount, MAX_PDF_PAGES)) {
                            _status.value = "Reading file ${index + 1} of ${uris.size}, page ${page + 1} of $pageCount…"
                            readPdfPage(uri, page)?.let { added += it }
                        }
                    } else {
                        _status.value = "Reading page ${index + 1} of ${uris.size}…"
                        readImage(uri)?.let { added += it }
                    }
                }
                if (added.isEmpty()) _error.value = "Couldn't find any readable text in what you picked."
                _pageTexts.update { it + added }
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't read the pages"
            } finally {
                _status.value = null
            }
        }
    }

    private suspend fun readPdfPage(uri: Uri, pageIndex: Int): String? = withContext(Dispatchers.IO) {
        val native = runCatching { PdfNativeTextExtractor.extractParagraphs(context, uri, pageIndex) }.getOrNull()
        if (native != null && native.reliable) {
            return@withContext native.paragraphs.joinToString("\n")
        }
        runCatching {
            val bitmap = PdfTextExtractor.openPdf(context, uri).use { PdfTextExtractor.renderPage(it, pageIndex) }
            ocr(bitmap)
        }.getOrNull() ?: native?.paragraphs?.joinToString("\n")
    }

    private suspend fun readImage(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching { ocr(PdfTextExtractor.loadImage(context, uri)) }.getOrNull()
    }

    private suspend fun ocr(bitmap: android.graphics.Bitmap): String? {
        val extraction = PdfTextExtractor.extractText(context, bitmap)
        return extraction.paragraphsFor(extraction.detected).joinToString("\n").takeIf { it.isNotBlank() }
    }

    fun clearPages() {
        if (busy) return
        _pageTexts.value = emptyList()
        _papers.value = emptyList()
        _error.value = null
    }

    /**
     * [useAi] false forces the offline fallback. When AI was asked for but fails (no key, no
     * internet, quota used up) the reason is shown and nothing is silently swapped in — the user
     * decides whether to fall back, since the offline questions are noticeably weaker.
     */
    fun generate(count: Int, types: List<QuestionType>, useAi: Boolean) {
        if (busy || types.isEmpty()) return
        val source = _pageTexts.value.joinToString("\n\n")
        if (source.isBlank()) return
        if (_papers.value.size >= QuestionPaperSettings.MAX_PAPERS) {
            _error.value = "You've made the maximum of ${QuestionPaperSettings.MAX_PAPERS} papers from these pages. Clear the pages to start a new set."
            return
        }
        val apiKey = QuestionPaperSettings.getApiKey(context)
        if (useAi && apiKey == null) {
            _error.value = "Add your free Gemini API key in Settings first, or use \"Make without AI\"."
            return
        }
        _error.value = null
        _status.value = if (useAi) "AI is writing your questions…" else "Making questions…"
        viewModelScope.launch {
            val asked = _papers.value.flatMap { paper -> paper.questions.map { it.text } }
            val result = if (useAi) {
                GeminiQuestionGenerator.generate(apiKey!!, source, count, types, asked)
            } else {
                runCatching {
                    OfflineQuestionGenerator.generate(source, count, types, asked).also {
                        if (it.isEmpty()) error("The text is too short to make questions from.")
                    }
                }
            }
            result
                .onSuccess { questions ->
                    _papers.update { it + QuestionPaper(it.size + 1, System.currentTimeMillis(), useAi, questions) }
                }
                .onFailure { _error.value = it.message ?: "Couldn't make the paper" }
            _status.value = null
        }
    }

    suspend fun exportPdf(paper: QuestionPaper, includeAnswers: Boolean): Result<File> = withContext(Dispatchers.IO) {
        runCatching { QuestionPaperPdf.write(context, paper, includeAnswers) }
    }

    private companion object {
        const val MAX_PDF_PAGES = 30
    }
}
