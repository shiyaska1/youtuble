package com.mobicareapp.collage

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved collage: [id] doubles as the output file's base name (`photos/<id>.jpg`) and the source-images subfolder name (`sources/<id>/`). */
data class CollageEntry(
    val id: String,
    val timestampMillis: Long,
    val sourcePaths: List<String>,
    val layoutName: String,
    val outputPath: String
) {
    val layout: CollageLayout get() = runCatching { CollageLayout.valueOf(layoutName) }.getOrDefault(CollageLayout.GRID)
}

/** Same small-JSON-file approach as [com.mobicareapp.passport.PassportPhotoHistoryStore] — one flat app-private list, no Room table needed. */
object CollageHistoryStore {
    private const val FILE_NAME = "history.json"

    fun loadAll(context: Context): List<CollageEntry> =
        readEntries(context).filter { File(it.outputPath).exists() }.sortedByDescending { it.timestampMillis }

    /** Upsert by id — see the passport photo store for why blind append is unsafe for a LazyColumn key. */
    fun add(context: Context, entry: CollageEntry) {
        val existing = readEntries(context)
        val updated = if (existing.any { it.id == entry.id }) {
            existing.map { if (it.id == entry.id) entry else it }
        } else {
            existing + entry
        }
        writeEntries(context, updated)
    }

    /** Updates the layout recorded for [id] — the output file itself is overwritten in place, so its path doesn't change. */
    fun updateLayout(context: Context, id: String, layoutName: String) {
        writeEntries(context, readEntries(context).map { if (it.id == id) it.copy(layoutName = layoutName) else it })
    }

    fun delete(context: Context, entry: CollageEntry) {
        File(entry.outputPath).delete()
        CollageFileStore.deleteSources(context, entry.id)
        writeEntries(context, readEntries(context).filterNot { it.id == entry.id })
    }

    private fun readEntries(context: Context): List<CollageEntry> {
        val file = historyFile(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val sources = obj.getJSONArray("sourcePaths")
                CollageEntry(
                    id = obj.getString("id"),
                    timestampMillis = obj.getLong("timestampMillis"),
                    sourcePaths = (0 until sources.length()).map { sources.getString(it) },
                    layoutName = obj.getString("layoutName"),
                    outputPath = obj.getString("outputPath")
                )
            }
        }.getOrDefault(emptyList())
            .asReversed().distinctBy { it.id }.asReversed()
    }

    private fun writeEntries(context: Context, entries: List<CollageEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("timestampMillis", entry.timestampMillis)
                    put("sourcePaths", JSONArray(entry.sourcePaths))
                    put("layoutName", entry.layoutName)
                    put("outputPath", entry.outputPath)
                }
            )
        }
        historyFile(context).writeText(array.toString())
    }

    private fun historyFile(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "Collages").apply { mkdirs() }
        return File(dir, FILE_NAME)
    }
}
