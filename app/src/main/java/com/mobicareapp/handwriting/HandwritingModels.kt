package com.mobicareapp.handwriting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One touch point of a stroke. [x] and [y] are both relative to the page WIDTH (so the drawing
 * keeps its shape on any screen size, and a page taller than it is wide just has y above 1), and
 * [t] is the touch time in milliseconds — the handwriting recognizer reads the speed and order
 * of writing, not just the final picture.
 */
data class InkPt(val x: Float, val y: Float, val t: Long)

data class HwStroke(val points: List<InkPt>) {
    val minX get() = points.minOf { it.x }
    val maxX get() = points.maxOf { it.x }
    val minY get() = points.minOf { it.y }
    val maxY get() = points.maxOf { it.y }
}

/**
 * Something on a page, in the order it was added. Items put on the page together by one action
 * (inserting a saved snippet) share a non-zero [group], so Undo and Redo move them as one.
 */
sealed interface PageItem {
    val group: Int
}

data class InkItem(val stroke: HwStroke, override val group: Int = 0) : PageItem

/**
 * A word the writer completed by tapping a suggestion instead of writing all of it. It is drawn in
 * a handwriting-style font where the unfinished word was, running at the same [angle] as the
 * writing around it; [replaced] keeps the strokes it stands in for, so Undo can give the
 * half-written word back. ([x], [y]) is where the word's baseline starts, [size] its font size, all
 * in page widths; [angle] is in radians, 0 meaning left to right.
 */
data class WordItem(
    val text: String,
    val x: Float,
    val y: Float,
    val size: Float,
    val replaced: List<HwStroke>,
    val angle: Float = 0f,
    override val group: Int = 0
) : PageItem {
    /** Rough width, only used to tell where this word sits among neighbouring handwriting. */
    val width: Float get() = text.length * size * 0.5f

    /** Roughly the middle of the word on the page. */
    val centreX: Float get() = x + kotlin.math.cos(angle) * width / 2f + kotlin.math.sin(angle) * size * 0.4f
    val centreY: Float get() = y + kotlin.math.sin(angle) * width / 2f - kotlin.math.cos(angle) * size * 0.4f
}

/**
 * One notepad page. [redo] holds items removed by Undo so Redo can bring them back (cleared as
 * soon as something new is written). [text] is what was read from the page; once the user edits it
 * by hand ([textEdited]) it's left alone instead of being overwritten by the next automatic
 * reading, until they ask to re-read the handwriting. [imagePath] is a photo inserted from the
 * gallery, fitted to the page behind the writing.
 */
data class HwPage(
    val items: List<PageItem> = emptyList(),
    val redo: List<PageItem> = emptyList(),
    val text: String = "",
    val textEdited: Boolean = false,
    val imagePath: String? = null
) {
    /** The lowest point written on the page, in page widths — used to tell when the page is full. */
    val lowestY: Float
        get() = items.maxOfOrNull { item ->
            when (item) {
                is InkItem -> item.stroke.maxY
                is WordItem -> maxOf(item.y, item.centreY) + item.size * 0.6f
            }
        } ?: 0f

    /** The highest point written on the page, in page widths. */
    val highestY: Float
        get() = items.minOfOrNull { item ->
            when (item) {
                is InkItem -> item.stroke.minY
                is WordItem -> minOf(item.y - item.size, item.centreY - item.size)
            }
        } ?: 0f
}

/** A saved piece of handwriting (a sentence, a signature…) that can be inserted into any page later by searching its title. */
data class HwSnippet(val id: Long, val title: String, val createdAt: Long, val items: List<PageItem>, val text: String)

/** Keeps the notepad on disk after every change, so many pages of handwriting survive the app being closed or the phone running low on memory. */
object HandwritingStore {
    private const val FILE_NAME = "handwriting_draft.json"
    private const val SNIPPETS_FILE = "handwriting_snippets.json"
    private const val PREFS = "handwriting_prefs"
    private const val KEY_AUTO_FIX = "auto_fix"
    private const val KEY_OPEN_AT_STARTUP = "open_at_startup"
    private const val KEY_PAGE_RATIO = "page_ratio"

