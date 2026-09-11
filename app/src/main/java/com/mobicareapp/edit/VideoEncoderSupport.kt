package com.mobicareapp.edit

import com.arthenica.ffmpegkit.FFmpegKit

/**
 * Picks a video encoder this specific FFmpegKit build actually has, instead of assuming libx264 —
 * not every build includes it (it's GPL-licensed and some distributions leave it out; this one
 * apparently does, despite its own docs claiming otherwise). Checked once against `ffmpeg
 * -encoders`'s real output and cached, preferring Android's own hardware encoder first (faster,
 * and has no licensing question at all since it doesn't come from FFmpeg's own code), then
 * libx264 if it's there after all, then whatever's guaranteed to exist in any ffmpeg build.
 */
internal object VideoEncoderSupport {
    private var cachedEncoder: String? = null

    @Synchronized
    private fun detectEncoder(): String {
        cachedEncoder?.let { return it }
        val output = runCatching {
            FFmpegKit.execute("-hide_banner -encoders").allLogsAsString ?: ""
        }.getOrDefault("")
        val chosen = when {
            Regex("""\sh264_mediacodec\s""").containsMatchIn(output) -> "h264_mediacodec"
            Regex("""\slibx264\s""").containsMatchIn(output) -> "libx264"
            Regex("""\slibopenh264\s""").containsMatchIn(output) -> "libopenh264"
            else -> "mpeg4"
        }
        cachedEncoder = chosen
        return chosen
    }

    /** The `-c:v ...` plus whatever quality flags that encoder actually understands — crf/preset are libx264-specific, mediacodec encoders want a bitrate instead. */
    fun encodeArgs(): String = when (val encoder = detectEncoder()) {
        "libx264" -> "-c:v libx264 -preset veryfast -crf 23"
        "h264_mediacodec" -> "-c:v h264_mediacodec -b:v 4M"
        "libopenh264" -> "-c:v libopenh264 -b:v 4M"
        else -> "-c:v $encoder -q:v 5"
    }
}
