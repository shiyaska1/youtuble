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
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Uploads a file to the user's own PHP endpoint (see server/upload.php) — a multipart POST with a
 * shared secret, matching that script's expectations exactly.
 *
 * PHP servers refuse a single upload above their upload_max_filesize / post_max_size (often only a
 * few MB). So a file bigger than one [CHUNK_BYTES] piece is sent in small pieces instead, which the
 * server script joins back together — no server setting needs changing. The app first asks the
 * server whether it understands pieces (an older copy of the script doesn't); if not, the whole
 * file is sent in one go, as before.
 */
object PersonalServerUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    /** Size of one piece: under the 2 MB that is a common server limit. */
    private const val CHUNK_BYTES = 1_000_000

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
        if (file.length() > CHUNK_BYTES && serverAcceptsPieces(url)) {
            uploadInPieces(url, secret, file, isVideo)
        } else {
            uploadWhole(url, secret, file, mimeType, isVideo)
        }
    }

    private fun uploadWhole(url: String, secret: String, file: File, mimeType: String, isVideo: Boolean) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("key", secret)
            .addFormDataPart("type", if (isVideo) "video" else "image")
            .addFormDataPart("file", "upload", file.asRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = response.body?.string().orEmpty().take(120)
                error("Server rejected the upload (${response.code}) $reason".trim())
            }
        }
    }

    /** Asks the server script whether it can join pieces. Anything but the exact answer means it can't (an old script, or not our script at all). */
    private fun serverAcceptsPieces(url: String): Boolean = runCatching {
        val probe = url + (if (url.contains('?')) "&" else "?") + "capabilities=1"
        client.newCall(Request.Builder().url(probe).get().build()).execute().use { response ->
            response.isSuccessful && response.body?.string().orEmpty().trim() == "chunked-v1"
        }
    }.getOrDefault(false)

    /** Sends the file one piece at a time (each tried up to 3 times); the server joins them when the last arrives. */
    private fun uploadInPieces(url: String, secret: String, file: File, isVideo: Boolean) {
        val id = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val total = ((file.length() + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            for (index in 0 until total) {
                val read = readFully(input, buffer)
                val piece = if (read == buffer.size) buffer else buffer.copyOf(read)
                val answer = sendPiece(url, secret, id, index, total, piece, isVideo)
                val last = index == total - 1
                if (last && answer != "OK") throw IOException("The server did not join the file (answer: $answer)")
            }
        }
    }

    private fun sendPiece(url: String, secret: String, id: String, index: Int, total: Int, piece: ByteArray, isVideo: Boolean): String {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("action", "chunk")
                    .addFormDataPart("key", secret)
                    .addFormDataPart("uploadId", id)
                    .addFormDataPart("index", index.toString())
                    .addFormDataPart("total", total.toString())
                    .addFormDataPart("type", if (isVideo) "video" else "image")
                    .addFormDataPart("name", "upload")
                    .addFormDataPart("file", "piece", piece.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                    val text = response.body?.string().orEmpty().trim()
                    if (!response.isSuccessful) throw IOException("Server rejected a piece (${response.code}): ${text.take(120)}")
                    return text
                }
            } catch (e: IOException) {
                lastError = e
                // A wrong key or a script error won't fix itself; a dropped connection might.
                if (e.message?.contains("rejected") == true && attempt >= 2) break
                Thread.sleep(1500L * attempt)
            }
        }
        throw lastError ?: IOException("Couldn't send a piece")
    }

    /** Reads until [buffer] is full or the stream ends; returns how many bytes were read. */
    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = input.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }
}
