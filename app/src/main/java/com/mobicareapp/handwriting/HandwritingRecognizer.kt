package com.mobicareapp.handwriting

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionContext
import com.google.mlkit.vision.digitalink.WritingArea
import kotlinx.coroutines.tasks.await
import kotlin.math.abs

/**
 * Turns handwriting strokes into English text with Google's on-device ML Kit Digital Ink
 * recognizer. Its small English model downloads once (internet needed the first time only).
 *
 * The recognizer is built for a line or a few words at a time, not a whole page, so a page is
 * first split into lines by how far apart the strokes are vertically, and each line is read on its
 * own. Words the writer completed by tapping a suggestion (see [WordItem]) have no strokes to read:
 * they are slotted into the line, in left-to-right order, as the text they already are.
 */
object HandwritingRecognizer {
    private val model: DigitalInkRecognitionModel by lazy {
        val identifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
            ?: error("English handwriting isn't available on this phone")
        DigitalInkRecognitionModel.builder(identifier).build()
    }

    @Volatile private var recognizer: DigitalInkRecognizer? = null

    suspend fun isModelReady(): Boolean = RemoteModelManager.getInstance().isModelDownloaded(model).await()

    /** Downloads the English model if it isn't on the phone yet (a few MB, once). */
    suspend fun ensureModel() {
        if (!isModelReady()) {
            RemoteModelManager.getInstance().download(model, DownloadConditions.Builder().build()).await()
        }
    }

    private fun client(): DigitalInkRecognizer =
        recognizer ?: DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build()).also { recognizer = it }

    /** Reads every line of [items], top to bottom, and returns them joined with line breaks. */
    suspend fun recognize(items: List<PageItem>): String {
        if (items.isEmpty()) return ""
        val texts = groupIntoLines(items).map { line -> readLine(line) }
        return texts.filter { it.isNotBlank() }.joinToString("\n")
    }

    /** The recognizer's best guesses (most likely first) for one partly written word — used to offer completions. */
    suspend fun candidatesFor(strokes: List<HwStroke>): List<String> {
        if (strokes.isEmpty()) return emptyList()
        val ink = buildInk(strokes)
        val width = (strokes.maxOf { it.maxX } - strokes.minOf { it.minX }) * SCALE
        val height = (strokes.maxOf { it.maxY } - strokes.minOf { it.minY }) * SCALE
        val context = RecognitionContext.builder()
            .setWritingArea(WritingArea(width.coerceAtLeast(20f), height.coerceAtLeast(20f)))
            .build()
        return client().recognize(ink, context).await().candidates.map { it.text }.take(3)
    }

    private fun buildInk(strokes: List<HwStroke>): Ink = Ink.builder().apply {
        strokes.forEach { stroke ->
            addStroke(
                Ink.Stroke.builder().apply {
                    stroke.points.forEach { p -> addPoint(Ink.Point.create(p.x * SCALE, p.y * SCALE, p.t)) }
                }.build()
            )
        }
    }.build()

    /** One line, left to right: runs of handwriting are read, suggestion-completed words are inserted as they are. */
    private suspend fun readLine(line: List<Entry>): String {
        val parts = mutableListOf<String>()
        val run = mutableListOf<Entry>()

        suspend fun flushRun() {
            if (run.isEmpty()) return
            // The recognizer needs the strokes in the order they were written, not left to right.
            val strokes = run.sortedBy { it.order }.map { (it.item as InkItem).stroke }
            val top = strokes.minOf { it.minY } * SCALE
            val bottom = strokes.maxOf { it.maxY } * SCALE
            val context = RecognitionContext.builder()
                .setWritingArea(WritingArea(SCALE, (bottom - top).coerceAtLeast(20f)))
                .build()
            val text = client().recognize(buildInk(strokes), context).await().candidates.firstOrNull()?.text.orEmpty()
            if (text.isNotBlank()) parts += text
            run.clear()
        }

        for (entry in line.sortedBy { it.centreX }) {
            if (entry.item is WordItem) {
                flushRun()
                parts += entry.item.text
            } else {
                run += entry
            }
        }
        flushRun()
        return parts.joinToString(" ")
    }

    private class Entry(val item: PageItem, val order: Int, val centreX: Float, val centreY: Float)

    /**
     * Items whose vertical centres are close belong to one line. Lines are returned top to bottom.
     */
    private fun groupIntoLines(items: List<PageItem>): List<List<Entry>> {
        val entries = items.mapIndexed { index, item ->
            when (item) {
                is InkItem -> Entry(item, index, (item.stroke.minX + item.stroke.maxX) / 2f, (item.stroke.minY + item.stroke.maxY) / 2f)
                is WordItem -> Entry(item, index, item.x + item.width / 2f, item.centreY)
            }
        }.sortedBy { it.centreY }

        val lines = mutableListOf<MutableList<Entry>>()
        for (entry in entries) {
            val line = lines.lastOrNull()
            val lineCentre = line?.map { it.centreY }?.average()?.toFloat()
            if (line != null && lineCentre != null && abs(entry.centreY - lineCentre) <= LINE_GAP) line += entry
            else lines += mutableListOf(entry)
        }
        return lines
    }

    /** The page is drawn in units of its width; the recognizer wants bigger numbers than 0..1. */
    private const val SCALE = 1000f

    /** How far (in page widths) an item's centre may drift from its line's average and still be the same line. */
    private const val LINE_GAP = 0.045f
}
