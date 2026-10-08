package com.mobicareapp.passport

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a hand-drawn stroke does to the passport photo:
 * - [ERASE] turns the touched area into background (fixes leftover background the automatic
 *   cut-out kept, e.g. around the hair or shoulders),
 * - [RESTORE] does the opposite — brings the original photo back where the cut-out removed too much,
 * - [BRIGHTEN] lightens just the touched part of the person (a shadowed side of the face, say)
 *   without changing the background.
 */
enum class BrushTool { ERASE, RESTORE, BRIGHTEN }

/**
 * Strokes are stored in coordinates relative to the finished 35x45 photo (0..1 across and down),
 * and the size as a fraction of its width — not in pixels — so they stay in the right place no
 * matter what resolution the photo is rendered at, and survive changing the background/filter
 * afterwards (each reprocess just replays them).
 */
data class PhotoStroke(
    val tool: BrushTool,
    /** Brush diameter as a fraction of the photo's width. */
    val diameter: Float,
    /** 0..1 — only meaningful for [BrushTool.BRIGHTEN]; erase/restore are always full strength. */
    val strength: Float,
    val points: List<Pair<Float, Float>>
)

object PhotoStrokeCodec {
    fun encode(strokes: List<PhotoStroke>): String {
        val array = JSONArray()
        strokes.forEach { stroke ->
            val flat = JSONArray()
            stroke.points.forEach { (x, y) ->
                // 4 decimals is under a tenth of a pixel on a 413px-wide photo — plenty, and keeps the history file small.
                flat.put(Math.round(x * 10000) / 10000.0)
                flat.put(Math.round(y * 10000) / 10000.0)
            }
            array.put(
                JSONObject()
                    .put("tool", stroke.tool.name)
                    .put("diameter", stroke.diameter.toDouble())
                    .put("strength", stroke.strength.toDouble())
                    .put("points", flat)
            )
        }
        return array.toString()
    }

    fun decode(json: String?): List<PhotoStroke> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.getJSONObject(i)
                val tool = runCatching { BrushTool.valueOf(obj.getString("tool")) }.getOrNull() ?: return@mapNotNull null
                val flat = obj.getJSONArray("points")
                val points = (0 until flat.length() / 2).map { p ->
                    flat.getDouble(p * 2).toFloat() to flat.getDouble(p * 2 + 1).toFloat()
                }
                PhotoStroke(tool, obj.getDouble("diameter").toFloat(), obj.optDouble("strength", 1.0).toFloat(), points)
            }
        }.getOrDefault(emptyList())
    }
}
