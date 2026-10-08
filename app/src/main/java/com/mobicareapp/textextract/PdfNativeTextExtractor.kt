package com.mobicareapp.textextract

import android.content.Context
import android.graphics.RectF
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.PDFTextStripperByArea

/** [reliable] is a heads-up, not a block — the paragraphs are always the real extracted text; false just means the checks below spotted a likely font-encoding problem, so the result is worth a second look (or switching to OCR instead) rather than being hidden automatically. */
data class NativeTextResult(val paragraphs: List<String>, val reliable: Boolean)

/**
 * Reads a PDF page's own embedded text layer directly, when it has one, instead of rendering the
 * page to a bitmap and running OCR on it — a PDF generated from a real document (as opposed to a
 * scanned photo of paper) already has exact, selectable text baked in, so re-reading it via OCR
 * would only be slower and less accurate. Returns null only when the page (or the selected region
 * of it) has no text at all (a scanned/image-only PDF) — deciding whether to trust a result that
 * *did* come back is left to the caller (see [NativeTextResult.reliable]), not decided silently
 * here, since automatic fallback previously made it hard to tell which method actually ran.
 */
object PdfNativeTextExtractor {

    fun extractParagraphs(context: Context, uri: Uri, pageIndex: Int): NativeTextResult? {
        val stripper = ParagraphCapturingStripper()
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1

        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { document ->
                stripper.getText(document)
            }
        }
        // The page's very last paragraph never gets an explicit writeParagraphEnd() call once
        // the text simply runs out, so whatever's left in the buffer needs flushing by hand.
        stripper.flushCurrent()

        val paragraphs = stripper.paragraphs.filter { it.length >= 3 }
        if (paragraphs.isEmpty()) return null
        return NativeTextResult(paragraphs, reliable = looksReliable(paragraphs))
    }

    /**
     * Same idea, but scoped to one rectangle the user drew on the page (fractions 0..1, top-left
     * origin — same convention as the on-screen crop selector) rather than the whole page — for
     * picking out just one paragraph, table cell, or line at a time instead of extracting
     * everything at once. Returned as a single combined string rather than a paragraph list: a
     * hand-drawn selection is already "one thing" the user isolated, so there's no need to
     * re-detect paragraph boundaries inside it.
     */
    fun extractTextInRegion(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): NativeTextResult? {
        var regionText = ""
        context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { document ->
                val page = document.getPage(pageIndex)
                val pageWidth = page.mediaBox.width
                val pageHeight = page.mediaBox.height
                // PDFTextStripperByArea.addRegion takes top-left-origin coordinates in the page's
                // own point units (its own Javadoc: "y == 0 is top, not PDF coordinates") — exactly
                // the same convention the fractional crop rectangle already uses, just scaled up
                // from a 0..1 fraction to the page's actual width/height in points.
                val rect = RectF(left * pageWidth, top * pageHeight, right * pageWidth, bottom * pageHeight)
                val stripper = PDFTextStripperByArea()
                stripper.addRegion("selection", rect)
                stripper.extractRegions(page)
                regionText = stripper.getTextForRegion("selection")
            }
        }

        val text = regionText.trim().replace(Regex("\\s+"), " ")
        if (text.length < 3) return null
        return NativeTextResult(listOf(text), reliable = looksReliable(listOf(text)))
    }

    /**
     * Two known symptoms of the same underlying problem — a PDF whose embedded font has a broken
     * or incomplete character-to-Unicode mapping table, most often seen with certain
     * embedded/subsetted Malayalam fonts. The page still LOOKS fine in a real PDF renderer (which
     * draws glyphs directly, no Unicode mapping needed to display them), so this only shows up
     * once text is actually extracted, and no amount of retrying the same library on the same
     * broken mapping recovers the lost information — that's exactly why this only flags the
     * result now instead of silently switching to OCR, since OCR (reading the rendered glyphs
     * directly) is the only method that actually sidesteps the problem.
     */
    private fun looksReliable(paragraphs: List<String>): Boolean {
        val nonEmpty = paragraphs.filter { it.isNotBlank() }
        if (nonEmpty.isEmpty()) return true

        // 1. An outright unresolvable glyph: PDFBox substitutes the Unicode replacement character
        // (U+FFFD) for one it can't map at all.
        if (nonEmpty.any { it.contains('�') }) return false

        // 2. A subtler version: the mapping resolves each glyph to *some* character, but doesn't
        // correctly reassemble Malayalam's multi-part letter clusters (a consonant, a vowel sign,
        // and often a virama, rendered as one visual unit but requiring proper reordering to read
        // back as text) — the result "looks like" valid Unicode text, but comes out as isolated
        // single letters instead of real words. A real Malayalam (or other non-Latin-script) word
        // runs several characters long, so an implausibly short average "word" in mostly-non-Latin
        // text is this failure, not genuinely short words.
        val nonLatin = nonEmpty.filter { p -> p.count { it.code > 127 } > p.length / 3 }
        if (nonLatin.isNotEmpty()) {
            val wordLengths = nonLatin.flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() }.map { it.length }
            if (wordLengths.isNotEmpty() && wordLengths.average() < 3.0) return false
        }

        return true
    }

    /**
     * PDFTextStripper writes plain text to its own internal Writer by default; these hooks are
     * overridden instead to capture the same words/spacing into paragraph-sized buffers directly,
     * using the library's own paragraph-boundary detection (based on line-spacing gaps) rather
     * than re-deriving it from the plain-text output.
     */
    private class ParagraphCapturingStripper : PDFTextStripper() {
        val paragraphs = mutableListOf<String>()
        private val current = StringBuilder()

        override fun writeString(text: String) {
            current.append(text)
        }

        override fun writeWordSeparator() {
            current.append(wordSeparator)
        }

        override fun writeLineSeparator() {
            current.append(lineSeparator)
        }

        override fun writeParagraphEnd() {
            super.writeParagraphEnd()
            flushCurrent()
        }

        fun flushCurrent() {
            val paragraph = current.toString().trim().replace(Regex("\\s+"), " ")
            if (paragraph.isNotEmpty()) paragraphs.add(paragraph)
            current.setLength(0)
        }
    }
}
