package com.mobicareapp.questionpaper

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File

/** Lays a [QuestionPaper] out onto A4 pages with Android's built-in [PdfDocument] — no extra dependency, and system fonts so Hindi/Malayalam text renders too. */
object QuestionPaperPdf {
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f

    fun write(context: Context, paper: QuestionPaper, includeAnswers: Boolean): File {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "QuestionPapers").apply { mkdirs() }
        val suffix = if (includeAnswers) "-with-answers" else ""
        val file = File(dir, "question-paper-${paper.number}-${paper.createdAt}$suffix.pdf")

        val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f; color = 0xFF000000.toInt() }
        val bold = TextPaint(body).apply { typeface = Typeface.DEFAULT_BOLD }
        val title = TextPaint(bold).apply { textSize = 18f }
        val answerPaint = TextPaint(body).apply { color = 0xFF1B5E20.toInt() }
        val width = (PAGE_WIDTH - 2 * MARGIN).toInt()

        val document = PdfDocument()
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = MARGIN

        fun newPage() {
            page?.let { document.finishPage(it) }
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create())
            y = MARGIN
        }

        fun drawBlock(text: String, paint: TextPaint, indent: Float = 0f, spaceAfter: Float = 6f) {
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - indent.toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(2f, 1f)
                .build()
            if (y + layout.height > PAGE_HEIGHT - MARGIN) newPage()
            val canvas = page!!.canvas
            canvas.save()
            canvas.translate(MARGIN + indent, y)
            layout.draw(canvas)
            canvas.restore()
            y += layout.height + spaceAfter
        }

        newPage()
        drawBlock("Question Paper ${paper.number}", title, spaceAfter = 4f)
        drawBlock("Name: ____________________    Date: ____________    Marks: ______ / ${paper.questions.size}", body, spaceAfter = 14f)

        paper.questions.forEachIndexed { index, question ->
            drawBlock("${index + 1}. ${question.text}", bold)
            question.options.forEachIndexed { optionIndex, option ->
                drawBlock("${'A' + optionIndex}. $option", body, indent = 18f, spaceAfter = 2f)
            }
            if (includeAnswers) {
                drawBlock("Answer: ${question.answer}", answerPaint, indent = 18f)
            } else if (question.type == QuestionType.SHORT) {
                // Blank ruled space to write the answer into.
                repeat(3) { drawBlock("_".repeat(78), body, indent = 18f, spaceAfter = 4f) }
            }
            y += 8f
        }

        page?.let { document.finishPage(it) }
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return file
    }
}
