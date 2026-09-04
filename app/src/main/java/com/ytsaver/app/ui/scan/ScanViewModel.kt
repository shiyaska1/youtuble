package com.ytsaver.app.ui.scan

import android.app.Activity
import android.app.Application
import android.content.IntentSender
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.ytsaver.app.YtSaverApp
import com.ytsaver.app.data.ScannedDocument
import com.ytsaver.app.scan.ScanFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Wraps Google Play Services' own document-scanning UI (edge detection, crop, multi-page
 * capture) so the app doesn't need a hand-rolled camera screen. The scanner hands back
 * transient Uris into its own cache, which ScanFileStore copies into our storage.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val database = (application as YtSaverApp).database

    val documents: StateFlow<List<ScannedDocument>> = database.scannedDocumentDao()
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val scanner = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    )

    suspend fun startScanIntentSender(activity: Activity): IntentSender =
        scanner.getStartScanIntent(activity).await()

    fun onScanResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: return
        val pdf = scanResult.pdf ?: return
        val firstPageUri = scanResult.pages?.firstOrNull()?.imageUri

        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<YtSaverApp>()
            val baseName = ScanFileStore.newBaseName()
            val pdfFile = ScanFileStore.copyPdf(context, pdf.uri, baseName)
            val thumbFile = firstPageUri?.let { ScanFileStore.copyThumbnail(context, it, baseName) }

            database.scannedDocumentDao().insert(
                ScannedDocument(
                    name = baseName,
                    pageCount = pdf.pageCount,
                    filePath = pdfFile.absolutePath,
                    thumbnailPath = thumbFile?.absolutePath,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun delete(document: ScannedDocument) {
        viewModelScope.launch(Dispatchers.IO) {
            ScanFileStore.delete(document.filePath, document.thumbnailPath)
            database.scannedDocumentDao().delete(document)
        }
    }
}
