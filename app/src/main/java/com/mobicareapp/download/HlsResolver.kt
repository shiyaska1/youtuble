package com.mobicareapp.download

import com.mobicareapp.extract.BROWSER_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

data class HlsPlaylist(val segmentUrls: List<String>, val isEncrypted: Boolean)

/**
 * Many sites don't serve one video file — they serve an HLS playlist (.m3u8) that points at
 * dozens of small segment files (.ts), meant to be requested one after another by a video
 * player. Detecting just those tiny segment files (which is all a page's own network traffic
 * shows) isn't useful on its own; this resolves a playlist URL down to the ordered list of
 * segment URLs that make up the whole video, which DownloadService then fetches and concatenates.
 */
object HlsResolver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", BROWSER_USER_AGENT).build())
        }
        .build()

    suspend fun resolve(playlistUrl: String, cookie: String?, referer: String?): HlsPlaylist =
        withContext(Dispatchers.IO) {
            val mediaPlaylistUrl = resolveMediaPlaylistUrl(playlistUrl, cookie, referer)
            val text = fetchText(mediaPlaylistUrl, cookie, referer)
            val lines = text.lines()

            val isEncrypted = lines.any { line ->
                line.startsWith("#EXT-X-KEY:") && !line.contains("METHOD=NONE")
            }

            val segmentUrls = lines
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { resolveUrl(mediaPlaylistUrl, it.trim()) }

            HlsPlaylist(segmentUrls, isEncrypted)
        }

    /** If [playlistUrl] is a master playlist (lists several quality variants), picks the highest-bandwidth one and returns its URL; otherwise returns [playlistUrl] unchanged. */
    private fun resolveMediaPlaylistUrl(playlistUrl: String, cookie: String?, referer: String?): String {
        val text = fetchText(playlistUrl, cookie, referer)
        if (!text.contains("#EXT-X-STREAM-INF")) return playlistUrl

        val lines = text.lines()
        var bestBandwidth = -1L
        var bestUri: String? = null
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val uriLine = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (uriLine != null && bandwidth > bestBandwidth) {
                    bestBandwidth = bandwidth
                    bestUri = uriLine.trim()
                }
            }
            index++
        }
        return bestUri?.let { resolveUrl(playlistUrl, it) } ?: playlistUrl
    }

    private fun fetchText(url: String, cookie: String?, referer: String?): String {
        val request = Request.Builder().url(url).apply {
            if (!cookie.isNullOrBlank()) header("Cookie", cookie)
            if (!referer.isNullOrBlank()) header("Referer", referer)
        }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Couldn't load playlist (${response.code})")
            return response.body?.string() ?: throw IOException("Empty playlist")
        }
    }

    private fun resolveUrl(baseUrl: String, ref: String): String =
        if (ref.startsWith("http://") || ref.startsWith("https://")) ref else URL(URL(baseUrl), ref).toString()
}
