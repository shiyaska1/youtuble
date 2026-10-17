package com.mobicareapp.handwriting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A typed text kept under a title, to be put into the "Type text" window again later. */
data class SavedText(val id: Long, val title: String, val text: String, val createdAt: Long)

/** The typed texts saved from the "Type text" window (one small file in the app's private storage). */
object SavedTexts {
    private const val FILE = "handwriting_saved_texts.json"

    fun load(context: Context): List<SavedText> {
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                SavedText(obj.getLong("id"), obj.getString("title"), obj.getString("text"), obj.optLong("createdAt"))
            }
        }.getOrDefault(emptyList())
    }

    private fun store(context: Context, items: List<SavedText>) {
        val array = JSONArray()
        items.forEach { array.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text).put("createdAt", it.createdAt)) }
        File(context.filesDir, FILE).writeText(array.toString())
    }

    /** Adds a new saved text (newest first) and returns the updated list. */
    fun add(context: Context, title: String, text: String): List<SavedText> {
        val now = System.currentTimeMillis()
        val updated = listOf(SavedText(now, title.trim(), text, now)) + load(context)
        store(context, updated)
        return updated
    }

    fun delete(context: Context, id: Long): List<SavedText> {
        val updated = load(context).filterNot { it.id == id }
        store(context, updated)
        return updated
    }
}
