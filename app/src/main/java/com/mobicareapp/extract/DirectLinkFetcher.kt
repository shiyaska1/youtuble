package com.mobicareapp.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Handles links that point straight at a media file on some other site
 * (e.g. https://example.com/video.mp4) rather than a YouTube watch page —
 * just a plain HTTP fetch, no page-scraping involved.
 */
object DirectLinkFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", BROWSER_USER_AGENT).build())
        }
        .build()

    fun looksLikeYoutubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    suspend fun fetch(rawUrl: String): Result<FetchedStream> = withContext(Dispatchers.IO) {
        runCatching {
            val url = rawUrl.trim()
            val headers = probeHeaders(url)
            val contentType = headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase() ?: "application/octet-stream"
            val isAudio = contentType.startsWith("audio/")

            val option = MediaOption(
                streamUrl = url,
                fileExtension = extensionFromUrlOrType(url, contentType),
                mimeType = contentType,
                label = if (isAudio) "Audio" else "Video"
            )

            FetchedStream(
                title = fileNameFromUrl(url),
                thumbnailUrl = null,
                durationSeconds = 0,
                sourceUrl = url,
                videoOption = if (isAudio) null else option,
                audioOption = if (isAudio) option else null
            )
        }
    }

    private fun probeHeaders(url: String): Headers {
        val headRequest = Request.Builder().url(url).head().build()
        runCatching {
            client.newCall(headRequest).execute().use { response ->
                if (response.isSuccessful) return response.headers
            }
        }
        val rangedGet = Request.Builder().url(url).header("Range", "bytes=0-0").build()
        client.newCall(rangedGet).execute().use { response ->
            if (!response.isSuccessful && response.code != 206) {
                throw java.io.IOException("Couldn't reach that link (server returned ${response.code})")
            }
            return response.headers
        }
    }

    private fun fileNameFromUrl(url: String): String {
        val path = url.substringBefore('?').substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        return decoded.substringBeforeLast('.').ifBlank { "Downloaded file" }
    }

    private fun extensionFromUrlOrType(url: String, contentType: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('.', "")
        if (fromUrl.isNotBlank() && fromUrl.length <= 5 && fromUrl.all { it.isLetterOrDigit() }) return fromUrl
        return when {
            contentType.contains("mp4") -> "mp4"
            contentType.contains("webm") -> "webm"
            contentType.contains("mpeg") -> "mp3"
            contentType.contains("mp3") -> "mp3"
            contentType.startsWith("audio/") -> "m4a"
            else -> "mp4"
        }
    }
}
