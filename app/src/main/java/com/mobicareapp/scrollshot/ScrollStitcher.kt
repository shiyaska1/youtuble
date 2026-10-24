package com.mobicareapp.scrollshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a screen recording of something being scrolled into one long picture (or a few, when it is very
 * long or the scrolling jumped), so a whole page can be read from top to bottom.
 *
 * How: the video is read as small grey frames. The bars that never move (a title bar, a bottom bar) are
 * found by looking at which rows never change while the screen moves. Between each two frames the
 * scrolled distance is found by sliding one frame over the other until the rows match best; the distances
 * add up to a position for every frame. A frame is kept each time the screen has moved about half its
 * height, the kept frames are read again at full size, their positions are made exact, and the part of
 * each that nobody else covers better is pasted into the long picture.
 *
 * If two frames cannot be matched (the scroll was a blur, or the content changed), the picture is cut
 * there and a new one starts: it is shown after it with a thin red line between, never guessed.
 */
object ScrollStitcher {
    /** The pictures made, top to bottom; [heights] are their heights in pixels (all [width] wide). */
    class Stitched(val files: List<File>, val width: Int, val heights: List<Int>, val jumps: Int)

    private const val MAX_PART_HEIGHT = 20000
    private const val ANALYSIS_WIDTH = 216
    private const val MAX_ANALYSIS_FRAMES = 3000

    suspend fun stitch(context: Context, video: Uri, onStatus: (String) -> Unit): Result<Stitched> = withContext(Dispatchers.IO) {
        try {
            runCatching { stitchBlocking(context, video, onStatus) }
        } finally {
            // The copy of the video and the small frames are not kept, whatever happened.
            File(context.cacheDir, "scrollshot").deleteRecursively()
        }
    }

