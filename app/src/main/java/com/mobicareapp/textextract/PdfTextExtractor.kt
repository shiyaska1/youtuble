package com.mobicareapp.textextract

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File

enum class DetectedLanguage(val label: String) { ENGLISH("English"), HINDI("Hindi"), MALAYALAM("Malayalam") }

/** Every recognizer's result is kept (not just the winner) so the UI can offer "actually, show me the other one" without re-running OCR — useful since the length-based detection heuristic is a guess, not a guarantee. */
data class PageExtraction(
    val detected: DetectedLanguage,
    val englishParagraphs: List<String>,
    val hindiParagraphs: List<String>,
    val malayalamParagraphs: List<String>
) {
    fun paragraphsFor(language: DetectedLanguage): List<String> = when (language) {
        DetectedLanguage.HINDI -> hindiParagraphs
        DetectedLanguage.MALAYALAM -> malayalamParagraphs
        DetectedLanguage.ENGLISH -> englishParagraphs
    }
}

/**
 * Renders one page of a PDF to a bitmap ([PdfRenderer], built into Android — no extra dependency)
 * and runs on-device text recognition on it: ML Kit covers English (Latin script) and Hindi
 * (Devanagari script), and Tesseract (a separate, open-source OCR engine — see [TesseractOcr])
 * covers Malayalam, which ML Kit has no recognizer for at all. Arabic script still isn't supported
 * by anything on-device — only Google's paid cloud OCR API covers that.
 *
 * Language isn't detected up front — every recognizer runs on the page, and whichever returns the
 * most recognized text wins, on the assumption that a script-mismatched recognizer mostly returns
 * nothing or noise rather than a full page of text.
 */
object PdfTextExtractor {

    /** Caller must call [PdfRenderer.close] on the result when done with the document. */
    fun openPdf(context: Context, uri: Uri): PdfRenderer {
        val pfd: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("Couldn't open the selected PDF")
        return PdfRenderer(pfd)
    }

    /** Renders [pageIndex] (0-based) at roughly [targetWidthPx] wide — OCR accuracy benefits from a reasonably high resolution, well above the PDF's own default screen-preview size. */
    fun renderPage(renderer: PdfRenderer, pageIndex: Int, targetWidthPx: Int = 1600): Bitmap {
        renderer.openPage(pageIndex).use { page ->
            val scale = targetWidthPx.toFloat() / page.width
            val width = targetWidthPx
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            // PDF pages are transparent where nothing is drawn; rendering onto a white bitmap
            // first avoids OCR seeing black text on a black/transparent background.
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    /** Decodes a plain image (JPG/PNG/etc, picked instead of a PDF) at a resolution capped for OCR + memory safety, the same reasoning as the passport photo and collage tools' downsampling. */
    fun loadImage(context: Context, uri: Uri, maxDimension: Int = 1600): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= maxDimension) sampleSize *= 2

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: error("Couldn't read the selected image")
    }

    /** Crops [source] to the rectangle described by fractions (0..1) of its own dimensions. */
    fun crop(source: Bitmap, left: Float, top: Float, right: Float, bottom: Float): Bitmap {
        val x = (left * source.width).toInt().coerceIn(0, source.width - 1)
        val y = (top * source.height).toInt().coerceIn(0, source.height - 1)
        val width = ((right - left) * source.width).toInt().coerceIn(1, source.width - x)
        val height = ((bottom - top) * source.height).toInt().coerceIn(1, source.height - y)
        return Bitmap.createBitmap(source, x, y, width, height)
    }

    /** A private, app-owned target for the camera intent to write a captured photo into. */
    fun newCaptureTarget(context: Context): Pair<File, Uri> {
        val dir = File(context.getExternalFilesDir(null), "TextExtract/captures").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    suspend fun extractText(context: Context, bitmap: Bitmap): PageExtraction {
        val input = InputImage.fromBitmap(bitmap, 0)

        val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val devanagariRecognizer = TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())

        val latinResult = runCatching { latinRecognizer.process(input).await() }.getOrNull()
        val devanagariResult = runCatching { devanagariRecognizer.process(input).await() }.getOrNull()
        latinRecognizer.close()
        devanagariRecognizer.close()

        // Tesseract has no notion of ML Kit's per-block layout detection — its plain text output
        // is split on blank lines instead, which is the closest equivalent to "paragraphs" it can
        // offer.
        val malayalamText = runCatching { TesseractOcr.recognize(context, bitmap) }.getOrDefault("")
        val malayalamParagraphs = malayalamText
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val latinText = latinResult?.text.orEmpty()
        val devanagariText = devanagariResult?.text.orEmpty()
        val detected = when {
            malayalamText.length > latinText.length && malayalamText.length > devanagariText.length -> DetectedLanguage.MALAYALAM
            devanagariText.length > latinText.length -> DetectedLanguage.HINDI
            else -> DetectedLanguage.ENGLISH
        }

        return PageExtraction(
            detected = detected,
            englishParagraphs = latinResult?.textBlocks?.map { it.text } ?: emptyList(),
            hindiParagraphs = devanagariResult?.textBlocks?.map { it.text } ?: emptyList(),
            malayalamParagraphs = malayalamParagraphs
        )
    }
}
