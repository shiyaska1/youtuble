package com.ytsaver.app.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Fallback for a link that isn't YouTube and doesn't point straight at a
 * media file: fetches the page and looks for a video URL using common,
 * widely-used embedding patterns (og:video meta tag, HTML5 <video>/<source>
 * tags, an HLS/DASH manifest reference). This covers a lot of ordinary sites
 * with a plain HTML5 player, but isn't a general-purpose scraper - sites that
 * render their player via JavaScript with no fallback markup, or that
 * actively block non-browser requests (as YouTube/Instagram do), won't work
 * here.
 */
object GenericVideoFetcher {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun fetch(rawUrl: String): Result<FetchedStream> = withContext(Dispatchers.IO) {
        runCatching {
            val url = rawUrl.trim()
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()

            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Couldn't reach that page (server returned ${response.code})")
                }
                response.body?.string().orEmpty()
            }

            val videoUrl = findVideoUrl(html, url)
                ?: throw IOException("Couldn't find a downloadable video on that page.")
            val title = metaContent(html, "og:title") ?: titleTag(html) ?: "Downloaded video"
            val thumbnail = metaContent(html, "og:image")

            val extension = when {
                videoUrl.contains(".m3u8") -> "m3u8"
                videoUrl.contains(".mpd") -> "mpd"
                videoUrl.contains(".webm") -> "webm"
                else -> "mp4"
            }
            val mimeType = when (extension) {
                "m3u8" -> "application/vnd.apple.mpegurl"
                "mpd" -> "application/dash+xml"
                "webm" -> "video/webm"
                else -> "video/mp4"
            }

            val option = MediaOption(
                streamUrl = resolveUrl(url, videoUrl),
                fileExtension = extension,
                mimeType = mimeType,
                label = "Video"
            )

            FetchedStream(
                title = unescapeHtml(title),
                thumbnailUrl = thumbnail?.let { unescapeHtml(resolveUrl(url, it)) },
                durationSeconds = 0,
                sourceUrl = url,
                videoOption = option,
                audioOption = null
            )
        }
    }

    private fun findVideoUrl(html: String, pageUrl: String): String? {
        metaContent(html, "og:video:secure_url")?.let { return it }
        metaContent(html, "og:video:url")?.let { return it }
        metaContent(html, "og:video")?.let { return it }
        metaContent(html, "twitter:player:stream")?.let { return it }

        Regex("""<(?:video|source)\b[^>]*\bsrc=["']([^"']+\.(?:mp4|webm|m3u8|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { return it }

        Regex("""["'](https?:[^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1)?.let { return it }
        Regex("""["'](https?:[^"']+\.mpd[^"']*)["']""").find(html)?.groupValues?.get(1)?.let { return it }
        Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.let { return it }

        return null
    }

    private fun metaContent(html: String, property: String): String? {
        Regex("""<meta[^>]*property=["']$property["'][^>]*content=["']([^"']+)["']""")
            .find(html)?.let { return it.groupValues[1] }
        Regex("""<meta[^>]*content=["']([^"']+)["'][^>]*property=["']$property["']""")
            .find(html)?.let { return it.groupValues[1] }
        Regex("""<meta[^>]*name=["']$property["'][^>]*content=["']([^"']+)["']""")
            .find(html)?.let { return it.groupValues[1] }
        return null
    }

    private fun titleTag(html: String): String? =
        Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()

    private fun resolveUrl(pageUrl: String, maybeRelative: String): String {
        val unescaped = unescapeHtml(maybeRelative)
        return runCatching { java.net.URL(java.net.URL(pageUrl), unescaped).toString() }.getOrDefault(unescaped)
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
