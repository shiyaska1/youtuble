package com.ytsaver.app.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** Merges a set of scanned pages into a single PDF, one page per image, saved to Downloads. */
object ScanPdfBuilder {

    suspend fun buildPdf(context: Context, pages: List<PageLocation>, fileName: String): Result<PageLocation> =
        withContext(Dispatchers.IO) {
            runCatching {
                val document = PdfDocument()
                try {
                    for (page in pages) {
                        val bitmap = openBitmap(context, page) ?: continue
                        val pdfPage = document.startPage(
                            PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, document.pages.size + 1).create()
                        )
                        pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                        document.finishPage(pdfPage)
                        bitmap.recycle()
                    }
                    if (document.pages.isEmpty()) error("Couldn't build a PDF - no readable pages")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        savePdfViaMediaStore(context, document, fileName)
                    } else {
                        savePdfToPublicDir(document, fileName)
                    }
                } finally {
                    document.close()
                }
            }
        }

    private fun openBitmap(context: Context, page: PageLocation) = when (page) {
        is PageLocation.MediaStoreUri ->
            context.contentResolver.openInputStream(page.uri)?.use { BitmapFactory.decodeStream(it) }
        is PageLocation.LegacyFile ->
            BitmapFactory.decodeFile(page.file.absolutePath)
    }

    private fun savePdfViaMediaStore(context: Context, document: PdfDocument, fileName: String): PageLocation {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/YTSaver")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values) ?: error("Couldn't save $fileName")
        context.contentResolver.openOutputStream(uri)?.use { out -> writePdf(document, out) }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        context.contentResolver.update(uri, values, null, null)
        return PageLocation.MediaStoreUri(uri)
    }

    private fun savePdfToPublicDir(document: PdfDocument, fileName: String): PageLocation {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "YTSaver")
        dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { out -> writePdf(document, out) }
        return PageLocation.LegacyFile(file)
    }

    private fun writePdf(document: PdfDocument, out: OutputStream) {
        document.writeTo(out)
    }
}
