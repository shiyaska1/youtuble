package com.mobicareapp.collage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

enum class CollageLayout(val label: String) {
    HORIZONTAL("Row"),
    VERTICAL("Column"),
    GRID("Grid")
}

/**
 * Combines several photos into one JPG — a single row, a single column, or an auto-sized grid.
 * Each source photo is downsampled before decoding (same reasoning as
 * [com.mobicareapp.passport.PassportPhotoFileStore]: a handful of full camera-resolution photos
 * decoded and held in memory at once is a fast way to crash on real hardware, for no visible
 * benefit once they're laid out at collage size).
 */
object CollageFileStore {

    private const val MAX_SOURCE_DIMENSION = 1200
    private const val GRID_CELL_SIZE = 720
    private const val STRIP_TARGET_SIZE = 900
    private const val GAP_PX = 6
    private val GAP_COLOR = Color.WHITE

    fun newBaseName(): String =
        "Collage-" + SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())

    /** Copies each picked/captured image into this collage's own source folder, so later re-layout doesn't depend on a gallery Uri or camera temp file that might not still be readable. */
    fun copySources(context: Context, baseName: String, uris: List<Uri>): List<File> {
        val dir = sourcesDir(context, baseName)
        return uris.mapIndexed { index, uri ->
            val file = File(dir, "img$index.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            } ?: error("Couldn't read one of the selected photos")
            file
        }
    }

    fun newCaptureTarget(context: Context, baseName: String, index: Int): Pair<File, Uri> {
        val file = File(sourcesDir(context, baseName), "img$index.jpg")
        return file to shareUri(context, file)
    }

    /** Lays out [sourcePaths] per [layout] and saves the result as `<baseName>.jpg`, overwriting whatever was there — same in-place-edit approach as the passport photo tool. */
    suspend fun createCollage(context: Context, baseName: String, sourcePaths: List<String>, layout: CollageLayout): File {
        require(sourcePaths.size >= 2) { "Add at least 2 photos." }
        val bitmaps = sourcePaths.map { loadUprightBitmap(context, Uri.fromFile(File(it))) }
        val combined = try {
            when (layout) {
                CollageLayout.HORIZONTAL -> combineStrip(bitmaps, horizontal = true)
                CollageLayout.VERTICAL -> combineStrip(bitmaps, horizontal = false)
                CollageLayout.GRID -> combineGrid(bitmaps)
            }
        } finally {
            bitmaps.forEach { it.recycle() }
        }

        val file = File(collagesDir(context), "$baseName.jpg")
        try {
            FileOutputStream(file).use { combined.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        } finally {
            combined.recycle()
        }
        return file
    }

    /** One row (or one column): every photo scaled to a common height (or width), placed edge to edge. */
    private fun combineStrip(bitmaps: List<Bitmap>, horizontal: Boolean): Bitmap {
        val scaled = bitmaps.map { bmp ->
            val scale = STRIP_TARGET_SIZE.toFloat() / if (horizontal) bmp.height else bmp.width
            val w = (bmp.width * scale).toInt().coerceAtLeast(1)
            val h = (bmp.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bmp, w, h, true)
        }
        try {
            val totalMain = scaled.sumOf { if (horizontal) it.width else it.height } + GAP_PX * (scaled.size - 1)
            val cross = if (horizontal) STRIP_TARGET_SIZE else STRIP_TARGET_SIZE
            val outWidth = if (horizontal) totalMain else cross
            val outHeight = if (horizontal) cross else totalMain
            val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            canvas.drawColor(GAP_COLOR)
            var offset = 0
            scaled.forEach { bmp ->
                if (horizontal) {
                    canvas.drawBitmap(bmp, offset.toFloat(), 0f, null)
                    offset += bmp.width + GAP_PX
                } else {
                    canvas.drawBitmap(bmp, 0f, offset.toFloat(), null)
                    offset += bmp.height + GAP_PX
                }
            }
            return result
        } finally {
            scaled.forEach { it.recycle() }
        }
    }

    /** An auto-sized grid (columns = ceil(sqrt(n))) with every photo center-cropped to fill its square cell. */
    private fun combineGrid(bitmaps: List<Bitmap>): Bitmap {
        val cols = ceil(sqrt(bitmaps.size.toDouble())).toInt().coerceAtLeast(1)
        val rows = ceil(bitmaps.size.toDouble() / cols).toInt().coerceAtLeast(1)
        val outWidth = cols * GRID_CELL_SIZE + (cols - 1) * GAP_PX
        val outHeight = rows * GRID_CELL_SIZE + (rows - 1) * GAP_PX
        val result = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(GAP_COLOR)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        bitmaps.forEachIndexed { index, bmp ->
            val cropped = centerCropToSquare(bmp)
            val scaled = Bitmap.createScaledBitmap(cropped, GRID_CELL_SIZE, GRID_CELL_SIZE, true)
            val col = index % cols
            val row = index / cols
            val left = col * (GRID_CELL_SIZE + GAP_PX)
            val top = row * (GRID_CELL_SIZE + GAP_PX)
            canvas.drawBitmap(scaled, left.toFloat(), top.toFloat(), paint)
            if (scaled !== cropped) scaled.recycle()
            if (cropped !== bmp) cropped.recycle()
        }
        return result
    }

    private fun centerCropToSquare(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val x = (source.width - size) / 2
        val y = (source.height - size) / 2
        return Bitmap.createBitmap(source, x, y, size, size)
    }

    private fun loadUprightBitmap(context: Context, uri: Uri): Bitmap {
        val exifDegrees = context.contentResolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, MAX_SOURCE_DIMENSION)

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
            ?: error("Couldn't read one of the photos")
        if (exifDegrees == 0) return bitmap

        val matrix = Matrix().apply { postRotate(exifDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sampleSize = 1
        while (maxOf(width, height) / (sampleSize * 2) >= maxDimension) sampleSize *= 2
        return sampleSize
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun deleteSources(context: Context, baseName: String) {
        sourcesDir(context, baseName).deleteRecursively()
    }

    private fun rootDir(context: Context): File =
        File(context.getExternalFilesDir(null), "Collages").apply { mkdirs() }

    private fun collagesDir(context: Context): File = File(rootDir(context), "photos").apply { mkdirs() }
    private fun sourcesDir(context: Context, baseName: String): File = File(rootDir(context), "sources/$baseName").apply { mkdirs() }
}