    /** Every page's height divided by its width. Fixed once measured (and only ever made taller), so pages keep one shape whichever way the phone is held. */
    fun pageRatio(context: Context): Float =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_PAGE_RATIO, 1.8f)

    fun setPageRatio(context: Context, ratio: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(KEY_PAGE_RATIO, ratio).apply()
    }

    fun isAutoFixEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_FIX, true)

    fun setAutoFixEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_FIX, enabled).apply()
    }

    /** Whether the handwriting notepad opens by itself when the app is launched (on by default: it is meant for quick notes). */
    fun isOpenAtStartup(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OPEN_AT_STARTUP, true)

    fun setOpenAtStartup(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OPEN_AT_STARTUP, enabled).apply()
    }

    // --- pages -----------------------------------------------------------------------------

    fun load(context: Context): List<HwPage> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return listOf(HwPage())
        return runCatching { pagesFromJson(JSONArray(file.readText())) }.getOrDefault(listOf(HwPage()))
    }

    fun save(context: Context, pages: List<HwPage>) {
        File(context.filesDir, FILE_NAME).writeText(pagesToJson(pages).toString())
    }

    /** Pages as JSON — shared by the notepad's own draft and by saved notes. */
    fun pagesToJson(pages: List<HwPage>): JSONArray {
        val array = JSONArray()
        pages.forEach { page ->
            array.put(
                JSONObject()
                    .put("items", itemsToJson(page.items))
                    .put("text", page.text)
                    .put("edited", page.textEdited)
                    .put("img", page.imagePath ?: "")
            )
        }
        return array
    }

    fun pagesFromJson(pages: JSONArray): List<HwPage> =
        (0 until pages.length()).map { i ->
            val obj = pages.getJSONObject(i)
            HwPage(
                items = itemsFromJson(obj.getJSONArray("items")),
                text = obj.optString("text"),
                textEdited = obj.optBoolean("edited"),
                imagePath = obj.optString("img").takeIf { it.isNotEmpty() && File(it).exists() }
            )
        }.ifEmpty { listOf(HwPage()) }

    // --- saved snippets --------------------------------------------------------------------

    fun loadSnippets(context: Context): List<HwSnippet> {
        val file = File(context.filesDir, SNIPPETS_FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HwSnippet(
                    id = obj.getLong("id"),
                    title = obj.getString("title"),
                    createdAt = obj.getLong("createdAt"),
                    items = itemsFromJson(obj.getJSONArray("items")),
                    text = obj.optString("text")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveSnippets(context: Context, snippets: List<HwSnippet>) {
        val array = JSONArray()
        snippets.forEach { s ->
            array.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("createdAt", s.createdAt)
                    .put("items", itemsToJson(s.items))
                    .put("text", s.text)
            )
        }
        File(context.filesDir, SNIPPETS_FILE).writeText(array.toString())
    }

    // --- items -----------------------------------------------------------------------------

    private fun itemsToJson(items: List<PageItem>): JSONArray = JSONArray().also { arr -> items.forEach { arr.put(itemToJson(it)) } }

    private fun itemsFromJson(array: JSONArray): List<PageItem> = (0 until array.length()).map { itemFromJson(array.getJSONObject(it)) }

    private fun itemToJson(item: PageItem): JSONObject = when (item) {
        is InkItem -> JSONObject().put("k", "s").put("p", strokeToJson(item.stroke)).put("g", item.group)
        is WordItem -> JSONObject()
            .put("k", "w")
            .put("t", item.text)
            .put("x", item.x.toDouble())
            .put("y", item.y.toDouble())
            .put("z", item.size.toDouble())
            .put("a", item.angle.toDouble())
            .put("g", item.group)
            .put("r", JSONArray().also { arr -> item.replaced.forEach { arr.put(strokeToJson(it)) } })
    }

    private fun itemFromJson(obj: JSONObject): PageItem =
        if (obj.getString("k") == "w") {
            val replaced = obj.getJSONArray("r")
            WordItem(
                text = obj.getString("t"),
                x = obj.getDouble("x").toFloat(),
                y = obj.getDouble("y").toFloat(),
                size = obj.getDouble("z").toFloat(),
                angle = obj.optDouble("a", 0.0).toFloat(),
                replaced = (0 until replaced.length()).map { strokeFromJson(replaced.getJSONArray(it)) },
                group = obj.optInt("g", 0)
            )
        } else {
            InkItem(strokeFromJson(obj.getJSONArray("p")), obj.optInt("g", 0))
        }

    private fun strokeToJson(stroke: HwStroke): JSONArray {
        val flat = JSONArray()
        stroke.points.forEach { p ->
            // 4 decimals is far finer than a pixel on any screen, and keeps the file small.
            flat.put(Math.round(p.x * 10000) / 10000.0)
            flat.put(Math.round(p.y * 10000) / 10000.0)
            flat.put(p.t)
        }
        return flat
    }

    private fun strokeFromJson(flat: JSONArray): HwStroke =
        HwStroke((0 until flat.length() / 3).map { p ->
            InkPt(flat.getDouble(p * 3).toFloat(), flat.getDouble(p * 3 + 1).toFloat(), flat.getLong(p * 3 + 2))
        })
}

/** Moves page items around: used to drop a saved snippet onto a page below what's already written. */
object ItemTransform {
    /** [items] shifted down by [dy] page widths, with all their touch times moved by [dt], and tagged with [group] so they undo together. */
    fun shifted(items: List<PageItem>, dy: Float, dt: Long, group: Int): List<PageItem> {
        fun stroke(s: HwStroke) = HwStroke(s.points.map { InkPt(it.x, it.y + dy, it.t + dt) })
        return items.map { item ->
            when (item) {
                is InkItem -> InkItem(stroke(item.stroke), group)
                is WordItem -> item.copy(y = item.y + dy, replaced = item.replaced.map { stroke(it) }, group = group)
            }
        }
    }

    fun firstTime(items: List<PageItem>): Long = items.minOfOrNull { item ->
        when (item) {
            is InkItem -> item.stroke.points.first().t
            is WordItem -> item.replaced.firstOrNull()?.points?.first()?.t ?: 0L
        }
    } ?: 0L

    fun lastTime(items: List<PageItem>): Long = items.maxOfOrNull { item ->
        when (item) {
            is InkItem -> item.stroke.points.last().t
            is WordItem -> item.replaced.lastOrNull()?.points?.last()?.t ?: 0L
        }
    } ?: 0L
}
