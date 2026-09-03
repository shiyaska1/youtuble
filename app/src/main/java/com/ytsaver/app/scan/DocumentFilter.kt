package com.ytsaver.app.scan

import android.graphics.Bitmap

/**
 * Turns a photo into a clean black-text-on-pure-white page, like Google
 * Drive's document scan mode - pure black/white instead of grayscale, so
 * printing doesn't waste ink on halftone-dithered gray fills for the
 * background.
 */
object DocumentFilter {

    fun apply(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var sum = 0.0
        val luminance = DoubleArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val lum = 0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)
            luminance[i] = lum
            sum += lum
        }
        // Threshold relative to the page's own average brightness, so it copes
        // reasonably with different lighting instead of a single fixed cutoff.
        val threshold = (sum / pixels.size) * 0.82

        for (i in pixels.indices) {
            pixels[i] = if (luminance[i] > threshold) BLACK_ON_WHITE_WHITE else BLACK_ON_WHITE_BLACK
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    private const val BLACK_ON_WHITE_WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK_ON_WHITE_BLACK = 0xFF000000.toInt()
}
