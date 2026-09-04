package com.ytsaver.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One captured page: the scanner's transient image Uri, plus any extra rotation the user applied during review. */
data class ScanPage(val uri: Uri, val rotationDegrees: Int = 0)

/**
 * Builds the final PDF/thumbnail from the scanner's page images ourselves (instead of using
 * Play Services' own PDF export) so every page's EXIF rotation is actually applied to the
 * pixels — the scanner sometimes tags a page's orientation in EXIF without baking it in,
 * which otherwise shows up as random landscape/upside-down pages — and so the user's manual
 * per-page rotation from the review screen is reflected in the saved file.
 */
object ScanFileStore {

    fun newBaseName(): String =
        "Scan-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    fun writePdf(context: Context, pages: List<ScanPage>, baseName: String): File {
        val file = uniqueFile(scansDir(context), baseName, "pdf")
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val bitmap = loadUprightBitmap(context, page)
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                    val pdfPage = document.startPage(pageInfo)
                    pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                    document.finishPage(pdfPage)
                } finally {
                    bitmap.recycle()
                }
            }
            FileOutputStream(file).use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return file
    }

    fun writeThumbnail(context: Context, page: ScanPage, baseName: String): File {
        val file = uniqueFile(thumbsDir(context), baseName, "jpg")
        val bitmap = loadUprightBitmap(context, page)
        try {
            val maxDim = 480
            val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bitmap
            try {
                FileOutputStream(file).use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    /** Decodes [page]'s image and applies its EXIF orientation plus any manual review rotation, so the result is always upright pixels. */
    private fun loadUprightBitmap(context: Context, page: ScanPage): Bitmap {
        val exifDegrees = context.contentResolver.openInputStream(page.uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0

        val bitmap = context.contentResolver.openInputStream(page.uri)?.use { BitmapFactory.decodeStream(it) }
            ?: error("Couldn't read scanned page")

        val totalDegrees = ((exifDegrees + page.rotationDegrees) % 360 + 360) % 360
        if (totalDegrees == 0) return bitmap

        val matrix = Matrix().apply { postRotate(totalDegrees.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun delete(filePath: String, thumbnailPath: String?) {
        File(filePath).delete()
        thumbnailPath?.let { File(it).delete() }
    }

    private fun scansDir(context: Context): File =
        File(context.getExternalFilesDir(null), "Scans").apply { mkdirs() }

    private fun thumbsDir(context: Context): File =
        File(scansDir(context), "thumbs").apply { mkdirs() }

    private fun uniqueFile(dir: File, baseName: String, extension: String): File {
        var candidate = File(dir, "$baseName.$extension")
        var counter = 1
        while (candidate.exists()) {
            candidate = File(dir, "$baseName ($counter).$extension")
            counter++
        }
        return candidate
    }
}
