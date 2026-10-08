package com.mobicareapp.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo

data class MediaOption(
    val streamUrl: String,
    val fileExtension: String,
    val mimeType: String,
    val label: String,
    // Set for links scraped off a page (GenericVideoFetcher) whose host checks that a download
    // request's Referer matches the page that linked it — null wherever that doesn't apply.
    val referer: String? = null,
    // Carries a signed-in session cookie (from the shared, app-wide WebView CookieManager) for
    // sites that only serve their video to the same session that's logged in — null if none.
    val cookie: String? = null,
    // True for a YouTube DASH video-only stream (no audio track of its own) — the resolutions
    // beyond what "progressive" (video+audio already combined) offers only exist this way.
    // Downloading one of these needs muxing with a separate audio stream via ffmpeg afterward.
    val needsAudioMux: Boolean = false
)

data class FetchedStream(
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val sourceUrl: String,
    /** Highest resolution first. More than one entry only for YouTube — other sources have exactly one (or zero). */
    val videoOptions: List<MediaOption>,
    val audioOption: MediaOption?
) {
    /** The single best video option — what non-YouTube sources always have, and what YouTube's own "best" was before quality selection existed. */
    val videoOption: MediaOption? get() = videoOptions.firstOrNull()
}

/**
 * Looks up a pasted YouTube link and collects every downloadable video quality, plus the best
 * audio-only stream. YouTube's "progressive" streams (video+audio already muxed, no ffmpeg
 * needed) are capped at a fairly low resolution — genuinely higher quality (1080p and up) only
 * exists as video-only DASH streams, which [MediaOption.needsAudioMux] flags so the caller knows
 * to mux one of those with [FetchedStream.audioOption] via ffmpeg after downloading both.
 */
object YoutubeStreamFetcher {

    suspend fun fetch(rawUrl: String): Result<FetchedStream> = withContext(Dispatchers.IO) {
        runCatching {
            val url = rawUrl.trim()
            val info = StreamInfo.getInfo(url)

            val progressive = info.videoStreams
                .filter { !it.url.isNullOrBlank() }
                .map { toVideoOption(it.url!!, it.format?.suffix, it.format?.mimeType, it.resolution, needsAudioMux = false) }
            val videoOnly = info.videoOnlyStreams
                .filter { !it.url.isNullOrBlank() }
                .map { toVideoOption(it.url!!, it.format?.suffix, it.format?.mimeType, it.resolution, needsAudioMux = true) }

            // One entry per distinct resolution, highest first — preferring the progressive
            // stream at that resolution when both exist, since it needs no muxing afterward.
            val videoOptions = (progressive + videoOnly)
                .groupBy { resolutionRank(it.label) }
                .toSortedMap(compareByDescending { it })
                .values
                .map { atThisResolution -> atThisResolution.firstOrNull { !it.needsAudioMux } ?: atThisResolution.first() }

            val bestAudio = info.audioStreams
                .filter { !it.url.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }
                ?.let {
                    MediaOption(
                        streamUrl = it.url!!,
                        fileExtension = it.format?.suffix ?: "m4a",
                        mimeType = it.format?.mimeType ?: "audio/mp4",
                        label = if (it.averageBitrate > 0) "${it.averageBitrate} kbps" else "Audio"
                    )
                }

            FetchedStream(
                title = info.name.ifBlank { "Untitled" },
                thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
                durationSeconds = info.duration,
                sourceUrl = url,
                videoOptions = videoOptions,
                audioOption = bestAudio
            )
        }
    }

    private fun toVideoOption(streamUrl: String, suffix: String?, mimeType: String?, resolution: String?, needsAudioMux: Boolean) = MediaOption(
        streamUrl = streamUrl,
        fileExtension = suffix ?: "mp4",
        mimeType = mimeType ?: "video/mp4",
        label = resolution ?: "Video",
        needsAudioMux = needsAudioMux
    )

    private fun resolutionRank(resolution: String?): Int =
        resolution?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
}