    private fun stitchBlocking(context: Context, video: Uri, onStatus: (String) -> Unit): Stitched {
        // --- the video's size and length ---
        val meta = MediaMetadataRetriever()
        val vw: Int
        val vh: Int
        val durationSec: Double
        try {
            meta.setDataSource(context, video)
            val w = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: error("This is not a video")
            val h = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: error("This is not a video")
            val rotation = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            vw = if (rotation == 90 || rotation == 270) h else w
            vh = if (rotation == 90 || rotation == 270) w else h
            durationSec = (meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 60_000L) / 1000.0
        } finally {
            runCatching { meta.release() }
        }

        val work = File(context.cacheDir, "scrollshot").apply { deleteRecursively(); mkdirs() }
        // The video is copied to the app's own storage first: reading it straight from the gallery
        // does not allow jumping around in the file, and many phone videos keep their index at the end.
        onStatus("Copying the video…")
        val copy = File(work, "input.mp4")
        context.contentResolver.openInputStream(video)?.use { src -> copy.outputStream().use { src.copyTo(it) } } ?: error("Couldn't read the video")
        val input = copy.absolutePath

        // --- 1. small grey frames, 25 a second at most (fewer for a very long video) ---
        val fps = (MAX_ANALYSIS_FRAMES / max(durationSec, 1.0)).coerceIn(5.0, 25.0)
        val fpsText = "%.3f".format(java.util.Locale.US, fps)
        val aw = min(ANALYSIS_WIDTH, vw)
        val ah = max(2, (vh.toLong() * aw / vw).toInt() / 2 * 2)
        val raw = File(work, "small.raw")
        onStatus("Reading the video…")
        run(listOf("-y", "-i", input, "-an", "-vf", "fps=$fpsText,scale=$aw:$ah:flags=area,format=gray", "-f", "rawvideo", raw.absolutePath))
        val frameBytes = aw * ah
        val n = (raw.length() / frameBytes).toInt()
        if (n < 3) error("The video is too short to find any scrolling in it")

        RandomAccessFile(raw, "r").use { file ->
            fun read(i: Int, into: ByteArray) {
                file.seek(i.toLong() * frameBytes)
                file.readFully(into)
            }

            // --- 2. which rows never move (the fixed bars) ---
            onStatus("Looking for the scrolling…")
            val rowSum = FloatArray(ah)
            var moving = 0
            val a = ByteArray(frameBytes)
            val b = ByteArray(frameBytes)
            read(0, a)
            for (i in 1 until n) {
                read(i, b)
                var total = 0L
                val row = FloatArray(ah)
                for (y in 0 until ah) {
                    var s = 0
                    val base = y * aw
                    for (x in 0 until aw) s += abs((a[base + x].toInt() and 0xFF) - (b[base + x].toInt() and 0xFF))
                    row[y] = s.toFloat() / aw
                    total += s
                }
                if (total.toFloat() / frameBytes > 1.0f) {
                    moving++
                    for (y in 0 until ah) rowSum[y] += row[y]
                }
                System.arraycopy(b, 0, a, 0, frameBytes)
            }
            if (moving < 2) error("I did not find any scrolling in this video")
            var top = 0
            while (top < ah && rowSum[top] / moving < 0.5f) top++
            var bottom = ah
            while (bottom > 0 && rowSum[bottom - 1] / moving < 0.5f) bottom--
            if (bottom - top < ah / 4) { top = 0; bottom = ah }
            val regionRows = bottom - top

            // --- 3. how far the screen moved between each two frames ---
            val offsets = IntArray(n)
            val segmentOf = IntArray(n)
            var segment = 0
            val maxShift = (regionRows * 0.8f).toInt()
            val minOverlap = (regionRows * 0.2f).toInt()
            read(0, a)
            for (i in 1 until n) {
                if (i % 20 == 0) onStatus("Following the scroll… ${i * 100 / n}%")
                read(i, b)
                if (regionDifference(a, b, aw, top, bottom) < 0.6f) {
                    offsets[i] = offsets[i - 1]
                } else {
                    val found = bestShift(a, b, aw, top, bottom, maxShift, minOverlap)
                    if (found != null) {
                        offsets[i] = offsets[i - 1] + found
                    } else {
                        // Could not be matched: the picture is cut here and a new one starts.
                        segment++
                        offsets[i] = 0
                    }
                }
                segmentOf[i] = segment
                System.arraycopy(b, 0, a, 0, frameBytes)
            }

            // --- 4. keep a frame each time the screen moved about half its height ---
            val keepEvery = (regionRows * 0.5f).toInt().coerceAtLeast(1)
            val kept = mutableListOf<Int>()
            var lastKept = -1
            for (i in 0 until n) {
                val newSegment = i == 0 || segmentOf[i] != segmentOf[i - 1]
                if (newSegment || abs(offsets[i] - offsets[lastKept]) >= keepEvery) {
                    kept += i
                    lastKept = i
                }
            }
            // The last frame of each segment too, if the screen moved after the last kept one.
            for (i in 1 until n) {
                val endOfSegment = i == n - 1 || segmentOf[i + 1] != segmentOf[i]
                if (endOfSegment) {
                    val last = kept.last { segmentOf[it] == segmentOf[i] }
                    if (last != i && abs(offsets[i] - offsets[last]) > 2 && i !in kept) kept += i
                }
            }
            kept.sort()
            if (kept.size < 2) error("The screen did not scroll in this video")

            // --- 5. the kept frames at full size ---
            onStatus("Taking ${kept.size} full-size pictures from the video…")
            val frames = File(work, "frames").apply { mkdirs() }
            val select = kept.joinToString("+") { "eq(n,$it)" }
            run(listOf("-y", "-i", input, "-an", "-vf", "fps=$fpsText,select='$select'", "-fps_mode", "passthrough", "-q:v", "2", File(frames, "f_%04d.jpg").absolutePath))
            val frameFiles = kept.indices.map { File(frames, "f_%04d.jpg".format(it + 1)) }
            if (frameFiles.any { !it.exists() }) error("Couldn't take the pictures out of the video")

            // --- 6. exact positions, at full size, and cuts where they do not fit ---
            onStatus("Making the positions exact…")
            val scale = vw.toFloat() / aw
            val fullTop = (top * scale).toInt().coerceIn(0, vh - 1)
            val fullBottom = (bottom * scale).toInt().coerceIn(fullTop + 1, vh)
            val fullRows = fullBottom - fullTop
            val groups = mutableListOf<MutableList<Placed>>()
            var currentGroup: MutableList<Placed>? = null
            var previousGray: Array<ByteArray>? = null
            for ((k, frameIndex) in kept.withIndex()) {
                val gray = grayRows(frameFiles[k], fullTop, fullBottom, vw)
                val startsGroup = k == 0 || segmentOf[frameIndex] != segmentOf[kept[k - 1]]
                if (startsGroup || currentGroup == null) {
                    currentGroup = mutableListOf(Placed(frameFiles[k], 0))
                    groups += currentGroup
                } else {
                    val predicted = ((offsets[frameIndex] - offsets[kept[k - 1]]) * scale).toInt()
                    val exact = exactShift(previousGray!!, gray, predicted)
                    if (exact == null) {
                        // Does not fit at full size: cut here too.
                        currentGroup = mutableListOf(Placed(frameFiles[k], 0))
                        groups += currentGroup
                    } else {
                        currentGroup += Placed(frameFiles[k], currentGroup.last().offset + exact)
                    }
                }
                previousGray = gray
            }

            // --- 7. paste the frames together ---
            val files = mutableListOf<File>()
            val heights = mutableListOf<Int>()
            val outDir = File(context.filesDir, "scrollshots").apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            var partNumber = 0
            for (group in groups) {
                val ordered = group.sortedBy { it.offset }
                val minOffset = ordered.first().offset
                val groupHeight = ordered.last().offset - minOffset + fullRows
                // Each frame owns the strip up to the middle of what it shares with its neighbours.
                val strips = ordered.mapIndexed { idx, placed ->
                    val o = placed.offset - minOffset
                    val stripFrom = if (idx == 0) 0 else ((ordered[idx - 1].offset - minOffset) + fullRows + o) / 2
                    val stripTo = if (idx == ordered.lastIndex) groupHeight else (o + fullRows + (ordered[idx + 1].offset - minOffset)) / 2
                    Triple(placed, o, Pair(stripFrom.coerceAtLeast(o), stripTo.coerceAtMost(o + fullRows)))
                }
                var partStart = 0
                while (partStart < groupHeight) {
                    val partEnd = min(groupHeight, partStart + MAX_PART_HEIGHT)
                    onStatus("Joining the pictures… part ${partNumber + 1}")
                    val bitmap = Bitmap.createBitmap(vw, partEnd - partStart, Bitmap.Config.RGB_565)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    for ((placed, frameTop, range) in strips) {
                        val rowFrom = max(range.first, partStart)
                        val rowTo = min(range.second, partEnd)
                        if (rowTo <= rowFrom) continue
                        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
                        val frame = BitmapFactory.decodeFile(placed.file.absolutePath, options) ?: continue
                        val srcTop = fullTop + (rowFrom - frameTop)
                        val src = Rect(0, srcTop, frame.width, srcTop + (rowTo - rowFrom))
                        val dst = Rect(0, rowFrom - partStart, frame.width, rowTo - partStart)
                        canvas.drawBitmap(frame, src, dst, null)
                        frame.recycle()
                    }
                    partNumber++
                    val file = File(outDir, "scroll_${stamp}_$partNumber.jpg")
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                    files += file
                    heights += bitmap.height
                    bitmap.recycle()
                    partStart = partEnd
                }
            }
            work.deleteRecursively()
            return Stitched(files, vw, heights, groups.size - 1)
        }
    }

