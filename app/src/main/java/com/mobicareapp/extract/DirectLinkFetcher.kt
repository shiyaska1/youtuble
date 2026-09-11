package com.mobicareapp.extract

import android.webkit.CookieManager
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
        // A server that never responds at all (common behind a login wall or bot-check) used to
        // hang for OkHttp's 10s read-timeout default on top of a 15s connect timeout, on *both*
        // the HEAD and the ranged-GET probe below — over 30s before the user saw any error at
        // all, and the same wait again on every retry. Capping the whole call (connect+write+read
        // together) is what actually bounds that.
        .callTimeout(10, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
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
            // Reuses a session cookie already sitting in the shared, app-wide WebView cookie
            // store (from Browse or the sign-in dialog) — some links only respond once signed in.
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            val headers = probeHeaders(url, cookie)
            val contentType = headers["Content-Type"]?.substringBefore(';')?.trim()?.lowercase() ?: "application/octet-stream"
            // A normal webpage (the common case when the pasted link isn't a raw file) comes back
            // as text/html here, not a media type — that's the signal to let the caller fall back
            // to GenericVideoFetcher's page-scraping instead of saving the HTML as a fake video.
            if (!looksLikeMediaContentType(contentType)) {
                throw java.io.IOException("That link doesn't point straight at a video/audio file (got \"$contentType\")")
            }
            val isAudio = contentType.startsWith("audio/")

            val option = MediaOption(
                streamUrl = url,
                fileExtension = extensionFromUrlOrType(url, contentType),
                mimeType = contentType,
                label = if (isAudio) "Audio" else "Video",
                cookie = cookie
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

    private fun looksLikeMediaContentType(contentType: String): Boolean =
        contentType.startsWith("video/") || contentType.startsWith("audio/") ||
            contentType == "application/octet-stream" ||
            contentType == "application/vnd.apple.mpegurl" || contentType == "application/x-mpegurl" ||
            contentType == "application/dash+xml"

    private fun probeHeaders(url: String, cookie: String?): Headers {
        val headRequest = Request.Builder().url(url).head().apply {
            if (!cookie.isNullOrBlank()) header("Cookie", cookie)
        }.build()
        runCatching {
            client.newCall(headRequest).execute().use { response ->
                if (response.isSuccessful) return response.headers
            }
        }
        val rangedGet = Request.Builder().url(url).header("Range", "bytes=0-0").apply {
            if (!cookie.isNullOrBlank()) header("Cookie", cookie)
        }.build()
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
