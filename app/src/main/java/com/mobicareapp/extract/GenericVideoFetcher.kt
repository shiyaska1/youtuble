package com.mobicareapp.extract

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Fallback for a pasted link that's a normal webpage rather than a raw file or a YouTube watch
 * page — DirectLinkFetcher rejects those (wrong content-type), so this fetches the page's HTML
 * instead and looks for the video the page embeds, the same way a link-preview crawler would:
 * Open Graph/Twitter-card video meta tags first, then a plain <video>/<source> tag, then a raw
 * .m3u8/.mpd URL or JSON-LD "contentUrl" anywhere in the markup. Works for sites that expose one
 * of those (many blogs/news sites do); doesn't help for JS-rendered players that only fetch their
 * video after the page loads — the in-app Browse screen covers those instead.
 */
object GenericVideoFetcher {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val client = OkHttpClient.Builder()
        // Bounds the whole request (connect+write+read together) — a server that never responds
        // at all (common behind a login wall or bot-check) would otherwise hang for the full
        // connect+read timeout on every retry instead of failing fast.
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(rawUrl: String): Result<FetchedStream> = withContext(Dispatchers.IO) {
        runCatching {
            val url = rawUrl.trim()
            // If the user already signed in to this site once (via the Browse screen or the
            // sign-in dialog), WebView's CookieManager still has that session — reusing it here
            // means a page behind a login wall works without asking again.
            val pageCookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).apply {
                if (!pageCookie.isNullOrBlank()) header("Cookie", pageCookie)
            }.build()
            val html = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Couldn't reach that page (server returned ${response.code})")
                response.body?.string() ?: ""
            }

            val videoUrl = findVideoUrl(html)
                ?: throw IOException("Couldn't find a downloadable video on that page.")
            val title = unescapeHtml(metaContent(html, "og:title") ?: titleTag(html) ?: "Downloaded video")
            val thumbnail = metaContent(html, "og:image")?.let { unescapeHtml(resolveUrl(url, it)) }

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

            val resolvedStreamUrl = resolveUrl(url, videoUrl)
            // Many sites only serve the video to a request whose Referer matches the page that
            // linked it, and/or the same signed-in session as the page itself — both carried
            // through to DownloadService via MediaOption.
            val option = MediaOption(
                streamUrl = resolvedStreamUrl,
                fileExtension = extension,
                mimeType = mimeType,
                label = "Video",
                referer = url,
                cookie = pageCookie ?: runCatching { CookieManager.getInstance().getCookie(resolvedStreamUrl) }.getOrNull()
            )

            FetchedStream(
                title = title,
                thumbnailUrl = thumbnail,
                durationSeconds = 0,
                sourceUrl = url,
                videoOption = option,
                audioOption = null
            )
        }
    }

    private fun findVideoUrl(html: String): String? {
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
            .find(html)?.groupValues?.get(1)?.let { return it }
        Regex("""<meta[^>]*content=["']([^"']+)["'][^>]*property=["']$property["']""")
            .find(html)?.groupValues?.get(1)?.let { return it }
        Regex("""<meta[^>]*name=["']$property["'][^>]*content=["']([^"']+)["']""")
            .find(html)?.groupValues?.get(1)?.let { return it }
        return null
    }

    private fun titleTag(html: String): String? =
        Regex("""<title[^>]*>([^<]+)</title>""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()

    private fun resolveUrl(pageUrl: String, maybeRelative: String): String {
        val unescaped = unescapeHtml(maybeRelative)
        return runCatching { URL(URL(pageUrl), unescaped).toString() }.getOrDefault(unescaped)
    }

    private fun unescapeHtml(s: String): String = s
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
}
