package com.mobicareapp.record

/**
 * "Normal" is the app's existing default. "Low" trades resolution/bitrate for far smaller files —
 * meant for recordings that might run a long time, where storage adds up fast at Normal's
 * bitrate (roughly 3.5GB/hour vs Low's ~450MB/hour).
 */
enum class VideoQuality(val label: String, val description: String, val width: Int, val height: Int, val bitrateBps: Int) {
    NORMAL("Normal", "720p — better quality, larger files (~3.5GB/hour)", 1280, 720, 8_000_000),
    LOW("Low (long recording)", "480p — smaller files (~450MB/hour), suited to recording for a long time", 640, 480, 1_000_000)
}
