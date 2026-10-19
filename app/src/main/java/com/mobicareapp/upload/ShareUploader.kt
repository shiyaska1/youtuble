package com.mobicareapp.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Sends a shared file straight to the user's server.
 *
 * A small file goes in one go through upload.php. A big one (a video, a zip...) goes through
 * uploadnew.php in pieces that the server joins into one file, so PHP's upload size limits never
 * matter (even 3 GB works). The file is read straight from the sender (WhatsApp, a file manager...),
 * never copied first. On the server it is called "1" plus the file's own ending (1.mp4, 1.xlsx).
 */
object ShareUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.MINUTES)
        .readTimeout(3, TimeUnit.MINUTES)
        .build()

    /** Up to this size a file goes in one go through upload.php; bigger ones go in pieces. */
    private const val SMALL_LIMIT = 1_000_000L

    /** Piece sizes to try, biggest first: the first one the server accepts is used. */
    private val PIECE_SIZES = intArrayOf(8, 4, 2, 1).map { it * 1024 * 1024 }

    private val SCRIPT_ENDINGS = setOf("php", "php3", "php4", "php5", "php7", "phtml", "phar", "pht", "phps", "htaccess", "htpasswd", "ini")

    data class Source(val name: String, val size: Long, val mime: String)

    /** The file's name, size and type as the sender describes them. */
    fun describe(context: Context, uri: Uri): Source {
        var name = "file"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        return Source(name, size, mime)
    }

    /** "1.mp4": the number plus the file's own ending, taken from its name or else from its type. */
    fun serverNameFor(source: Source, number: Int): String {
        var ending = source.name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
        if (ending.isEmpty()) {
            ending = MimeTypeMap.getSingleton().getExtensionFromMimeType(source.mime)?.lowercase() ?: "bin"
        }
        // A script must never land on the server as a script.
        if (ending in SCRIPT_ENDINGS) ending = "$ending.txt"
        return "$number.$ending"
    }

    /** The folder on the server that the upload script is in (no trailing slash). */
    fun serverFolder(context: Context): String? =
        PersonalServerSettings.getUploadUrl(context)?.substringBeforeLast('/')

    /**
     * Uploads one file and returns its address. [onProgress] gets the bytes sent so far;
     * [cancelled] is checked between pieces.
     */
    suspend fun upload(
        context: Context,
        uri: Uri,
        source: Source,
        serverName: String,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean
    ): String = withContext(Dispatchers.IO) {
        val folder = serverFolder(context) ?: error("No server is set up")
        val secret = PersonalServerSettings.getSecret(context)

        var size = source.size
        if (size <= 0) size = countBytes(context, uri)
        if (size <= 0) error("The file is empty or could not be read")

        if (size <= SMALL_LIMIT) {
            PersonalServerUploader.uploadAs(context, uri, source.mime, serverName).getOrThrow()
            onProgress(size)
        } else {
            val script = folder + "/uploadnew.php"
            uploadInPieces(context, uri, size, serverName, script, secret, onProgress, cancelled)
        }
        "$folder/$serverName"
    }

    private fun countBytes(context: Context, uri: Uri): Long {
        var total = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
        }
        return total
    }

    /** The server answered, but said no (as opposed to the connection failing). */
    private class Rejected(val code: Int, message: String) : IOException(message)

    private fun uploadInPieces(
        context: Context,
        uri: Uri,
        size: Long,
        serverName: String,
        script: String,
        secret: String,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        var lastError: IOException? = null
        for ((attempt, pieceBytes) in PIECE_SIZES.withIndex()) {
            val id = randomId()
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    sendAllPieces(input, size, pieceBytes, id, serverName, script, secret, onProgress, cancelled)
                } ?: error("Couldn't read the file")
                return
            } catch (e: Rejected) {
                // The server refused a piece that big (its own size limit): clean up and try smaller pieces.
                lastError = e
                runCatching { postForm(script, mapOf("action" to "deletepart", "key" to secret, "uploadId" to id)) }
                onProgress(0)
                val canTrySmaller = e.code != 403 && attempt < PIECE_SIZES.lastIndex
                if (!canTrySmaller) throw e
            }
        }
        throw lastError ?: IOException("The upload failed")
    }

    private fun sendAllPieces(
        input: InputStream,
        size: Long,
        pieceBytes: Int,
        id: String,
        serverName: String,
        script: String,
        secret: String,
        onProgress: (Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        val total = ((size + pieceBytes - 1) / pieceBytes).toInt()
        val buffer = ByteArray(pieceBytes)
        var sent = 0L
        for (index in 0 until total) {
            if (cancelled()) throw IOException("Cancelled")
            val read = readFully(input, buffer)
            if (read <= 0) throw IOException("The file ended early")
            val piece = if (read == buffer.size) buffer else buffer.copyOf(read)
            val answer = sendPiece(script, secret, id, index, total, pieceBytes, size, serverName, piece, index == 0)
            sent += read
            onProgress(sent)
            if (index == total - 1 && answer != "OK") throw IOException("The server did not finish the file (answer: $answer)")
        }
    }

    /** One piece, tried up to 4 times when the connection drops. A refusal from the server is not retried. */
    private fun sendPiece(
        script: String,
        secret: String,
        id: String,
        index: Int,
        total: Int,
        pieceBytes: Int,
        size: Long,
        serverName: String,
        piece: ByteArray,
        first: Boolean
    ): String {
        var failure: IOException? = null
        for (attempt in 1..4) {
            try {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("action", "chunk")
                    .addFormDataPart("key", secret)
                    .addFormDataPart("uploadId", id)
                    .addFormDataPart("index", index.toString())
                    .addFormDataPart("total", total.toString())
                    .addFormDataPart("chunk", pieceBytes.toString())
                    .addFormDataPart("size", size.toString())
                    .addFormDataPart("name", serverName)
                    .addFormDataPart("file", "piece", piece.toRequestBody("application/octet-stream".toMediaType()))
                    .build()
                client.newCall(Request.Builder().url(script).post(body).build()).execute().use { response ->
                    val text = response.body?.string().orEmpty().trim()
                    if (response.isSuccessful) return text
                    val hint = when {
                        response.code == 403 -> "Wrong key"
                        response.code == 404 -> "uploadnew.php is not on the server yet"
                        text.isNotEmpty() -> text.take(120)
                        else -> "HTTP ${response.code}"
                    }
                    // 5xx may pass on a retry; everything else is a refusal.
                    if (response.code in 500..599 && attempt < 4 && !first) throw IOException(hint)
                    throw Rejected(response.code, hint)
                }
            } catch (e: Rejected) {
                throw e
            } catch (e: IOException) {
                failure = e
                if (attempt < 4) Thread.sleep(2000L * attempt)
            }
        }
        throw failure ?: IOException("Couldn't send a piece")
    }

    private fun postForm(url: String, fields: Map<String, String>) {
        val form = okhttp3.FormBody.Builder().also { b -> fields.forEach { (k, v) -> b.add(k, v) } }.build()
        client.newCall(Request.Builder().url(url).post(form).build()).execute().close()
    }

    private fun randomId(): String =
        ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

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
