package com.mobicareapp.textextract

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Malayalam-only OCR via the open-source Tesseract engine — ML Kit has no on-device recognizer
 * for Malayalam script at all. Tesseract needs its trained data as a real file under a directory
 * it controls (not read straight out of the APK), so the bundled asset
 * (app/src/main/assets/tessdata/mal.traineddata) is copied into the app's private storage once,
 * the first time Malayalam OCR is used.
 */
internal object TesseractOcr {
    private const val LANGUAGE = "mal"

    private fun dataDir(context: Context): File = File(context.filesDir, "tesseract")

    /** Returns the directory Tesseract expects (which itself must contain a "tessdata" subfolder), copying the bundled trained-data asset into it on first call. */
    private fun ensureTrainedData(context: Context): String {
        val dir = dataDir(context)
        val tessdataDir = File(dir, "tessdata").apply { mkdirs() }
        val target = File(tessdataDir, "$LANGUAGE.traineddata")
        if (!target.exists()) {
            context.assets.open("tessdata/$LANGUAGE.traineddata").use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
        }
        return dir.absolutePath
    }

    suspend fun recognize(context: Context, bitmap: Bitmap): String = withContext(Dispatchers.IO) {
        val dataPath = ensureTrainedData(context)
        val tess = TessBaseAPI()
        try {
            if (!tess.init(dataPath, LANGUAGE)) return@withContext ""
            tess.setImage(bitmap)
            tess.getUTF8Text() ?: ""
        } catch (e: Exception) {
            ""
        } finally {
            tess.recycle()
        }
    }
}
