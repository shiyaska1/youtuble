package com.mobicareapp.ui.scan

import android.app.Activity
import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.activity.result.ActivityResult
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.mobicareapp.YtSaverApp
import com.mobicareapp.data.ScannedDocument
import com.mobicareapp.scan.ScanFileStore
import com.mobicareapp.scan.ScanPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** A captured page awaiting review; [rotationDegrees] is extra rotation the user applied on top of whatever the scanner captured. */
data class ReviewPage(val uri: Uri, val rotationDegrees: Int = 0)

/**
 * Wraps Google Play Services' own document-scanning UI (edge detection, crop, multi-page
 * capture) so the app doesn't need a hand-rolled camera screen. Captured pages go through an
 * in-app review step (see [reviewPages]) before saving, since the scanner doesn't always get
 * page orientation right on its own — the user can rotate any page there.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val database = (application as YtSaverApp).database

    val documents: StateFlow<List<ScannedDocument>> = database.scannedDocumentDao()
        .observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _reviewPages = MutableStateFlow<List<ReviewPage>?>(null)
    val reviewPages: StateFlow<List<ReviewPage>?> = _reviewPages.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val scanner = GmsDocumentScanning.getClient(
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    )

    suspend fun startScanIntentSender(activity: Activity): IntentSender =
        scanner.getStartScanIntent(activity).await()

    fun onScanResult(result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data) ?: return
        val pages = scanResult.pages?.map { ReviewPage(it.imageUri) } ?: return
        if (pages.isEmpty()) return
        _reviewPages.value = pages
    }

    fun rotatePage(index: Int) {
        _reviewPages.update { pages ->
            pages?.toMutableList()?.also { list ->
                val page = list[index]
                list[index] = page.copy(rotationDegrees = (page.rotationDegrees + 90) % 360)
            }
        }
    }

    fun cancelReview() {
        _reviewPages.value = null
    }

    fun confirmReview() {
        val pages = _reviewPages.value?.takeIf { it.isNotEmpty() } ?: return
        _saving.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<YtSaverApp>()
            val baseName = ScanFileStore.newBaseName()
            val scanPages = pages.map { ScanPage(it.uri, it.rotationDegrees) }
            val saved = ScanFileStore.save(context, scanPages, baseName)

            database.scannedDocumentDao().insert(
                ScannedDocument(
                    name = baseName,
                    pageCount = scanPages.size,
                    filePath = saved.pdfFile.absolutePath,
                    thumbnailPath = saved.thumbnailFile.absolutePath,
                    createdAt = System.currentTimeMillis(),
                    pagePaths = saved.pageFiles.map { it.absolutePath }
                )
            )
            _reviewPages.value = null
            _saving.value = false
        }
    }

    fun delete(document: ScannedDocument) {
        viewModelScope.launch(Dispatchers.IO) {
            ScanFileStore.delete(document.filePath, document.thumbnailPath, document.pagePaths)
            database.scannedDocumentDao().delete(document)
        }
    }
}
