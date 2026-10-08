package com.mobicareapp.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

// A4 portrait in PDF points (72pt/inch) — same point-based sizing convention as
// PassportPhotoFileStore's print sheet, so the output prints at true A4 size, not scaled to fit.
private const val A4_WIDTH_PT = 595
private const val A4_HEIGHT_PT = 842
private const val MARGIN_PT = 24f
private const val MM_TO_PT = 2.8346f

/**
 * Combines a subset of a scan's already-saved page JPGs (e.g. an ID card's front and back) onto
 * one A4-portrait PDF page, stacked vertically and centered as a block, instead of each page
 * printing full-screen on its own sheet. [sizeFraction] is each image's width as a fraction of
 * the page's content width; [gapMm] is the vertical gap between them. If the requested size and
 * gap don't fit the page, the whole stack is shrunk uniformly to fit rather than spilling onto a
 * second page.
 */
object ScanCombineStore {

    fun buildCombinedPage(
        context: Context,
        imageFiles: List<File>,
        sizeFraction: Float,
        gapMm: Float,
        baseName: String
    ): SavedScan {
        require(imageFiles.size >= 2) { "Need at least 2 pages to combine" }
        val contentWidth = A4_WIDTH_PT - MARGIN_PT * 2
        val contentHeight = A4_HEIGHT_PT - MARGIN_PT * 2
        val gapPt = gapMm * MM_TO_PT

        val bitmaps = imageFiles.map { BitmapFactory.decodeFile(it.absolutePath) ?: error("Couldn't read page") }
        try {
            val naturalWidth = contentWidth * sizeFraction
            val naturalHeights = bitmaps.map { naturalWidth * it.height / it.width }
            val naturalTotalHeight = naturalHeights.sum() + gapPt * (bitmaps.size - 1)

            val fitScale = if (naturalTotalHeight > contentHeight) contentHeight / naturalTotalHeight else 1f
            val width = naturalWidth * fitScale
            val heights = naturalHeights.map { it * fitScale }
            val scaledGapPt = gapPt * fitScale
            val totalHeight = heights.sum() + scaledGapPt * (bitmaps.size - 1)

            val startX = MARGIN_PT + (contentWidth - width) / 2f
            val startY = MARGIN_PT + (contentHeight - totalHeight) / 2f

            val pdfFile = ScanFileStore.uniqueFile(ScanFileStore.scansDir(context), baseName, "pdf")
            val document = PdfDocument()
            try {
                val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, 1).create()
                val page = document.startPage(pageInfo)
                drawStack(page.canvas, bitmaps, heights, width, startX, startY, scaledGapPt)
                document.finishPage(page)
                FileOutputStream(pdfFile).use { document.writeTo(it) }
            } finally {
                document.close()
            }

            // A flattened JPG of the same page, rendered sharper than the PDF's 72pt/inch — used
            // as the list thumbnail and as a plain image for sharing, same as every other page.
            val renderScale = 2.5f
            val renderBitmap = Bitmap.createBitmap(
                (A4_WIDTH_PT * renderScale).toInt(),
                (A4_HEIGHT_PT * renderScale).toInt(),
                Bitmap.Config.ARGB_8888
            )
            val renderCanvas = Canvas(renderBitmap)
            renderCanvas.drawColor(Color.WHITE)
            drawStack(
                renderCanvas, bitmaps, heights.map { it * renderScale }, width * renderScale,
                startX * renderScale, startY * renderScale, scaledGapPt * renderScale
            )

            val pageFile = ScanFileStore.uniqueFile(ScanFileStore.pagesDir(context, baseName), "page-1", "jpg")
            FileOutputStream(pageFile).use { renderBitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            val thumbnailFile = ScanFileStore.writeScaledJpeg(
                renderBitmap,
                ScanFileStore.uniqueFile(ScanFileStore.thumbsDir(context), baseName, "jpg")
            )
            renderBitmap.recycle()

            return SavedScan(pdfFile, thumbnailFile, listOf(pageFile))
        } finally {
            bitmaps.forEach { it.recycle() }
        }
    }

    private fun drawStack(
        canvas: Canvas,
        bitmaps: List<Bitmap>,
        heights: List<Float>,
        width: Float,
        x: Float,
        startY: Float,
        gap: Float
    ) {
        var y = startY
        bitmaps.forEachIndexed { index, bitmap ->
            val h = heights[index]
            canvas.drawBitmap(bitmap, null, RectF(x, y, x + width, y + h), null)
            y += h + gap
        }
    }
}
