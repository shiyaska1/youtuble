package com.mobicareapp.scrollshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import com.mobicareapp.textextract.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Reads the text of the long pictures (the same reader as "Extract text from PDF": English, Hindi and
 * Malayalam). A very tall picture is read in pieces, and each piece is cut in a gap between lines
 * (a row with almost nothing on it) so a line of text is never cut in half.
 */
object ScrollOcr {
    private const val PIECE_HEIGHT = 2000
    private const val SEARCH_BACK = 500

    suspend fun read(context: Context, files: List<File>, onStatus: (String) -> Unit): String = withContext(Dispatchers.IO) {
        // First plan every piece of every picture, so the progress can say "piece 3 of 12".
        class Piece(val decoder: BitmapRegionDecoder, val from: Int, val to: Int)
        val decoders = files.map { BitmapRegionDecoder.newInstance(it.absolutePath, false) }
        try {
            val pieces = mutableListOf<Piece>()
            for (decoder in decoders) {
                var start = 0
                while (start < decoder.height) {
                    val target = start + PIECE_HEIGHT
                    val end = if (target >= decoder.height - 300) decoder.height else quietRowNear(decoder, target)
                    pieces += Piece(decoder, start, end)
                    start = end
                }
            }
            val text = StringBuilder()
            pieces.forEachIndexed { index, piece ->
                onStatus("Reading the text… part ${index + 1} of ${pieces.size}")
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bitmap = piece.decoder.decodeRegion(Rect(0, piece.from, piece.decoder.width, piece.to), options) ?: return@forEachIndexed
                val extraction = runCatching { PdfTextExtractor.extractText(context, bitmap) }.getOrNull()
                bitmap.recycle()
                val paragraphs = extraction?.paragraphsFor(extraction.detected).orEmpty().joinToString("\n").trim()
                if (paragraphs.isNotEmpty()) {
                    if (text.isNotEmpty()) text.append("\n\n")
                    text.append(paragraphs)
                }
            }
            text.toString()
        } finally {
            decoders.forEach { runCatching { it.recycle() } }
        }
    }

    /** The row, searching upwards from [target], with the least on it (a gap between lines of text). */
    private fun quietRowNear(decoder: BitmapRegionDecoder, target: Int): Int {
        val from = (target - SEARCH_BACK).coerceAtLeast(0)
        val to = target.coerceAtMost(decoder.height)
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        val strip = decoder.decodeRegion(Rect(0, from, decoder.width, to), options) ?: return target
        val width = strip.width
        val pixels = IntArray(width)
        val ink = FloatArray(strip.height)
        for (y in 0 until strip.height) {
            strip.getPixels(pixels, 0, width, 0, y, width, 1)
            var sum = 0
            var previous = luminance(pixels[0])
            for (x in 1 until width) {
                val now = luminance(pixels[x])
                sum += abs(now - previous)
                previous = now
            }
            ink[y] = sum.toFloat()
        }
        strip.recycle()
        // A little smoothing, so a single clean row inside a letter is not taken for a gap.
        var bestRow = ink.size - 1
        var bestValue = Float.MAX_VALUE
        for (y in 1 until ink.size - 1) {
            val value = ink[y - 1] + ink[y] + ink[y + 1]
            if (value <= bestValue) {
                bestValue = value
                bestRow = y
            }
        }
        return from + bestRow * 4
    }

    private fun luminance(p: Int): Int = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
}
