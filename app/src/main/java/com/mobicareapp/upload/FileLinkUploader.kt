package com.mobicareapp.upload

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Free file hosts that accept an upload with no account or key — the app uploads straight to them and hands back a download link. */
enum class FileHost(val label: String, val note: String, val maxBytes: Long?) {
    GOFILE("Gofile", "Any size, link stays while the file is used", null),
    TEMPSH("temp.sh", "Up to 4 GB, link works for 3 days", 4_000_000_000L),
    LITTERBOX("Litterbox", "Up to 1 GB, link works for 3 days", 1_000_000_000L),
    CATBOX("Catbox", "Up to 200 MB, link doesn't expire", 200_000_000L);

    fun accepts(sizeBytes: Long): Boolean = maxBytes == null || sizeBytes <= maxBytes
}

data class UploadedLink(val url: String, val host: FileHost)

/**
 * Uploads one file and returns a public download link. The file is streamed from disk in small
 * chunks (never loaded whole into memory), so a multi-GB video is fine. In automatic mode it tries
 * Gofile first (the only one with no size limit) and falls back to the next host that can take the
 * file if that one is down or blocked on the user's network.
 *
 * Anyone with the returned link can download the file — callers should say so.
 */
object FileLinkUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Per write/read operation, not the whole upload — a slow phone connection moving a big file
        // is fine as long as bytes keep flowing. The read timeout is long because the host may
        // spend minutes processing a large file after the last byte is sent.
        .writeTimeout(2, TimeUnit.MINUTES)
        .readTimeout(15, TimeUnit.MINUTES)
        .build()

    /**
     * [host] null = automatic. [onHost] is told which host is being tried (it changes on a fallback),
     * [onProgress] gets bytes sent so far (resets to 0 when a fallback host starts over).
     */
    suspend fun upload(
        context: Context,
        uri: Uri,
        fileName: String,
        sizeBytes: Long,
        mimeType: String,
        host: FileHost?,
        onHost: (FileHost) -> Unit,
        onProgress: (Long) -> Unit
    ): Result<UploadedLink> = withContext(Dispatchers.IO) {
        val candidates = if (host != null) listOf(host) else FileHost.entries.filter { it.accepts(sizeBytes) }
        if (candidates.isEmpty()) {
            return@withContext Result.failure(IOException("No free host accepts a file this large."))
        }
        val errors = mutableListOf<String>()
        for (candidate in candidates) {
            onHost(candidate)
            onProgress(0)
            try {
                val url = when (candidate) {
                    FileHost.GOFILE -> uploadToGofile(context, uri, fileName, sizeBytes, mimeType, onProgress)
                    FileHost.TEMPSH -> uploadToCatboxFamily(
                        "https://temp.sh/upload", extraField = null,
                        context, uri, fileName, sizeBytes, mimeType, onProgress,
                        fileField = "file", reqType = false
                    )
                    FileHost.LITTERBOX -> uploadToCatboxFamily(
                        "https://litterbox.catbox.moe/resources/internals/api.php", extraField = "time" to "72h",
                        context, uri, fileName, sizeBytes, mimeType, onProgress
                    )
                    FileHost.CATBOX -> uploadToCatboxFamily(
                        "https://catbox.moe/user/api.php", extraField = null,
                        context, uri, fileName, sizeBytes, mimeType, onProgress
                    )
                }
                return@withContext Result.success(UploadedLink(url, candidate))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "${candidate.label}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        Result.failure(IOException(errors.joinToString("\n")))
    }

    /** Gofile: ask which upload server to use, then POST the file there. Guest uploads need no key. */
    private suspend fun uploadToGofile(
        context: Context, uri: Uri, fileName: String, sizeBytes: Long, mimeType: String, onProgress: (Long) -> Unit
    ): String {
        val servers = runCatching { gofileServers() }.getOrDefault(emptyList()).ifEmpty { listOf("upload") }
        var lastError: Exception? = null
        // Gofile has served uploads at two paths over time; the current one first.
        for (server in servers.take(2)) {
            for (path in listOf("contents/uploadfile", "uploadFile")) {
                try {
                    val body = multipart(context, uri, fileName, sizeBytes, mimeType, "file", extra = null, onProgress)
                    val request = Request.Builder().url("https://$server.gofile.io/$path").post(body).build()
                    client.newCall(request).await().use { response ->
                        val text = response.body?.string().orEmpty()
                        if (response.code == 404) throw IOException("HTTP 404")
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${text.take(120)}")
                        val json = JSONObject(text)
                        if (json.optString("status") != "ok") throw IOException(json.optString("status", "upload refused"))
                        return json.getJSONObject("data").getString("downloadPage")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                }
            }
        }
        throw lastError ?: IOException("couldn't reach Gofile")
    }

    private suspend fun gofileServers(): List<String> {
        val request = Request.Builder().url("https://api.gofile.io/servers").get().build()
        client.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val array = JSONObject(text).getJSONObject("data").getJSONArray("servers")
            return (0 until array.length()).map { array.getJSONObject(it).getString("name") }
        }
    }

    /** Catbox, its temporary-file sibling Litterbox, and temp.sh all work the same way: a form POST that answers with the bare URL as plain text. */
    private suspend fun uploadToCatboxFamily(
        endpoint: String, extraField: Pair<String, String>?,
        context: Context, uri: Uri, fileName: String, sizeBytes: Long, mimeType: String, onProgress: (Long) -> Unit,
        fileField: String = "fileToUpload", reqType: Boolean = true
    ): String {
        val body = multipart(context, uri, fileName, sizeBytes, mimeType, fileField, extra = extraField, onProgress, reqType = reqType)
        val request = Request.Builder().url(endpoint).post(body).build()
        client.newCall(request).await().use { response ->
            val text = response.body?.string().orEmpty().trim()
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ${text.take(120)}")
            if (!text.startsWith("http")) throw IOException(text.take(160).ifEmpty { "empty reply" })
            return text
        }
    }

    private fun multipart(
        context: Context, uri: Uri, fileName: String, sizeBytes: Long, mimeType: String,
        fileField: String, extra: Pair<String, String>?, onProgress: (Long) -> Unit, reqType: Boolean = false
    ): RequestBody {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
        if (reqType) builder.addFormDataPart("reqtype", "fileupload")
        extra?.let { builder.addFormDataPart(it.first, it.second) }
        builder.addFormDataPart(
            fileField, fileName.ifBlank { "file" },
            StreamingBody(context, uri, mimeType.toMediaTypeOrNull(), sizeBytes, onProgress)
        )
        return builder.build()
    }

    /** Streams the file from its Uri in 64 KB chunks, reporting how much has gone out. */
    private class StreamingBody(
        private val context: Context,
        private val uri: Uri,
        private val type: MediaType?,
        private val length: Long,
        private val onProgress: (Long) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType? = type
        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't read the file")
            input.use { stream ->
                val buffer = ByteArray(64 * 1024)
                var sent = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    sent += read
                    onProgress(sent)
                }
            }
        }
    }

    /** Cancelling the coroutine cancels the in-flight HTTP call, which stops the upload immediately. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }
}
