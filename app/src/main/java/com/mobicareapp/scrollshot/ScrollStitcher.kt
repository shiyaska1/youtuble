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
 * Turns a screen recording of something being scrolled, up and down or left and right, into one long
 * (or wide) picture in which no part is repeated, so a whole page can be read from end to end.
 *
 * Step one ([analyze]): the video is read as small grey frames. The scrolling direction is found (the
 * direction in which the content moves between frames matches best). The bars that never move (a title
 * bar, a bottom bar, a floating button) are found by looking at which rows and columns never change while
 * the screen moves. Between each two frames the scrolled distance is found by sliding one frame over the
 * other until they match best; the distances add up to a position for every frame. A frame is kept each
 * time the screen has moved about half its size, the kept frames are read again at full size, their
 * positions are made exact, and each is given a sharpness number (a frame taken while the screen was
 * moving fast is blurred).
 *
 * Step two ([compose]): from the kept frames - all of them, or all but the ones the user marked as
 * unclear - each one contributes the strip that nobody else covers better (up to the middle of what it
 * shares with its neighbours), and the strips are joined one after the other. A strip that was removed
 * leaves no gap: what was beside it is simply joined up.
 *
 * If two frames cannot be matched (the scroll was a blur, or the content changed), the picture is cut
 * there and a new one starts, shown after it with a thin red line between, never guessed.
 */
object ScrollStitcher {
    /** One kept frame: where it sits along the scrolling direction (in its own group), and how sharp it is. */
    class Frame(val file: File, val group: Int, val along: Int, val sharpness: Float, val unclear: Boolean)

    /** Everything found in the video; the kept frames stay on disk (in the app's cache) until [discard]. */
    class Analysis(
        val frames: List<Frame>,
        val horizontal: Boolean,
        val width: Int,
        val height: Int,
        /** The part of each frame, along the scrolling direction, that moves (full-size pixels). */
        val regionFrom: Int,
        val regionTo: Int,
        /** The part across the scrolling direction that moves (all of it, unless only a block of the screen scrolls, like a table). */
        val crossFrom: Int,
        val crossTo: Int,
        val folder: File
    )

    /** The pictures made, in reading order. They are all [across] pixels wide (tall, if the scroll was sideways). */
    class Stitched(val files: List<File>, val horizontal: Boolean, val lengths: List<Int>, val across: Int, val jumps: Int)

    private const val MAX_PART_LENGTH = 20000
    private const val ANALYSIS_WIDTH = 216
    private const val MAX_ANALYSIS_FRAMES = 3000

    suspend fun analyze(context: Context, video: Uri, onStatus: (String) -> Unit): Result<Analysis> = withContext(Dispatchers.IO) {
        try {
            runCatching { analyzeBlocking(context, video, onStatus) }
        } finally {
            // The copy of the video and the small frames are not kept (the kept full-size frames are, in another folder).
            File(context.cacheDir, "scrollshot").deleteRecursively()
        }
    }

    suspend fun compose(context: Context, analysis: Analysis, excluded: Set<Int>, onStatus: (String) -> Unit): Result<Stitched> =
        withContext(Dispatchers.IO) { runCatching { composeBlocking(context, analysis, excluded, onStatus) } }

    /** Removes the frames kept for the review (call when the screen is left or a new video is chosen). */
    fun discard(analysis: Analysis?) {
        analysis?.folder?.deleteRecursively()
    }

    // ============================================================================================
    // Step one
    // ============================================================================================

    private class Track(
        val horizontal: Boolean,
        val sw: Int,
        val sh: Int,
        val top: Int,
        val bottom: Int,
        val columns: IntArray,
        val activeFrom: Int,
        val activeTo: Int,
        val offsets: IntArray,
        val segmentOf: IntArray,
        val score: Long
    )

