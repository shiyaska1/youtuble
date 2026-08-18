package com.ytsaver.app.extract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.StreamInfo

data class MediaOption(
    val streamUrl: String,
    val fileExtension: String,
    val label: String
)

data class FetchedStream(
    val title: String,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val sourceUrl: String,
    val videoOption: MediaOption?,
    val audioOption: MediaOption?
)

/**
 * Looks up a pasted YouTube link and picks the best "progressive" video
 * stream (video+audio already muxed together, so no ffmpeg is needed) and
 * the best audio-only stream.
 */
object YoutubeStreamFetcher {

    suspend fun fetch(rawUrl: String): Result<FetchedStream> = withContext(Dispatchers.IO) {
        runCatching {
            val url = rawUrl.trim()
            val info = StreamInfo.getInfo(url)

            val bestVideo = info.videoStreams
                .filter { !it.url.isNullOrBlank() }
                .maxByOrNull { resolutionRank(it.resolution) }
                ?.let {
                    MediaOption(
                        streamUrl = it.url!!,
                        fileExtension = it.format?.suffix ?: "mp4",
                        label = it.resolution ?: "Video"
                    )
                }

            val bestAudio = info.audioStreams
                .filter { !it.url.isNullOrBlank() }
                .maxByOrNull { it.averageBitrate }
                ?.let {
                    MediaOption(
                        streamUrl = it.url!!,
                        fileExtension = it.format?.suffix ?: "m4a",
                        label = if (it.averageBitrate > 0) "${it.averageBitrate} kbps" else "Audio"
                    )
                }

            FetchedStream(
                title = info.name.ifBlank { "Untitled" },
                thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
                durationSeconds = info.duration,
                sourceUrl = url,
                videoOption = bestVideo,
                audioOption = bestAudio
            )
        }
    }

    private fun resolutionRank(resolution: String?): Int =
        resolution?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
}