    private class Placed(val file: File, val offset: Int)

    private fun run(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException("The video could not be read (" + (session.failStackTrace ?: session.output).orEmpty().takeLast(200) + ")")
        }
    }

    /** Average change between two frames in the moving part (0 = identical), sampling every second row and third column. */
    private fun regionDifference(a: ByteArray, b: ByteArray, width: Int, top: Int, bottom: Int): Float {
        var sum = 0L
        var count = 0
        var y = top
        while (y < bottom) {
            val base = y * width
            var x = 0
            while (x < width) {
                sum += abs((a[base + x].toInt() and 0xFF) - (b[base + x].toInt() and 0xFF))
                count++
                x += 3
            }
            y += 2
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    /**
     * How many rows the content of [b] moved up compared with [a] (negative: down), or null when the two
     * do not match clearly. A match is clear when the best fit is far better than a typical wrong one.
     */
    private fun bestShift(a: ByteArray, b: ByteArray, width: Int, top: Int, bottom: Int, maxShift: Int, minOverlap: Int): Int? {
        val rows = bottom - top
        val errors = FloatArray(2 * maxShift + 1) { -1f }
        var best = Float.MAX_VALUE
        var bestShift = 0
        for (dy in -maxShift..maxShift) {
            val overlap = rows - abs(dy)
            if (overlap < minOverlap) continue
            var sum = 0L
            var count = 0
            var r = 0
            while (r < overlap) {
                val rowA = if (dy >= 0) top + dy + r else top + r
                val rowB = if (dy >= 0) top + r else top - dy + r
                val baseA = rowA * width
                val baseB = rowB * width
                var x = 0
                while (x < width) {
                    sum += abs((a[baseA + x].toInt() and 0xFF) - (b[baseB + x].toInt() and 0xFF))
                    count++
                    x += 3
                }
                r += 2
            }
            val error = sum.toFloat() / max(count, 1)
            errors[dy + maxShift] = error
            if (error < best) {
                best = error
                bestShift = dy
            }
        }
        val valid = errors.filter { it >= 0f }.sorted()
        if (valid.isEmpty()) return null
        val median = valid[valid.size / 2]
        return if (best / (median + 1e-3f) < 0.5f) bestShift else null
    }

    /** The picture rows (only the moving part, every 4th column) of a kept frame, as grey values. */
    private fun grayRows(file: File, fromRow: Int, toRow: Int, width: Int): Array<ByteArray> {
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: throw IOException("Couldn't open a picture from the video")
        val pixels = IntArray(bitmap.width)
        val columns = (bitmap.width + 3) / 4
        val rows = Array(toRow - fromRow) { ByteArray(columns) }
        for (y in fromRow until min(toRow, bitmap.height)) {
            bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
            val row = rows[y - fromRow]
            var c = 0
            var x = 0
            while (x < bitmap.width) {
                val p = pixels[x]
                row[c++] = ((((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8).toByte()
                x += 4
            }
        }
        bitmap.recycle()
        return rows
    }

    /** The exact full-size shift of [b] against [a], searched around [predicted]; null when it does not fit well. */
    private fun exactShift(a: Array<ByteArray>, b: Array<ByteArray>, predicted: Int): Int? {
        val rows = a.size
        val columns = a[0].size
        var best = Float.MAX_VALUE
        var bestShift = predicted
        for (dy in predicted - 14..predicted + 14) {
            val overlap = rows - abs(dy)
            if (overlap < 60) continue
            var sum = 0L
            var count = 0
            var r = 0
            while (r < overlap) {
                val rowA = a[if (dy >= 0) dy + r else r]
                val rowB = b[if (dy >= 0) r else -dy + r]
                for (x in 0 until columns) {
                    sum += abs((rowA[x].toInt() and 0xFF) - (rowB[x].toInt() and 0xFF))
                    count++
                }
                r += 2
            }
            val error = sum.toFloat() / max(count, 1)
            if (error < best) {
                best = error
                bestShift = dy
            }
        }
        return if (best < 24f) bestShift else null
    }

    /** A thin red line, used by the viewer between pictures that were cut (the scrolling jumped there). */
    val DIVIDER_COLOR: Int = Color.rgb(198, 40, 40)
}