    private fun analyzeBlocking(context: Context, video: Uri, onStatus: (String) -> Unit): Analysis {
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
            // --- 2. the direction: the content is followed up and down, and left and right; the better fit wins ---
            onStatus("Looking for the scrolling…")
            val vertical = track(file, n, aw, ah, horizontal = false) { onStatus("Following the scroll (up and down)… $it%") }
            val sideways = track(file, n, aw, ah, horizontal = true) { onStatus("Following the scroll (sideways)… $it%") }
            val chosen = if (sideways.score > vertical.score) sideways else vertical
            if (chosen.score <= 0L) error("I did not find any scrolling in this video")
            val horizontal = chosen.horizontal
            val offsets = chosen.offsets
            val segmentOf = chosen.segmentOf
            val regionRows = chosen.bottom - chosen.top

            // --- 3. keep a frame each time the screen moved about half its size ---
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

            // --- 4. the kept frames at full size (they stay for the review) ---
            onStatus("Taking ${kept.size} full-size pictures from the video…")
            val session = File(context.cacheDir, "scrollshot_session").apply { deleteRecursively(); mkdirs() }
            val select = kept.joinToString("+") { "eq(n,$it)" }
            run(listOf("-y", "-i", input, "-an", "-vf", "fps=$fpsText,select='$select'", "-fps_mode", "passthrough", "-q:v", "2", File(session, "f_%04d.jpg").absolutePath))
            val frameFiles = kept.indices.map { File(session, "f_%04d.jpg".format(it + 1)) }
            if (frameFiles.any { !it.exists() }) error("Couldn't take the pictures out of the video")

            // --- 5. exact positions at full size, sharpness, and cuts where frames do not fit ---
            onStatus("Making the positions exact…")
            val alongLength = if (horizontal) vw else vh
            val alongScale = if (horizontal) vw.toFloat() / aw else vh.toFloat() / ah
            val regionFrom = (chosen.top * alongScale).toInt().coerceIn(0, alongLength - 1)
            val regionTo = (chosen.bottom * alongScale).toInt().coerceIn(regionFrom + 1, alongLength)

            val groupOf = IntArray(kept.size)
            val alongOf = IntArray(kept.size)
            val sharpness = FloatArray(kept.size)
            var group = 0
            var previousLines: Array<ByteArray>? = null
            for ((k, frameIndex) in kept.withIndex()) {
                val lines = grayLines(frameFiles[k], horizontal, regionFrom, regionTo)
                sharpness[k] = gradientAlong(lines)
                if (k == 0 || segmentOf[frameIndex] != segmentOf[kept[k - 1]]) {
                    if (k > 0) group++
                    groupOf[k] = group
                    alongOf[k] = 0
                } else {
                    val predicted = ((offsets[frameIndex] - offsets[kept[k - 1]]) * alongScale).toInt()
                    val exact = exactShift(previousLines!!, lines, predicted)
                    if (exact == null) {
                        // Does not fit at full size: cut here too.
                        group++
                        groupOf[k] = group
                        alongOf[k] = 0
                    } else {
                        groupOf[k] = group
                        alongOf[k] = alongOf[k - 1] + exact
                    }
                }
                previousLines = lines
            }
            val sorted = sharpness.sorted()
            val median = sorted[sorted.size / 2]
            val frames = kept.indices.map { k ->
                Frame(frameFiles[k], groupOf[k], alongOf[k], sharpness[k], unclear = sharpness[k] < median * 0.6f)
            }
            // Only a block of the screen scrolled (a table in a page, a list in a window): then only that block is joined.
            val acrossLength = if (horizontal) vh else vw
            val acrossScale = if (horizontal) vh.toFloat() / ah else vw.toFloat() / aw
            val activeSpan = chosen.activeTo - chosen.activeFrom
            val localised = activeSpan < chosen.sw * 0.6f
            val crossFrom = if (localised) (chosen.activeFrom * acrossScale).toInt().coerceIn(0, acrossLength - 1) else 0
            val crossTo = if (localised) (chosen.activeTo * acrossScale).toInt().coerceIn(crossFrom + 1, acrossLength) else acrossLength
            return Analysis(frames, horizontal, vw, vh, regionFrom, regionTo, crossFrom, crossTo, session)
        }
    }

    /** Reads frame [i] of the small video; for a sideways scroll the frame is turned on its side, so the same code can follow it. */
    private fun readFrame(file: RandomAccessFile, i: Int, aw: Int, ah: Int, horizontal: Boolean, raw: ByteArray, into: ByteArray) {
        file.seek(i.toLong() * aw * ah)
        if (!horizontal) {
            file.readFully(into)
        } else {
            file.readFully(raw)
            for (y in 0 until ah) for (x in 0 until aw) into[x * ah + y] = raw[y * aw + x]
        }
    }

    /** Follows the content through every frame in one direction; the score says how much consistent scrolling was found. */
    private fun track(file: RandomAccessFile, n: Int, aw: Int, ah: Int, horizontal: Boolean, progress: (Int) -> Unit): Track {
        val sw = if (horizontal) ah else aw
        val sh = if (horizontal) aw else ah
        val frameBytes = aw * ah
        val raw = ByteArray(frameBytes)
        val a = ByteArray(frameBytes)
        val b = ByteArray(frameBytes)

        // Which rows never change while the screen moves (the fixed bars at the two ends).
        val rowSum = FloatArray(sh)
        val movingPair = BooleanArray(n)
        var moving = 0
        readFrame(file, 0, aw, ah, horizontal, raw, a)
        val row = FloatArray(sh)
        for (i in 1 until n) {
            readFrame(file, i, aw, ah, horizontal, raw, b)
            var total = 0L
            for (y in 0 until sh) {
                var s = 0
                val base = y * sw
                for (x in 0 until sw) s += abs((a[base + x].toInt() and 0xFF) - (b[base + x].toInt() and 0xFF))
                row[y] = s.toFloat() / sw
                total += s
            }
            if (total.toFloat() / frameBytes > 1.0f) {
                moving++
                movingPair[i] = true
                for (y in 0 until sh) rowSum[y] += row[y]
            }
            System.arraycopy(b, 0, a, 0, frameBytes)
        }
        val empty = Track(horizontal, sw, sh, 0, sh, IntArray(0), 0, sw, IntArray(n), IntArray(n), 0L)
        if (moving < 2) return empty
        var top = 0
        while (top < sh && rowSum[top] / moving < 0.5f) top++
        var bottom = sh
        while (bottom > 0 && rowSum[bottom - 1] / moving < 0.5f) bottom--
        if (bottom - top < sh / 4) { top = 0; bottom = sh }

        // Which columns never change either (a floating button, a fixed side bar): they are left out of the matching.
        val colSum = FloatArray(sw)
        readFrame(file, 0, aw, ah, horizontal, raw, a)
        for (i in 1 until n) {
            readFrame(file, i, aw, ah, horizontal, raw, b)
            if (movingPair[i]) {
                for (x in 0 until sw) {
                    var s = 0
                    for (y in top until bottom) s += abs((a[y * sw + x].toInt() and 0xFF) - (b[y * sw + x].toInt() and 0xFF))
                    colSum[x] += s.toFloat() / (bottom - top)
                }
            }
            System.arraycopy(b, 0, a, 0, frameBytes)
        }
        var active = (0 until sw).filter { colSum[it] / moving >= 0.5f }
        if (active.size < sw / 4) active = (0 until sw).toList()
        val columns = active.filterIndexed { index, _ -> index % 3 == 0 }.toIntArray()

        // How far the screen moved between each two frames.
        val offsets = IntArray(n)
        val segmentOf = IntArray(n)
        var segment = 0
        var score = 0L
        val regionRows = bottom - top
        val maxShift = (regionRows * 0.8f).toInt()
        val minOverlap = (regionRows * 0.2f).toInt()
        readFrame(file, 0, aw, ah, horizontal, raw, a)
        for (i in 1 until n) {
            if (i % 20 == 0) progress(i * 100 / n)
            readFrame(file, i, aw, ah, horizontal, raw, b)
            if (regionDifference(a, b, sw, top, bottom, columns) < 0.6f) {
                offsets[i] = offsets[i - 1]
            } else {
                val found = bestShift(a, b, sw, top, bottom, maxShift, minOverlap, columns)
                if (found != null) {
                    offsets[i] = offsets[i - 1] + found
                    score += abs(found)
                } else {
                    // Could not be matched: the picture is cut here and a new one starts.
                    segment++
                    offsets[i] = 0
                }
            }
            segmentOf[i] = segment
            System.arraycopy(b, 0, a, 0, frameBytes)
        }
        return Track(horizontal, sw, sh, top, bottom, columns, active.first(), active.last() + 1, offsets, segmentOf, score)
    }

    private fun run(arguments: List<String>) {
        val session = FFmpegKit.executeWithArguments(arguments.toTypedArray())
        if (!ReturnCode.isSuccess(session.returnCode)) {
            throw IOException("The video could not be read (" + (session.failStackTrace ?: session.output).orEmpty().takeLast(200) + ")")
        }
    }

    /** Average change between two frames in the moving part (0 = identical), sampling every second row. */
    private fun regionDifference(a: ByteArray, b: ByteArray, width: Int, top: Int, bottom: Int, columns: IntArray): Float {
        if (columns.isEmpty()) return 0f
        var sum = 0L
        var count = 0
        var y = top
        while (y < bottom) {
            val base = y * width
            for (x in columns) {
                sum += abs((a[base + x].toInt() and 0xFF) - (b[base + x].toInt() and 0xFF))
                count++
            }
            y += 2
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    /**
     * How many rows the content of [b] moved up compared with [a] (negative: down), or null when the two
     * do not match clearly. A match is clear when the best fit is far better than a typical wrong one.
     */
    private fun bestShift(a: ByteArray, b: ByteArray, width: Int, top: Int, bottom: Int, maxShift: Int, minOverlap: Int, columns: IntArray): Int? {
        if (columns.isEmpty()) return null
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
                val baseA = (if (dy >= 0) top + dy + r else top + r) * width
                val baseB = (if (dy >= 0) top + r else top - dy + r) * width
                for (x in columns) {
                    sum += abs((a[baseA + x].toInt() and 0xFF) - (b[baseB + x].toInt() and 0xFF))
                    count++
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

    /**
     * The lines of a kept frame along the scrolling direction (rows for an up-and-down scroll, columns for
     * a sideways one), only the moving part, with every 4th pixel of each line, as grey values.
     */
    private fun grayLines(file: File, horizontal: Boolean, from: Int, to: Int): Array<ByteArray> {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?: throw IOException("Couldn't open a picture from the video")
        try {
            val lineCount = to - from
            if (!horizontal) {
                val pixels = IntArray(bitmap.width)
                val samples = (bitmap.width + 3) / 4
                val lines = Array(lineCount) { ByteArray(samples) }
                for (y in from until min(to, bitmap.height)) {
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, y, bitmap.width, 1)
                    var c = 0
                    var x = 0
                    while (x < bitmap.width) {
                        lines[y - from][c++] = luminance(pixels[x]).toByte()
                        x += 4
                    }
                }
                return lines
            }
            val pixels = IntArray(bitmap.height)
            val samples = (bitmap.height + 3) / 4
            val lines = Array(lineCount) { ByteArray(samples) }
            for (x in from until min(to, bitmap.width)) {
                bitmap.getPixels(pixels, 0, 1, x, 0, 1, bitmap.height)
                var c = 0
                var y = 0
                while (y < bitmap.height) {
                    lines[x - from][c++] = luminance(pixels[y]).toByte()
                    y += 4
                }
            }
            return lines
        } finally {
            bitmap.recycle()
        }
    }

    private fun luminance(p: Int): Int = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8

    /** How sharp a frame is: the average change from one line to the next along the scrolling direction (motion blur smooths it out). */
    private fun gradientAlong(lines: Array<ByteArray>): Float {
        if (lines.size < 2) return 0f
        var sum = 0L
        var count = 0
        for (l in 0 until lines.size - 1 step 2) {
            val a = lines[l]
            val b = lines[l + 1]
            for (s in a.indices) {
                sum += abs((a[s].toInt() and 0xFF) - (b[s].toInt() and 0xFF))
                count++
            }
        }
        return if (count == 0) 0f else sum.toFloat() / count
    }

    /** The exact full-size shift of [b] against [a], searched around [predicted]; null when it does not fit well. */
    private fun exactShift(a: Array<ByteArray>, b: Array<ByteArray>, predicted: Int): Int? {
        val lines = a.size
        val samples = a[0].size
        var best = Float.MAX_VALUE
        var bestShift = predicted
        for (dy in predicted - 14..predicted + 14) {
            val overlap = lines - abs(dy)
            if (overlap < 60) continue
            var sum = 0L
            var count = 0
            var r = 0
            while (r < overlap) {
                val lineA = a[if (dy >= 0) dy + r else r]
                val lineB = b[if (dy >= 0) r else -dy + r]
                for (x in 0 until samples) {
                    sum += abs((lineA[x].toInt() and 0xFF) - (lineB[x].toInt() and 0xFF))
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

    // ============================================================================================
    // Step two
    // ============================================================================================

    private class Strip(val frame: Frame, val from: Int, val to: Int)

    private fun composeBlocking(context: Context, analysis: Analysis, excluded: Set<Int>, onStatus: (String) -> Unit): Stitched {
        val horizontal = analysis.horizontal
        val regionLength = analysis.regionTo - analysis.regionFrom
        val across = analysis.crossTo - analysis.crossFrom
        val included = analysis.frames.withIndex().filter { it.index !in excluded }.map { it.value }
        if (included.isEmpty()) error("Every picture was removed: keep at least one")

        val outDir = File(context.filesDir, "scrollshots").apply { mkdirs() }
        val stamp = System.currentTimeMillis()
        val files = mutableListOf<File>()
        val lengths = mutableListOf<Int>()
        var partNumber = 0
        var groupsUsed = 0

        for ((_, members) in included.groupBy { it.group }.toSortedMap()) {
            groupsUsed++
            val ordered = members.sortedBy { it.along }
            // Each frame owns the strip up to the middle of what it shares with its neighbours; strips are joined one after the other.
            val strips = ordered.mapIndexed { index, frame ->
                val start = frame.along
                val end = frame.along + regionLength
                val ownFrom = if (index == 0) start else ((ordered[index - 1].along + regionLength + start) / 2).coerceIn(start, end)
                val ownTo = if (index == ordered.lastIndex) end else ((end + ordered[index + 1].along) / 2).coerceIn(start, end)
                Strip(frame, analysis.regionFrom + (ownFrom - start), analysis.regionFrom + (ownTo - start))
            }.filter { it.to > it.from }
            val groupLength = strips.sumOf { it.to - it.from }
            var partStart = 0
            while (partStart < groupLength) {
                val partEnd = min(groupLength, partStart + MAX_PART_LENGTH)
                onStatus("Joining the pictures… part ${partNumber + 1}")
                val bitmap = if (horizontal) Bitmap.createBitmap(partEnd - partStart, across, Bitmap.Config.RGB_565)
                else Bitmap.createBitmap(across, partEnd - partStart, Bitmap.Config.RGB_565)
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)
                var position = 0
                for (strip in strips) {
                    val length = strip.to - strip.from
                    val from = max(position, partStart)
                    val to = min(position + length, partEnd)
                    if (to > from) {
                        val frame = BitmapFactory.decodeFile(strip.frame.file.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
                        if (frame != null) {
                            val srcFrom = strip.from + (from - position)
                            val srcTo = srcFrom + (to - from)
                            if (horizontal) {
                                canvas.drawBitmap(frame, Rect(srcFrom, analysis.crossFrom, srcTo, analysis.crossTo), Rect(from - partStart, 0, to - partStart, across), null)
                            } else {
                                canvas.drawBitmap(frame, Rect(analysis.crossFrom, srcFrom, analysis.crossTo, srcTo), Rect(0, from - partStart, across, to - partStart), null)
                            }
                            frame.recycle()
                        }
                    }
                    position += length
                }
                partNumber++
                val file = File(outDir, "scroll_${stamp}_$partNumber.jpg")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                files += file
                lengths += partEnd - partStart
                bitmap.recycle()
                partStart = partEnd
            }
        }
        return Stitched(files, horizontal, lengths, across, groupsUsed - 1)
    }
}
