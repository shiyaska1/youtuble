package com.mobicareapp.upload

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Stacks multiple picked images into one tall JPEG, each labeled "Screenshot N" so a multi-image
 * upload is easy to read through in order, optionally with a heading + block of text (a transcript,
 * or a written note) underneath everything — used when uploading to the personal server, which
 * only ever keeps a single file, so images (and any text alongside them) need to become one image
 * first.
 */
object ImageCombiner {
    private const val PADDING_PX = 32f
    private const val LABEL_SIZE_PX = 34f
    private const val HEADING_SIZE_PX = 40f
    private const val TEXT_SIZE_PX = 36f
    private const val DEFAULT_WIDTH_PX = 1080
    private const val NOTE_HEADING = "Instructions for Claude — please read:"

    suspend fun combineVertically(context: Context, uris: List<Uri>): Result<File> =
        combineVerticallyWithText(context, uris, null)

    /** [text], if non-blank, is drawn under everything as its own heading + wrapped block, same width as the combined image. Two or more images each get a "Screenshot N" label directly above them. */
    suspend fun combineVerticallyWithText(context: Context, uris: List<Uri>, text: String?): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmaps = uris.mapNotNull { uri ->
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            if (bitmaps.isEmpty() && text.isNullOrBlank()) error("Nothing to combine")

            // Scale every image to the widest one's width (keeping its own aspect ratio) — or, if
            // there are no images at all (just text), fall back to a plain readable page width.
            val targetWidth = if (bitmaps.isNotEmpty()) bitmaps.maxOf { it.width } else DEFAULT_WIDTH_PX
            val scaled = bitmaps.map { bitmap ->
                if (bitmap.width == targetWidth) bitmap
                else {
                    val scaledHeight = (bitmap.height.toFloat() * targetWidth / bitmap.width).toInt()
                    Bitmap.createScaledBitmap(bitmap, targetWidth, scaledHeight, true)
                }
            }
            val innerWidth = (targetWidth - PADDING_PX * 2).toInt().coerceAtLeast(1)

            // Only label images when there's more than one — a single picture doesn't need "Screenshot 1".
            val labelLayouts: List<StaticLayout>? = if (scaled.size > 1) {
                val labelPaint = TextPaint().apply { isAntiAlias = true; color = Color.DKGRAY; textSize = LABEL_SIZE_PX; isFakeBoldText = true }
                scaled.indices.map { i -> buildLayout("Screenshot ${i + 1}", labelPaint, innerWidth) }
            } else null
            val labelBlockHeight = { l: StaticLayout -> l.height + (PADDING_PX * 1.5f).toInt() }

            val headingLayout: StaticLayout? = if (!text.isNullOrBlank()) {
                val headingPaint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = HEADING_SIZE_PX; isFakeBoldText = true }
                buildLayout(NOTE_HEADING, headingPaint, innerWidth)
            } else null
            val textLayout: StaticLayout? = if (!text.isNullOrBlank()) {
                val textPaint = TextPaint().apply { isAntiAlias = true; color = Color.BLACK; textSize = TEXT_SIZE_PX }
                buildLayout(text, textPaint, innerWidth)
            } else null

            val imagesAndLabelsHeight = scaled.indices.sumOf { i ->
                val labelHeight = labelLayouts?.get(i)?.let(labelBlockHeight) ?: 0
                labelHeight + scaled[i].height
            }
            val notesHeight = (headingLayout?.let { it.height + (PADDING_PX * 1.5f).toInt() } ?: 0) +
                (textLayout?.let { it.height + (PADDING_PX * 2).toInt() } ?: 0) +
                if (headingLayout != null || textLayout != null) PADDING_PX.toInt() else 0

            val totalHeight = (imagesAndLabelsHeight + notesHeight).coerceAtLeast(1)
            val combined = Bitmap.createBitmap(targetWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combined)
            canvas.drawColor(Color.WHITE)

            var y = 0f
            scaled.forEachIndexed { i, bitmap ->
                labelLayouts?.get(i)?.let { label ->
                    canvas.save()
                    canvas.translate(PADDING_PX, y + PADDING_PX / 2)
                    label.draw(canvas)
                    canvas.restore()
                    y += labelBlockHeight(label)
                }
                canvas.drawBitmap(bitmap, 0f, y, null)
                y += bitmap.height
            }

            if (headingLayout != null || textLayout != null) {
                y += PADDING_PX
                headingLayout?.let { heading ->
                    canvas.save()
                    canvas.translate(PADDING_PX, y)
                    heading.draw(canvas)
                    canvas.restore()
                    y += heading.height + (PADDING_PX * 1.5f).toInt()
                }
                textLayout?.let { body ->
                    canvas.save()
                    canvas.translate(PADDING_PX, y + PADDING_PX / 2)
                    body.draw(canvas)
                    canvas.restore()
                }
            }

            val outFile = File.createTempFile("combined", ".jpg", context.cacheDir)
            FileOutputStream(outFile).use { out -> combined.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            outFile
        }
    }

    private fun buildLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.2f)
            .build()
}
