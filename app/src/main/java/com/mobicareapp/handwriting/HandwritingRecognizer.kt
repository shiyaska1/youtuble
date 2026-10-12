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

    /**
     * Reads everything on the page, in the order it was written, one run of writing per line of the
     * result. Each run is turned level first (see [InkLayout]), so it makes no difference whether it
     * was written straight across, at a slant, or up the page.
     */
    suspend fun recognize(items: List<PageItem>): String {
        if (items.isEmpty()) return ""
        val lines = InkLayout.buildRuns(items).map { run -> readRun(run) }
        return lines.filter { it.isNotBlank() }.joinToString("\n")
    }

    /** The recognizer's best guesses (most likely first) for one partly written word — used to offer completions. */
    suspend fun candidatesFor(word: InkLayout.CurrentWord): List<String> {
        if (word.strokes.isEmpty()) return emptyList()
        val strokes = InkLayout.levelled(word.strokes, word.angle)
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

    /** One run of writing: stretches of handwriting are read (turned level first), completed words are inserted as they are. */
    private suspend fun readRun(run: List<InkLayout.Element>): String {
        val angle = InkLayout.direction(run)
        val parts = mutableListOf<String>()
        val pending = mutableListOf<HwStroke>()

        suspend fun flush() {
            if (pending.isEmpty()) return
            val strokes = InkLayout.levelled(pending.toList(), angle)
            val top = strokes.minOf { it.minY } * SCALE
            val bottom = strokes.maxOf { it.maxY } * SCALE
            val context = RecognitionContext.builder()
                .setWritingArea(WritingArea(SCALE, (bottom - top).coerceAtLeast(20f)))
                .build()
            val text = client().recognize(buildInk(strokes), context).await().candidates.firstOrNull()?.text.orEmpty()
            if (text.isNotBlank()) parts += text
            pending.clear()
        }

        for (element in run) {
            val item = element.item
            if (item is WordItem) {
                flush()
                parts += item.text
            } else {
                element.stroke?.let { pending += it }
            }
        }
        flush()
        return parts.joinToString(" ")
    }

    /** The page is drawn in units of its width; the recognizer wants bigger numbers than 0..1. */
    private const val SCALE = 1000f

}
