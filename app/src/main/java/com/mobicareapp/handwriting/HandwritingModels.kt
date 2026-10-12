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

/** Something on a page, in the order it was added. */
sealed interface PageItem

data class InkItem(val stroke: HwStroke) : PageItem

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
    val angle: Float = 0f
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
 * reading, until they ask to re-read the handwriting.
 */
data class HwPage(
    val items: List<PageItem> = emptyList(),
    val redo: List<PageItem> = emptyList(),
    val text: String = "",
    val textEdited: Boolean = false
) {
    /** The lowest point written on the page, in page widths — used to tell when the page is full. */
    val lowestY: Float
        get() = items.maxOfOrNull { item ->
            when (item) {
                is InkItem -> item.stroke.maxY
                is WordItem -> maxOf(item.y, item.centreY) + item.size * 0.6f
            }
        } ?: 0f
}

/** Keeps the notepad on disk after every change, so many pages of handwriting survive the app being closed or the phone running low on memory. */
object HandwritingStore {
    private const val FILE_NAME = "handwriting_draft.json"
    private const val PREFS = "handwriting_prefs"
    private const val KEY_AUTO_FIX = "auto_fix"

    fun isAutoFixEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_FIX, true)

    fun setAutoFixEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO_FIX, enabled).apply()
    }

    fun load(context: Context): List<HwPage> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return listOf(HwPage())
        return runCatching {
            val pages = JSONArray(file.readText())
            (0 until pages.length()).map { i ->
                val obj = pages.getJSONObject(i)
                val itemsJson = obj.getJSONArray("items")
                val items = (0 until itemsJson.length()).map { n -> itemFromJson(itemsJson.getJSONObject(n)) }
                HwPage(items = items, text = obj.optString("text"), textEdited = obj.optBoolean("edited"))
            }.ifEmpty { listOf(HwPage()) }
        }.getOrDefault(listOf(HwPage()))
    }

    fun save(context: Context, pages: List<HwPage>) {
        val array = JSONArray()
        pages.forEach { page ->
            val items = JSONArray()
            page.items.forEach { items.put(itemToJson(it)) }
            array.put(JSONObject().put("items", items).put("text", page.text).put("edited", page.textEdited))
        }
        File(context.filesDir, FILE_NAME).writeText(array.toString())
    }

    private fun itemToJson(item: PageItem): JSONObject = when (item) {
        is InkItem -> JSONObject().put("k", "s").put("p", strokeToJson(item.stroke))
        is WordItem -> JSONObject()
            .put("k", "w")
            .put("t", item.text)
            .put("x", item.x.toDouble())
            .put("y", item.y.toDouble())
            .put("z", item.size.toDouble())
            .put("a", item.angle.toDouble())
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
                replaced = (0 until replaced.length()).map { strokeFromJson(replaced.getJSONArray(it)) }
            )
        } else {
            InkItem(strokeFromJson(obj.getJSONArray("p")))
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
