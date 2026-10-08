package com.mobicareapp.upload

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

/** Uploads a file to the user's own PHP endpoint (see server/upload.php) — a plain multipart POST with a shared secret, matching that script's expectations exactly. */
object PersonalServerUploader {
    private val client = OkHttpClient()

    /** For a picked content:// URI — copies it to a temp file first, since OkHttp's multipart body needs an actual File. [isVideo] picks which fixed slot on the server this overwrites (1.jpg or 1.mp4 — see server/upload.php). */
    suspend fun upload(context: Context, sourceUri: Uri, mimeType: String, isVideo: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val tempFile = File.createTempFile("upload", ".tmp", context.cacheDir)
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: error("Couldn't read the selected file")
            try {
                uploadFileBlocking(context, tempFile, mimeType, isVideo)
            } finally {
                tempFile.delete()
            }
        }
    }

    /** For a file already on disk — e.g. the combined image ImageCombiner produces — no copy step needed. */
    suspend fun uploadFile(context: Context, file: File, mimeType: String, isVideo: Boolean = false): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching { uploadFileBlocking(context, file, mimeType, isVideo) }
    }

    private fun uploadFileBlocking(context: Context, file: File, mimeType: String, isVideo: Boolean) {
        val url = PersonalServerSettings.getUploadUrl(context) ?: error("No server URL configured")
        val secret = PersonalServerSettings.getSecret(context)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("key", secret)
            .addFormDataPart("type", if (isVideo) "video" else "image")
            .addFormDataPart("file", "upload", file.asRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Server rejected the upload (${response.code})")
        }
    }
}
