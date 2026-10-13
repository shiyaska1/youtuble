package com.mobicareapp.handwriting

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A saved handwritten note (the whole notepad: every page, any pictures, and optionally an attached
 * voice recording), kept in a list so it can be found, read, edited and deleted later. This is the
 * small part that is always in memory for the list and for searching; the pages themselves
 * (thousands of points) are stored separately and only loaded when a note is opened.
 *
 * [text] is the note's text as English; [title] starts out as the first few words of it.
 */
data class HwNoteMeta(
    val id: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String,
    val text: String,
    val audioPath: String?,
    val pageCount: Int
) {
    /** The note's file-style name: the date and time it was first saved. */
    val name: String get() = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.US).format(Date(createdAt))
}

object HandwritingNoteStore {
    private fun dir(context: Context): File = File(context.filesDir, "handwriting_notes").apply { mkdirs() }
    private fun indexFile(context: Context) = File(dir(context), "index.json")
    private fun pagesFile(context: Context, id: Long) = File(dir(context), "note_$id.json")

    fun loadIndex(context: Context): List<HwNoteMeta> {
        val file = indexFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                HwNoteMeta(
                    id = o.getLong("id"),
                    createdAt = o.getLong("createdAt"),
                    updatedAt = o.getLong("updatedAt"),
                    title = o.getString("title"),
                    text = o.optString("text"),
                    audioPath = o.optString("audio").takeIf { it.isNotEmpty() && File(it).exists() },
                    pageCount = o.optInt("pages", 1)
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveIndex(context: Context, notes: List<HwNoteMeta>) {
        val array = JSONArray()
        notes.forEach { n ->
            array.put(
                JSONObject()
                    .put("id", n.id)
                    .put("createdAt", n.createdAt)
                    .put("updatedAt", n.updatedAt)
                    .put("title", n.title)
                    .put("text", n.text)
                    .put("audio", n.audioPath ?: "")
                    .put("pages", n.pageCount)
            )
        }
        indexFile(context).writeText(array.toString())
    }

    fun loadPages(context: Context, id: Long): List<HwPage> {
        val file = pagesFile(context, id)
        if (!file.exists()) return listOf(HwPage())
        return runCatching { HandwritingStore.pagesFromJson(JSONArray(file.readText())) }.getOrDefault(listOf(HwPage()))
    }

    /** Writes the note's pages; pictures are copied next to it so they outlive the notepad being cleared. Returns the pages as stored. */
    fun savePages(context: Context, id: Long, pages: List<HwPage>): List<HwPage> {
        // Drop the pictures of an earlier save first, so a note that lost pages doesn't keep orphan files.
        dir(context).listFiles { f -> f.name.startsWith("note_${id}_img_") }?.forEach { it.delete() }
        val stored = pages.mapIndexed { n, page ->
            val imagePath = page.imagePath?.let { source ->
                val target = File(dir(context), "note_${id}_img_$n.jpg")
                runCatching { File(source).copyTo(target, overwrite = true) }.getOrNull()?.absolutePath
            }
            page.copy(redo = emptyList(), imagePath = imagePath)
        }
        pagesFile(context, id).writeText(HandwritingStore.pagesToJson(stored).toString())
        return stored
    }

    /** Copies a note's pictures into the notepad's working folder and points the pages at the copies, so editing the notepad never touches the saved note. */
    fun pagesForEditing(context: Context, pages: List<HwPage>): List<HwPage> {
        val workDir = File(context.filesDir, "handwriting_images").apply { mkdirs() }
        return pages.mapIndexed { n, page ->
            val imagePath = page.imagePath?.let { source ->
                val target = File(workDir, "img_${System.currentTimeMillis()}_$n.jpg")
                runCatching { File(source).copyTo(target, overwrite = true) }.getOrNull()?.absolutePath
            }
            page.copy(imagePath = imagePath)
        }
    }

    /** Copies [source] into the note's own folder as its attached voice; returns the stored path. */
    fun storeAudio(context: Context, id: Long, source: File): String {
        dir(context).listFiles { f -> f.name.startsWith("audio_$id.") }?.forEach { it.delete() }
        val extension = source.extension.ifEmpty { "mp3" }
        val target = File(dir(context), "audio_$id.$extension")
        source.copyTo(target, overwrite = true)
        return target.absolutePath
    }

    fun deleteAudio(context: Context, id: Long) {
        dir(context).listFiles { f -> f.name.startsWith("audio_$id.") }?.forEach { it.delete() }
    }

    fun delete(context: Context, id: Long) {
        pagesFile(context, id).delete()
        dir(context).listFiles { f -> f.name.startsWith("note_${id}_img_") || f.name.startsWith("audio_$id.") }?.forEach { it.delete() }
    }

    /** A small part of the note's text to use as its title: the first few words, kept short. */
    fun makeTitle(text: String): String {
        val words = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return "Handwritten note"
        val title = words.take(6).joinToString(" ")
        return if (title.length > 40) title.take(40).trimEnd() + "…" else title
    }
}
