package com.ytsaver.app.ui.scan

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import com.ytsaver.app.YtSaverApp
import com.ytsaver.app.data.MediaAccess
import com.ytsaver.app.data.MediaCategory
import com.ytsaver.app.data.MediaType
import com.ytsaver.app.data.SavedMedia
import com.ytsaver.app.scan.PageLocation
import com.ytsaver.app.scan.ScanFileStore
import kotlinx.coroutines.flow.first

private const val CAMERA_CATEGORY_NAME = "CAMERA"

class PhotoCaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = (application as YtSaverApp).database.savedMediaDao()
    private val categoryDao = (application as YtSaverApp).database.mediaCategoryDao()
    private var cameraCategoryId: Long? = null

    /** Saves one captured page both to public storage and to the Library (under the
     *  "CAMERA" category), so scanned pages show up alongside downloaded media. */
    suspend fun savePage(bitmap: Bitmap, sessionFolder: String, pageNumber: Int): PageLocation {
        val location = ScanFileStore.savePage(getApplication(), bitmap, sessionFolder, pageNumber)
        val filePath = when (location) {
            is PageLocation.MediaStoreUri -> location.uri.toString()
            is PageLocation.LegacyFile -> location.file.absolutePath
        }
        val categoryId = cameraCategoryId ?: getOrCreateCameraCategory().also { cameraCategoryId = it }
        dao.insert(
            SavedMedia(
                caption = "$sessionFolder - Page %03d".format(pageNumber),
                sourceUrl = "",
                type = MediaType.IMAGE,
                filePath = filePath,
                fileName = "Page %03d.jpg".format(pageNumber),
                thumbnailUrl = filePath,
                sizeBytes = MediaAccess.length(getApplication(), filePath),
                durationSeconds = 0,
                createdAt = System.currentTimeMillis(),
                categoryId = categoryId
            )
        )
        return location
    }

    private suspend fun getOrCreateCameraCategory(): Long {
        val existing = categoryDao.observeAll().first().find { it.name.equals(CAMERA_CATEGORY_NAME, ignoreCase = true) }
        return existing?.id ?: categoryDao.insert(MediaCategory(name = CAMERA_CATEGORY_NAME))
    }
}
