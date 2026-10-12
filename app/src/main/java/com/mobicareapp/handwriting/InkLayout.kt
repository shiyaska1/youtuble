package com.mobicareapp.handwriting

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Works out which strokes belong together as one piece of writing, and at what angle it runs —
 * with no ruled lines to lean on. The writer can write horizontally, diagonally, even up or down the
 * page; this follows the writing stroke by stroke, the way a person's eye would, and the angle it
 * finds is used to turn that piece level before it is read.
 *
 * A "run" is a stretch of writing that carries on in one direction (a line of text at whatever
 * angle). Strokes are taken in the order they were written: a new stroke continues the current run
 * when it lands a little further along the run's direction and not too far off to the side, and
 * starts a new run when it doesn't (the start of the next line, or writing somewhere else).
 * Angles are in radians in screen coordinates (y points down), so 0 is left-to-right.
 */
object InkLayout {

    /** One stroke or completed word, with the numbers needed to place it in a run. */
    class Element(val item: PageItem) {
        val centreX: Float
        val centreY: Float
        /** The larger of its width and height — how big this piece of writing is. */
        val size: Float

        init {
            when (item) {
                is InkItem -> {
                    val s = item.stroke
                    centreX = (s.minX + s.maxX) / 2f
                    centreY = (s.minY + s.maxY) / 2f
                    size = max(s.maxX - s.minX, s.maxY - s.minY)
                }
                is WordItem -> {
                    centreX = item.centreX
                    centreY = item.centreY
                    size = item.size
                }
            }
        }

        val stroke: HwStroke? get() = (item as? InkItem)?.stroke
    }

    /** How big this person writes: the median size of the pieces, ignoring dots and tiny marks. */
    fun typicalSize(elements: List<Element>): Float {
        val sizes = elements.map { it.size }.filter { it > 0.02f }.sorted()
        return if (sizes.isEmpty()) 0.05f else sizes[sizes.size / 2]
    }

    /** The direction the writing runs, from the line through the pieces' centres (0 when there's too little to tell). */
    fun direction(elements: List<Element>): Float {
        if (elements.size < 2) return 0f
        val n = elements.size
        val meanX = elements.sumOf { it.centreX.toDouble() } / n
        val meanY = elements.sumOf { it.centreY.toDouble() } / n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (e in elements) {
            val dx = e.centreX - meanX
            val dy = e.centreY - meanY
            sxx += dx * dx
            sxy += dx * dy
            syy += dy * dy
        }
        var phi = (0.5 * atan2(2 * sxy, sxx - syy)).toFloat()
        // The axis has two ends; the writing runs from the first piece towards the last.
        val towardsEnd = (elements.last().centreX - elements.first().centreX) * cos(phi) +
            (elements.last().centreY - elements.first().centreY) * sin(phi)
        if (towardsEnd < 0) phi += PI.toFloat()
        // Too little spread to tell a direction (a couple of letters close together): assume level.
        val spread = elements.maxOf { it.centreX * cos(phi) + it.centreY * sin(phi) } -
            elements.minOf { it.centreX * cos(phi) + it.centreY * sin(phi) }
        if (spread < 0.8f * typicalSize(elements)) return 0f
        val normalised = if (phi > PI) phi - 2 * PI.toFloat() else phi
        // Within about 8 degrees of level counts as level, so slightly wobbly lines aren't rotated at all.
        return if (abs(normalised) < 0.14f) 0f else normalised
    }

    /** Splits [items] (in writing order) into runs of writing. */
    fun buildRuns(items: List<PageItem>): List<List<Element>> {
        val runs = mutableListOf<MutableList<Element>>()
        for (item in items) {
            val element = Element(item)
            val run = runs.lastOrNull()
            if (run != null && continues(run, element)) run += element else runs += mutableListOf(element)
        }
        return runs
    }

    /** Does [next] carry on the writing in [run]? */
    private fun continues(run: List<Element>, next: Element): Boolean {
        val size = typicalSize(run + next)
        val phi = direction(run)
        val dx = next.centreX - run.last().centreX
        val dy = next.centreY - run.last().centreY
        val along = dx * cos(phi) + dy * sin(phi)
        val lateral = -dx * sin(phi) + dy * cos(phi)
        // A little backwards is fine (the dot of an "i", the cross of a "t", letters that overlap);
        // up to about two letters forwards covers the space between words; sideways must stay near the line.
        return along >= -1.0f * size && along <= 2.4f * size && abs(lateral) <= 1.0f * size
    }

    /** [strokes] turned by -[angle] about their own centre, so writing that ran at [angle] now runs level. */
    fun levelled(strokes: List<HwStroke>, angle: Float): List<HwStroke> {
        if (angle == 0f) return strokes
        val all = strokes.flatMap { it.points }
        val cx = all.map { it.x }.average().toFloat()
        val cy = all.map { it.y }.average().toFloat()
        val c = cos(angle)
        val s = sin(angle)
        return strokes.map { stroke ->
            HwStroke(stroke.points.map { p ->
                val dx = p.x - cx
                val dy = p.y - cy
                InkPt(cx + dx * c + dy * s, cy - dx * s + dy * c, p.t)
            })
        }
    }

    /** The word being written right now: its strokes, and the angle it runs at. */
    class CurrentWord(val strokes: List<HwStroke>, val angle: Float)

    /**
     * The newest strokes that make up one word: the end of the latest run, going backwards until
     * there's a word-sized gap (measured along the writing, and scaled to how big this person
     * writes) or a word that was already completed from a suggestion.
     */
    fun currentWord(items: List<PageItem>): CurrentWord? {
        val run = buildRuns(items).lastOrNull() ?: return null
        val angle = direction(run)
        val trailing = run.takeLastWhile { it.item is InkItem }
        if (trailing.isEmpty()) return null
        val c = cos(angle)
        val s = sin(angle)
        val gapLimit = max(0.02f, 0.9f * typicalSize(trailing))

        val word = mutableListOf<HwStroke>()
        var wordMin = Float.MAX_VALUE
        var wordMax = -Float.MAX_VALUE
        for (element in trailing.asReversed()) {
            val stroke = element.stroke ?: break
            val projections = stroke.points.map { it.x * c + it.y * s }
            val pMin = projections.min()
            val pMax = projections.max()
            if (word.isNotEmpty()) {
                val gap = if (pMax < wordMin) wordMin - pMax else if (pMin > wordMax) pMin - wordMax else 0f
                if (gap > gapLimit) break
            }
            word += stroke
            wordMin = min(wordMin, pMin)
            wordMax = max(wordMax, pMax)
        }
        return CurrentWord(word.asReversed(), angle)
    }
}
