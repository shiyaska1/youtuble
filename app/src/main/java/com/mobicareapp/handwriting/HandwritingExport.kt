package com.mobicareapp.handwriting

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws notepad pages onto a bitmap, outside the screen, for saving: every page is drawn the same
 * way as on the pad (inserted photo fitted behind, smooth strokes, suggestion-completed words in
 * the handwriting-style font) and the pages are stacked top to bottom into one long image.
 */
object HandwritingExport {
    private const val PAGE_WIDTH_PX = 1000
    /** A very long notepad is drawn smaller rather than running the phone out of memory. */
    private const val MAX_PIXELS = 48_000_000L
    private const val PAPER = 0xFFFFFDF6.toInt()
    private const val INK = 0xFF14213D.toInt()
    private const val INK_WIDTH = 0.0048f

    /** [pageRatio] is a page's height divided by its width, as it was on this phone's pad. */
    fun renderAll(pages: List<HwPage>, pageRatio: Float): Bitmap {
        val ratio = pageRatio.coerceIn(0.5f, 4f)
        var width = PAGE_WIDTH_PX
        var pageHeight = (width * ratio).toInt()
        val total = pages.size.toLong() * width * pageHeight
        if (total > MAX_PIXELS) {
            width = (width * sqrt(MAX_PIXELS.toDouble() / total)).toInt().coerceAtLeast(200)
            pageHeight = (width * ratio).toInt()
        }
        // 16-bit colour halves the memory of a tall stack and is plenty for dark ink on paper.
        val bitmap = Bitmap.createBitmap(width, pageHeight * pages.size, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        canvas.drawColor(PAPER)
        val divider = Paint().apply { color = 0xFFC9C4B5.toInt(); strokeWidth = 2f }
        pages.forEachIndexed { index, page ->
            canvas.save()
            canvas.translate(0f, (index * pageHeight).toFloat())
            canvas.clipRect(0, 0, width, pageHeight)
            drawPage(canvas, page, width, pageHeight)
            canvas.restore()
            if (index > 0) canvas.drawLine(0f, (index * pageHeight).toFloat(), width.toFloat(), (index * pageHeight).toFloat(), divider)
        }
        return bitmap
    }

    private fun drawPage(canvas: Canvas, page: HwPage, width: Int, height: Int) {
        page.imagePath?.let { path -> drawFittedImage(canvas, path, width, height) }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            style = Paint.Style.STROKE
            strokeWidth = width * INK_WIDTH
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = INK; style = Paint.Style.FILL }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK
            typeface = Typeface.create("cursive", Typeface.NORMAL)
        }

        for (item in page.items) {
            when (item) {
                is InkItem -> drawStroke(canvas, item.stroke, width, strokePaint, dotPaint)
                is WordItem -> {
                    textPaint.textSize = item.size * width * 1.15f
                    canvas.save()
                    canvas.translate(item.x * width, item.y * width)
                    canvas.rotate(Math.toDegrees(item.angle.toDouble()).toFloat())
                    canvas.drawText(item.text, 0f, 0f, textPaint)
                    canvas.restore()
                }
            }
        }
    }

    /** Draws the stroke as the same smooth curve the pad shows: touch points are control points, the curve runs through the midpoints. */
    private fun drawStroke(canvas: Canvas, stroke: HwStroke, width: Int, paint: Paint, dotPaint: Paint) {
        val points = stroke.points
        if (points.isEmpty()) return
        if (points.size == 1) {
            canvas.drawCircle(points[0].x * width, points[0].y * width, paint.strokeWidth / 2f, dotPaint)
            return
        }
        val path = Path()
        path.moveTo(points[0].x * width, points[0].y * width)
        for (i in 1 until points.size - 1) {
            val midX = (points[i].x + points[i + 1].x) / 2f * width
            val midY = (points[i].y + points[i + 1].y) / 2f * width
            path.quadTo(points[i].x * width, points[i].y * width, midX, midY)
        }
        path.lineTo(points.last().x * width, points.last().y * width)
        canvas.drawPath(path, paint)
    }

    /** The photo scaled to fit wholly inside the page (nothing cropped) and centred. */
    private fun drawFittedImage(canvas: Canvas, path: String, width: Int, height: Int) {
        val image = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return
        val scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate((width - image.width * scale) / 2f, (height - image.height * scale) / 2f)
        }
        canvas.drawBitmap(image, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        image.recycle()
    }
}
